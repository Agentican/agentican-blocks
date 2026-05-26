package ai.agentican.blocks.llm.impl;

import ai.agentican.blocks.llm.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

public abstract class AbstractModel implements Model {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractModel.class);

    private static final long DEFAULT_MAX_TOKENS = 16384L;
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final int DEFAULT_JITTER_MS = 500;

    private static final Duration MAX_RETRY_DELAY = Duration.ofSeconds(30);
    private static final Duration DEFAULT_BASE_DELAY = Duration.ofSeconds(1);

    protected final String model;
    protected final Double temperature;
    protected final long maxTokens;

    private final int maxRetries;
    private final Duration baseDelay;

    protected AbstractModel(String model) {

        this(model, DEFAULT_MAX_TOKENS, null, DEFAULT_MAX_RETRIES, DEFAULT_BASE_DELAY);
    }

    protected AbstractModel(String model, long maxTokens, Double temperature) {

        this(model, maxTokens, temperature, DEFAULT_MAX_RETRIES, DEFAULT_BASE_DELAY);
    }

    protected AbstractModel(String model, long maxTokens, Double temperature, int maxRetries, Duration baseDelay) {

        if (model == null || model.isBlank())
            throw new IllegalArgumentException("model is required");

        this.model = model;
        this.maxTokens = maxTokens > 0 ? maxTokens : DEFAULT_MAX_TOKENS;
        this.temperature = temperature;
        this.maxRetries = maxRetries > 0 ? maxRetries : DEFAULT_MAX_RETRIES;
        this.baseDelay = baseDelay != null ? baseDelay : DEFAULT_BASE_DELAY;
    }

    @Override
    public final <T> ModelResponse<T> send(ModelRequest<T> request) {

        var messages = List.of(ModelMessage.user(new TextBlock(request.userMessage())));

        return chat(request.systemPrompt(), messages, request.tools(), request.outputType());
    }

    @Override
    public final ModelSession session(String systemPrompt, List<ToolDefinition> tools) {

        return new ChatSession(this, systemPrompt, tools);
    }

    final <T> ModelResponse<T> chat(String systemPrompt, List<ModelMessage> modelMessages,
                                    List<ToolDefinition> tools, Class<T> outputType) {

        return retry(() -> executeChat(systemPrompt, modelMessages, tools, outputType));
    }

    protected abstract <T> ModelResponse<T> executeChat(String systemPrompt, List<ModelMessage> messages,
                                                        List<ToolDefinition> tools, Class<T> outputType);

    private <R> R retry(Supplier<R> call) {

        Exception lastException = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {

            try {

                return call.get();
            }
            catch (Exception e) {

                lastException = e;

                if (attempt >= maxRetries || !isRetryable(e)) throw wrapException(e);

                var delay = computeBackoffDelay(attempt);

                LOG.warn("LLM call failed (attempt {}/{}), retrying in {}ms: {}",
                        attempt + 1, maxRetries + 1, delay.toMillis(), e.getMessage());

                try {

                    Thread.sleep(delay.toMillis());
                }
                catch (InterruptedException ie) {

                    Thread.currentThread().interrupt();

                    throw wrapException(e);
                }
            }
        }

        throw wrapException(lastException);
    }

    private Duration computeBackoffDelay(int attempt) {

        var exponentialMs = baseDelay.toMillis() * (1L << attempt);
        var jitterMs = ThreadLocalRandom.current().nextLong(DEFAULT_JITTER_MS);
        var totalMs = Math.min(exponentialMs + jitterMs, MAX_RETRY_DELAY.toMillis());

        return Duration.ofMillis(totalMs);
    }

    private static boolean isRetryable(Exception e) {

        if (e instanceof IOException) return true;
        if (e instanceof TimeoutException) return true;
        if (e.getCause() instanceof IOException) return true;
        if (e.getCause() instanceof TimeoutException) return true;

        var msg = e.getMessage();

        if (msg == null) return false;

        var lower = msg.toLowerCase();

        return lower.contains("429") || lower.contains("529")
                || lower.contains("500") || lower.contains("503")
                || lower.contains("rate_limit") || lower.contains("rate limit")
                || lower.contains("overloaded") || lower.contains("too many requests");
    }

    private static RuntimeException wrapException(Exception e) {

        if (e instanceof RuntimeException re) return re;

        return new RuntimeException(e);
    }
}
