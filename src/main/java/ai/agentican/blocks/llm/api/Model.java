package ai.agentican.blocks.llm.api;

import java.util.List;

public interface Model {

    <T> ModelResponse<T> send(ModelRequest<T> request);

    ModelSession session(String systemPrompt, List<ToolDefinition> tools);
}
