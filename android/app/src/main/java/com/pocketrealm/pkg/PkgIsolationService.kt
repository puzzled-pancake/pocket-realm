package com.pocketrealm.pkg

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import com.pocketrealm.log.AppLog

/**
 * The isolated `:pkg` child process (report §6.7 / §8.4 PKG-02). The deliberate
 * native abort() happens here, never in `:main`. PKG-02 binds to this service,
 * asks it to load the real realm shared object by SONAME, confirms hello, then
 * triggers the crash; the runner observes the child PID disappear and a Binder
 * death notification, then restarts the child to prove a fresh PID answers.
 *
 * Cross-process binder: the runner runs in :main (or the test instrumentation
 * process), so a plain LocalBinder cast would fail. We expose [IPkgIsolation]
 * via its generated Stub; the platform hands the client a Proxy.
 *
 * This service is NOT a realm component and is NOT part of the production
 * RuntimeSupervisor topology (ADR-013). It exists only to contain the PKG-02
 * fault injection.
 */
class PkgIsolationService : Service() {

    private val binder = object : IPkgIsolation.Stub() {
        override fun pid(): Int = Process.myPid()
        override fun hello(): String = PkgNative.helloNative()
        override fun probePageSize(): Int = PkgNative.probePageSizeNative()
        override fun loadRealmSoBySoname(): RealmSoInfoParcelable =
            PkgNative.loadRealmSoBySonameNative()
        override fun crash(kind: Int) {
            AppLog.w(TAG, ":pkg pid=${Process.myPid()} triggering native crash kind=$kind (PKG-02)")
            PkgNative.crashNative(kind)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        // Load the JNI shim in this process. Missing .so is a real failure,
        // surfaced — never stubbed.
        PkgNative.load()
        AppLog.i(TAG, "PkgIsolationService created in pid=${Process.myPid()} process=${packageName}:pkg")
    }

    companion object {
        private const val TAG = "PkgIsolation"
    }
}
