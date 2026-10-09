/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.platform.internal.health;

/** One device-wide owner. Callbacks run under ChargingControlController's lock. */
final class ChargingControlOwner {
    static final int NONE = 0, GOOGLE = 1, LINEAGE = 2;

    interface Backend {
        int readOwner();
        boolean googleSelected();
        boolean lineageSelected();
        void saveOwner(int owner) throws Exception;
        void mirror(int owner) throws Exception;
        void releaseLineage() throws Exception;
        void applyGoogle(int policy) throws Exception;
        void activateLineage() throws Exception;
    }

    private final Backend b;
    ChargingControlOwner(Backend backend) { b = backend; }

    int owner() throws Exception {
        int owner = b.readOwner();
        if (owner >= NONE && owner <= LINEAGE) return owner;
        // Migration from v2: retain the explicit Lineage limit if both were enabled.
        boolean lineage = b.lineageSelected(), google = b.googleSelected();
        owner = lineage ? LINEAGE : google ? GOOGLE : NONE;
        if (lineage && google) b.applyGoogle(1);
        b.saveOwner(owner);
        b.mirror(owner);
        return owner;
    }

    void reconcile() throws Exception { b.mirror(owner()); }

    boolean google(int policy, boolean restore) throws Exception {
        if ((policy != 1 && policy != 2) || (restore && policy != 2)) return false;
        int old = owner();
        if (restore && old != GOOGLE) {
            // A queued restore must not steal ownership from a newer Lineage request.
            b.mirror(old);
            return true;
        }
        if (policy == 1 && old != GOOGLE) {
            b.mirror(old);
            return true;
        }
        try {
            if (policy == 2) b.releaseLineage();
            b.applyGoogle(policy);
            int next = policy == 2 ? GOOGLE : NONE;
            b.saveOwner(next);
            b.mirror(next);
            return true;
        } catch (Exception failure) {
            rollback(old, failure);
            throw failure;
        }
    }

    boolean lineage(boolean enabled) throws Exception {
        int old = owner();
        if (!enabled && old != LINEAGE) {
            b.mirror(old);
            return true;
        }
        try {
            if (enabled && old == GOOGLE) b.applyGoogle(1);
            if (!enabled) b.releaseLineage();
            int next = enabled ? LINEAGE : NONE;
            b.saveOwner(next);
            b.mirror(next);
            if (enabled) b.activateLineage();
            return true;
        } catch (Exception failure) {
            rollback(old, failure);
            throw failure;
        }
    }

    private void rollback(int old, Exception failure) {
        try {
            if (old != LINEAGE) b.releaseLineage();
            b.applyGoogle(old == GOOGLE ? 2 : 1);
            b.saveOwner(old);
            b.mirror(old);
            if (old == LINEAGE) b.activateLineage();
        } catch (Exception rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }
}
