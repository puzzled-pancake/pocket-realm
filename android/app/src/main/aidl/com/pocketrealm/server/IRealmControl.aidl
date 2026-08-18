package com.pocketrealm.server;

import android.os.IBinder;

/** Fixed app-private control surface for the isolated :realm process. */
interface IRealmControl {
    String claim(String sessionId, String instanceToken, IBinder ownerLease);
    String status();
    String start();
    String startAt(String bindAddress);
    String stop();
    String stopOwned(String instanceToken);
    String forceStopOwned(String instanceToken);
    String killForTest();
}
