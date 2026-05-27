# Workspace

A `Workspace` is a shared-`Client` container that emits pre-wired `Fn`, `Chat`, and `Agent` builders. Use it when you'd otherwise build a `Client` once and pass it into multiple handle builders by hand — Workspace makes that pattern explicit and removes the per-handle `.client(...)` wiring.

## Quick start

```java
var workspace = Workspace.builder()
        .model(m -> m.anthropic()
                .apiKey(System.getenv("ANTHROPIC_API_KEY"))
                .model("claude-opus-4-7"))
        .build();

Fn<WeatherReport> weatherFn = workspace.fn(WeatherReport.class)
        .systemPrompt("You forecast weather.")
        .tool(weatherTool.definition())
        .build();

Chat assistant = workspace.chat()
        .systemPrompt("You are a helpful assistant.")
        .build();

Agent researcher = workspace.agent().reAct()
        .systemPrompt("You research and answer questions.")
        .tool(searchTool)
        .build();

Client client = workspace.client();   // raw access if you need it
Model model   = workspace.model();    // the underlying Model
```

All handles share the same underlying `Model` and `Client` (so same retry policy too).

## API

```java
public interface Workspace {

    Client client();
    Model model();

    <T> Fn.Builder<T> fn(Class<T> outputType);
    Chat.Builder chat();
    Agent.Builder agent();    // selector — call .reAct() (or future .planExecute(), etc.) to pick a loop type

    static Builder builder();
}
```

`fn` and `chat` return the standard builders pre-wired with the workspace's `Client`. `agent()` returns the `Agent.Builder` selector — call `.reAct()` (today's only loop type) to get a `ReActAgent.Builder` with the same `Client` pre-wired. The selector pattern lets future loop types slot in (`.planExecute()`, `.reflective()`, …) without breaking existing call sites.

`client()` and `model()` give raw access for direct `send(...)` calls outside the higher-level handles.

## Builder

```java
public final class Builder {
    public Builder client(Client client);
    public Builder model(Model model);
    public Builder model(Function<Model.Builder, ModelBuilder<?>> config);
    public Workspace build();
}
```

Provide one of:

- `.client(Client)` — use a pre-built client (custom retry config, shared with non-workspace code).
- `.model(Model)` — wrap a built `Model` in a default `Client`.
- `.model(lambda)` — inline model config; auto-wraps in a default `Client`.

## When to use Workspace

| Scenario | Best fit |
|---|---|
| One `Fn` / one `Chat` / one `Agent` | Just build the handle directly — no need for Workspace. |
| Several handles that share a `Model` | **Workspace** — saves per-handle `.client(...)` plumbing. |
| Several handles that share a `Model` AND a `Client` retry config | **Workspace** — set retry once on `.client(Client.builder()...build())`. |
| Several handles with different `Model`s | Don't use Workspace — each handle builds its own. |

## What Workspace does *not* share

`tools` and `systemPrompt` are intentionally **not** Workspace properties. Each handle declares its own:

- A `Chat` and an `Agent` typically want different system prompts (different roles).
- An `Agent` takes `Tool` (with executors); a `Chat` takes `ToolDefinition` (schema only). Sharing one list across both forces an awkward type compromise.

If two handles genuinely share tools or a prompt, declare the list/string once in your code and pass it to each builder:

```java
var researchTools = List.of(searchTool, weatherTool);

var researcher = workspace.agent().reAct().systemPrompt("You research topics.").tools(researchTools).build();
var summarizer = workspace.fn(Summary.class)
        .systemPrompt("You summarize research output.")
        .tools(researchTools.stream().map(Tool::definition).toList())
        .build();
```

## Workspace vs no Workspace

```java
// Without Workspace
var client = Client.builder().model(m -> m.anthropic()...).build();
var fn1 = Fn.builder(WeatherReport.class).client(client).systemPrompt("...").build();
var fn2 = Fn.builder(StockReport.class).client(client).systemPrompt("...").build();
var chat = Chat.builder().client(client).systemPrompt("...").build();
```

```java
// With Workspace
var workspace = Workspace.builder().model(m -> m.anthropic()...).build();
var fn1 = workspace.fn(WeatherReport.class).systemPrompt("...").build();
var fn2 = workspace.fn(StockReport.class).systemPrompt("...").build();
var chat = workspace.chat().systemPrompt("...").build();
```

The savings are marginal per handle (`.client(client)` removed), but Workspace makes the "these all share a model" intent explicit at the type level.
