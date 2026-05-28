package ai.agentican.blocks.examples;

import ai.agentican.blocks.llm.api.Fn;

public final class FnExample {

    record CountryQuery(String country) {}

    record CountryInfo(String country, String capital, long population) {}

    private FnExample() {}

    static void main(String[] args) {

        var fn = Fn.builder(CountryQuery.class, CountryInfo.class)
                .model(m -> m.anthropic().apiKey(Keys.require("ANTHROPIC_API_KEY")).model("claude-opus-4-7"))
                .systemPrompt("You provide structured information about country capitals.")
                .userPrompt("Provide the capital and population of {country}.")
                .build();

        // run(I) — direct typed return
        var countryInfoFr = fn.run(new CountryQuery("France"));

        System.out.println("Country: " + countryInfoFr.country());
        System.out.println("Capital: " + countryInfoFr.capital());
        System.out.println("Population: " + countryInfoFr.population());

        // respond(I) — rich response with usage, stop reason, tool calls
        var response = fn.respond(new CountryQuery("Japan"));

        var countryInfoJp = response.output();

        System.out.println();
        System.out.println("Country: " + countryInfoJp.country());
        System.out.println("Capital: " + countryInfoJp.capital());
        System.out.println("Population: " + countryInfoJp.population());

        System.out.println("Tokens: " + response.usage().total());
        System.out.println("Stop reason: " + response.stopReason());
    }
}
