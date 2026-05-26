package ai.agentican.blocks.llm.api;

import ai.agentican.blocks.llm.impl.ModelMessage;

import java.util.List;

public interface Model {

    <T> ModelResponse<T> send(ModelRequest<T> request);

    <T> ModelResponse<T> send(String systemPrompt, List<ModelMessage> messages,
                              List<ToolDefinition> tools, Class<T> outputType);

    ModelSession session(String systemPrompt, List<ToolDefinition> tools);
}
