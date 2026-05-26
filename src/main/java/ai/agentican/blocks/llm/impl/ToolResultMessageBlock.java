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

    public static Builder builder() { return new Builder(); }

    public static final class Builder {

        private String toolUseId;
        private String content = "";
        private boolean isError = false;

        private Builder() {}

        public Builder toolUseId(String toolUseId) { this.toolUseId = toolUseId; return this; }
        public Builder content(String content) { this.content = content; return this; }
        public Builder isError(boolean isError) { this.isError = isError; return this; }

        public ToolResultMessageBlock build() {

            return new ToolResultMessageBlock(toolUseId, content, isError);
        }
    }
}
