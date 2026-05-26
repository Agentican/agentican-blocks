package ai.agentican.blocks.llm.api;

import java.util.List;

public record ModelRequest<T>(
        String systemPrompt,
        String userMessage,
        List<ToolDefinition> tools,
        Class<T> outputType) {

    public ModelRequest {

        if (systemPrompt == null || systemPrompt.isBlank())
            throw new IllegalArgumentException("System prompt required");

        if (userMessage == null || userMessage.isBlank())
            throw new IllegalArgumentException("User message required");

        if (tools == null)
            tools = List.of();
    }

    public static ModelRequest<Void> of(String systemPrompt, String userMessage) {

        return new ModelRequest<>(systemPrompt, userMessage, List.of(), null);
    }

    public static ModelRequest<Void> of(String systemPrompt, String userMessage, List<ToolDefinition> tools) {

        return new ModelRequest<>(systemPrompt, userMessage, tools, null);
    }
}
