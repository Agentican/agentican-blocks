package ai.agentican.blocks.llm.api;

import ai.agentican.blocks.llm.Utils;

import java.util.Collections;
import java.util.HashMap;
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

        args = args == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(args));
    }
}
