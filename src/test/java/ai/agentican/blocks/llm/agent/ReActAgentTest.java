package ai.agentican.blocks.llm.agent;

import ai.agentican.blocks.llm.api.*;
import ai.agentican.blocks.llm.impl.MessageRole;
import ai.agentican.blocks.llm.impl.ModelMessage;
import ai.agentican.blocks.llm.impl.ToolResultMessageBlock;
import ai.agentican.blocks.llm.impl.ToolUseMessageBlock;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReActAgentTest {

    private static final String SYS = "You are a test agent.";

    @Test
    void happyPath_singleToolCall_thenFinalText() {

        var fakeModel = new FakeModel(List.of(
                toolUse("tu_1", "echo", Map.of("msg", "hello")),
                endTurn("done: hello")));

        var loop = ReActAgent.builder()
                .model(fakeModel)
                .systemPrompt(SYS)
                .tool(echoTool())
                .build();

        var result = loop.run("say hello via echo");

        assertEquals("done: hello", result.text());
        assertEquals(StopReason.END_TURN, result.stopReason());
        assertEquals(2, result.turns());

        // history: user task, assistant (tool use), user (tool result), assistant (text)
        var msgs = result.messages();
        assertEquals(4, msgs.size());
        assertEquals(MessageRole.USER, msgs.get(0).messageRole());
        assertEquals(MessageRole.ASSISTANT, msgs.get(1).messageRole());
        assertInstanceOf(ToolUseMessageBlock.class, msgs.get(1).messageBlocks().get(0));

        assertEquals(MessageRole.USER, msgs.get(2).messageRole());
        var resultBlock = assertInstanceOf(ToolResultMessageBlock.class, msgs.get(2).messageBlocks().get(0));
        assertEquals("echoed:hello", resultBlock.content());
        assertFalse(resultBlock.isError());

        assertEquals(MessageRole.ASSISTANT, msgs.get(3).messageRole());
    }

    @Test
    void multipleToolCallsInSingleTurn_executedAndBundledIntoOneUserMessage() {

        var fakeModel = new FakeModel(List.of(
                multiToolUse(
                        new ToolCall("tu_a", "echo", Map.of("msg", "a")),
                        new ToolCall("tu_b", "echo", Map.of("msg", "b"))),
                endTurn("got both")));

        var loop = ReActAgent.builder()
                .model(fakeModel)
                .systemPrompt(SYS)
                .tool(echoTool())
                .build();

        var result = loop.run("call echo twice");

        assertEquals("got both", result.text());
        assertEquals(StopReason.END_TURN, result.stopReason());

        // The tool-result message should bundle BOTH results in one user message.
        var msgs = result.messages();
        var toolResultMsg = msgs.get(2);
        assertEquals(MessageRole.USER, toolResultMsg.messageRole());
        assertEquals(2, toolResultMsg.messageBlocks().size());

        var first = assertInstanceOf(ToolResultMessageBlock.class, toolResultMsg.messageBlocks().get(0));
        var second = assertInstanceOf(ToolResultMessageBlock.class, toolResultMsg.messageBlocks().get(1));
        assertEquals("echoed:a", first.content());
        assertEquals("echoed:b", second.content());
        assertEquals("tu_a", first.toolUseId());
        assertEquals("tu_b", second.toolUseId());
    }

    @Test
    void toolThrows_returnsErrorResult_loopContinues() {

        var fakeModel = new FakeModel(List.of(
                toolUse("tu_1", "boom", Map.of()),
                endTurn("recovered")));

        Tool boom = new Tool() {
            @Override public ToolDefinition definition() {
                return new ToolDefinition("boom", "always fails", Map.of());
            }
            @Override public String execute(Map<String, Object> args) {
                throw new RuntimeException("kaboom");
            }
        };

        var loop = ReActAgent.builder()
                .model(fakeModel)
                .systemPrompt(SYS)
                .tool(boom)
                .build();

        var result = loop.run("trigger boom");

        assertEquals("recovered", result.text());

        var toolResultMsg = result.messages().get(2);
        var block = assertInstanceOf(ToolResultMessageBlock.class, toolResultMsg.messageBlocks().get(0));
        assertTrue(block.isError());
        assertEquals("kaboom", block.content());
    }

    @Test
    void unknownTool_returnsErrorResult_loopContinues() {

        var fakeModel = new FakeModel(List.of(
                toolUse("tu_1", "ghost", Map.of()),
                endTurn("ok")));

        var loop = ReActAgent.builder()
                .model(fakeModel)
                .systemPrompt(SYS)
                .tool(echoTool())
                .build();

        var result = loop.run("call ghost");

        var block = assertInstanceOf(ToolResultMessageBlock.class,
                result.messages().get(2).messageBlocks().get(0));
        assertTrue(block.isError());
        assertTrue(block.content().contains("ghost"));
        assertEquals("ok", result.text());
    }

    @Test
    void maxTurnsExhausted_returnsMaxTurnsStop() {

        // Always tool-use forever; loop should stop at maxTurns.
        var fakeModel = new InfiniteToolUseModel();

        var loop = ReActAgent.builder()
                .model(fakeModel)
                .systemPrompt(SYS)
                .tool(echoTool())
                .maxTurns(3)
                .build();

        var result = loop.run("loop forever");

        assertEquals(StopReason.MAX_TURNS, result.stopReason());
        assertEquals(3, result.turns());
        assertEquals(3, fakeModel.calls.get());
    }

    @Test
    void builderValidation_nullModel() {

        assertThrows(IllegalStateException.class, () ->
                ReActAgent.builder().systemPrompt(SYS).build());
    }

    @Test
    void builderValidation_blankSystemPrompt() {

        assertThrows(IllegalStateException.class, () ->
                ReActAgent.builder().model(new FakeModel(List.of())).systemPrompt("  ").build());
    }

    @Test
    void builderValidation_duplicateToolNames() {

        assertThrows(IllegalStateException.class, () ->
                ReActAgent.builder()
                        .model(new FakeModel(List.of()))
                        .systemPrompt(SYS)
                        .tool(echoTool())
                        .tool(echoTool())
                        .build());
    }

    @Test
    void builderValidation_nonPositiveMaxTurns() {

        assertThrows(IllegalStateException.class, () ->
                ReActAgent.builder()
                        .model(new FakeModel(List.of()))
                        .systemPrompt(SYS)
                        .maxTurns(0)
                        .build());
    }

    // ---- helpers ----

    private static Tool echoTool() {

        return new Tool() {
            @Override public ToolDefinition definition() {
                return new ToolDefinition("echo", "echoes back its msg arg", Map.of(
                        "msg", Map.of("type", "string")));
            }
            @Override public String execute(Map<String, Object> args) {
                return "echoed:" + args.get("msg");
            }
        };
    }

    private static SingleResponse<Void> toolUse(String id, String name, Map<String, Object> args) {

        return new SingleResponse<>(null, "", List.of(new ToolCall(id, name, args)),
                StopReason.TOOL_USE, ModelUsage.ZERO);
    }

    private static SingleResponse<Void> multiToolUse(ToolCall... calls) {

        return new SingleResponse<>(null, "", List.of(calls), StopReason.TOOL_USE, ModelUsage.ZERO);
    }

    private static SingleResponse<Void> endTurn(String text) {

        return new SingleResponse<>(null, text, List.of(), StopReason.END_TURN, ModelUsage.ZERO);
    }

    /** Plays back a scripted sequence of responses. Ignores inputs beyond shape. */
    private static final class FakeModel implements Model {

        private final Deque<SingleResponse<?>> scripted;

        FakeModel(List<SingleResponse<Void>> responses) {

            this.scripted = new ArrayDeque<>(new ArrayList<>(responses));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> ModelResponse<T> send(String systemPrompt, List<ModelMessage> messages,
                                          List<ToolDefinition> tools, Class<T> outputType) {

            if (scripted.isEmpty())
                throw new AssertionError("FakeModel exhausted — test expected fewer model calls");

            return (ModelResponse<T>) scripted.removeFirst();
        }

        @Override
        public <T> ModelResponse<T> send(ModelRequest<T> request) {
            throw new UnsupportedOperationException("not used by ReActLoop");
        }

        @Override
        public ModelSession session(String systemPrompt, List<ToolDefinition> tools) {
            throw new UnsupportedOperationException("not used by ReActLoop");
        }
    }

    /** Always returns one tool call so the loop is forced to hit maxTurns. */
    private static final class InfiniteToolUseModel implements Model {

        final AtomicInteger calls = new AtomicInteger();

        @Override
        @SuppressWarnings("unchecked")
        public <T> ModelResponse<T> send(String systemPrompt, List<ModelMessage> messages,
                                          List<ToolDefinition> tools, Class<T> outputType) {

            int n = calls.incrementAndGet();

            var response = new SingleResponse<>(null, "thinking " + n,
                    List.of(new ToolCall("tu_" + n, "echo", Map.of("msg", "m" + n))),
                    StopReason.TOOL_USE, ModelUsage.ZERO);

            return (ModelResponse<T>) response;
        }

        @Override
        public <T> ModelResponse<T> send(ModelRequest<T> request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ModelSession session(String systemPrompt, List<ToolDefinition> tools) {
            throw new UnsupportedOperationException();
        }
    }
}
