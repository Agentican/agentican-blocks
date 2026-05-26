package ai.agentican.blocks.llm.api;

import java.util.Map;

public interface Tool {

    ToolDefinition definition();

    String execute(Map<String, Object> args) throws Exception;
}
