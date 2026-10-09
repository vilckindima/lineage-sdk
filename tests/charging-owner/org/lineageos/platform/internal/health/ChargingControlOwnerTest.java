/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.platform.internal.health;
import java.util.ArrayList;
import java.util.List;

/** Host test for the actual production transition engine, no Android runtime required. */
public final class ChargingControlOwnerTest {
    static class Backend implements ChargingControlOwner.Backend {
        int owner = -1, policy = 1;
        boolean google, lineage, lineageHardware;
        String fail;
        final List<String> calls = new ArrayList<>();
        void step(String name) throws Exception {
            calls.add(name);
            if (name.equals(fail)) { fail = null; throw new Exception("injected " + name); }
        }
        public int readOwner() { return owner; }
        public boolean googleSelected() { return google; }
        public boolean lineageSelected() { return lineage; }
        public void saveOwner(int value) throws Exception { step("save"+value); owner=value; }
        public void mirror(int value) throws Exception {
            step("mirror"+value);
            google=value==1; lineage=value==2;
        }
        public void releaseLineage() throws Exception { step("release"); lineageHardware=false; }
        public void applyGoogle(int value) throws Exception {
            step("google"+value);
            if (value==2 && lineageHardware) throw new AssertionError("overlapping hardware owners");
            policy=value;
        }
        public void activateLineage() throws Exception {
            step("activate");
            if (policy==2) throw new AssertionError("Google not released");
            lineageHardware=true;
        }
    }
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    static Backend state(int owner) {
        Backend b=new Backend(); b.owner=owner; b.google=owner==1; b.lineage=owner==2;
        b.policy=owner==1?2:1; b.lineageHardware=owner==2; return b;
    }
    static void consistent(Backend b, int owner) {
        check(b.owner==owner && b.google==(owner==1) && b.lineage==(owner==2));
        check(b.policy==(owner==1?2:1) && b.lineageHardware==(owner==2));
    }
    public static void main(String[] args) throws Exception {
        int cases=0;
        for (int start=0; start<=2; start++) {
            for (int target=0; target<=2; target++) {
                Backend b=state(start); ChargingControlOwner c=new ChargingControlOwner(b);
                if (target==1) c.google(2,false);
                else if (target==2) c.lineage(true);
                else if (start==1) c.google(1,false);
                else c.lineage(false);
                consistent(b,target); cases++;
            }
        }
        // A restore queued before the Lineage switch cannot later steal ownership.
        Backend b=state(1); ChargingControlOwner c=new ChargingControlOwner(b);
        c.lineage(true); b.google=true; c.google(2,true); consistent(b,2); cases++;
        // Disabling the inactive controller never changes hardware.
        for (int owner=1; owner<=2; owner++) {
            b=state(owner); c=new ChargingControlOwner(b);
            if (owner==1) c.lineage(false); else c.google(1,false);
            consistent(b,owner); check(b.calls.equals(List.of("mirror"+owner))); cases++;
        }
        // Hardware policy is lost at reboot, owner remains persisted.
        b=state(1); b.policy=1; c=new ChargingControlOwner(b); c.google(2,true);
        consistent(b,1); cases++;
        // Repair a delayed proprietary UI write using the canonical owner.
        b=state(2); b.google=true; c=new ChargingControlOwner(b); c.reconcile();
        consistent(b,2); cases++;
        // Legacy migration: both selected retains Lineage, explicitly releases Google.
        b=state(2); b.owner=-1; b.google=true; b.policy=2;
        c=new ChargingControlOwner(b); check(c.owner()==2); consistent(b,2); cases++;
        // Failed release/apply/save/mirror restores previous owner and hardware.
        for (String failure: List.of("release","google2","save1","mirror1")) {
            b=state(2); b.fail=failure; c=new ChargingControlOwner(b);
            try { c.google(2,false); throw new AssertionError("expected failure"); }
            catch (Exception expected) { consistent(b,2); } cases++;
        }
        for (String failure: List.of("google1","save2","mirror2","activate")) {
            b=state(1); b.fail=failure; c=new ChargingControlOwner(b);
            try { c.lineage(true); throw new AssertionError("expected failure"); }
            catch (Exception expected) { consistent(b,1); } cases++;
        }
        // Rollback to NONE must also release partially activated Lineage hardware.
        ChargingControlOwner.Backend partial = new Backend() {
            @Override public void activateLineage() throws Exception {
                lineageHardware=true;
                throw new Exception("partial activation");
            }
        };
        Backend pending=(Backend)partial; pending.owner=0;
        c=new ChargingControlOwner(pending);
        try { c.lineage(true); throw new AssertionError("expected failure"); }
        catch (Exception expected) { consistent(pending,0); } cases++;
        b=state(2); c=new ChargingControlOwner(b);
        check(!c.google(3,false)); check(!c.google(1,true)); check(b.calls.isEmpty()); cases++;
        System.out.println(cases+" charging-owner scenarios passed");
    }
}
