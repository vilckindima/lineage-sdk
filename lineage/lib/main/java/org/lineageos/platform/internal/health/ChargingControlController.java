/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.platform.internal.health;

import static lineageos.health.HealthInterface.MODE_AUTO;
import static lineageos.health.HealthInterface.MODE_LIMIT;
import static lineageos.health.HealthInterface.MODE_MANUAL;
import static lineageos.health.HealthInterface.MODE_NONE;

import static org.lineageos.platform.internal.health.Util.getTimeMillisFromSecondOfDay;
import static org.lineageos.platform.internal.health.Util.msToString;

import android.app.AlarmManager;
import android.app.ActivityManager;
import android.provider.Settings;
import vendor.lineage.health.ChargingControlSupportedMode;
import vendor.lineage.health.ChargingLimitInfo;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.text.format.DateUtils;
import android.util.Log;

import lineageos.providers.LineageSettings;

import org.lineageos.platform.internal.R;
import org.lineageos.platform.internal.health.ccprovider.ChargingControlProvider;
import org.lineageos.platform.internal.health.ccprovider.Deadline;
import org.lineageos.platform.internal.health.ccprovider.Limit;
import org.lineageos.platform.internal.health.ccprovider.Toggle;

import vendor.lineage.health.IChargingControl;

import java.io.PrintWriter;

public class ChargingControlController extends LineageHealthFeature {
    private final IChargingControl mChargingControl;
    private final ContentResolver mContentResolver;
    private ChargingControlNotification mChargingNotification;
    private LineageHealthBatteryBroadcastReceiver mBattReceiver;
    private BroadcastReceiver mAlarmBroadcastReceiver;
    private boolean mIsEnabled = false;

    private static final String OWNER = "evox_charging_control_owner";
    private static final String GOOGLE_MODE = "charge_optimization_mode";
    private final boolean mUseOwner;
    private boolean mStarted;
    private final ChargingControlOwner mOwner = new ChargingControlOwner(new ChargingControlOwner.Backend() {
        public int readOwner() {
            return Settings.Global.getInt(mContentResolver, OWNER, -1);
        }
        public boolean googleSelected() {
            return Settings.Secure.getIntForUser(mContentResolver, GOOGLE_MODE, 0,
                    ActivityManager.getCurrentUser()) == 1;
        }
        public boolean lineageSelected() { return readLineageEnabled(); }
        public void saveOwner(int owner) {
            if (!Settings.Global.putInt(mContentResolver, OWNER, owner)) {
                throw new IllegalStateException("Cannot save charging owner");
            }
        }
        public void mirror(int owner) {
            int user = ActivityManager.getCurrentUser();
            int google = owner == ChargingControlOwner.GOOGLE ? 1 : 0;
            int lineage = owner == ChargingControlOwner.LINEAGE ? 1 : 0;
            // Clear the previous UI before enabling the new one. These are mirrors only:
            // a late Settings Intelligence write cannot change the canonical owner.
            if (google == 0) writeGoogleMode(0, user);
            if (LineageSettings.System.getIntForUser(mContentResolver,
                    LineageSettings.System.CHARGING_CONTROL_ENABLED, 0, user) != lineage
                    && !LineageSettings.System.putIntForUser(mContentResolver,
                    LineageSettings.System.CHARGING_CONTROL_ENABLED, lineage, user)) {
                throw new IllegalStateException("Cannot mirror Lineage charging mode");
            }
            if (google == 1) writeGoogleMode(1, user);
        }
        public void releaseLineage() throws Exception { releaseLineageControl(); }
        public void applyGoogle(int policy) throws Exception { GoogleChargingPolicy.set(policy); }
        public void activateLineage() throws Exception {
            handleSettingChange();
            // Limit's legacy provider logs write failures instead of throwing them.
            // Verify the limit before reporting a successful transfer to the UI.
            if (getMode() == MODE_LIMIT && mCurrentProvider == mLimit && mIsPowerConnected
                    && mChargingControl.getChargingLimit().max != getLimit()) {
                throw new IllegalStateException("Lineage charging limit was not applied");
            }
        }
    });

    private void writeGoogleMode(int mode, int user) {
        if (Settings.Secure.getIntForUser(mContentResolver, GOOGLE_MODE, -1, user) != mode
                && !Settings.Secure.putIntForUser(mContentResolver, GOOGLE_MODE, mode, user)) {
            throw new IllegalStateException("Cannot mirror Google charging mode");
        }
    }

    private boolean readLineageEnabled() {
        return LineageSettings.System.getIntForUser(mContentResolver,
                LineageSettings.System.CHARGING_CONTROL_ENABLED, 0,
                ActivityManager.getCurrentUser()) != 0;
    }

    /** Unknown owner is blocked until migration succeeds, including when HAL is late. */
    private boolean ownsLineageHardware() {
        return !mUseOwner || Settings.Global.getInt(mContentResolver, OWNER, -1)
                == ChargingControlOwner.LINEAGE;
    }

    public synchronized boolean setGoogleChargingPolicy(int policy, boolean restore) {
        if (!mUseOwner || !mStarted || !isSupported()) return false;
        try {
            return mOwner.google(policy, restore);
        } catch (Exception e) {
            Log.e(TAG, "Google charging ownership transfer failed", e);
            return false;
        }
    }

    private void stopLineageMonitoring() {
        if (mBattReceiver != null) {
            mContext.unregisterReceiver(mBattReceiver);
            mBattReceiver = null;
        }
        if (mAlarmBroadcastReceiver != null) {
            mContext.unregisterReceiver(mAlarmBroadcastReceiver);
            mAlarmBroadcastReceiver = null;
        }
        mIsEnabled = false;
        mChargingNotification.cancel();
    }

    /** Provider reset swallows exceptions; independently verify release through the HAL. */
    private void releaseLineageControl() throws Exception {
        stopLineageMonitoring();
        mDeadline.disable();
        mLimit.disable();
        mToggle.disable();
        int modes = mChargingControl.getSupportedMode();
        if ((modes & ChargingControlSupportedMode.DEADLINE) != 0) {
            mChargingControl.setChargingDeadline(-1);
        }
        if ((modes & ChargingControlSupportedMode.LIMIT) != 0) {
            ChargingLimitInfo actual = mChargingControl.getChargingLimit();
            // The provider above normally already reset the limit. Google charger
            // sysfs rejects writes of an unchanged threshold with EINVAL, so a
            // second setChargingLimit(0, 100) would turn success into a failure.
            if (actual.min != 0 || actual.max != 100) {
                ChargingLimitInfo limit = new ChargingLimitInfo();
                limit.min = 0;
                limit.max = 100;
                mChargingControl.setChargingLimit(limit);
                actual = mChargingControl.getChargingLimit();
            }
            if (actual.min != 0 || actual.max != 100) {
                throw new IllegalStateException("Lineage limit not released");
            }
        }
        if ((modes & ChargingControlSupportedMode.TOGGLE) != 0) {
            mChargingControl.setChargingEnabled(true);
            if (!mChargingControl.getChargingEnabled()) {
                throw new IllegalStateException("Lineage charging toggle not released");
            }
        }
    }

    private final Runnable mReconcile = this::handleSettingChange;

    // Defaults
    private boolean mDefaultEnabled = false;
    private int mDefaultMode;
    private int mDefaultLimit;
    private int mDefaultStartTime;
    private int mDefaultTargetTime;

    // Settings uris
    private final Uri MODE_URI = LineageSettings.System.getUriFor(
            LineageSettings.System.CHARGING_CONTROL_MODE);
    private final Uri LIMIT_URI = LineageSettings.System.getUriFor(
            LineageSettings.System.CHARGING_CONTROL_LIMIT);
    private final Uri ENABLED_URI = LineageSettings.System.getUriFor(
            LineageSettings.System.CHARGING_CONTROL_ENABLED);
    private final Uri START_TIME_URI = LineageSettings.System.getUriFor(
            LineageSettings.System.CHARGING_CONTROL_START_TIME);
    private final Uri TARGET_TIME_URI = LineageSettings.System.getUriFor(
            LineageSettings.System.CHARGING_CONTROL_TARGET_TIME);

    // Internal state
    private float mBatteryPct;
    private boolean mIsPowerConnected;
    private boolean mIsControlCancelledOnce;

    // Current selected provider
    private ChargingControlProvider mCurrentProvider;
    private Deadline mDeadline;
    private Limit mLimit;
    private Toggle mToggle;

    public ChargingControlController(Context context, Handler handler) {
        super(context, handler);

        mContentResolver = mContext.getContentResolver();
        mUseOwner = mContext.getResources().getBoolean(R.bool.config_chargingControlGooglePolicy);
        mChargingControl = IChargingControl.Stub.asInterface(
                ServiceManager.waitForDeclaredService(
                        IChargingControl.DESCRIPTOR + "/default"));

        if (mChargingControl == null) {
            Log.i(TAG, "Lineage Health HAL not found");
            return;
        }

        mChargingNotification = new ChargingControlNotification(context, this);

        mDefaultEnabled = mContext.getResources().getBoolean(
                R.bool.config_chargingControlEnabled);
        mDefaultMode = mContext.getResources().getInteger(
                R.integer.config_defaultChargingControlMode);
        mDefaultStartTime = mContext.getResources().getInteger(
                R.integer.config_defaultChargingControlStartTime);
        mDefaultTargetTime = mContext.getResources().getInteger(
                R.integer.config_defaultChargingControlTargetTime);
        mDefaultLimit = mContext.getResources().getInteger(
                R.integer.config_defaultChargingControlLimit);

        // Set up charging control providers
        mDeadline = new Deadline(mChargingControl, mContext);
        mLimit = new Limit(mChargingControl, mContext);
        mToggle = new Toggle(mChargingControl, mContext);

        mCurrentProvider = getProviderForMode(getMode());
        if (mCurrentProvider == null) {
            if (mLimit.isSupported()) {
                mCurrentProvider = mLimit;
            } else if (mToggle.isSupported()) {
                mCurrentProvider = mToggle;
            } else if (mDeadline.isSupported()) {
                mCurrentProvider = mDeadline;
            } else {
                Log.wtf(TAG, "No charging control provider is supported");
            }
        }
    }

    @Override
    public boolean isSupported() {
        return mChargingControl != null;
    }

    public synchronized boolean isEnabled() {
        return mUseOwner ? ownsLineageHardware() : readLineageEnabled();
    }

    public synchronized boolean setEnabled(boolean enabled) {
        if (!mUseOwner) {
            putBoolean(LineageSettings.System.CHARGING_CONTROL_ENABLED, enabled);
            return true;
        }
        if (!mStarted || !isSupported()) return false;
        try {
            return mOwner.lineage(enabled);
        } catch (Exception e) {
            Log.e(TAG, "Lineage charging ownership transfer failed", e);
            return false;
        }
    }

    public int getMode() {
        return LineageSettings.System.getIntForUser(mContentResolver,
                LineageSettings.System.CHARGING_CONTROL_MODE,
                mDefaultMode, ActivityManager.getCurrentUser());
    }

    public synchronized boolean setMode(int mode) {
        if (mode < MODE_NONE || mode > MODE_LIMIT) {
            return false;
        }

        ChargingControlProvider next = getProviderForMode(mode);
        if (next == null) return false;
        if (ownsLineageHardware() && mCurrentProvider != null && next != mCurrentProvider) {
            mCurrentProvider.disable();
        }
        mCurrentProvider = next;

        putInt(LineageSettings.System.CHARGING_CONTROL_MODE, mode);
        return true;
    }

    ChargingControlProvider getProviderForMode(int mode) {
        if (mode < MODE_NONE || mode > MODE_LIMIT) {
            return null;
        }

        if (mode == MODE_LIMIT) {
            if (mLimit.isSupported()) {
                return mLimit;
            }
            if (mToggle.isSupported()) {
                return mToggle;
            }
        } else if (mode == MODE_AUTO || mode == MODE_MANUAL) {
            if (mDeadline.isSupported()) {
                return mDeadline;
            }
            if (mLimit.isSupported()) {
                return mLimit;
            }
            if (mToggle.isSupported()) {
                return mToggle;
            }
        }

        return null;
    }

    public int getStartTime() {
        return LineageSettings.System.getIntForUser(mContentResolver,
                LineageSettings.System.CHARGING_CONTROL_START_TIME,
                mDefaultStartTime, ActivityManager.getCurrentUser());
    }

    public synchronized boolean setStartTime(int time) {
        if (time < 0 || time > 24 * 60 * 60) {
            return false;
        }

        putInt(LineageSettings.System.CHARGING_CONTROL_START_TIME, time);
        return true;
    }

    public int getTargetTime() {
        return LineageSettings.System.getIntForUser(mContentResolver,
                LineageSettings.System.CHARGING_CONTROL_TARGET_TIME,
                mDefaultTargetTime, ActivityManager.getCurrentUser());
    }

    public synchronized boolean setTargetTime(int time) {
        if (time < 0 || time > 24 * 60 * 60) {
            return false;
        }

        putInt(LineageSettings.System.CHARGING_CONTROL_TARGET_TIME, time);
        return true;
    }

    public int getLimit() {
        return LineageSettings.System.getIntForUser(mContentResolver,
                LineageSettings.System.CHARGING_CONTROL_LIMIT,
                mDefaultLimit, ActivityManager.getCurrentUser());
    }

    public synchronized boolean setLimit(int limit) {
        if (limit < 0 || limit > 100) {
            return false;
        }

        putInt(LineageSettings.System.CHARGING_CONTROL_LIMIT, limit);
        return true;
    }

    public synchronized boolean reset() {
        return setEnabled(mDefaultEnabled) && setMode(mDefaultMode) && setLimit(mDefaultLimit)
                && setStartTime(mDefaultStartTime) && setTargetTime(mDefaultTargetTime);
    }

    private synchronized void updateBatteryInfo(Intent intent) {
        int battStatus = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        int battPlugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);

        if (battStatus == BatteryManager.BATTERY_STATUS_FULL) {
            mIsControlCancelledOnce = false;
        }

        if (mCurrentProvider.requiresBatteryLevelMonitoring()) {
            mIsPowerConnected = true;
        } else {
            mIsPowerConnected =
                    battPlugged != 0 || (battStatus != BatteryManager.BATTERY_STATUS_DISCHARGING &&
                            battStatus != BatteryManager.BATTERY_STATUS_UNKNOWN);
        }

        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level == -1 || scale == -1) {
            return;
        }

        mBatteryPct = level * 100 / (float) scale;

        Log.i(TAG, "mIsPowerConnected: " + mIsPowerConnected + ", mBatteryPct: " + mBatteryPct);
    }

    private synchronized void updateBatteryInfo() {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = mContext.registerReceiver(null, ifilter, Context.RECEIVER_EXPORTED);
        if (batteryStatus == null) {
            Log.e(TAG, "batteryStatus is NULL!");
            return;
        }
        updateBatteryInfo(batteryStatus);
    }

    @Override
    public synchronized void onStart() {
        if (mCurrentProvider == null || mChargingControl == null) {
            return;
        }

        // Register setting observer
        if (mUseOwner) {
            registerSettings(MODE_URI, LIMIT_URI, ENABLED_URI, START_TIME_URI, TARGET_TIME_URI,
                    Settings.Secure.getUriFor(GOOGLE_MODE), Settings.Global.getUriFor(OWNER));
        } else {
            registerSettings(MODE_URI, LIMIT_URI, ENABLED_URI, START_TIME_URI, TARGET_TIME_URI);
        }
        mStarted = true;
        handleSettingChange();
    }

    public boolean isChargingModeSupported(int mode) {
        try {
            return isSupported() && (mChargingControl.getSupportedMode() & mode) != 0;
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    protected synchronized void resetInternalState() {
        if (!ownsLineageHardware()) return;
        if (mCurrentProvider == null) {
            return;
        }

        mIsControlCancelledOnce = false;
        mChargingNotification.cancel();

        mCurrentProvider.reset();
    }

    protected synchronized void setChargingCancelledOnce() {
        if (!ownsLineageHardware()) return;
        if (mCurrentProvider == null) {
            return;
        }

        mIsControlCancelledOnce = true;

        if (mCurrentProvider.requiresBatteryLevelMonitoring()) {
            IntentFilter disconnectFilter = new IntentFilter(
                    Intent.ACTION_POWER_DISCONNECTED);

            // Register a one-time receiver that resets internal state on power
            // disconnection
            mContext.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    Log.i(TAG, "Power disconnected, reset internal states");
                    resetInternalState();
                    mContext.unregisterReceiver(this);
                }
            }, disconnectFilter);
        }

        mCurrentProvider.disable();
        mChargingNotification.cancel();
    }

    private synchronized void onPowerConnected() {
        if (mBattReceiver == null) {
            mBattReceiver = new LineageHealthBatteryBroadcastReceiver();
        } else {
            mContext.unregisterReceiver(mBattReceiver);
        }
        IntentFilter battFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        mContext.registerReceiver(mBattReceiver, battFilter);
    }

    private synchronized void onPowerDisconnected() {
        if (mBattReceiver != null) {
            mContext.unregisterReceiver(mBattReceiver);
            mBattReceiver = null;
        }

        // On disconnected, reset internal state
        resetInternalState();
    }

    private synchronized void onPowerStatus(boolean enable) {
        // Don't do anything if it is not enabled
        if (!isEnabled()) {
            return;
        }

        if (enable) {
            onPowerConnected();
            updateChargeControl();
        } else {
            onPowerDisconnected();
        }
    }

    private ChargeTime getChargeTime() {
        // Get duration to target full time
        final long currentTime = System.currentTimeMillis();
        Log.i(TAG, "Current time is " + msToString(mContext, currentTime));
        long targetTime = 0, startTime = currentTime;
        int mode = getMode();

        if (mode == MODE_AUTO) {
            // Use alarm as the target time. Maybe someday we can use a model.
            AlarmManager m = mContext.getSystemService(AlarmManager.class);
            if (m == null) {
                Log.e(TAG, "Failed to get alarm service!");
                mChargingNotification.cancel();
                return null;
            }
            AlarmManager.AlarmClockInfo alarmClockInfo = m.getNextAlarmClock();
            if (alarmClockInfo == null) {
                // We didn't find an alarm. Clear waiting flags because we can't predict anyway
                Log.w(TAG, "No alarm found, auto charging control has no effect");
                mChargingNotification.cancel();
                return null;
            }
            targetTime = alarmClockInfo.getTriggerTime();

            // Start time is 9 hours before the alarm
            startTime = targetTime - DateUtils.HOUR_IN_MILLIS * 9;
        } else if (mode == MODE_MANUAL) {
            // User manually controlled time
            startTime = getTimeMillisFromSecondOfDay(getStartTime());
            targetTime = getTimeMillisFromSecondOfDay(getTargetTime());

            if (startTime > targetTime) {
                if (currentTime > targetTime) {
                    targetTime += DateUtils.DAY_IN_MILLIS;
                } else {
                    startTime -= DateUtils.DAY_IN_MILLIS;
                }
            } else if (currentTime >= targetTime) {
                startTime += DateUtils.DAY_IN_MILLIS;
                targetTime += DateUtils.DAY_IN_MILLIS;
            }
        } else {
            Log.e(TAG, "invalid charging control mode " + mode);
            return null;
        }

        Log.i(TAG, "Got target time " + msToString(mContext, targetTime)
                + ", start time " + msToString(mContext, startTime)
                + ", current time " + msToString(mContext, currentTime));
        Log.i(TAG, "Raw: " + targetTime + ", " + startTime + ", " + currentTime);

        return new ChargeTime(startTime, targetTime);
    }

    protected synchronized void updateChargeControl() {
        if (!ownsLineageHardware()) return;
        if (mCurrentProvider == null) {
            return;
        }

        if (!isEnabled() || mIsControlCancelledOnce || !mIsPowerConnected) {
            mCurrentProvider.disable();
            mChargingNotification.cancel();
            return;
        }

        int mode = getMode();
        int limit = getLimit();

        mCurrentProvider.enable();

        if (mode == MODE_LIMIT) {
            if (mCurrentProvider.update(mBatteryPct, limit) && mIsPowerConnected) {
                mChargingNotification.post(limit, mBatteryPct >= limit);
            } else {
                mChargingNotification.cancel();
            }
        } else {
            ChargeTime chargeTime = getChargeTime();
            if (chargeTime != null) {
                if (mCurrentProvider.update(mBatteryPct, chargeTime.getStartTime(),
                        chargeTime.getTargetTime(), mode)) {
                    mChargingNotification.post(chargeTime.getTargetTime(),
                            mBatteryPct == 100);
                } else {
                    mChargingNotification.cancel();
                }
            }
        }

        if (mode == MODE_AUTO) {
            if (mAlarmBroadcastReceiver == null) {
                IntentFilter alarmChangedFilter = new IntentFilter(
                        android.app.AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED);
                mAlarmBroadcastReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        Log.i(TAG, "Alarm changed, update charge times");
                        updateChargeControl();
                    }
                };
                mContext.registerReceiver(mAlarmBroadcastReceiver, alarmChangedFilter);
            }
        } else {
            if (mAlarmBroadcastReceiver != null) {
                mContext.unregisterReceiver(mAlarmBroadcastReceiver);
                mAlarmBroadcastReceiver = null;
            }
        }
    }

    /**
     * Whether the current charging control mode supports supports the mode.
     * Available modes:
     *     - ${@link lineageos.health.HealthInterface#MODE_AUTO}
     *     - ${@link lineageos.health.HealthInterface#MODE_MANUAL}
     *     - ${@link lineageos.health.HealthInterface#MODE_LIMIT}
     */
    private boolean isProvideSupportCCMode(int mode) {
        if (mCurrentProvider == null) {
            return false;
        }

        return mCurrentProvider.isChargingControlModeSupported(mode);
    }

    private synchronized void handleSettingChange() {
        if (mUseOwner) {
            try {
                mOwner.reconcile();
                mHandler.removeCallbacks(mReconcile);
            } catch (Exception e) {
                Log.w(TAG, "Charging owner reconciliation failed; retrying", e);
                mHandler.removeCallbacks(mReconcile);
                mHandler.postDelayed(mReconcile, 5000);
                return;
            }
            if (!ownsLineageHardware()) {
                stopLineageMonitoring();
                return;
            }
        }
        int mode = getMode();

        if (mIsEnabled != isEnabled()) {
            mIsEnabled = isEnabled();

            if (mIsEnabled) {
                if (mBattReceiver == null) {
                    mBattReceiver = new LineageHealthBatteryBroadcastReceiver();
                } else {
                    mContext.unregisterReceiver(mBattReceiver);
                }
                IntentFilter battFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
                mContext.registerReceiver(mBattReceiver, battFilter);
                Log.i(TAG, "Enabled charging control, start monitoring battery");
            } else {
                if (mBattReceiver != null) {
                    mContext.unregisterReceiver(mBattReceiver);
                    mBattReceiver = null;
                }
                Log.i(TAG, "Disabled charging control, stop monitoring battery");
            }
        }

        if (!isProvideSupportCCMode(mode)) {
            Log.e(TAG, "Current provider does not support mode: " + mode
                    + ", setting to default mode");
            setMode(mDefaultMode);
        }

        // Reset internal states
        resetInternalState();

        // Update battery info
        updateBatteryInfo();

        // Update based on those values
        updateChargeControl();
    }

    @Override
    protected synchronized void onSettingsChanged(Uri uri) {
        handleSettingChange();
    }

    @Override
    public synchronized void dump(PrintWriter pw) {
        pw.println();
        pw.println("ChargingControlController Configuration:");
        pw.println("  Google arbitration: " + mUseOwner);
        pw.println("  Charging owner: " + Settings.Global.getInt(mContentResolver, OWNER, -1));
        pw.println("  Enabled: " + isEnabled());
        pw.println("  Mode: " + getMode());
        pw.println("  Limit: " + getLimit());
        pw.println("  StartTime: " + getStartTime());
        pw.println("  TargetTime: " + getTargetTime());
        pw.println();
        pw.println("ChargingControlController State:");
        pw.println("  mIsEnabled: " + mIsEnabled);
        pw.println("  mBatteryPct: " + mBatteryPct);
        pw.println("  mIsPowerConnected: " + mIsPowerConnected);
        pw.println("  mIsNotificationPosted: " + mChargingNotification.isPosted());
        pw.println("  mIsDoneNotification: " + mChargingNotification.isDoneNotification());
        pw.println("  mIsControlCancelledOnce: " + mIsControlCancelledOnce);
        pw.println();
        if (mCurrentProvider != null) {
            mCurrentProvider.dump(pw);
        }
    }

    /* Battery Broadcast Receiver */
    private class LineageHealthBatteryBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateBatteryInfo(intent);
            updateChargeControl();
        }
    }

    /* A representation of start and target time */
    static final class ChargeTime {
        private final long mStartTime;
        private final long mTargetTime;

        ChargeTime(long startTime, long targetTime) {
            mStartTime = startTime;
            mTargetTime = targetTime;
        }

        public long getStartTime() {
            return mStartTime;
        }

        public long getTargetTime() {
            return mTargetTime;
        }
    }
}
