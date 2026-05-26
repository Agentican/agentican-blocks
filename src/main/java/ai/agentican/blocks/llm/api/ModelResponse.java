package ai.agentican.blocks.llm.api;

import java.util.List;

public record ModelResponse<T>(
        T output,
        String text,
        List<ToolCall> toolCalls,
        StopReason stopReason,
        ModelUsage usage) {

    public ModelResponse {

        if (stopReason == null)
            throw new IllegalArgumentException("Stop reason required");

        if (toolCalls == null)
            toolCalls = List.of();

        if (usage == null)
            usage = ModelUsage.ZERO;
    }
}
