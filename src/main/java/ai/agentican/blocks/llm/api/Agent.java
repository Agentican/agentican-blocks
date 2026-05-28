package ai.agentican.blocks.llm.api;

import ai.agentican.blocks.llm.agent.ReActAgent;

public interface Agent {

    default String perform(String task) {
        return respond(task).output();
    }

    default String perform(String task, Object input) {
        return respond(task, input).output();
    }

    default <T> T perform(String task, Class<T> outputType) {
        return respond(task, outputType).output();
    }

    default <T> T perform(String task, Object input, Class<T> outputType) {
        return respond(task, input, outputType).output();
    }

    default LoopResponse<String> respond(String task) {
        return respond(task, null, String.class);
    }

    default LoopResponse<String> respond(String task, Object input) {
        return respond(task, input, String.class);
    }

    default <T> LoopResponse<T> respond(String task, Class<T> outputType) {
        return respond(task, null, outputType);
    }

    <T> LoopResponse<T> respond(String task, Object input, Class<T> outputType);

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
