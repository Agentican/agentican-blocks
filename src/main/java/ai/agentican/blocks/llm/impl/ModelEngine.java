package ai.agentican.blocks.llm.impl;

import ai.agentican.blocks.llm.Utils;
import ai.agentican.blocks.llm.api.Model;
import ai.agentican.blocks.llm.api.ModelRequest;
import ai.agentican.blocks.llm.api.ModelResponse;
import ai.agentican.blocks.llm.api.ModelSession;
import ai.agentican.blocks.llm.api.ToolDefinition;
import ai.agentican.blocks.llm.provider.ProviderModel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

public final class ModelEngine implements Model {

    private static final Logger LOG = LoggerFactory.getLogger(ModelEngine.class);

    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final int DEFAULT_JITTER_MS = 500;

    private static final Duration MAX_RETRY_DELAY = Duration.ofSeconds(30);
    private static final Duration DEFAULT_BASE_DELAY = Duration.ofSeconds(1);

    private final ProviderModel impl;
    private final int maxRetries;
    private final Duration baseDelay;

    public ModelEngine(ProviderModel impl) {

        this(impl, DEFAULT_MAX_RETRIES, DEFAULT_BASE_DELAY);
    }

    public ModelEngine(ProviderModel impl, int maxRetries, Duration baseDelay) {

        if (impl == null) throw new IllegalArgumentException("impl is required");

        this.impl = impl;
        this.maxRetries = maxRetries > 0 ? maxRetries : DEFAULT_MAX_RETRIES;
        this.baseDelay = baseDelay != null ? baseDelay : DEFAULT_BASE_DELAY;
    }

    @Override
    public <T> ModelResponse<T> send(ModelRequest<T> request) {

        var messages = List.of(ModelMessage.user(new TextBlock(request.userMessage())));

        return chat(request.systemPrompt(), messages, request.tools(), request.outputType());
    }

    @Override
    public ModelSession session(String systemPrompt, List<ToolDefinition> tools) {

        return new EngineSession(this, systemPrompt, tools);
    }

    /** Package-private hook so {@link EngineSession} can share the retry path. */
    final <T> ModelResponse<T> chat(String systemPrompt, List<ModelMessage> messages,
                                     List<ToolDefinition> tools, Class<T> outputType) {

        return retry(() -> impl.execute(systemPrompt, messages, tools, outputType));
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

    /**
     * Session implementation paired with {@link ModelEngine}. Mirrors {@link ChatSession}
     * but talks to a {@link ModelEngine} instead of an {@link AbstractModel}.
     */
    private static final class EngineSession implements ModelSession {

        private final ModelEngine engine;
        private final String systemPrompt;
        private final List<ToolDefinition> tools;
        private final List<ModelMessage> history = new ArrayList<>();

        EngineSession(ModelEngine engine, String systemPrompt, List<ToolDefinition> tools) {

            if (Utils.isMissing(systemPrompt)) throw new IllegalArgumentException("System prompt is required");

            this.engine = engine;
            this.systemPrompt = systemPrompt;
            this.tools = tools != null ? List.copyOf(tools) : List.of();
        }

        @Override
        public ModelResponse<Void> send(String userMessage) {

            return send(userMessage, Void.class);
        }

        @Override
        public <T> ModelResponse<T> send(String userMessage, Class<T> outputType) {

            if (userMessage == null || userMessage.isBlank())
                throw new IllegalArgumentException("userMessage is required");

            history.add(ModelMessage.user(new TextBlock(userMessage)));

            ModelResponse<T> response;

            try {
                response = engine.chat(systemPrompt, List.copyOf(history), tools, outputType);
            }
            catch (RuntimeException e) {

                history.removeLast();
                throw e;
            }

            history.add(ModelMessage.assistant(new TextBlock(response.text() != null ? response.text() : "")));

            return response;
        }

        @Override
        public List<ModelMessage> messages() {

            return List.copyOf(history);
        }
    }
}
