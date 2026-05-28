package ai.agentican.blocks.examples;

import ai.agentican.blocks.llm.api.Agent;
import ai.agentican.blocks.llm.api.Tool;
import ai.agentican.blocks.llm.api.ToolDefinition;

import java.util.List;
import java.util.Map;

public final class AgentExample {

    record WeatherQuery(String city) {}

    record WeatherReport(String city, double tempF, String conditions) {}

    private AgentExample() {}

    static void main(String[] args) {

        var agent = Agent.builder().reAct()
                .model(m -> m.anthropic().apiKey(Keys.require("ANTHROPIC_API_KEY")).model("claude-opus-4-7"))
                .systemPrompt("You answer weather questions using the get_weather tool.")
                .tool(new WeatherTool())
                .build();

        // perform(task) — verbatim task, text reply
        var resultOne = agent.perform("Briefly: what's the difference between weather and climate?");

        System.out.println("Q: Briefly: what's the difference between weather and climate?");
        System.out.println("A: " + resultOne);

        // perform(task, input) — templated task, text reply
        var resultTwo = agent.perform("What's the weather in {city}?", new WeatherQuery("Tokyo"));

        System.out.println();
        System.out.println("Q: What's the weather in Tokyo?");
        System.out.println("A: " + resultTwo);

        // perform(task, Class<T>) — verbatim task, typed reply
        var weatherReportOne = agent.perform("Weather in Berlin", WeatherReport.class);

        System.out.println();
        System.out.println("City: " + weatherReportOne.city());
        System.out.println("Temp: " + weatherReportOne.tempF() + "°F");
        System.out.println("Conditions: " + weatherReportOne.conditions());

        // perform(task, input, Class<T>) — templated task, typed reply
        var weatherReportTwo = agent.perform("Weather in {city}", new WeatherQuery("Paris"), WeatherReport.class);

        System.out.println();
        System.out.println("City: " + weatherReportTwo.city());
        System.out.println("Temp: " + weatherReportTwo.tempF() + "°F");

        // respond(task, input) — rich LoopResponse<String> with usage/turns/stopReason
        var responseOne = agent.respond("Describe the weather in {city}.", new WeatherQuery("Sydney"));

        System.out.println();
        System.out.println("Q: Describe the weather in Sydney.");
        System.out.println("A: " + responseOne.text());

        System.out.println("Turns: " + responseOne.turns());
        System.out.println("Tokens: " + responseOne.usage().total());

        // respond(task, input, Class<T>) — rich LoopResponse<T>
        var responseTwo = agent.respond("Weather in {city}", new WeatherQuery("Berlin"), WeatherReport.class);

        var berlin = responseTwo.output();

        System.out.println();
        System.out.println("City: " + berlin.city());
        System.out.println("Stop reason: " + responseTwo.stopReason());
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
