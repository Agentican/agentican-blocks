package ai.agentican.blocks.llm.api;

import ai.agentican.blocks.llm.impl.DefaultClient;
import ai.agentican.blocks.llm.impl.ModelMessage;

import java.time.Duration;
import java.util.List;
import java.util.function.Function;

public interface Client {

    Model model();

    <T> ModelResponse<T> send(ModelRequest<T> request);

    <T> ModelResponse<T> send(String systemPrompt, List<ModelMessage> messages,
                              List<ToolDefinition> tools, Class<T> outputType);

    static Builder builder() { return new Builder(); }

    final class Builder {

        private Model model;
        private Integer maxRetries;
        private Duration baseDelay;

        Builder() {}

        public Builder model(Model model) {
            this.model = model;
            return this;
        }

        public Builder model(Function<Model.Builder, ModelBuilder<?>> config) {

            if (config == null) throw new IllegalArgumentException("Model config required");

            this.model = config.apply(Model.builder()).build();
            return this;
        }

        public Builder maxRetries(int n) {
            this.maxRetries = n;
            return this;
        }

        public Builder baseDelay(Duration d) {
            this.baseDelay = d;
            return this;
        }

        public Client build() {

            if (model == null) throw new IllegalStateException("Model required");

            if (maxRetries == null && baseDelay == null) return new DefaultClient(model);

            return new DefaultClient(model,
                    maxRetries != null ? maxRetries : 3,
                    baseDelay != null ? baseDelay : Duration.ofSeconds(1));
        }
    }
}
