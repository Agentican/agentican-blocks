package ai.agentican.blocks.llm.impl;

public record ToolResultBlock(
        String toolUseId,
        String content,
        boolean isError) implements Block {

    public ToolResultBlock {

        if (toolUseId == null || toolUseId.isBlank())
            throw new IllegalArgumentException("Tool result id is required");

        if (content == null) content = "";
    }

    public ToolResultBlock(String toolUseId, String content) {

        this(toolUseId, content, false);
    }
}
