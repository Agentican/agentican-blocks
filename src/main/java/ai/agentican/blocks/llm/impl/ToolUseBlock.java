package ai.agentican.blocks.llm.impl;

import java.util.Map;

public record ToolUseBlock(
        String id,
        String toolName,
        Map<String, Object> args) implements Block {

    public ToolUseBlock {

        if (id == null || id.isBlank()) throw new IllegalArgumentException("Tool use id is required");
        if (toolName == null || toolName.isBlank()) throw new IllegalArgumentException("Tool use name is required");

        if (args == null) args = Map.of();

        args = Map.copyOf(args);
    }
}
