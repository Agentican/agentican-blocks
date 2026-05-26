package ai.agentican.blocks.llm.api;

import ai.agentican.blocks.llm.Utils;

import java.util.Map;

public record ToolCall(
        String id,
        String name,
        Map<String, Object> args) {

    public ToolCall {

        if (Utils.isMissing(id))
            throw new IllegalArgumentException("Tool call id required");

        if (Utils.isMissing(name))
            throw new IllegalArgumentException("Tool name required");

        if (args == null) args = Map.of();
    }
}
