/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.platform.internal.health;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;

/** caimito Google Battery V5+ wire adapter; no direct sysfs writes. */
final class GoogleChargingPolicy {
    private static final String DESCRIPTOR = "vendor.google.google_battery.IGoogleBattery";

    static void set(int policy) throws RemoteException {
        IBinder remote = ServiceManager.checkService(DESCRIPTOR + "/default");
        if (remote == null || !DESCRIPTOR.equals(remote.getInterfaceDescriptor())) {
            throw new RemoteException("Google Battery HAL unavailable");
        }
        Parcel data = Parcel.obtain(remote), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (!remote.transact(16777215, data, reply, 0)) {
                throw new RemoteException("Google Battery interface version unavailable");
            }
            reply.readException();
            if (reply.readInt() < 5) throw new RemoteException("Google Battery V5 required");
        } finally {
            data.recycle(); reply.recycle();
        }
        data = Parcel.obtain(remote); reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(policy);
            if (!remote.transact(23, data, reply, 0)) {
                throw new RemoteException("Google charging policy unsupported");
            }
            reply.readException();
        } finally {
            data.recycle(); reply.recycle();
        }
    }
}
