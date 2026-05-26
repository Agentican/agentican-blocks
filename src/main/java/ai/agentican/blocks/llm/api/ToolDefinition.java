package ai.agentican.blocks.llm.api;

import ai.agentican.blocks.llm.Utils;

import java.util.List;
import java.util.Map;

public record ToolDefinition(
        String name,
        String description,
        Map<String, Object> properties,
        List<String> required) {

    public ToolDefinition {

        if (Utils.isMissing(name))
            throw new IllegalArgumentException("Tool name required");

        if (Utils.isMissing(description))
            throw new IllegalArgumentException("Tool description required");

        properties = properties == null ? Map.of() : Map.copyOf(properties);
        required = required == null ? List.of() : List.copyOf(required);
    }

    public ToolDefinition(String name, String description, Map<String, Object> properties) {

        this(name, description, properties, List.of());
    }
}
