# Tools

Tools let the model take actions in the outside world — fetch data, run computation, call APIs. A tool has two halves:

1. A **definition** the model sees: name, description, JSON schema for arguments.
2. An **executor** your code provides: takes the model-supplied arguments, returns a result string.

## The Tool interface

```java
public interface Tool {

    ToolDefinition definition();

    String execute(Map<String, Object> args) throws Exception;
}
```

One tool = one definition + one executor. Keeping them in the same object means the schema and the code that handles the call can't drift apart.

`execute` returns a `String` — usually JSON, but anything the model can read is fine. It's allowed to throw any exception; the agent loop will package it as an error tool-result so the model can recover.

## A minimal tool

```java
public class GetWeatherTool implements Tool {

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "get_weather",
                "Get current weather for a given city.",
                Map.of(
                        "city", Map.of("type", "string", "description", "City name, e.g. 'Tokyo'.")
                ),
                List.of("city"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        var city = (String) args.get("city");
        var weather = weatherApi.lookup(city);
        return "{\"city\":\"" + city + "\",\"tempF\":" + weather.tempF() + "}";
    }
}
```

## ToolDefinition

```java
public record ToolDefinition(
        String name,
        String description,
        Map<String, Object> properties,
        List<String> required) {

    public ToolDefinition(String name, String description, Map<String, Object> properties);
}
```

| Field | Meaning |
|---|---|
| `name` | Unique tool identifier. Must match `^[a-zA-Z0-9_-]+$`-ish — providers vary. Lowercase snake_case is safe everywhere. |
| `description` | One- or two-sentence summary the model uses to decide *when* to call the tool. Be specific. |
| `properties` | A `Map<String, Object>` representing the JSON-schema `properties` object — keys are parameter names, values are schema objects. |
| `required` | Names of required parameters. Optional parameters live in `properties` but not in `required`. |

The two-argument constructor defaults `required` to an empty list (all parameters optional).

### Writing the properties map

Each entry's value is a JSON-schema fragment expressed as a `Map<String, Object>`. Common shapes:

```java
// A string
Map.of("type", "string", "description", "City name")

// A string from a fixed set
Map.of("type", "string", "enum", List.of("celsius", "fahrenheit"))

// An integer with bounds
Map.of("type", "integer", "minimum", 1, "maximum", 100)

// An array of strings
Map.of(
    "type", "array",
    "items", Map.of("type", "string"))

// A nested object
Map.of(
    "type", "object",
    "properties", Map.of(
        "lat", Map.of("type", "number"),
        "lon", Map.of("type", "number")),
    "required", List.of("lat", "lon"))
```

The model sees these as full JSON schema, so anything JSON-schema-valid works.

### Optional vs required

```java
// All required
new ToolDefinition(
        "search",
        "Search the web.",
        Map.of(
                "query", Map.of("type", "string"),
                "limit", Map.of("type", "integer")),
        List.of("query", "limit"));

// Only `query` required; `limit` optional
new ToolDefinition(
        "search",
        "Search the web.",
        Map.of(
                "query", Map.of("type", "string"),
                "limit", Map.of("type", "integer")),
        List.of("query"));
```

## ToolCall — what the model emits

When the model decides to call a tool, the provider translates that into a `ToolCall`:

```java
public record ToolCall(
        String id,
        String name,
        Map<String, Object> args) { }
```

| Field | Meaning |
|---|---|
| `id` | Unique correlation id assigned by the model. Echo this in the matching tool result. |
| `name` | The `ToolDefinition.name()` the model wants to invoke. |
| `args` | JSON-decoded arguments. May contain `null` values. Immutable. |

You usually don't construct `ToolCall`s — the provider does it when translating the model's response. But you receive them on `response.toolCalls()` and pass `call.args()` to your `Tool.execute(...)`.

## What happens when a tool throws

Inside a `ReActAgent`, a thrown `Exception` becomes a `ToolResultMessageBlock` with `isError=true` and `content` set to the exception message. The agent loop continues so the model can self-correct.

```java
public String execute(Map<String, Object> args) {
    var city = (String) args.get("city");
    if (city == null || city.isBlank())
        throw new IllegalArgumentException("city is required");
    return weatherApi.lookup(city);
}
```

If the model calls `get_weather` with `args={}`, the next user message becomes:

```
USER: [ToolResultMessageBlock(toolUseId="tu_1", content="city is required", isError=true)]
```

The model sees the error, often apologizes, and retries with the correct args.

## What happens for unknown tools

If the model hallucinates a tool name that wasn't registered, the agent emits:

```
USER: [ToolResultMessageBlock(toolUseId="tu_X", content="Unknown tool: <name>", isError=true)]
```

Same recovery pattern — the model usually picks a real tool on the next turn.

## Registering tools

For one-shot `Client.send(...)` calls, pass `List<ToolDefinition>`:

```java
var defs = List.of(weatherTool.definition(), calculatorTool.definition());
var response = client.send(systemPrompt, history, defs, Void.class);

for (ToolCall call : response.toolCalls()) {
    // route call.name() to the matching tool yourself
}
```

For agent loops, just hand the `Tool` objects to the builder and the agent handles routing:

```java
Agent.builder().reAct()
        .provider(...)
        .systemPrompt(...)
        .tool(weatherTool)
        .tool(calculatorTool)
        // or: .tools(List.of(weatherTool, calculatorTool))
        .build();
```

Tool names must be unique within the registered set — `ReActAgent.Builder.build()` throws `IllegalStateException` on duplicates.

## Designing good tools

A few patterns that hold up across providers:

- **Single responsibility.** A tool that does one thing well is easier for the model to choose correctly than a swiss-army knife.
- **Clear descriptions.** The description is the model's primary signal for *when* to use the tool. Say what the tool does AND when not to use it: "Get current weather for a city. Do not use for forecasts beyond 24 hours."
- **Return structured output.** JSON or a stable line format. The model parses your output as text — make it easy.
- **Validate args yourself.** Don't trust the model to follow the schema perfectly. Throw `IllegalArgumentException` with a clear message; the model will recover.
- **Keep results small.** Big tool outputs eat context. If you must return a lot, summarize and offer a `details_id` the model can request later.
