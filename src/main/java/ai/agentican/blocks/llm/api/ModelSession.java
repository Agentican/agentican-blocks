package ai.agentican.blocks.llm.api;

import ai.agentican.blocks.llm.impl.ModelMessage;

import java.util.List;

public interface ModelSession {

    ModelResponse<Void> send(String userMessage);

    <T> ModelResponse<T> send(String userMessage, Class<T> outputType);

    List<ModelMessage> messages();
}
