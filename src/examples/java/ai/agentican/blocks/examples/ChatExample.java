package ai.agentican.blocks.examples;

import ai.agentican.blocks.llm.api.Chat;

public final class ChatExample {

    private ChatExample() {}

    static void main(String[] args) {

        var chat = Chat.builder()
                .model(m -> m.anthropic().apiKey(Keys.require("ANTHROPIC_API_KEY")).model("claude-opus-4-7"))
                .systemPrompt("You are a helpful assistant. Keep answers under two sentences.")
                .build();

        // send(String) — direct text reply
        var reply = chat.send("Explain entropy in one sentence.");

        System.out.println("Q: Explain entropy in one sentence.");
        System.out.println("A: " + reply);

        // respond(String) — rich response with usage, stop reason, etc.
        var response = chat.respond("Now offer one analogy.");

        System.out.println();
        System.out.println("Q: Now offer one analogy.");
        System.out.println("A: " + response.text());

        System.out.println("Tokens this turn: " + response.usage().total());
        System.out.println("Stop reason: " + response.stopReason());

        // messages() — the full conversation history (snapshot)
        var history = chat.messages();

        System.out.println();
        System.out.println("Conversation length: " + history.size() + " messages");
    }

}
