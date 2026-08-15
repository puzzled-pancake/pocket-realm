package com.pocketrealm.client;

import android.os.IBinder;

/** Versioned, app-private control plane for the :client Wine process. */
interface IClientRuntimeControl {
    String claim(String sessionId, String instanceToken, IBinder ownerLease);
    String probe(String requestJson);
    String preparePrefix(String requestJson);
    String launch(String requestJson);
    String requestClose(String sessionId);
    String forceStop(String sessionId);
    String status(String sessionId);
    String collectDiagnostics(String sessionId);
    String reportWindowVisible(String sessionId);
    String reportGraphicsProof(String sessionId, String renderer, int transportContexts,
        int rendererContexts, long presentedFrames);
    String statusCurrent();
    String closeOwned(String instanceToken);
    String releaseOwned(String instanceToken);
    String forceStopOwned(String instanceToken);
}
