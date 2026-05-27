package ai.agentican.blocks.examples;

import ai.agentican.blocks.llm.api.Model;
import ai.agentican.blocks.llm.impl.ModelMessage;
import ai.agentican.blocks.llm.impl.TextMessageBlock;

import java.util.List;

public final class ModelExample {

    private ModelExample() {}

    static void main(String[] args) {

        var apiKey = System.getenv("ANTHROPIC_API_KEY");

        if (apiKey == null || apiKey.isBlank()) {

            System.err.println("Set ANTHROPIC_API_KEY in the environment to run this example.");
            System.exit(1);
        }

        var model = Model.builder().anthropic().apiKey(apiKey).model("claude-opus-4-7").build();

        var messages = List.of(ModelMessage.user(TextMessageBlock.of("Explain entropy in one sentence.")));

        var response = model.send("You are a helpful assistant.", messages, List.of(), Void.class);

        System.out.println("Question: Explain entropy in one sentence.");
        System.out.println("Answer: " + response.text());
    }
}
