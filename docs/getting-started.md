# Getting started

This walkthrough takes you from a fresh project to a working ReAct agent in five steps.

## 1. Add the dependency

```xml
<dependency>
    <groupId>ai.agentican</groupId>
    <artifactId>agentican-blocks</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

The provider SDKs (Anthropic, OpenAI, Gemini, AWS Bedrock) are declared `<optional>true</optional>`. Pull in only the ones you actually call:

```xml
<!-- pick one or more -->
<dependency>
    <groupId>com.anthropic</groupId>
    <artifactId>anthropic-java</artifactId>
</dependency>
<dependency>
    <groupId>com.openai</groupId>
    <artifactId>openai-java</artifactId>
</dependency>
<dependency>
    <groupId>com.google.genai</groupId>
    <artifactId>google-genai</artifactId>
</dependency>
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>bedrockruntime</artifactId>
</dependency>
```

agentican-blocks itself only requires Jackson and SLF4J at runtime.

## 2. Build a Client

A `Model` represents one vendor endpoint. A `Client` wraps a `Model` with retry/backoff and session support. Build a `Client` directly via `Client.builder()` with a model-config lambda:

```java
var client = Client.builder()
        .model(m -> m.anthropic()
                .apiKey(System.getenv("ANTHROPIC_API_KEY"))
                .model("claude-opus-4-7")
                .maxTokens(4096))
        .build();
```

If you just want a raw `Model` (no retry), use `Model.builder().anthropic()...build()` instead — see [providers.md](providers.md). Each vendor has its own builder with vendor-specific knobs (API key, base URL, AWS region, …).

## 3. Make a one-shot call

```java
var response = client.send(ModelRequest.builder()
        .systemPrompt("You are a concise technical writer.")
        .userMessage("Explain Java records in two sentences.")
        .build());

System.out.println(response.text());
System.out.println("Tokens used: " + response.usage().total());
```

`response.text()` is the assistant's reply. `response.stopReason()` tells you why generation stopped (`END_TURN`, `MAX_TOKENS`, `TOOL_USE`). See [responses.md](responses.md).

## 4. Add a tool

Define a `Tool` by implementing two methods — `definition()` (JSON schema the model sees) and `execute(Map<String, Object> args)` (your code that runs when the model calls it):

```java
public class CalculatorTool implements Tool {

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "calculate",
                "Evaluate a basic arithmetic expression like 2 + 2 * 3.",
                Map.of("expression", Map.of("type", "string")),
                List.of("expression"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        var expr = (String) args.get("expression");
        return Double.toString(eval(expr));
    }

    private double eval(String expr) { /* ... */ }
}
```

The `execute` method returns a `String` — typically JSON, but anything the model can read works. If it throws, the agent loop will package the error as a `tool_result` with `isError=true` and let the model recover.

See [tools.md](tools.md) for the full Tool API.

## 5. Run a ReAct agent

`Agent.builder()` is the entry point. `.reAct()` returns a fresh `ReActAgent.Builder`. Configure a model (inline lambda or pre-built), a system prompt, and your tools:

```java
var agent = Agent.builder().reAct()
        .model(m -> m.anthropic()
                .apiKey(System.getenv("ANTHROPIC_API_KEY"))
                .model("claude-opus-4-7"))
        .systemPrompt("You are a helpful math assistant. Use tools when arithmetic is needed.")
        .tool(new CalculatorTool())
        .maxTurns(10)
        .build();

var result = agent.respond("What's (47 * 13) + (1024 / 16)?");

System.out.println(result.text());                 // final answer
System.out.println("Turns: " + result.turns());    // how many model calls it took
System.out.println("Tokens: " + result.usage().total());
```

Each `turn` is one model call. The loop keeps going until the model either stops with text (`END_TURN`) or you hit `maxTurns` (in which case `result.stopReason() == MAX_TURNS`).

See [agents.md](agents.md) for the full agent API, including how to access the conversation history.

## Where to next

- Need typed output instead of free text? See [structured-output.md](structured-output.md).
- Building a chat interface? See [sessions.md](sessions.md).
- Want to swap providers? See [providers.md](providers.md) — usually a one-line change.
- Want to understand what `ModelResponse`, `SingleResponse`, `LoopResponse` are? See [responses.md](responses.md).
- Need to handle transient failures gracefully? See [errors-and-retries.md](errors-and-retries.md).
