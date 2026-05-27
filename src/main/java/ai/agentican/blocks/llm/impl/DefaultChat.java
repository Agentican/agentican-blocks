package ai.agentican.blocks.llm.impl;

import ai.agentican.blocks.llm.Utils;
import ai.agentican.blocks.llm.api.Chat;
import ai.agentican.blocks.llm.api.Client;
import ai.agentican.blocks.llm.api.ModelResponse;
import ai.agentican.blocks.llm.api.ToolDefinition;

import java.util.ArrayList;
import java.util.List;

public final class DefaultChat implements Chat {

    private final Client client;
    private final String systemPrompt;
    private final List<ToolDefinition> tools;
    private final List<ModelMessage> history = new ArrayList<>();

    public DefaultChat(Client client, String systemPrompt, List<ToolDefinition> tools) {

        if (client == null) throw new IllegalArgumentException("Client required");

        this.client = client;
        this.systemPrompt = systemPrompt;
        this.tools = tools == null ? List.of() : List.copyOf(tools);
    }

    @Override
    public String send(String userMessage) {

        var response = respond(userMessage);

        return response.text() != null ? response.text() : "";
    }

    @Override
    public ModelResponse<Void> respond(String userMessage) {

        if (Utils.isMissing(userMessage))
            throw new IllegalArgumentException("User message required");

        history.add(ModelMessage.user(TextMessageBlock.of(userMessage)));

        ModelResponse<Void> response;

        try {

            response = client.send(systemPrompt, history, tools, Void.class);
        }
        catch (RuntimeException e) {

            history.removeLast();

            throw e;
        }

        var responseText = response.text() != null ? response.text() : "";

        history.add(ModelMessage.assistant(TextMessageBlock.of(responseText)));

        return response;
    }

    @Override
    public List<ModelMessage> messages() {

        return List.copyOf(history);
    }
}
