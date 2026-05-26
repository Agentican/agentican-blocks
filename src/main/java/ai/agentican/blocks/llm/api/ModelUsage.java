package ai.agentican.blocks.llm.api;

import java.util.stream.Stream;

public record ModelUsage(
        long inputTokens,
        long outputTokens,
        long cacheReadTokens,
        long cacheWriteTokens,
        long webSearches) {

    public static final ModelUsage ZERO = new ModelUsage(0, 0, 0, 0, 0);

    public long total() {

        return inputTokens + cacheReadTokens + cacheWriteTokens + outputTokens;
    }

    public ModelUsage plus(ModelUsage other) {

        return new ModelUsage(inputTokens + other.inputTokens,
                outputTokens + other.outputTokens, cacheReadTokens + other.cacheReadTokens,
                cacheWriteTokens + other.cacheWriteTokens, webSearches + other.webSearches);
    }

    public static ModelUsage sum(Stream<ModelUsage> usages) {

        return usages.reduce(ZERO, ModelUsage::plus);
    }
}
