# Agentican Blocks

A small Java library for calling large-language-model providers behind a single uniform API, plus building blocks for agents (tool use, multi-turn loops, structured output).

`agentican-blocks` wraps seven providers — **Anthropic**, **OpenAI**, **Google Gemini**, **AWS Bedrock**, **Hugging Face** (Inference Providers Router with partner routing), **Cohere** (native SDK), and **OpenAI-compatible** endpoints (Groq, SambaNova, Together, Fireworks, and friends) — behind one `Client` / `ModelResponse` API, so switching providers is a one-line builder change instead of a code rewrite.

It also ships a minimal but production-shaped ReAct agent loop and an `Agent` interface that you can extend with other loop styles.

## What you get

- **One uniform `send(...)`** across providers
- **Automatic retry with exponential backoff** on transient failures
- **Tool calling** with a developer-implemented `Tool` interface
- **Structured (typed) output** via a JSON-schema-driven `Class<T> outputType`
- **`Chat`** for stateful chat
- **`Agent` + `ReActAgent`** for the standard reason-act-observe loop
- **No required runtime dependencies on every provider** — provider SDKs are `<optional>true</optional>` in the POM; pull in only what you use

## Documentation map

Start here:

- [Getting started](getting-started.md) — install, configure a provider, make your first call, then run a ReAct agent.

Reference:

- [Providers](providers.md) — building Anthropic, OpenAI, Gemini, Bedrock, and OpenAI-compatible providers.
- [Messages](messages.md) — `ModelMessage`, roles, and the four block types (`TextMessageBlock`, `ToolUseMessageBlock`, `ToolResultMessageBlock`).
- [Tools](tools.md) — defining tools the model can call (`Tool`, `ToolDefinition`, `ToolCall`).
- [Structured output](structured-output.md) — typed responses driven by Jackson + JSON schema.
- [Chat](chat.md) — stateful user-driven free-form chat with `Chat`.
- [Fn](fn.md) — single-turn typed input → typed output with `Fn`.
- [Agents](agents.md) — the `Agent` interface and `ReActAgent`.
- [Workspace](workspace.md) — shared-`Client` container that emits pre-wired `Fn`, `Chat`, and `Agent` builders.
- [Responses](responses.md) — the `ModelResponse` sealed hierarchy, `SingleResponse`, `LoopResponse`, `StopReason`, `ModelUsage`.
- [Errors and retries](errors-and-retries.md) — what gets retried, what doesn't, and how to react to failures.

## A 30-second taste

```java
// One-shot call
var chat = Chat.builder()
        .model(m -> m.anthropic()
                .apiKey(System.getenv("ANTHROPIC_API_KEY"))
                .model("claude-opus-4-7"))
        .systemPrompt("You are a helpful assistant.")
        .build();

String answer = chat.send("Summarize the second law of thermodynamics in one sentence.");

System.out.println(answer);
```

```java
// ReAct agent with a tool
var weather = new Tool() {
    
    @Override public ToolDefinition definition() {
        
        return new ToolDefinition(
                "get_weather",
                "Get current weather for a city.",
                Map.of("city", Map.of("type", "string")),
                List.of("city"));
    }
    
    @Override public String execute(Map<String, Object> args) {
        return "{\"city\":\"" + args.get("city") + "\",\"tempF\":72}";
    }
};

var agent = Agent.builder().reAct()
        .model(m -> m.anthropic().apiKey(KEY).model("claude-opus-4-7"))
        .systemPrompt("You are a helpful weather assistant.")
        .tool(weather)
        .build();

String result = agent.perform("What's the weather in Tokyo?");

System.out.println(result);
```

## Versioning and stability

The library is in active development; the public API may change. Each release is tested against all five providers. When breaking changes happen, the docs and call sites move together — there is no back-compat shim layer.
