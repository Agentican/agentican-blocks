package ai.agentican.blocks.llm.agent;

import ai.agentican.blocks.llm.Utils;
import ai.agentican.blocks.llm.api.*;
import ai.agentican.blocks.llm.impl.MessageBlock;
import ai.agentican.blocks.llm.impl.MessageRole;
import ai.agentican.blocks.llm.impl.ModelMessage;
import ai.agentican.blocks.llm.impl.TextMessageBlock;
import ai.agentican.blocks.llm.impl.ToolResultMessageBlock;
import ai.agentican.blocks.llm.impl.ToolUseMessageBlock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public final class ReActAgent {

    private static final Logger LOG = LoggerFactory.getLogger(ReActAgent.class);

    private static final int DEFAULT_MAX_TURNS = 10;

    private final Model model;
    private final String systemPrompt;
    private final Map<String, Tool> tools;
    private final List<ToolDefinition> toolDefinitions;
    private final int maxTurns;

    private ReActAgent(Model model, String systemPrompt, Map<String, Tool> tools, int maxTurns) {

        this.model = model;
        this.systemPrompt = systemPrompt;
        this.tools = Map.copyOf(tools);
        this.toolDefinitions = tools.values().stream().map(Tool::definition).toList();
        this.maxTurns = maxTurns;
    }

    public LoopResponse<Void> run(String userTask) {

        if (Utils.isMissing(userTask))
            throw new IllegalArgumentException("User task required");

        var messageHistory = new ArrayList<ModelMessage>();

        messageHistory.add(ModelMessage.user(new TextMessageBlock(userTask)));

        var modelUsage = ModelUsage.ZERO;

        for (int turn = 0; turn < maxTurns; turn++) {

            var modelResponse = model.send(systemPrompt, messageHistory, toolDefinitions, Void.class);

            modelUsage = modelUsage.plus(modelResponse.usage());

            var assistantMessage = toAssistantMessage(modelResponse.text(), modelResponse.toolCalls());

            messageHistory.add(assistantMessage);

            if (modelResponse.stopReason() != StopReason.TOOL_USE || modelResponse.toolCalls().isEmpty())
                return new LoopResponse<>(null, modelResponse.text(), List.of(), modelResponse.stopReason(), modelUsage,
                        List.copyOf(messageHistory), turn + 1);

            var resultBlocks = new ArrayList<MessageBlock>();

            for (var call : modelResponse.toolCalls())
                resultBlocks.add(executeToolCall(call));

            var toolResponse = new ModelMessage(MessageRole.USER, resultBlocks);

            messageHistory.add(toolResponse);
        }

        return new LoopResponse<>(null, lastAssistantText(messageHistory), List.of(), StopReason.MAX_TURNS, modelUsage,
                List.copyOf(messageHistory), maxTurns);
    }

    private ToolResultMessageBlock executeToolCall(ToolCall call) {

        var tool = tools.get(call.name());

        if (tool == null) {

            LOG.warn("Unknown tool requested: {}", call.name());

            return new ToolResultMessageBlock(call.id(), "Unknown tool: " + call.name(), true);
        }

        try {

            var output = tool.execute(call.args());

            return new ToolResultMessageBlock(call.id(), output != null ? output : "", false);
        }
        catch (Exception e) {

            LOG.warn("Tool {} failed: {}", call.name(), e.getMessage());

            var msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();

            return new ToolResultMessageBlock(call.id(), msg, true);
        }
    }

    private static ModelMessage toAssistantMessage(String text, List<ToolCall> toolCalls) {

        var blocks = new ArrayList<MessageBlock>();

        if (text != null && !text.isBlank())
            blocks.add(new TextMessageBlock(text));

        for (var call : toolCalls)
            blocks.add(new ToolUseMessageBlock(call.id(), call.name(), call.args()));

        return ModelMessage.assistant(blocks);
    }

    private static String lastAssistantText(List<ModelMessage> history) {

        for (int i = history.size() - 1; i >= 0; i--) {

            var msg = history.get(i);

            if (msg.messageRole() != MessageRole.ASSISTANT) continue;

            for (var block : msg.messageBlocks())
                if (block instanceof TextMessageBlock text && !text.text().isBlank()) return text.text();
        }

        return "";
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {

        private Model model;
        private String systemPrompt;
        private final List<Tool> tools = new ArrayList<>();
        private int maxTurns = DEFAULT_MAX_TURNS;

        private Builder() {}

        public Builder model(Model model)               { this.model = model; return this; }
        public Builder systemPrompt(String prompt)      { this.systemPrompt = prompt; return this; }
        public Builder maxTurns(int maxTurns)           { this.maxTurns = maxTurns; return this; }

        public Builder tool(Tool tool) {

            if (tool == null) throw new IllegalArgumentException("Tool required");

            tools.add(tool);

            return this;
        }

        public Builder tools(List<Tool> tools) {

            if (tools == null) throw new IllegalArgumentException("Tools list required");

            for (var tool : tools) tool(tool);

            return this;
        }

        public ReActAgent build() {

            if (model == null) throw new IllegalStateException("Model required");
            if (Utils.isMissing(systemPrompt)) throw new IllegalStateException("System prompt required");
            if (maxTurns <= 0) throw new IllegalStateException("maxTurns must be > 0");

            var byName = new HashMap<String, Tool>();
            var seen = new HashSet<String>();

            for (var tool : tools) {

                var name = tool.definition().name();

                if (!seen.add(name))
                    throw new IllegalStateException("Duplicate tool name: " + name);

                byName.put(name, tool);
            }

            return new ReActAgent(model, systemPrompt, byName, maxTurns);
        }
    }
}
