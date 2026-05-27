package ai.agentican.blocks.llm.impl;

import ai.agentican.blocks.llm.api.Client;
import ai.agentican.blocks.llm.api.Model;
import ai.agentican.blocks.llm.api.ModelRequest;
import ai.agentican.blocks.llm.api.ModelResponse;
import ai.agentican.blocks.llm.api.Chat;
import ai.agentican.blocks.llm.api.ToolDefinition;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

public final class DefaultClient implements Client {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultClient.class);

    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final int DEFAULT_JITTER_MS = 500;

    private static final Duration MAX_RETRY_DELAY = Duration.ofSeconds(30);
    private static final Duration DEFAULT_BASE_DELAY = Duration.ofSeconds(1);

    private final Model model;
    private final int maxRetries;
    private final Duration baseDelay;

    public DefaultClient(Model model) {

        this(model, DEFAULT_MAX_RETRIES, DEFAULT_BASE_DELAY);
    }

    public DefaultClient(Model model, int maxRetries, Duration baseDelay) {

        if (model == null) throw new IllegalArgumentException("Model required");

        this.model = model;
        this.maxRetries = maxRetries > 0 ? maxRetries : DEFAULT_MAX_RETRIES;
        this.baseDelay = baseDelay != null ? baseDelay : DEFAULT_BASE_DELAY;
    }

    @Override
    public Model model() {

        return model;
    }

    @Override
    public <T> ModelResponse<T> send(ModelRequest<T> request) {

        var messages = List.of(ModelMessage.user(new TextMessageBlock(request.userMessage())));

        return send(request.systemPrompt(), messages, request.tools(), request.outputType());
    }

    @Override
    public <T> ModelResponse<T> send(String systemPrompt, List<ModelMessage> messages,
                                      List<ToolDefinition> tools, Class<T> outputType) {

        return retry(() -> model.send(systemPrompt, messages, tools, outputType));
    }

    @Override
    public Chat chat(String systemPrompt, List<ToolDefinition> tools) {

        return new DefaultChat(this, systemPrompt, tools);
    }

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
