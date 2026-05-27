# Messages

Conversations with a model are sequences of `ModelMessage` records. Each message has a **role** (user or assistant) and a **list of blocks** that make up its content.

## ModelMessage

```java
public record ModelMessage(MessageRole messageRole, List<MessageBlock> messageBlocks) {
    public static ModelMessage user(MessageBlock... blocks);
    public static ModelMessage user(List<MessageBlock> blocks);
    public static ModelMessage assistant(MessageBlock... blocks);
    public static ModelMessage assistant(List<MessageBlock> blocks);
}
```

Two roles exist:

```java
public enum MessageRole { USER, ASSISTANT }
```

There is no `SYSTEM` role on `ModelMessage` — the system prompt is passed as its own `String` parameter to `send(...)`. (Tool results live inside `USER`-role messages; see below.)

Use the factory methods rather than the canonical constructor — they're shorter and convey intent:

```java
var msg = ModelMessage.user(TextMessageBlock.of("Hello!"));
```

The constructor defensively copies the `messageBlocks` list, so passing a mutable `ArrayList` is safe — your copy and the record's copy are independent.

## Blocks

`MessageBlock` is a sealed interface with three permitted record implementations:

```java
public sealed interface MessageBlock permits
    TextMessageBlock,
    ToolUseMessageBlock,
    ToolResultMessageBlock {}
```

A single message can mix blocks. For example, an assistant message might contain a `TextMessageBlock` (the model's "thinking" prose) followed by one or more `ToolUseMessageBlock` entries (the tools it wants to call).

### TextMessageBlock

Plain text content.

```java
public record TextMessageBlock(String text) implements MessageBlock {
    public static TextMessageBlock of(String text);
    public static TextMessageBlock of(String format, Object... args);  // String.format
}
```

```java
var hello = TextMessageBlock.of("Hello!");
var formatted = TextMessageBlock.of("Hi %s, you have %d new messages.", name, count);
```

`text` is never `null` — the compact constructor coerces `null` to `""`.

### ToolUseMessageBlock

Lives on **assistant** messages. Represents one tool call the model wants made.

```java
public record ToolUseMessageBlock(
        String id,
        String toolName,
        Map<String, Object> args) implements MessageBlock {

    public static Builder builder();
}
```

| Field | Meaning |
|---|---|
| `id` | Unique correlation id assigned by the model. You echo this in the matching `ToolResultMessageBlock`. |
| `toolName` | The tool's `definition().name()`. |
| `args` | JSON-decoded arguments the model supplied. May contain `null` values (JSON `null` is preserved). Immutable. |

You don't normally construct these yourself — providers produce them when translating the upstream response. But you can:

```java
var block = ToolUseMessageBlock.builder()
        .id("tu_abc")
        .toolName("get_weather")
        .args(Map.of("city", "Tokyo"))
        .build();
```

### ToolResultMessageBlock

Lives on **user** messages. Represents the output of one tool call, indexed back by `toolUseId` to the corresponding `ToolUseMessageBlock`.

```java
public record ToolResultMessageBlock(
        String toolUseId,
        String content,
        boolean isError) implements MessageBlock {

    public static Builder builder();
}
```

| Field | Meaning |
|---|---|
| `toolUseId` | Must equal the `id` of the matching `ToolUseMessageBlock`. |
| `content` | The tool's output, usually JSON. `null` is coerced to `""`. |
| `isError` | When `true`, signals to the model that the tool failed; the model may retry or change strategy. |

```java
var ok = ToolResultMessageBlock.builder()
        .toolUseId("tu_abc")
        .content("{\"tempF\":72}")
        .build();

var err = ToolResultMessageBlock.builder()
        .toolUseId("tu_abc")
        .content("API timeout after 30s")
        .isError(true)
        .build();
```

## Tool results live in user messages

This often surprises people. Although a tool result conceptually comes "from the tool," in the message stream it's delivered as a **user-role message** containing one or more `ToolResultMessageBlock`s. This matches Anthropic's convention; other providers convert internally.

```
USER:      "What's the weather in Tokyo?"
ASSISTANT: [TextMessageBlock("Let me check.")]
           [ToolUseMessageBlock(id="tu_1", toolName="get_weather", args={...})]
USER:      [ToolResultMessageBlock(toolUseId="tu_1", content="{...}", isError=false)]
ASSISTANT: "It's 72°F in Tokyo."
```

When you build histories by hand (rare — usually you let `ReActAgent` or `Chat` manage them), bundle all tool results from a single assistant turn into one user message:

```java
var toolResults = new ArrayList<MessageBlock>();
for (ToolCall call : assistantResponse.toolCalls()) {
    var output = runTool(call);
    toolResults.add(ToolResultMessageBlock.builder()
            .toolUseId(call.id())
            .content(output)
            .build());
}
history.add(new ModelMessage(MessageRole.USER, toolResults));
```

## Putting it together

A complete `send(...)` call:

```java
var history = List.of(
        ModelMessage.user(TextMessageBlock.of("What's 2 + 2?")));

var response = model.send(
        "You are a math tutor.",          // system prompt
        history,                          // message history
        List.of(),                        // available tools (empty here)
        Void.class);                      // unstructured output

System.out.println(response.text());     // "4."
```

For multi-turn use, see [sessions.md](sessions.md). For tool-aware loops, see [agents.md](agents.md).
