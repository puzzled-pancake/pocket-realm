package com.pocketrealm.client;

import android.os.IBinder;

/** Same-APK bridge from :supervisor to the UI-owned X11 display process. */
interface IClientDisplayControl {
    String claim(String sessionId, String instanceToken, IBinder ownerLease);
    String prepare(String runtimeRoot, String instanceToken, boolean singlePlayerAutoLogin, String clientId);
    String attachSession(String instanceToken, String sessionId);
    String status();
    String requestClose(String instanceToken);
    String release(String instanceToken);
}
