package ai.agentican.blocks.llm.provider;

import ai.agentican.blocks.llm.Utils;
import ai.agentican.blocks.llm.api.Model;
import ai.agentican.blocks.llm.api.ModelBuilder;
import ai.agentican.blocks.llm.api.ModelResponse;
import ai.agentican.blocks.llm.api.ToolDefinition;
import ai.agentican.blocks.llm.impl.ModelMessage;

import java.util.List;

public final class HuggingFace implements Model {

    public static final String DEFAULT_BASE_URL = "https://router.huggingface.co/v1";

    private static final String DEFAULT_POLICY = "fastest";

    private final OpenAiCompatible delegate;

    HuggingFace(String apiKey, String baseUrl, String modelName, long maxTokens, Double temperature) {

        this.delegate = new OpenAiCompatible(apiKey, baseUrl, modelName, maxTokens, temperature);
    }

    @Override
    public <T> ModelResponse<T> send(String systemPrompt, List<ModelMessage> messages,
                                     List<ToolDefinition> tools, Class<T> outputType) {

        return delegate.send(systemPrompt, messages, tools, outputType);
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder implements ModelBuilder<Builder> {

        private String apiKey;
        private String baseUrl = DEFAULT_BASE_URL;
        private String modelName;
        private String routeTo;
        private String policy;
        private long maxTokens = DEFAULT_MAX_TOKENS;
        private Double temperature;

        public Builder apiKey(String apiKey) { this.apiKey = apiKey; return this; }
        public Builder baseUrl(String baseUrl) { this.baseUrl = baseUrl; return this; }

        public Builder routeTo(String partner) {

            this.routeTo = partner;
            this.policy = null;
            return this;
        }

        public Builder policy(String policy) {

            this.policy = policy;
            this.routeTo = null;
            return this;
        }

        @Override public Builder model(String model) { this.modelName = model; return this; }
        @Override public Builder maxTokens(long n) { this.maxTokens = n; return this; }
        @Override public Builder temperature(Double t) { this.temperature = t; return this; }

        @Override public Model build() {

            if (Utils.isMissing(modelName))
                throw new IllegalArgumentException("Model name required");

            var effectiveModel = modelName;

            if (Utils.isFound(routeTo))
                effectiveModel = modelName + ":" + routeTo;
            else if (Utils.isFound(policy) && !DEFAULT_POLICY.equals(policy))
                effectiveModel = modelName + ":" + policy;

            return new HuggingFace(apiKey, baseUrl, effectiveModel, maxTokens, temperature);
        }
    }
}
