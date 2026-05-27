package ai.agentican.blocks.llm.api;

import ai.agentican.blocks.llm.agent.ReActAgent;

public interface Agent {

    String perform(String task);

    <T> T perform(String task, Class<T> outputType);

    LoopResponse<Void> respond(String task);

    <T> LoopResponse<T> respond(String task, Class<T> outputType);

    static Builder builder() { return new Builder(); }

    final class Builder {

        private Client client;

        Builder() {}

        public Builder client(Client client) {
            this.client = client;
            return this;
        }

        public ReActAgent.Builder reAct() {

            var builder = ReActAgent.builder();

            if (client != null) builder.client(client);

            return builder;
        }
    }
}
