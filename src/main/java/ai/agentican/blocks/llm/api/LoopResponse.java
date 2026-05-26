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

        if (toolCalls == null) toolCalls = List.of();
        if (usage == null) usage = ModelUsage.ZERO;
        if (messages == null) messages = List.of();
        if (text == null) text = "";
    }
}
