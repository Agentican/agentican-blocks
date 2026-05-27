package ai.agentican.blocks.examples;

import ai.agentican.blocks.llm.api.Agent;
import ai.agentican.blocks.llm.api.Tool;
import ai.agentican.blocks.llm.api.ToolDefinition;

import java.util.List;
import java.util.Map;

public final class AgentExample {

    record WeatherReport(String city, double tempF, String conditions) {}

    private AgentExample() {}

    static void main(String[] args) {

        var apiKey = System.getenv("ANTHROPIC_API_KEY");

        if (apiKey == null || apiKey.isBlank()) {

            System.err.println("Set ANTHROPIC_API_KEY in the environment to run this example.");
            System.exit(1);
        }

        var agent = Agent.builder().reAct()
                .model(m -> m.anthropic().apiKey(apiKey).model("claude-opus-4-7"))
                .systemPrompt("You answer weather questions using the get_weather tool.")
                .tool(new WeatherTool())
                .build();

        // perform(String) — direct text answer
        var reply = agent.perform("What's the weather in Tokyo?");

        System.out.println("Question: What's the weather in Tokyo?");
        System.out.println("Answer: " + reply);

        // perform(String, Class<T>) — direct typed answer
        var result = agent.perform("What's the weather in Paris?", WeatherReport.class);

        System.out.println();
        System.out.println("City: " + result.city());
        System.out.println("Temp: " + result.tempF() + "°F");
        System.out.println("Conditions: " + result.conditions());

        // respond(String) — rich LoopResponse<Void> with usage/turns/stopReason/history
        var firstResponse = agent.respond("What's the weather in Sydney?");

        System.out.println();
        System.out.println("Question: What's the weather in Sydney?");
        System.out.println("Answer: " + firstResponse.text());

        System.out.println("Turns: " + firstResponse.turns());
        System.out.println("Tokens: " + firstResponse.usage().total());

        // respond(String, Class<T>) — rich LoopResponse<T> with typed output
        var secondResponse = agent.respond("What's the weather in Berlin?", WeatherReport.class);

        var berlin = secondResponse.output();

        System.out.println();
        System.out.println("City: " + berlin.city());
        System.out.println("Temp: " + berlin.tempF() + "°F");

        System.out.println("Turns: " + secondResponse.turns());
        System.out.println("Stop reason: " + secondResponse.stopReason());
    }

    static final class WeatherTool implements Tool {

        @Override public ToolDefinition definition() {

            return new ToolDefinition(
                    "get_weather",
                    "Get the current weather for a city.",
                    Map.of("city", Map.of("type", "string")),
                    List.of("city"));
        }

        @Override public String execute(Map<String, Object> args) {

            var city = (String) args.get("city");

            return "{\"city\":\"" + city + "\",\"tempF\":72,\"conditions\":\"sunny\"}";
        }
    }
}
