package ai.agentican.blocks.llm.impl;

import ai.agentican.blocks.llm.Utils;
import ai.agentican.blocks.llm.api.Client;
import ai.agentican.blocks.llm.api.Fn;
import ai.agentican.blocks.llm.api.ModelResponse;
import ai.agentican.blocks.llm.api.ToolDefinition;

import java.util.List;

public final class DefaultFn<T> implements Fn<T> {

    private final Client client;
    private final String systemPrompt;
    private final List<ToolDefinition> tools;
    private final Class<T> outputType;

    public DefaultFn(Client client, String systemPrompt, List<ToolDefinition> tools, Class<T> outputType) {

        if (client == null) throw new IllegalArgumentException("Client required");
        if (outputType == null) throw new IllegalArgumentException("Output type required");

        this.client = client;
        this.systemPrompt = systemPrompt;
        this.tools = tools == null ? List.of() : List.copyOf(tools);
        this.outputType = outputType;
    }

    @Override
    public T run(String userMessage) {

        return respond(userMessage).output();
    }

    @Override
    public ModelResponse<T> respond(String userMessage) {

        if (Utils.isMissing(userMessage))
            throw new IllegalArgumentException("User message required");

        var messages = List.of(ModelMessage.user(TextMessageBlock.of(userMessage)));

        return client.send(systemPrompt, messages, tools, outputType);
    }
}
