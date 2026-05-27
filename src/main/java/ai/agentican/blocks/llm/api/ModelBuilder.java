package ai.agentican.blocks.llm.api;

public interface ModelBuilder<B extends ModelBuilder<B>> {

    B model(String model);

    B maxTokens(long maxTokens);

    B temperature(Double temperature);

    Model build();
}
