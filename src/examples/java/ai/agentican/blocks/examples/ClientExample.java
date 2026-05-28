package ai.agentican.blocks.examples;

import ai.agentican.blocks.llm.api.Client;
import ai.agentican.blocks.llm.api.ModelRequest;
import ai.agentican.blocks.llm.impl.ModelMessage;
import ai.agentican.blocks.llm.impl.TextMessageBlock;

import java.util.List;

public final class ClientExample {

    private ClientExample() {}

    static void main(String[] args) {

        var client = Client.builder()
                .model(m -> m.anthropic().apiKey(Keys.require("ANTHROPIC_API_KEY")).model("claude-opus-4-7"))
                .build();

        // send(ModelRequest) — the high-level firstResponse-input form
        var firstResponse = client.send(ModelRequest.builder()
                .systemPrompt("You are a helpful assistant.")
                .userMessage("Explain entropy in one sentence.")
                .build());

        System.out.println("Question (firstResponse): Explain entropy in one sentence.");
        System.out.println("Answer: " + firstResponse.text());

        // send(systemPrompt, messages, tools, Class<T>) — the low-level form
        var messages = List.of(ModelMessage.user(TextMessageBlock.of("Name three primary colors.")));

        var secondResponse = client.send("You are a helpful assistant.", messages, List.of(), Void.class);

        System.out.println();
        System.out.println("Question (low-level): Name three primary colors.");
        System.out.println("Answer: " + secondResponse.text());
    }
}
