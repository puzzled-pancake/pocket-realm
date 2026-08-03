package com.pocketrealm.supervisor;

/** Fixed same-APK control surface; operations are asynchronous and journaled. */
interface IRuntimeSupervisorControl {
    String status();
    String start(String profileId, boolean includeClient);
    String stop(boolean forced);
    String relaunchClient();
    String recover();
    String createAccount(String username, String password, int gmLevel);
    String createBackup(String name);
    String listBackups();
    String restoreBackup(String snapshotId);
    String backupStatus();
    String forceComponentForTest(String component);
    String killSupervisorForTest();
}
