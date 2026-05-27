# Responses

Every `send(...)` / `respond(...)` / `run(...)` call — whether from `Client`, `Chat`, `Fn`, or `Agent` — returns something that implements `ModelResponse<T>`. This page covers the response hierarchy and supporting types.

## The sealed hierarchy

```java
public sealed interface ModelResponse<T> permits SingleResponse, LoopResponse {
    T output();
    String text();
    List<ToolCall> toolCalls();
    StopReason stopReason();
    ModelUsage usage();
}
```

Two concrete records:

- **`SingleResponse<T>`** — produced by `Model.send(...)`, `Client.send(...)`, and `Chat.send(...)`. Represents the result of one model API call.
- **`LoopResponse<T>`** — produced by `Agent.respond(...)`. Represents the aggregated result of a multi-turn loop and adds `messages()` and `turns()`.

Because both implement the same sealed interface, code that only needs the common fields (`text()`, `usage()`, etc.) can take `ModelResponse<T>` and accept either. Code that needs loop-specific data unwraps to `LoopResponse<T>`.

## Common fields

| Field | Meaning |
|---|---|
| `output()` | Typed structured output, or `null` if `outputType` was `Void.class`. See [structured-output.md](structured-output.md). |
| `text()` | The assistant's final text response. Never `null` — defaults to `""`. |
| `toolCalls()` | Tool calls the model wants made. Empty unless `stopReason == TOOL_USE`. Immutable. For `LoopResponse`, always empty (the loop already executed all calls). |
| `stopReason()` | Why generation stopped. See below. |
| `usage()` | Token usage. For `LoopResponse`, summed across every turn. |

## StopReason

```java
public enum StopReason {
    END_TURN,    // model finished naturally with text
    TOOL_USE,    // model wants to call tools (only on SingleResponse)
    MAX_TOKENS,  // hit the maxTokens limit
    MAX_TURNS    // (LoopResponse only) hit the agent's maxTurns cap
}
```

Reading the stop reason:

- **`END_TURN`** — happy path. `text()` is the answer.
- **`TOOL_USE`** — only on `SingleResponse`. The model wants tools called. Inspect `toolCalls()` and decide how to handle them.
- **`MAX_TOKENS`** — the model ran out of token budget before finishing. `text()` is whatever was produced so far. Usually means `maxTokens(...)` was set too low.
- **`MAX_TURNS`** — only on `LoopResponse`. The agent loop exhausted its `maxTurns` cap without the model deciding to stop. `text()` is the last assistant text seen.

## SingleResponse

```java
public record SingleResponse<T>(
        T output,
        String text,
        List<ToolCall> toolCalls,
        StopReason stopReason,
        ModelUsage usage) implements ModelResponse<T> { }
```

What every provider returns. `toolCalls` is non-empty when the model wants tools.

```java
var response = model.send(systemPrompt, history, tools, Void.class);

switch (response.stopReason()) {
    case END_TURN -> System.out.println(response.text());
    case TOOL_USE -> {
        for (ToolCall call : response.toolCalls()) {
            // execute and feed results back in the next send()
        }
    }
    case MAX_TOKENS -> System.err.println("Truncated: " + response.text());
    case MAX_TURNS -> throw new IllegalStateException("Shouldn't happen on SingleResponse");
}
```

## LoopResponse

```java
public record LoopResponse<T>(
        T output,
        String text,
        List<ToolCall> toolCalls,
        StopReason stopReason,
        ModelUsage usage,
        List<ModelMessage> messages,
        int turns) implements ModelResponse<T> {

    public static <T> Builder<T> builder();
}
```

Returned by `Agent.respond(...)`. Adds two fields beyond `SingleResponse`:

| Field | Meaning |
|---|---|
| `messages()` | Full conversation history — user task, assistant turns (text + tool-use blocks), user-role tool-result messages, final assistant answer. Immutable. |
| `turns()` | Number of model calls made (between 1 and `maxTurns`). |

`toolCalls()` is always empty for `LoopResponse` — the loop already executed every tool call. `usage()` is the sum across all turns.

```java
LoopResponse<Void> result = agent.respond("...");

System.out.println("Answer: " + result.text());
System.out.println("Used " + result.turns() + " turns, " + result.usage().total() + " tokens");

if (result.stopReason() == StopReason.MAX_TURNS) {
    System.err.println("WARNING: hit maxTurns cap, agent did not finish");
}
```

### LoopResponse builder

`LoopResponse` has a typed builder. You won't usually construct one yourself — `ReActAgent` does that internally — but it's there if you're implementing a custom `Agent`:

```java
return LoopResponse.<Void>builder()
        .text("the final answer")
        .stopReason(StopReason.END_TURN)
        .usage(accumulatedUsage)
        .messages(history)
        .turns(turnCount)
        .build();
```

Defaults: `output=null`, `text=""`, `toolCalls=List.of()`, `usage=ModelUsage.ZERO`, `messages=List.of()`, `turns=0`. Only `stopReason` must be set explicitly.

## ModelUsage

```java
public record ModelUsage(
        long inputTokens,
        long outputTokens,
        long cacheReadTokens,
        long cacheWriteTokens,
        long webSearches) {

    public static final ModelUsage ZERO;
    public long total();                // sum of all four token fields
    public ModelUsage plus(ModelUsage other);
    public static ModelUsage sum(Stream<ModelUsage> usages);
}
```

Reported per call by every provider. Fields:

| Field | Meaning |
|---|---|
| `inputTokens` | Net new input tokens (prompt + appended messages). |
| `outputTokens` | Tokens the model generated. |
| `cacheReadTokens` | Tokens served from the provider's prompt cache (cheaper). |
| `cacheWriteTokens` | Tokens written into the prompt cache on this call. |
| `webSearches` | Web search requests made (Anthropic, Gemini). |

Use `total()` for a simple sum, `plus(...)` to accumulate across calls, and `sum(stream)` to aggregate a collection.

Not every provider populates every field — values default to `0` when not reported.

## Equality and immutability

All response types are `record`s, so:

- **Value equality.** Two responses with the same fields are `.equals()`.
- **Deep immutability.** Collections are defensively copied at construction; you cannot mutate a response after creation, and the response cannot be mutated through your reference to a passed-in collection.
- **Pattern matching.** Use record patterns in `switch` expressions:

```java
switch (response) {
    case SingleResponse<?>(var output, var text, var calls, var reason, var usage) ->
        handleSingle(text, calls, reason);
    case LoopResponse<?>(var output, var text, var calls, var reason, var usage, var msgs, var turns) ->
        handleLoop(text, msgs, turns);
}
```

The sealed interface means the `switch` is exhaustive — no `default` branch needed.
