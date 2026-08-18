package com.winlator.alsaserver;

import com.winlator.xconnector.ConnectedClient;
import com.winlator.xconnector.ConnectionHandler;

public class ALSAClientConnectionHandler implements ConnectionHandler {
    private final ALSAClient.Options options;
    private final ALSADiagnostics diagnostics;

    public ALSAClientConnectionHandler(ALSAClient.Options options) {
        this(options, ALSADiagnostics.disabled());
    }

    /** Opt-in diagnostics constructor used by bounded acceptance/health checks. */
    public ALSAClientConnectionHandler(ALSAClient.Options options, ALSADiagnostics diagnostics) {
        this.options = options;
        this.diagnostics = diagnostics != null ? diagnostics : ALSADiagnostics.disabled();
    }

    @Override
    public void handleNewConnection(ConnectedClient client) {
        ALSAClient alsaClient = new ALSAClient(options, diagnostics);
        client.setTag(alsaClient);
        diagnostics.onConnectionOpened(alsaClient);
    }

    @Override
    public void handleConnectionShutdown(ConnectedClient client) {
        if (client.getTag() != null) {
            ALSAClient alsaClient = (ALSAClient)client.getTag();
            alsaClient.release();
            diagnostics.onConnectionClosed(alsaClient);
        }
    }
}
