package ai.agentican.blocks.llm.api;

import ai.agentican.blocks.llm.impl.DefaultFn;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public interface Fn<T> {

    T run(String userMessage);

    ModelResponse<T> respond(String userMessage);

    static <T> Builder<T> builder(Class<T> outputType) {

        if (outputType == null) throw new IllegalArgumentException("Output type required");

        return new Builder<>(outputType);
    }

    final class Builder<T> {

        private final Class<T> outputType;
        private final List<ToolDefinition> tools = new ArrayList<>();

        private Client client;
        private Model model;
        private Function<Model.Builder, ModelBuilder<?>> modelConfig;
        private String systemPrompt;

        Builder(Class<T> outputType) {
            this.outputType = outputType;
        }

        public Builder<T> client(Client client) {
            this.client = client;
            return this;
        }

        public Builder<T> model(Model model) {
            this.model = model;
            return this;
        }

        public Builder<T> model(Function<Model.Builder, ModelBuilder<?>> config) {

            if (config == null) throw new IllegalArgumentException("Model config required");

            this.modelConfig = config;
            return this;
        }

        public Builder<T> systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder<T> tool(ToolDefinition tool) {

            if (tool == null) throw new IllegalArgumentException("Tool required");

            tools.add(tool);
            return this;
        }

        public Builder<T> tools(List<ToolDefinition> tools) {

            if (tools == null) throw new IllegalArgumentException("Tools list required");

            this.tools.addAll(tools);
            return this;
        }

        public Fn<T> build() {

            Client resolved = client;

            if (resolved == null) {

                Model resolvedModel = model;

                if (resolvedModel == null && modelConfig != null)
                    resolvedModel = modelConfig.apply(Model.builder()).build();

                if (resolvedModel == null)
                    throw new IllegalStateException("Client or Model required");

                resolved = Client.builder().model(resolvedModel).build();
            }

            return new DefaultFn<>(resolved, systemPrompt, tools, outputType);
        }
    }
}
