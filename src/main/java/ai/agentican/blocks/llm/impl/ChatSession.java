package ai.agentican.blocks.llm.impl;

import ai.agentican.blocks.llm.Utils;
import ai.agentican.blocks.llm.api.ModelResponse;
import ai.agentican.blocks.llm.api.ModelSession;
import ai.agentican.blocks.llm.api.ToolDefinition;

import java.util.ArrayList;
import java.util.List;

public final class ChatSession implements ModelSession {

    private final AbstractModel model;
    private final String systemPrompt;
    private final List<ToolDefinition> tools;
    private final List<ModelMessage> history = new ArrayList<>();

    ChatSession(AbstractModel model, String systemPrompt, List<ToolDefinition> tools) {

        if (model == null) throw new IllegalArgumentException("model is required");
        if (Utils.isMissing(systemPrompt)) throw new IllegalArgumentException("System prompt is required");

        this.model = model;
        this.systemPrompt = systemPrompt;
        this.tools = tools != null ? List.copyOf(tools) : List.of();
    }

    @Override
    public ModelResponse<Void> send(String userMessage) {

        return send(userMessage, Void.class);
    }

    @Override
    public <T> ModelResponse<T> send(String userMessage, Class<T> outputType) {

        if (userMessage == null || userMessage.isBlank())
            throw new IllegalArgumentException("userMessage is required");

        history.add(ModelMessage.user(new TextBlock(userMessage)));

        ModelResponse<T> response;

        try {

            response = model.chat(systemPrompt, List.copyOf(history), tools, outputType);
        }
        catch (RuntimeException e) {

            history.removeLast();

            throw e;
        }

        history.add(ModelMessage.assistant(new TextBlock(response.text() != null ? response.text() : "")));

        return response;
    }

    @Override
    public List<ModelMessage> messages() {

        return List.copyOf(history);
    }
}
