package ai.agentican.blocks.llm.agent;

import ai.agentican.blocks.llm.Templates;
import ai.agentican.blocks.llm.Utils;
import ai.agentican.blocks.llm.api.*;
import ai.agentican.blocks.llm.impl.DefaultClient;
import ai.agentican.blocks.llm.impl.MessageBlock;
import ai.agentican.blocks.llm.impl.MessageRole;
import ai.agentican.blocks.llm.impl.ModelMessage;
import ai.agentican.blocks.llm.impl.TextMessageBlock;
import ai.agentican.blocks.llm.impl.ToolResultMessageBlock;
import ai.agentican.blocks.llm.impl.ToolUseMessageBlock;

import io.quarkus.qute.Template;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class ReActAgent implements Agent {

    private static final Logger LOG = LoggerFactory.getLogger(ReActAgent.class);

    private static final int DEFAULT_MAX_TURNS = 10;

    private final Client client;
    private final Template systemTemplate;
    private final Map<String, Tool> tools;
    private final int maxTurns;

    private final List<ToolDefinition> toolDefinitions;

    private ReActAgent(Client client, String systemPrompt, Map<String, Tool> tools, int maxTurns) {

        this.client = client;
        this.systemTemplate = Templates.parse(systemPrompt);
        this.tools = Map.copyOf(tools);
        this.maxTurns = maxTurns;

        this.toolDefinitions = tools.values().stream().map(Tool::definition).toList();
    }

    @Override
    public <T> LoopResponse<T> respond(String task, Object input, Class<T> outputType) {

        if (Utils.isMissing(task))
            throw new IllegalArgumentException("Task required");

        if (outputType == null)
            throw new IllegalArgumentException("Output type required");

        var renderedSystem = Templates.render(systemTemplate, input);

        var renderedTask = Templates.render(Templates.parse(task), input);

        boolean structured = outputType != Void.class && outputType != String.class;

        Class<?> modelCallType = structured ? outputType : Void.class;

        var messageHistory = new ArrayList<ModelMessage>();

        var userMessageBlock = TextMessageBlock.of(renderedTask);

        var userMessage = ModelMessage.user(userMessageBlock);

        messageHistory.add(userMessage);

        var modelUsage = ModelUsage.ZERO;

        for (int turn = 0; turn < maxTurns; turn++) {

            LOG.debug("Turn {}: starting", turn + 1);

            var modelResponse = client.send(renderedSystem, messageHistory, toolDefinitions, modelCallType);

            var responseText = modelResponse.text();
            var stopReason = modelResponse.stopReason();

            var addlModelUsage = modelResponse.usage();

            modelUsage = modelUsage.plus(addlModelUsage);

            var assistantMessage = toAssistantMessage(modelResponse);

            messageHistory.add(assistantMessage);

            if (stopReason != StopReason.TOOL_USE || modelResponse.toolCalls().isEmpty()) {

                LOG.debug("Turn {}: exiting loop ({})", turn + 1, stopReason);

                T finalOutput = extractOutput(modelResponse, structured);

                return LoopResponse.<T>builder()
                        .output(finalOutput)
                        .text(responseText)
                        .stopReason(stopReason)
                        .usage(modelUsage)
                        .messages(messageHistory)
                        .turns(turn + 1)
                        .build();
            }

            var toolResultBlocks = new ArrayList<MessageBlock>();

            var toolCalls = modelResponse.toolCalls();

            LOG.debug("Turn {}: calling {} tools", turn + 1, toolCalls.size());

            for (var toolCall : toolCalls) {

                var toolResultBlock = callTool(toolCall);

                toolResultBlocks.add(toolResultBlock);
            }

            var toolResponse = ModelMessage.user(toolResultBlocks);

            messageHistory.add(toolResponse);

            LOG.debug("Turn {}: finished", turn + 1);
        }

        var responseText = lastAssistantText(messageHistory);

        return LoopResponse.<T>builder()
                .text(responseText)
                .stopReason(StopReason.MAX_TURNS)
                .usage(modelUsage)
                .messages(messageHistory)
                .turns(maxTurns)
                .build();
    }

    @SuppressWarnings("unchecked")
    private static <T> T extractOutput(ModelResponse<?> modelResponse, boolean structured) {

        if (structured) return (T) modelResponse.output();

        var text = modelResponse.text();

        return (T) (text == null ? "" : text);
    }

    private ToolResultMessageBlock callTool(ToolCall toolCall) {

        var toolCallId = toolCall.id();
        var toolName = toolCall.name();

        var tool = tools.get(toolName);

        if (tool == null) {

            LOG.warn("Unknown tool: {}", toolName);

            return ToolResultMessageBlock.builder()
                    .toolUseId(toolCallId)
                    .content("Unknown tool: " + toolName)
                    .isError(true)
                    .build();
        }

        try {

            var toolArgs = toolCall.args();

            var tmpToolResult = tool.execute(toolArgs);

            var toolResult = tmpToolResult != null ? tmpToolResult : "";

            return ToolResultMessageBlock.builder()
                    .toolUseId(toolCallId)
                    .content(toolResult)
                    .build();
        }
        catch (Exception ex) {

            LOG.warn("Tool call: {} failed ({})", toolCall.name(), ex.getMessage());

            var errorMessage = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();

            return ToolResultMessageBlock.builder()
                    .toolUseId(toolCallId)
                    .content(errorMessage)
                    .isError(true)
                    .build();
        }
    }

    private static ModelMessage toAssistantMessage(ModelResponse<?> modelResponse) {

        var blocks = new ArrayList<MessageBlock>();

        var text = modelResponse.text();
        var toolCalls = modelResponse.toolCalls();

        if (text != null && !text.isBlank()) {

            var textBlock = TextMessageBlock.of(text);

            blocks.add(textBlock);
        }

        for (var toolCall : toolCalls) {

            var toolCallId = toolCall.id();
            var toolName = toolCall.name();
            var toolArgs = toolCall.args();

            var toolBlock = ToolUseMessageBlock.builder()
                    .id(toolCallId)
                    .toolName(toolName)
                    .args(toolArgs)
                    .build();

            blocks.add(toolBlock);
        }

        return ModelMessage.assistant(blocks);
    }

    private static String lastAssistantText(List<ModelMessage> messageHistory) {

        for (int i = messageHistory.size() - 1; i >= 0; i--) {

            var message = messageHistory.get(i);

            if (message.messageRole() != MessageRole.ASSISTANT) continue;

            for (var block : message.messageBlocks())
                if (block instanceof TextMessageBlock(String text) && !text.isBlank()) return text;
        }

        return "";
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {

        private final List<Tool> tools = new ArrayList<>();

        private Client client;
        private Model model;
        private String systemPrompt;
        private int maxTurns = DEFAULT_MAX_TURNS;

        private Builder() {}

        public Builder client(Client client) { this.client = client; return this; }

        public Builder model(Model model) { this.model = model; return this; }

        public Builder model(Function<Model.Builder, ModelBuilder<?>> config) {

            if (config == null) throw new IllegalArgumentException("Model config required");

            this.model = config.apply(Model.builder()).build();

            return this;
        }

        public Builder systemPrompt(String prompt) { this.systemPrompt = prompt; return this; }
        public Builder maxTurns(int maxTurns) { this.maxTurns = maxTurns; return this; }

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

            if (maxTurns <= 0) throw new IllegalStateException("maxTurns must be > 0");

            Client resolved = client;

            if (resolved == null) {

                if (model == null) throw new IllegalStateException("Client or Model required");

                resolved = new DefaultClient(model);
            }

            var byName = new HashMap<String, Tool>();
            var seen = new HashSet<String>();

            for (var tool : tools) {

                var name = tool.definition().name();

                if (!seen.add(name))
                    throw new IllegalStateException("Duplicate tool name: " + name);

                byName.put(name, tool);
            }

            return new ReActAgent(resolved, systemPrompt, byName, maxTurns);
        }
    }
}
