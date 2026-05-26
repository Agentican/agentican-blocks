package ai.agentican.blocks.llm.api;

public interface ProviderBuilder<B extends ProviderBuilder<B>> {

    B model(String model);

    B maxTokens(long maxTokens);

    B temperature(Double temperature);

    Provider build();
}
