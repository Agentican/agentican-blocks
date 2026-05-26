package ai.agentican.blocks.llm;

import ai.agentican.blocks.llm.api.*;
import ai.agentican.blocks.llm.impl.*;
import ai.agentican.blocks.llm.provider.ProviderModel;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class ModelLibraryCompileTest {

    private static ModelRequest<Void> sampleRequest() {

        return ModelRequest.of("You are a helpful assistant.", "Summarize the day.");
    }

    private static <T> ModelResponse<T> sampleResponse(T parsed, String text) {

        return new ModelResponse<>(parsed, text, List.of(), StopReason.END_TURN, new ModelUsage(1, 2, 0, 0, 0));
    }

    /**
     * Test provider: a {@link ProviderModel} whose {@code execute} delegates to the given
     * function, wrapped in a {@link ModelFactory}. Lets us drive retry tests with millisecond
     * delays so they stay fast.
     */
    private static ModelFactory stubClient(Function<ModelRequest<?>, ModelResponse<?>> sendImpl) {
        return stubClient(3, Duration.ofMillis(1), sendImpl);
    }

    private static ModelFactory stubClient(int maxRetries, Duration baseDelay,
                                           Function<ModelRequest<?>, ModelResponse<?>> sendImpl) {

        ProviderModel provider = new ProviderModel() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> ModelResponse<T> send(String sp, List<ModelMessage> messages,
                                             List<ToolDefinition> t, Class<T> outputType) {
                var lastUser = messages.get(messages.size() - 1);
                var text = ((TextMessageBlock) lastUser.messageBlocks().get(0)).text();
                return (ModelResponse<T>) sendImpl.apply(new ModelRequest<>(sp, text,
                        t == null ? List.of() : t, outputType));
            }
        };

        return new ModelFactory(provider, maxRetries, baseDelay);
    }

    @Test
    void clientRetriesOnRetryableErrorThenSucceeds() {

        var attempts = new AtomicInteger(0);

        var client = stubClient(5, Duration.ofMillis(1), request -> {
            if (attempts.incrementAndGet() < 3)
                throw new RuntimeException("HTTP 429 rate_limit");
            return sampleResponse(null, "recovered");
        });

        var response = client.send(sampleRequest());

        assertEquals(3, attempts.get(), "should have retried twice before succeeding");
        assertEquals("recovered", response.text());
    }

    @Test
    void clientGivesUpAfterMaxRetries() {

        var attempts = new AtomicInteger(0);

        var client = stubClient(2, Duration.ofMillis(1), request -> {
            attempts.incrementAndGet();
            throw new RuntimeException("HTTP 500 internal error");
        });

        assertThrows(RuntimeException.class, () -> client.send(sampleRequest()));
        assertEquals(3, attempts.get(), "should have made initial attempt + 2 retries");
    }

    @Test
    void clientDoesNotRetryNonRetryableErrors() {

        var attempts = new AtomicInteger(0);

        var client = stubClient(5, Duration.ofMillis(1), request -> {
            attempts.incrementAndGet();
            throw new IllegalArgumentException("bad request — not retryable");
        });

        assertThrows(IllegalArgumentException.class, () -> client.send(sampleRequest()));
        assertEquals(1, attempts.get(), "non-retryable errors should fail immediately");
    }

    @Test
    void sessionAppendsUserAndAssistantMessagesToHistory() {

        var underlying = stubClient(request -> sampleResponse(null, "hi there"));

        var session = underlying.session("You are friendly.", List.of());

        var response = session.send("hello");

        assertEquals("hi there", response.text());

        var history = session.messages();

        assertEquals(2, history.size());
        assertEquals(MessageRole.USER, history.get(0).messageRole());
        assertEquals("hello", ((TextMessageBlock) history.get(0).messageBlocks().get(0)).text());
        assertEquals(MessageRole.ASSISTANT, history.get(1).messageRole());
        assertEquals("hi there", ((TextMessageBlock) history.get(1).messageBlocks().get(0)).text());
    }

    @Test
    void sessionAccumulatesMultipleTurns() {

        var counter = new AtomicInteger(0);

        var underlying = stubClient(request -> sampleResponse(null, "reply " + counter.incrementAndGet()));

        var session = underlying.session("You are friendly.", List.of());

        session.send("turn one");
        session.send("turn two");
        session.send("turn three");

        var history = session.messages();

        assertEquals(6, history.size(), "3 user + 3 assistant messages");
        assertEquals("turn one", ((TextMessageBlock) history.get(0).messageBlocks().get(0)).text());
        assertEquals("reply 1",  ((TextMessageBlock) history.get(1).messageBlocks().get(0)).text());
        assertEquals("turn three", ((TextMessageBlock) history.get(4).messageBlocks().get(0)).text());
        assertEquals("reply 3",   ((TextMessageBlock) history.get(5).messageBlocks().get(0)).text());
    }

    @Test
    void sessionRetriesFlakyTurnsAndKeepsHistoryConsistent() {

        var attempts = new AtomicInteger(0);

        var client = stubClient(5, Duration.ofMillis(1), request -> {
            if (attempts.incrementAndGet() < 3)
                throw new RuntimeException("HTTP 429 rate_limit");
            return sampleResponse(null, "succeeded");
        });

        var session = client.session("sp", List.of());

        var response = session.send("hello");

        assertEquals("succeeded", response.text());
        assertEquals(3, attempts.get());

        var history = session.messages();
        assertEquals(2, history.size(), "exactly one user + one assistant entry despite retries");
        assertEquals("hello",     ((TextMessageBlock) history.get(0).messageBlocks().get(0)).text());
        assertEquals("succeeded", ((TextMessageBlock) history.get(1).messageBlocks().get(0)).text());
    }

    @Test
    void sessionFailureLeavesHistoryClean() {

        var underlying = stubClient(request -> {
            throw new IllegalArgumentException("permanent fail");
        });

        var session = underlying.session("sp", List.of());

        assertThrows(IllegalArgumentException.class, () -> session.send("hello"));

        assertTrue(session.messages().isEmpty(), "failed turn must not leave a stray user message in history");
    }

    // ── Typed-outputTokens coverage ───────────────────────────────────────────

    public record Greeting(String greeting, int score) {}

    @Test
    void typedRequestParsesResponseJsonIntoT() {

        // The stub returns canned JSON; the provider-side deserialize path inside the
        // stub doesn't apply (DefaultSession routes back through sendImpl which returns
        // the LlmResponse we hand-build here, outputTokens field included).
        var greeting = new Greeting("hello", 7);

        var underlying = stubClient(request -> {
            assertEquals(Greeting.class, request.outputType(), "outputType should be propagated");
            return sampleResponse(greeting, "{\"greeting\":\"hello\",\"score\":7}");
        });

        var response = underlying.send(new ModelRequest<>(
                "You are helpful.", "say hi", List.of(), Greeting.class));

        assertNotNull(response.output());
        assertEquals("hello", response.output().greeting());
        assertEquals(7,       response.output().score());
    }

    @Test
    void typedSessionTurnReturnsTypedResponse() {

        var greeting = new Greeting("hi from session", 9);

        var underlying = stubClient(request -> sampleResponse(greeting,
                "{\"greeting\":\"hi from session\",\"score\":9}"));

        var session = underlying.session("sp", List.of());

        ModelResponse<Greeting> response = session.send("greet me", Greeting.class);

        assertNotNull(response.output());
        assertEquals("hi from session", response.output().greeting());
        assertEquals(9, response.output().score());
    }

    @Test
    void schemaGeneratorProducesObjectSchemaForRecord() {

        // Direct sanity check that the schema generator wiring works inside this module.
        var schema = Utils.schema(Greeting.class);

        assertNotNull(schema);
        assertEquals("object", schema.get("type").asText());
        assertTrue(schema.has("properties"));
        assertTrue(schema.get("properties").has("greeting"));
        assertTrue(schema.get("properties").has("score"));
    }

    @Test
    void schemasIsUnstructuredHandlesNullAndVoid() {

        assertTrue(Utils.isUnstructured(null));
        assertTrue(Utils.isUnstructured(Void.class));
        assertFalse(Utils.isUnstructured(Greeting.class));
        assertFalse(Utils.isUnstructured(String.class));
    }
}
