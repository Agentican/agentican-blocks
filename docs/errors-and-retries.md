# Errors and retries

agentican-blocks tries to keep transient failures out of your code path: when the underlying SDK throws a retryable exception, the library backs off and tries again. Only after retries are exhausted (or the exception is non-retryable) does an exception propagate to you.

## Where retries happen

`DefaultClient` is the retry layer — it's the default `Client` implementation that wraps a `Model` with retry/backoff. When you do:

```java
var client = Client.builder()
        .model(m -> m.anthropic().apiKey(...).model("..."))
        .build();
// or, equivalently
var client = new DefaultClient(model);
```

every `client.send(...)` call goes through retry logic. The raw `Model.send(...)` does **not** retry — if you call a model directly (bypassing the client), you get the raw exception.

`Agent.builder().reAct().model(...).build()` always wraps in `DefaultClient` internally, so agents always get retries for free. Likewise for `Chat.builder().model(...).build()`.

## What's considered retryable

The following are retried with exponential backoff:

- `java.io.IOException` (or any cause chain containing one)
- `java.util.concurrent.TimeoutException` (or in cause chain)
- Exception messages containing any of: `"429"`, `"529"`, `"500"`, `"503"`, `"rate_limit"`, `"rate limit"`, `"overloaded"`, `"too many requests"` (case-insensitive)

Everything else propagates immediately. In particular:

- `IllegalArgumentException` from your own validation — propagates.
- 400 Bad Request — propagates (your prompt or schema is wrong; retrying won't help).
- 401/403 — propagates (credentials issue; retrying won't help).
- Tool exceptions inside an agent loop — caught by the agent and turned into error tool-results; **not** retried at the model level.

## Retry policy

Defaults:

| Setting | Value |
|---|---|
| `maxRetries` | 3 (so up to 4 total attempts) |
| `baseDelay` | 1 second |
| Backoff | Exponential: `baseDelay * 2^attempt` plus up to 500ms random jitter |
| Cap | 30 seconds per individual delay |

Customize via `Client.builder()`:

```java
var client = Client.builder()
        .model(m -> m.anthropic().apiKey(...).model("..."))
        .maxRetries(5)
        .baseDelay(Duration.ofMillis(500))
        .build();
```

For agents and chats with a custom retry policy, build the `Client` first, then pass it via `.client(client)`:

```java
var agent = Agent.builder().reAct()
        .client(client)
        .systemPrompt("...")
        .tool(myTool)
        .build();
```

## Backoff schedule example

With defaults (`baseDelay=1s`, `maxRetries=3`):

| Attempt | Delay before attempt | Cumulative wall time |
|---|---|---|
| 1 (first try) | 0s | 0s |
| 2 | 1–1.5s | 1–1.5s |
| 3 | 2–2.5s | 3–4s |
| 4 (last try) | 4–4.5s | 7–8.5s |

After the 4th attempt fails, the exception propagates wrapped in a `RuntimeException` (or directly, if it was already a `RuntimeException`).

## Logs

`DefaultClient` logs retry attempts at `WARN`:

```
[WARN ] ai.agentican.blocks.llm.impl.DefaultClient - LLM call failed (attempt 1/4), retrying in 1234ms: Connection reset
```

If retries succeed, your code sees the successful response — the warnings are your audit trail. If retries fail, the final exception comes through normally.

## What to catch

In most cases:

```java
try {
    var response = client.send(...);
    // handle response
}
catch (RuntimeException e) {
    // network failure, retries exhausted, or other unrecoverable error
    log.error("Model call failed", e);
}
```

For finer-grained handling, unwrap and inspect the cause:

```java
catch (RuntimeException e) {
    if (e.getCause() instanceof IOException io) {
        // network problem after retries
    }
    else {
        // probably a bad-request / auth / unrecoverable error
    }
}
```

## Sessions and error recovery

When `Chat.send(...)` fails, the failing user message is **removed from history** before the exception propagates. This means you can retry cleanly without ending up with a stale user message in the conversation:

```java
try {
    String answer = chat.send(userInput);
}
catch (RuntimeException e) {
    // history is unchanged; safe to retry the same input or surface to user
}
```

## Agents and error propagation

`Agent.perform(...)` and `Agent.respond(...)` only catch **tool exceptions** (converted to error tool-results so the loop can recover). Model-call exceptions propagate after the retry layer is exhausted.

```java
try {
    var result = agent.respond("...");
    // result.stopReason() tells you END_TURN vs MAX_TURNS
}
catch (RuntimeException e) {
    // model call failed even after retries
}
```

Partial progress is lost when the loop throws — the agent doesn't return a partial `LoopResponse`. If you need partial-progress recovery, you'll need to build a custom loop and use `Client.send(...)` directly, persisting history between turns.

## Common failure modes

| Symptom | Likely cause | Action |
|---|---|---|
| `Connection reset` / `SocketTimeoutException` after retries | Network or provider outage | Wait and retry the whole operation |
| `429 / rate_limit` exhausting retries | Sustained over-quota | Lower request rate, request higher quota, or add a request queue |
| `400 Bad Request` (no retry) | Invalid model name, bad schema, malformed messages | Fix the request |
| `401 / 403` (no retry) | Bad or missing API key | Check credentials |
| `MAX_TURNS` in `LoopResponse` | Agent didn't converge | Increase `maxTurns`, improve system prompt, or check that tools expose what the model needs |
| `MAX_TOKENS` in `ModelResponse` | Hit the `maxTokens` limit mid-generation | Increase `maxTokens` on the provider builder, or shorten the prompt |
| `output()` is `null` despite a non-null `outputType` | JSON didn't parse | Inspect `text()` and tighten the system prompt or schema |
