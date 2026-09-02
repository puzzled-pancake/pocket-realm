package com.pocketrealm.database;

import android.os.IBinder;

/**
 * Fixed, same-APK control surface for the fault-isolated database service
 * (the MariaDB provider, and - in dual-provider window builds - the
 * in-tree SQLite provider behind the same opaque lifecycle). Every
 * response is bounded JSON. No method accepts a path, executable,
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
    String provisionSqliteProvider();
    String translateUserStateToSqliteStaging();
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
