package com.pocketrealm.client;

import android.os.IBinder;

/** Same-APK bridge from :supervisor to the UI-owned X11 display process. */
interface IClientDisplayControl {
    String claim(String sessionId, String instanceToken, IBinder ownerLease);
    String prepare(String runtimeRoot, String instanceToken, String autoLoginUsername,
        String autoLoginPassword, String autoLoginTimingJson, String audioMode, String clientId,
        String vulkanDriverId, String rendererPackageId, String displayProfileId, int frameCap);
    String attachSession(String instanceToken, String sessionId);
    String status();
    String requestClose(String instanceToken);
    String release(String instanceToken);
}
