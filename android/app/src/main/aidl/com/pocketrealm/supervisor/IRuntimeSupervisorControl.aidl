package com.pocketrealm.supervisor;

/** Fixed same-APK control surface; operations are asynchronous and journaled. */
interface IRuntimeSupervisorControl {
    String status();
    String start(String profileId, boolean includeClient);
    String stop(boolean forced);
    String relaunchClient();
    String recover();
    String forceComponentForTest(String component);
    String killSupervisorForTest();
}
