package com.pocketrealm.client;

/** Same-APK bridge from :supervisor to the UI-owned X11 display process. */
interface IClientDisplayControl {
    String prepare(String runtimeRoot, String instanceToken);
    String attachSession(String instanceToken, String sessionId);
    String status();
    String requestClose(String instanceToken);
    String release(String instanceToken);
}
