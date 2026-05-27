package ai.agentican.blocks.llm.api;

import java.util.ArrayList;
import java.util.List;

public record ModelRequest<T>(
        String systemPrompt,
        String userMessage,
        List<ToolDefinition> tools,
        Class<T> outputType) {

    public ModelRequest {

        if (userMessage == null || userMessage.isBlank())
            throw new IllegalArgumentException("User message required");

        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    public static Builder<Void> builder() { return new Builder<>(null); }

    public static <T> Builder<T> builder(Class<T> outputType) { return new Builder<>(outputType); }

    public static final class Builder<T> {

        private final List<ToolDefinition> tools = new ArrayList<>();
        private final Class<T> outputType;

        private String systemPrompt;
        private String userMessage;

        private Builder(Class<T> outputType) {

            this.outputType = outputType;
        }

        public Builder<T> systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder<T> userMessage(String userMessage) {
            this.userMessage = userMessage;
            return this;
        }

        public Builder<T> tool(ToolDefinition tool) {

            if (tool == null) throw new IllegalArgumentException("Tool required");

            tools.add(tool);
            return this;
        }

        public Builder<T> tools(List<ToolDefinition> tools) {

            if (tools == null) throw new IllegalArgumentException("Tools list required");

            this.tools.addAll(tools);
            return this;
        }

        public ModelRequest<T> build() {

            return new ModelRequest<>(systemPrompt, userMessage, tools, outputType);
        }
    }
}
