# Providers

A `Model` is the thin wrapper around a vendor SDK. It exposes one method:

```java
<T> ModelResponse<T> send(String systemPrompt, List<ModelMessage> messages,
                          List<ToolDefinition> tools, Class<T> outputType);
```

You don't usually call `send` on a raw `Model` — instead, wrap it in a `Client` via `Client.builder().model(...)` (which adds retry/backoff and session support), or pass the model to an `Agent.Builder` / `Chat.Builder`. But you always *configure* it with the same fluent builder.

## Entry point

```java
Model.builder()  // returns Model.Builder
```

`Model.Builder` is a selector — call one of these to pick a vendor:

| Method | Returns | Backend |
|---|---|---|
| `.anthropic()` | `Anthropic.Builder` | Claude (anthropic-java SDK) |
| `.openai()` | `OpenAi.Builder` | OpenAI Responses API |
| `.groq()` | `OpenAi.Builder` | Groq via the OpenAI-compatible Responses API |
| `.gemini()` | `Gemini.Builder` | Google GenAI |
| `.bedrock()` | `Bedrock.Builder` | AWS Bedrock Converse API |
| `.sambanova()` | `OpenAiCompatible.Builder` | SambaNova (preconfigured base URL) |
| `.together()` | `OpenAiCompatible.Builder` | Together AI |
| `.fireworks()` | `OpenAiCompatible.Builder` | Fireworks AI |
| `.openAiCompatible()` | `OpenAiCompatible.Builder` | Any OpenAI Chat Completions API |

## Common builder methods

Every concrete builder implements `ModelBuilder<B>`:

```java
B model(String model);
B maxTokens(long maxTokens);
B temperature(Double temperature);
Model build();
```

- `model(String)` — required. The model identifier as the vendor expects it (`"claude-opus-4-7"`, `"gpt-4o-mini"`, `"gemini-2.5-pro"`, etc.).
- `maxTokens(long)` — optional. Defaults to `Model.DEFAULT_MAX_TOKENS` (16384). Caps assistant output length.
- `temperature(Double)` — optional. `null` lets the vendor pick its default.
- `build()` — returns a `Model` (raw, no retry). For a production `Client` (retry + session-spawning), use `Client.builder().model(...)`.

## Anthropic

```java
var provider = Model.builder().anthropic()
        .apiKey(System.getenv("ANTHROPIC_API_KEY"))
        .model("claude-opus-4-7")
        .maxTokens(8192)
        .temperature(0.2)
        .build();
```

| Method | Purpose |
|---|---|
| `.apiKey(String)` | Anthropic API key (or set `ANTHROPIC_API_KEY` env var and omit) |
| `.model(String)` | Model name, e.g. `"claude-opus-4-7"` |

Web search and web fetch tools are automatically enabled on every request.

## OpenAI

```java
var provider = Model.builder().openai()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .model("gpt-4o")
        .build();
```

| Method | Purpose |
|---|---|
| `.apiKey(String)` | OpenAI API key |
| `.provider(String)` | Routing hint — `OpenAi.OPENAI` (default) or `OpenAi.GROQ` |
| `.model(String)` | Model name |

Uses OpenAI's Responses API (not the legacy Chat Completions API).

### Groq

`Model.builder().groq()` is shorthand for `.openai().provider(OpenAi.GROQ)`:

```java
var provider = Model.builder().groq()
        .apiKey(System.getenv("GROQ_API_KEY"))
        .model("llama-3.3-70b-versatile")
        .build();
```

## Gemini

```java
var provider = Model.builder().gemini()
        .apiKey(System.getenv("GOOGLE_API_KEY"))
        .model("gemini-2.5-pro")
        .build();
```

| Method | Purpose |
|---|---|
| `.apiKey(String)` | Google AI Studio API key |
| `.model(String)` | Model name |

## Bedrock

```java
var provider = Model.builder().bedrock()
        .accessKeyId(System.getenv("AWS_ACCESS_KEY_ID"))     // optional — falls back to DefaultCredentialsProvider
        .secretAccessKey(System.getenv("AWS_SECRET_ACCESS_KEY"))
        .region("us-west-2")
        .model("anthropic.claude-3-5-sonnet-20241022-v2:0")
        .build();
```

| Method | Purpose |
|---|---|
| `.accessKeyId(String)` | AWS access key ID. Omit to use the default AWS credentials chain. |
| `.secretAccessKey(String)` | AWS secret access key. Must be set together with `accessKeyId`. |
| `.region(String)` | AWS region, e.g. `"us-west-2"` |
| `.model(String)` | Bedrock model ID, e.g. `"anthropic.claude-3-5-sonnet-20241022-v2:0"` |

Uses Bedrock's Converse API.

## OpenAI-compatible

Many providers (Groq, SambaNova, Together, Fireworks, vLLM, llama.cpp, …) speak OpenAI's Chat Completions API. Use `Model.builder().openAiCompatible()` and point it at the right base URL:

```java
var provider = Model.builder().openAiCompatible()
        .baseUrl("https://api.together.xyz/v1")
        .apiKey(System.getenv("TOGETHER_API_KEY"))
        .model("meta-llama/Llama-3.3-70B-Instruct-Turbo")
        .build();
```

### Preconfigured shortcuts

For the most common OpenAI-compatible providers, base URLs are wired in:

```java
Model.builder().sambanova().apiKey(...).model(...).build();
Model.builder().together().apiKey(...).model(...).build();
Model.builder().fireworks().apiKey(...).model(...).build();
```

The shortcut sets the `baseUrl` for you; everything else is the same.

| Method | Purpose |
|---|---|
| `.apiKey(String)` | API key for the upstream service |
| `.baseUrl(String)` | Override the API endpoint (preset by the shortcut, or set manually) |
| `.model(String)` | Model name as the upstream service expects it |

## Switching providers

Because every provider returns the same `Model` and goes through the same `Client` / `ModelResponse` API, switching providers is almost always a one-line change:

```java
// Before
var provider = Model.builder().anthropic().apiKey(KEY).model("claude-opus-4-7").build();

// After
var provider = Model.builder().openai().apiKey(KEY).model("gpt-4o").build();
```

Everything downstream — `Client`, `Chat`, `Agent`, `ReActAgent` — stays identical.

## What's not supported (yet)

- **Streaming** — `send` is request/response; partial tokens are not exposed.
- **Multi-modal input** — message blocks are text + tool-use + tool-result only. No image, audio, or PDF blocks.
- **Vendor-specific knobs** beyond `model`, `maxTokens`, `temperature`, and credentials. If you need top-p, logit bias, etc., you'll need to extend the relevant provider.
