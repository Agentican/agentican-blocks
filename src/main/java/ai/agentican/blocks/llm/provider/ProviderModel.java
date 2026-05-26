package ai.agentican.blocks.llm.provider;

import ai.agentican.blocks.llm.api.ModelResponse;
import ai.agentican.blocks.llm.api.ToolDefinition;
import ai.agentican.blocks.llm.impl.ModelMessage;

import java.util.List;

@FunctionalInterface
public interface ProviderModel {

    long DEFAULT_MAX_TOKENS = 16384L;

    <T> ModelResponse<T> send(String systemPrompt, List<ModelMessage> messages,
                              List<ToolDefinition> tools, Class<T> outputType);
}
