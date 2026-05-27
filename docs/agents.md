# Agents

An `Agent` runs a model-driven multi-turn loop: the model reasons, calls tools, observes results, and keeps going until it has an answer. Unlike `Chat` (which is user-driven — each `send` is a user turn), an agent's loop is driven by the model's decisions.

`ReActAgent` is the first concrete implementation, following the classic Reason-Act-Observe pattern. More loop types (plan-execute, reflective, etc.) will be added behind the same `Agent` interface.

## The Agent interface

```java
public interface Agent {

    String perform(String task);                                  // direct, text
    <T> T perform(String task, Class<T> outputType);              // direct, typed

    LoopResponse<Void> respond(String task);                      // rich, untyped
    <T> LoopResponse<T> respond(String task, Class<T> outputType); // rich, typed

    static Builder builder() { return new Builder(); }

    final class Builder {
        public ReActAgent.Builder reAct();
        // future: planExecute(), reflective(), ...
    }
}
```

`perform` returns the answer directly. `respond` returns the rich `LoopResponse` for usage/stopReason/messages/turns.

`Agent.builder()` is the entry point. `.reAct()` returns a fresh `ReActAgent.Builder` — the same pattern as `Model.builder().anthropic()`, where the parent builder is a selector for the concrete type.

## Building a ReAct agent

```java
var agent = Agent.builder().reAct()
        .model(m -> m.anthropic()
                .apiKey(System.getenv("ANTHROPIC_API_KEY"))
                .model("claude-opus-4-7"))
        .systemPrompt("You are a helpful research assistant.")
        .tool(searchTool)
        .tool(fetchTool)
        .maxTurns(10)
        .build();
```

`ReActAgent.Builder` methods:

| Method | Required | Purpose |
|---|---|---|
| `.model(Function<Model.Builder, ModelBuilder<?>>)` | yes | Configure the model inline. The lambda receives `Model.Builder` and returns the configured concrete builder; the agent calls `.build()` on it internally and wraps in a `DefaultClient` so retries are automatic. |
| `.model(Model)` | (alt) | Use a pre-built `Model`. |
| `.client(Client)` | (alt) | Use a pre-built `Client` (advanced: custom retry policy, sharing a Client across agents). |
| `.systemPrompt(String)` | no | Optional system prompt the model receives on every turn. Omit when you don't need one. |
| `.tool(Tool)` | no | Register one tool. Call multiple times for multiple tools. |
| `.tools(List<Tool>)` | no | Register a batch of tools. Equivalent to looping `.tool(...)`. |
| `.maxTurns(int)` | no | Cap on model calls in one `run`. Default `10`. Throws on `<= 0`. |
| `.build()` | yes | Validate and construct the agent. Throws `IllegalStateException` on missing required fields or duplicate tool names. |

The `.model(...)` overloads set the same internal field; use the lambda for inline construction, the direct `.model(Model)` overload when you have a `Model` instance (e.g., shared across multiple agents, or for tests). Use `.client(Client)` when you want a custom retry policy or to reuse one Client across multiple agents.

## Running

```java
String answer = agent.perform("Find the population of Tokyo and Osaka.");
```

`perform(task)` returns the model's final text answer directly. For the rich response (usage, stop reason, full message history, turn count), use `respond`:

```java
LoopResponse<Void> result = agent.respond("Find the population of Tokyo and Osaka.");
```

`LoopResponse<Void>` (see [responses.md](responses.md)) fields:

| Field | Meaning |
|---|---|
| `text()` | The model's final answer. |
| `messages()` | Full conversation history — user task, assistant turns, tool results, final answer. Immutable. |
| `usage()` | Accumulated token usage across every model call. |
| `stopReason()` | `END_TURN` (model answered) or `MAX_TURNS` (loop exhausted). |
| `turns()` | Number of model calls made (1 to `maxTurns`). |

Agent instances are stateless and reusable — call `perform`/`respond` multiple times for independent tasks. Each invocation builds its own history; state does not carry between calls.

## Typed output

The output type isn't bound to the agent — the same agent can produce different typed outputs across calls. Pass a `Class<T>` to either `perform` or `respond`:

```java
// Direct typed return:
WeatherReport report = agent.perform("Research the weather in Tokyo.", WeatherReport.class);

// Rich typed response:
LoopResponse<WeatherReport> result = agent.respond("Research the weather in Tokyo.", WeatherReport.class);
WeatherReport report = result.output();
```

The loop passes `outputType` to every model call. Intermediate turns (when the model emits tool calls) typically don't satisfy the schema and `response.output()` is `null` on those — provider behavior here is forgiving for tool-use turns. The final assistant turn (no tool calls) is parsed into `T` and returned via `LoopResponse.output()`.

When the loop exhausts `maxTurns` without a clean termination, `LoopResponse.output()` is `null` — there was no final typed answer to parse.

## What happens during run

For each turn:

1. **Send.** The full message history + tool definitions go to the model.
2. **Receive.** The model returns text and possibly one or more `ToolCall`s.
3. **Append.** The assistant message (text + tool-use blocks) is appended to history.
4. **Branch:**
   - If `stopReason != TOOL_USE` or there are no tool calls, the loop returns with the model's text as the final answer.
   - Otherwise, execute every tool call, bundle the `ToolResultMessageBlock`s into one user-role message, append it to history, and loop.
5. **Terminate.** If `maxTurns` is reached without the model stopping naturally, return with `stopReason = MAX_TURNS` and the last assistant text.

Tool execution is **sequential** within a turn. If the model emits three tool calls, they run one after another, in order.

## Tool errors

- **Tool throws an exception:** caught, packaged as a `ToolResultMessageBlock` with `isError=true` and the exception message as content. The loop continues so the model can recover.
- **Model calls an unknown tool name:** packaged as `ToolResultMessageBlock(toolUseId, "Unknown tool: <name>", isError=true)`. Same recovery flow.

The loop **never** raises tool exceptions to the caller. `Client` exceptions (network failures after retries exhausted) do propagate — see [errors-and-retries.md](errors-and-retries.md).

## Accessing the conversation

```java
LoopResponse<Void> result = agent.respond("...");

for (ModelMessage msg : result.messages()) {
    System.out.println(msg.messageRole() + ":");
    for (MessageBlock block : msg.messageBlocks()) {
        switch (block) {
            case TextMessageBlock(String text) ->
                System.out.println("  text: " + text);
            case ToolUseMessageBlock t ->
                System.out.println("  tool_use: " + t.toolName() + "(" + t.args() + ")");
            case ToolResultMessageBlock r ->
                System.out.println("  tool_result: " + r.content() + (r.isError() ? " [error]" : ""));
        }
    }
}
```

`MessageBlock` is sealed — exhaustive `switch` on the three permitted records works with no `default` branch needed (record patterns are supported on JDK 21+).

## Picking maxTurns

`maxTurns` is the loop's emergency brake. Pick a value high enough that legitimate work has room (most tasks finish in 1–5 turns), but low enough that runaway loops fail loudly rather than burning tokens silently.

Reasonable defaults:

| Task shape | Suggested maxTurns |
|---|---|
| One or two tool calls then answer | 5 |
| Multi-step reasoning with tools | 10 (default) |
| Open-ended research or planning | 20–30, monitor usage |

If you hit `MAX_TURNS` repeatedly, that's a signal: either the system prompt is unclear, the tools don't expose what the model needs, or the task is genuinely too big for a single agent run.

## Implementing your own Agent

Anything that implements `Agent` and returns a `LoopResponse<Void>` works. To slot into `Agent.builder()`, add a new selector method:

```java
// Inside Agent.Builder
public PlanExecuteAgent.Builder planExecute() {
    return PlanExecuteAgent.builder();
}
```

Each agent type provides its own `Builder` with type-specific options (`maxTurns` for ReAct, plan-step depth for plan-execute, etc.). The shared shape is just the interface contract — `run(String) -> LoopResponse<Void>`.
