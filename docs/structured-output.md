# Structured output

For programmatic use, you often want a typed Java object back instead of free-form text. agentican-blocks supports this via the `outputType` parameter on `Client`-level send methods, and via [`Fn`](fn.md) for bound-prompt single-shot typed calls.

## Quick example with `Fn` (recommended for typed work)

```java
public record WeatherReport(String city, double tempF, String conditions) {}

var fn = Fn.builder(WeatherReport.class)
        .model(m -> m.anthropic()
                .apiKey(System.getenv("ANTHROPIC_API_KEY"))
                .model("claude-opus-4-7"))
        .systemPrompt("You are a weather service.")
        .build();

WeatherReport report = fn.run("Weather in Tokyo?");

System.out.println(report.city() + ": " + report.tempF() + "°F");
```

## Same thing via `Client` (no bound state)

```java
var client = Client.builder()
        .model(m -> m.anthropic().apiKey(KEY).model("claude-opus-4-7"))
        .build();

var response = client.send(ModelRequest.builder(WeatherReport.class)
        .systemPrompt("You are a weather service.")
        .userMessage("Weather in Tokyo?")
        .build());

WeatherReport report = response.output();
```

The difference is whether the system prompt + tools live on a long-lived `Fn` (reused across calls) or on each `ModelRequest` (independent). Both produce identical wire traffic.

The library uses [victools/jsonschema-generator](https://github.com/victools/jsonschema-generator) plus the Jackson module to derive a JSON schema from your Java class. That schema is sent to the model as the required output shape. On the way back, the JSON is parsed into your type with Jackson.

## What can be the output type?

Anything Jackson can deserialize and victools can describe with JSON schema:

- **Records** with primitive, `String`, `List`, `Map`, or other-record fields.
- **POJOs** with a public no-arg constructor and getters/setters (or Jackson annotations).
- **Generic collections** — though usually you want a wrapper record (Java's type erasure makes raw `List<WeatherReport>.class` awkward).

```java
public record TaskList(List<TaskItem> items) {}
public record TaskItem(String title, boolean done, int priority) {}

var response = model.send(systemPrompt, history, tools, TaskList.class);
TaskList tasks = response.output();
```

## Unstructured output

Pass `Void.class` (or use `ModelRequest.builder()` which defaults to it) when you don't care about a typed return:

```java
var response = client.send(
        "You are helpful.",
        history,
        tools,
        Void.class);

// response.output() is null
// response.text()   is the assistant's reply
```

`Utils.isUnstructured(type)` returns `true` for `null` and `Void.class` — providers use this internally to decide whether to attach a schema.

## Behavior across providers

| Provider | How it works |
|---|---|
| Anthropic | Output schema attached to the request. The model returns text and the library JSON-decodes the final assistant text into your type. |
| OpenAI | Uses the Responses API's `text.format` JSON-schema constraint. |
| Gemini | Uses `responseSchema` on the `GenerateContentConfig`. |
| Bedrock | Schema appended to the system prompt as text instructions. The model returns JSON in the assistant text; the library parses it. |
| OpenAI-compatible | Uses `response_format: json_schema` from the OpenAI Chat Completions spec. |

The user-facing behavior is uniform: pass a `Class<T>`, get a `T` back via `response.output()`. Behind the scenes each provider does what its API supports.

## Interaction with tools

You can request structured output **and** allow tool use in the same call. The model uses tools until it's ready to answer, then its final assistant message is parsed into your type.

```java
var response = client.send(
        systemPrompt,
        history,
        List.of(weatherTool.definition()),
        WeatherReport.class);

if (response.stopReason() == StopReason.TOOL_USE) {
    // Handle tool calls manually for one-shot send().
    // For automated tool execution + structured final answer, use a ReAct agent.
}

WeatherReport report = response.output();
```

In a `ReActAgent` loop today, the loop hardcodes `Void.class` and returns the final text — structured output isn't yet exposed through the agent builder. To get typed agent results, use `Client.send(...)` directly or build your own loop.

## Schema generation

The schema is produced by:

```java
Utils.schema(WeatherReport.class)  // returns a JsonNode
```

You don't usually call this directly — providers do. But if you want to inspect what the model sees:

```java
System.out.println(Utils.schema(WeatherReport.class).toPrettyString());
```

This is useful for debugging "the model didn't return what I expected" — verify the schema matches your mental model first.

## Error handling

If the model returns malformed JSON or JSON that doesn't match the schema:

- Providers that natively enforce schemas (OpenAI, Gemini) almost always return valid JSON.
- Providers without native enforcement (Bedrock, sometimes Anthropic) may produce close-but-not-quite-right output.

When parsing fails, the provider returns `output = null` and the raw `text` is still populated. Always defensive-check:

```java
WeatherReport report = response.output();
if (report == null) {
    // Fall back to parsing response.text() yourself, or retry with a stricter prompt.
}
```
