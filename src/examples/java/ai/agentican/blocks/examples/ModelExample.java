package ai.agentican.blocks.examples;

import ai.agentican.blocks.llm.api.Model;
import ai.agentican.blocks.llm.impl.ModelMessage;
import ai.agentican.blocks.llm.impl.TextMessageBlock;

import java.util.List;

public final class ModelExample {

    private ModelExample() {}

    static void main(String[] args) {

        var model = Model.builder().anthropic().apiKey(Keys.require("ANTHROPIC_API_KEY")).model("claude-opus-4-7").build();

        var messages = List.of(ModelMessage.user(TextMessageBlock.of("Explain entropy in one sentence.")));

        var response = model.send("You are a helpful assistant.", messages, List.of(), Void.class);

        System.out.println("Question: Explain entropy in one sentence.");
        System.out.println("Answer: " + response.text());
    }
}
