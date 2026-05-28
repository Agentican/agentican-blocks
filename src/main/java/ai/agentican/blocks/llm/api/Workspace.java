package ai.agentican.blocks.llm.api;

import ai.agentican.blocks.llm.impl.DefaultWorkspace;

import java.util.function.Function;

public interface Workspace {

    Client client();

    default Model model() { return client().model(); }

    default <I, O> Fn.Builder<I, O> fn(Class<I> inputType, Class<O> outputType) {

        return Fn.builder(inputType, outputType).client(client());
    }

    default Chat.Builder chat() {

        return Chat.builder().client(client());
    }

    default Agent.Builder agent() {

        return Agent.builder().client(client());
    }

    static Builder builder() { return new Builder(); }

    final class Builder {

        private Client client;
        private Model model;
        private Function<Model.Builder, ModelBuilder<?>> modelConfig;

        Builder() {}

        public Builder client(Client client) {
            this.client = client;
            return this;
        }

        public Builder model(Model model) {
            this.model = model;
            return this;
        }

        public Builder model(Function<Model.Builder, ModelBuilder<?>> config) {

            if (config == null) throw new IllegalArgumentException("Model config required");

            this.modelConfig = config;
            return this;
        }

        public Workspace build() {

            Client resolved = client;

            if (resolved == null) {

                Model resolvedModel = model;

                if (resolvedModel == null && modelConfig != null)
                    resolvedModel = modelConfig.apply(Model.builder()).build();

                if (resolvedModel == null)
                    throw new IllegalStateException("Client or Model required");

                resolved = Client.builder().model(resolvedModel).build();
            }

            return new DefaultWorkspace(resolved);
        }
    }
}
