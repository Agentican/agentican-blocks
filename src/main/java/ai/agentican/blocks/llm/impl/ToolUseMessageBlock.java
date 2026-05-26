package ai.agentican.blocks.llm.impl;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public record ToolUseMessageBlock(
        String id,
        String toolName,
        Map<String, Object> args) implements MessageBlock {

    public ToolUseMessageBlock {

        if (id == null || id.isBlank()) throw new IllegalArgumentException("Tool use id is required");
        if (toolName == null || toolName.isBlank()) throw new IllegalArgumentException("Tool use name is required");

        args = args == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(args));
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {

        private String id;
        private String toolName;
        private Map<String, Object> args = Map.of();

        private Builder() {}

        public Builder id(String id) { this.id = id; return this; }
        public Builder toolName(String toolName) { this.toolName = toolName; return this; }
        public Builder args(Map<String, Object> args) { this.args = args; return this; }

        public ToolUseMessageBlock build() {

            return new ToolUseMessageBlock(id, toolName, args);
        }
    }
}
