package com.pocketrealm.importer;

/** Same-APK diagnostics/control only; paths and commands are never accepted. */
interface IImportWorker {
    String statusJson();
    void cancel();
    void killForTest();
}
