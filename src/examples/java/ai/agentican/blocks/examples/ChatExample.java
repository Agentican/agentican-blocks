package ai.agentican.blocks.examples;

import ai.agentican.blocks.llm.api.Chat;

public final class ChatExample {

    private ChatExample() {}

    static void main(String[] args) {

        var apiKey = System.getenv("ANTHROPIC_API_KEY");

        if (apiKey == null || apiKey.isBlank()) {

            System.err.println("Set ANTHROPIC_API_KEY in the environment to run this example.");
            System.exit(1);
        }

        var chat = Chat.builder()
                .model(m -> m.anthropic().apiKey(apiKey).model("claude-opus-4-7"))
                .systemPrompt("You are a helpful assistant. Keep answers under two sentences.")
                .build();

        // send(String) — direct text reply
        var reply = chat.send("Explain entropy in one sentence.");

        System.out.println("Question: Explain entropy in one sentence.");
        System.out.println("Answer: " + reply);

        // respond(String) — rich response with usage, stop reason, etc.
        var response = chat.respond("Now offer one analogy.");

        System.out.println();
        System.out.println("Question: Now offer one analogy.");
        System.out.println("Answer: " + response.text());

        System.out.println("Tokens this turn: " + response.usage().total());
        System.out.println("Stop reason: " + response.stopReason());

        // messages() — the full conversation history (snapshot)
        var history = chat.messages();

        System.out.println();
        System.out.println("Conversation length: " + history.size() + " messages");
    }

}
