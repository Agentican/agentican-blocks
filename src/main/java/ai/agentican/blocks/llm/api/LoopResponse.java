package ai.agentican.blocks.llm.api;

import ai.agentican.blocks.llm.impl.ModelMessage;

import java.util.List;

public record LoopResponse<T>(
        T output,
        String text,
        List<ToolCall> toolCalls,
        StopReason stopReason,
        ModelUsage usage,
        List<ModelMessage> messages,
        int turns) implements ModelResponse<T> {

    public LoopResponse {

        if (stopReason == null)
            throw new IllegalArgumentException("Stop reason required");

        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        messages = messages == null ? List.of() : List.copyOf(messages);

        if (usage == null)
            usage = ModelUsage.ZERO;

        if (text == null)
            text = "";
    }

    public static <T> Builder<T> builder() { return new Builder<>(); }

    public static final class Builder<T> {

        private T output;
        private String text = "";
        private List<ToolCall> toolCalls = List.of();
        private StopReason stopReason;
        private ModelUsage usage = ModelUsage.ZERO;
        private List<ModelMessage> messages = List.of();
        private int turns;

        private Builder() {}

        public Builder<T> output(T output) { this.output = output; return this; }
        public Builder<T> text(String text) { this.text = text; return this; }
        public Builder<T> toolCalls(List<ToolCall> toolCalls) { this.toolCalls = toolCalls; return this; }
        public Builder<T> stopReason(StopReason stopReason) { this.stopReason = stopReason; return this; }
        public Builder<T> usage(ModelUsage usage) { this.usage = usage; return this; }
        public Builder<T> messages(List<ModelMessage> messages) { this.messages = messages; return this; }
        public Builder<T> turns(int turns) { this.turns = turns; return this; }

        public LoopResponse<T> build() {

            return new LoopResponse<>(output, text, toolCalls, stopReason, usage, messages, turns);
        }
    }
}
