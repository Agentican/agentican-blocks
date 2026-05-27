package ai.agentican.blocks.examples;

import ai.agentican.blocks.llm.api.Fn;

public final class FnExample {

    record CapitalInfo(String country, String capital, long population) {}

    private FnExample() {}

    static void main(String[] args) {

        var apiKey = System.getenv("ANTHROPIC_API_KEY");

        if (apiKey == null || apiKey.isBlank()) {

            System.err.println("Set ANTHROPIC_API_KEY in the environment to run this example.");
            System.exit(1);
        }

        var fn = Fn.builder(CapitalInfo.class)
                .model(m -> m.anthropic().apiKey(apiKey).model("claude-opus-4-7"))
                .systemPrompt("You provide structured information about country capitals.")
                .build();

        // run(String) — direct typed return
        var result = fn.run("France");

        System.out.println("Country: " + result.country());
        System.out.println("Capital: " + result.capital());
        System.out.println("Population: " + result.population());

        // respond(String) — rich response with usage, stop reason, tool calls
        var response = fn.respond("Japan");

        var japan = response.output();

        System.out.println();
        System.out.println("Country: " + japan.country());
        System.out.println("Capital: " + japan.capital());
        System.out.println("Population: " + japan.population());

        System.out.println("Tokens: " + response.usage().total());
        System.out.println("Stop reason: " + response.stopReason());
    }

}
