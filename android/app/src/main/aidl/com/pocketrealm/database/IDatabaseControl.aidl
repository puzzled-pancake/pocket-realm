package com.pocketrealm.database;

import android.os.IBinder;

/**
 * Fixed, same-APK control surface for the fault-isolated MariaDB service.
 * Every response is bounded JSON. No method accepts a path, executable,
 * environment, SQL string, or credential from the caller.
 */
interface IDatabaseControl {
    String claim(String sessionId, String instanceToken, IBinder ownerLease);
    String status();
    String initialize();
    String start();
    String queryHealth();
    String projectRealmEndpoint(String instanceToken, String address, int worldPort);
    String applyPinnedMigrations();
    String stop();
    String stopOwned(String instanceToken);
    String forceStopOwned(String instanceToken);
    String killForTest();
    String recover();
    String snapshotAndRestoreTest();
    String createNamedBackup(String name);
    String listBackups();
    String beginRestore(String snapshotId);
    String commitRestore(String restoreToken);
    String rollbackRestore(String restoreToken);
    String rollbackPendingRestore();
    String storageFullTest();
}
