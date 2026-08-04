package com.pocketrealm.server;

import android.os.IBinder;

/** Fixed app-private O09 control surface for the isolated :world process. */
interface IWorldControl {
    String claim(String sessionId, String instanceToken, IBinder ownerLease);
    String status();
    String start();
    String startNormal();
    String startBotProfile(String profileId);
    String setBotTarget(int target);
    String botStatus();
    String createAccount(String username, String password);
    String setAccountGmLevel(String username, int level);
    String accountStatus(String username);
    String characterPersistence(String username, String characterName);
    String realmStatus();
    String save();
    String stop();
    String stopOwned(String instanceToken);
    String forceStopOwned(String instanceToken);
    String killForTest();
}
