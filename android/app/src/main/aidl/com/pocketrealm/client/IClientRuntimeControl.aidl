package com.pocketrealm.client;

/** Versioned, app-private control plane for the :client Wine process. */
interface IClientRuntimeControl {
    String probe(String requestJson);
    String preparePrefix(String requestJson);
    String launch(String requestJson);
    String requestClose(String sessionId);
    String forceStop(String sessionId);
    String status(String sessionId);
    String collectDiagnostics(String sessionId);
    String reportWindowVisible(String sessionId);
}
