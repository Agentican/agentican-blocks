package ai.agentican.blocks.llm.api;

import java.util.List;

public record SingleResponse<T>(
        T output,
        String text,
        List<ToolCall> toolCalls,
        StopReason stopReason,
        ModelUsage usage) implements ModelResponse<T> {

    public SingleResponse {

        if (stopReason == null)
            throw new IllegalArgumentException("Stop reason required");

        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);

        if (usage == null)
            usage = ModelUsage.ZERO;
    }
}
