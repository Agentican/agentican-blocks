package ai.agentican.blocks.llm.api;

import ai.agentican.blocks.llm.impl.DefaultFn;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public interface Fn<I, O> {

    O run(I input);

    ModelResponse<O> respond(I input);

    static <I, O> Builder<I, O> builder(Class<I> inputType, Class<O> outputType) {

        if (inputType == null) throw new IllegalArgumentException("Input type required");
        if (outputType == null) throw new IllegalArgumentException("Output type required");

        return new Builder<>(inputType, outputType);
    }

    final class Builder<I, O> {

        private final Class<I> inputType;
        private final Class<O> outputType;
        private final List<ToolDefinition> tools = new ArrayList<>();

        private Client client;
        private Model model;
        private Function<Model.Builder, ModelBuilder<?>> modelConfig;
        private String systemPrompt;
        private String userPrompt;

        Builder(Class<I> inputType, Class<O> outputType) {
            this.inputType = inputType;
            this.outputType = outputType;
        }

        public Builder<I, O> client(Client client) {
            this.client = client;
            return this;
        }

        public Builder<I, O> model(Model model) {
            this.model = model;
            return this;
        }

        public Builder<I, O> model(Function<Model.Builder, ModelBuilder<?>> config) {

            if (config == null) throw new IllegalArgumentException("Model config required");

            this.modelConfig = config;
            return this;
        }

        public Builder<I, O> systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder<I, O> userPrompt(String userPrompt) {
            this.userPrompt = userPrompt;
            return this;
        }

        public Builder<I, O> tool(ToolDefinition tool) {

            if (tool == null) throw new IllegalArgumentException("Tool required");

            tools.add(tool);
            return this;
        }

        public Builder<I, O> tools(List<ToolDefinition> tools) {

            if (tools == null) throw new IllegalArgumentException("Tools list required");

            this.tools.addAll(tools);
            return this;
        }

        public Fn<I, O> build() {

            if (userPrompt == null && inputType != String.class)
                throw new IllegalStateException("userPrompt required when input type is not String");

            Client resolved = client;

            if (resolved == null) {

                Model resolvedModel = model;

                if (resolvedModel == null && modelConfig != null)
                    resolvedModel = modelConfig.apply(Model.builder()).build();

                if (resolvedModel == null)
                    throw new IllegalStateException("Client or Model required");

                resolved = Client.builder().model(resolvedModel).build();
            }

            return new DefaultFn<>(resolved, systemPrompt, userPrompt, tools, inputType, outputType);
        }
    }
}
