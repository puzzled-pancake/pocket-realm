package com.pocketrealm.database;

/**
 * Fixed, same-APK control surface for the fault-isolated MariaDB service.
 * Every response is bounded JSON. No method accepts a path, executable,
 * environment, SQL string, or credential from the caller.
 */
interface IDatabaseControl {
    String status();
    String initialize();
    String start();
    String queryHealth();
    String applyPinnedMigrations();
    String stop();
    String killForTest();
    String recover();
    String snapshotAndRestoreTest();
    String storageFullTest();
}
