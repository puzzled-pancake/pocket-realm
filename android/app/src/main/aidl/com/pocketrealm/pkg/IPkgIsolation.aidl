// IPkgIsolation.aidl — the cross-process packaging-experiment control surface.
//
// PkgIsolationService lives in the :pkg process (android:process=":pkg"); the
// runner binds to it from :main / the test process. Because the binder crosses
// processes, a plain Kotlin LocalBinder cast does NOT work — an AIDL interface
// generates a Stub (server side, in :pkg) + Proxy (client side) pair.
//
// Kept deliberately tiny: only what the experiments need. Realm loading happens by
// SONAME inside :pkg; crash() never returns for kind 0 (SIGABRT).
package com.pocketrealm.pkg;

import com.pocketrealm.pkg.RealmSoInfoParcelable;

interface IPkgIsolation {
    int pid();
    String hello();
    int probePageSize();
    RealmSoInfoParcelable loadRealmSoBySoname();
    // Per-lib probe (RTLD_NOLOAD then RTLD_NOW); proves every APK lib loads.
    RealmSoInfoParcelable probeSoBySoname(String soname);
    void crash(int kind);  // deterministic fault; kind 0 = abort()
}
