package ai.agentican.blocks.llm.impl;

import ai.agentican.blocks.llm.api.Client;
import ai.agentican.blocks.llm.api.Workspace;

public final class DefaultWorkspace implements Workspace {

    private final Client client;

    public DefaultWorkspace(Client client) {

        if (client == null) throw new IllegalArgumentException("Client required");

        this.client = client;
    }

    @Override
    public Client client() {

        return client;
    }
}
