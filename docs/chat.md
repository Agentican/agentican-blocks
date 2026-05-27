# Chat

A `Chat` is a stateful, user-driven multi-turn handle. You give it a system prompt and a tool set once, then call `send(userMessage)` repeatedly — the chat tracks the conversation history for you.

`Chat` is **user-driven** and **free-form**: each `send(String)` represents one user turn and returns the assistant's text. There is no structured-output variant here; for one-shot typed input → typed output, reach for [`Fn`](fn.md) instead. For model-driven multi-turn behavior with autonomous tool execution, use [`Agent`](agents.md).

## Creating a Chat

The simplest way is `Chat.builder()` with a model-config lambda — it wraps the model in a default `Client` internally:

```java
var chat = Chat.builder()
        .model(m -> m.anthropic()
                .apiKey(System.getenv("ANTHROPIC_API_KEY"))
                .model("claude-opus-4-7"))
        .systemPrompt("You are a helpful assistant.")
        .build();
```

If you already have a `Client` (shared across multiple chats, or with a custom retry config), pass it directly:

```java
var chat = Chat.builder()
        .client(existingClient)
        .systemPrompt("...")
        .build();
```

Or spawn a `Chat` from an existing `Client`:

```java
Chat chat = client.chat("You are helpful.", List.of());
```

## Sending messages

```java
String answer = chat.send("Hi! What's your name?");
System.out.println(answer);

answer = chat.send("Cool. What did I just ask you?");
System.out.println(answer);  // the model remembers
```

`send(String)` returns the assistant's `String` reply directly. For the rich `ModelResponse<Void>` (token usage, stop reason, tool calls), use `respond`:

```java
var response = chat.respond("What's the weather in Tokyo?");
String answer = response.text();
ModelUsage usage = response.usage();
StopReason reason = response.stopReason();
List<ToolCall> toolCalls = response.toolCalls();
```

Both `send` and `respond` advance the conversation history equivalently. If you need a typed return, use [`Fn`](fn.md) (single-shot typed) or `Client.send(ModelRequest)` (one-shot, typed, no history).

## Inspecting history

```java
List<ModelMessage> history = chat.messages();
```

The returned list is a snapshot — you can't mutate the chat by modifying it. Useful for logging, debugging, or saving conversations.

## Tools in chats

Tools can be registered on the builder so the model sees them on every turn:

```java
var chat = Chat.builder()
        .model(m -> m.anthropic().apiKey(...).model("..."))
        .systemPrompt("You are a weather assistant.")
        .tool(weatherTool.definition())
        .build();

String answer = chat.send("What's the weather in Tokyo?");
```

**Important:** `Chat` does not execute tools. If the model decides to call a registered tool, `send(String)` returns whatever text the model produced (often empty if the model intends to call a tool). There is no API on `Chat` to feed a `ToolResultMessageBlock` back into the conversation.

For a real tool-execution loop, use [`ReActAgent`](agents.md). For a one-off tool call where you handle results yourself, use `Client.send(ModelRequest)` directly and inspect `response.toolCalls()`.

## Error recovery

If a `send` call fails (network error, rate limit, etc.), the failing user message is automatically removed from history before the exception propagates. The chat is left in the same state as before the call, so you can retry cleanly:

```java
try {
    String answer = chat.send(userInput);
}
catch (RuntimeException e) {
    // history is unchanged; safe to retry or surface the error
}
```

Retries on transient errors (timeouts, 5xx, rate limits) are already handled by `DefaultClient` (the default `Client` impl) — you only see exceptions for hard failures or after retries are exhausted. See [errors-and-retries.md](errors-and-retries.md).

## When to use Chat vs alternatives

| Use case | Best fit |
|---|---|
| Free-form multi-turn conversation | `Chat` |
| Single-turn typed input → typed output | [`Fn`](fn.md) |
| Model-driven loop with tool execution | [`ReActAgent`](agents.md) |
| Stateless one-off call (no shared state) | `Client.send(ModelRequest)` |
| Custom loop logic | `Client.send(systemPrompt, history, tools, outputType)` and manage history yourself |
