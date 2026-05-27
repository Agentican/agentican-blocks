package ai.agentican.blocks.llm.api;

import ai.agentican.blocks.llm.impl.DefaultChat;
import ai.agentican.blocks.llm.impl.ModelMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public interface Chat {

    String send(String userMessage);

    ModelResponse<Void> respond(String userMessage);

    List<ModelMessage> messages();

    static Builder builder() { return new Builder(); }

    final class Builder {

        private Client client;
        private Model model;
        private Function<Model.Builder, ModelBuilder<?>> modelConfig;
        private String systemPrompt;
        private final List<ToolDefinition> tools = new ArrayList<>();

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

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder tool(ToolDefinition tool) {

            if (tool == null) throw new IllegalArgumentException("Tool required");

            tools.add(tool);
            return this;
        }

        public Builder tools(List<ToolDefinition> tools) {

            if (tools == null) throw new IllegalArgumentException("Tools list required");

            this.tools.addAll(tools);
            return this;
        }

        public Chat build() {

            Client resolved = client;

            if (resolved == null) {

                Model resolvedModel = model;

                if (resolvedModel == null && modelConfig != null)
                    resolvedModel = modelConfig.apply(Model.builder()).build();

                if (resolvedModel == null)
                    throw new IllegalStateException("Client or Model required");

                resolved = Client.builder().model(resolvedModel).build();
            }

            return new DefaultChat(resolved, systemPrompt, tools);
        }
    }
}
