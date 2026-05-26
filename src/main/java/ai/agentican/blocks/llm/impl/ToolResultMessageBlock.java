package ai.agentican.blocks.llm.impl;

public record ToolResultMessageBlock(
        String toolUseId,
        String content,
        boolean isError) implements MessageBlock {

    public ToolResultMessageBlock {

        if (toolUseId == null || toolUseId.isBlank())
            throw new IllegalArgumentException("Tool result id is required");

        if (content == null) content = "";
    }

    public ToolResultMessageBlock(String toolUseId, String content) {

        this(toolUseId, content, false);
    }
}
