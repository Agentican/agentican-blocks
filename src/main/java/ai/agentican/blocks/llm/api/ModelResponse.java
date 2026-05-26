package ai.agentican.blocks.llm.api;

import java.util.List;

public sealed interface ModelResponse<T> permits SingleResponse, LoopResponse {

    T output();

    String text();

    List<ToolCall> toolCalls();

    StopReason stopReason();

    ModelUsage usage();
}
