# Fn

An `Fn<T>` is a single-shot typed call against an LLM — input goes in, typed output of type `T` comes out, no history kept between calls. Use it when you have a specific output shape in mind ("extract this", "classify that", "summarize into a record"). For conversational, free-form interaction use [`Chat`](chat.md) instead.

The output type is bound at construction, so call sites don't repeat it:

```java
WeatherReport report = fn.run("Tokyo");
```

## Quick start

```java
public record WeatherReport(String city, double tempF, String conditions) {}

var fn = Fn.builder(WeatherReport.class)
        .model(m -> m.anthropic()
                .apiKey(System.getenv("ANTHROPIC_API_KEY"))
                .model("claude-opus-4-7"))
        .systemPrompt("You are a weather service. Respond with structured JSON.")
        .build();

WeatherReport report = fn.run("Weather in Tokyo?");
```

## API

```java
public interface Fn<T> {

    T run(String userMessage);
    ModelResponse<T> respond(String userMessage);

    static <T> Builder<T> builder(Class<T> outputType);
}
```

Two methods:

- **`T run(String)`** — direct. Returns the typed payload. Use when you only need the output.
- **`ModelResponse<T> respond(String)`** — rich. Returns the full response (text, usage, stop reason, tool calls, output). Use when you need metadata.

The bound `systemPrompt` and `tools` (set on the builder) apply on every call. There's no per-call override; if you need per-call control, drop down to `Client.send(ModelRequest)`.

## Builder

`Fn.builder(Class<T>)` is the only entry point — `T` must be specified at construction time. (Use `Client.send(ModelRequest)` for untyped one-shot calls.)

| Method | Required | Purpose |
|---|---|---|
| `Fn.builder(Class<T>)` | yes | Entry. Binds the output type. |
| `.model(Function<Model.Builder, ModelBuilder<?>>)` | one of | Configure the model inline; auto-wrapped in `DefaultClient`. |
| `.model(Model)` | one of | Use a pre-built `Model`; auto-wrapped in `DefaultClient`. |
| `.client(Client)` | one of | Use a pre-built `Client` (custom retry config, shared client). |
| `.systemPrompt(String)` | no | Optional system prompt — applies to every send. |
| `.tool(ToolDefinition)` / `.tools(List<ToolDefinition>)` | no | Optional tools — applies to every send. |
| `.build()` | yes | Returns the `Fn<T>`. |

## Accessing metadata

For usage stats, stop reason, or tool calls, use `respond(...)`:

```java
var response = fn.respond("Tokyo");

WeatherReport report = response.output();
ModelUsage usage = response.usage();
StopReason reason = response.stopReason();
List<ToolCall> toolCalls = response.toolCalls();
```

## Fn vs Chat vs Agent

| Type | Driven by | History | Typical use |
|---|---|---|---|
| [`Fn<T>`](fn.md) | One call in, one typed response out | No history | "Extract WeatherReport from this text." |
| [`Chat`](chat.md) | User-driven multi-turn | Yes | Free-form conversation. |
| [`Agent`](agents.md) | Model-driven loop with tool execution | Yes | "Research this and answer." |

## Without an Fn

If you only have a one-off typed call (no shared system prompt or tools to bind), `Client.send(ModelRequest)` works without setting up an Fn:

```java
var client = Client.builder()
        .model(m -> m.anthropic().apiKey(KEY).model("claude-opus-4-7"))
        .build();

WeatherReport report = client.send(ModelRequest.builder(WeatherReport.class)
        .systemPrompt("You are a weather service.")
        .userMessage("Tokyo")
        .build())
        .output();
```

`Fn` saves you from repeating the system prompt and tool definitions on every call when they don't change, and lets you write `fn.run("Tokyo")` instead of building a `ModelRequest` each time.
