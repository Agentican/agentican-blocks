package ai.agentican.blocks.llm.provider;

import ai.agentican.blocks.llm.*;

import ai.agentican.blocks.llm.api.*;
import ai.agentican.blocks.llm.api.Model;
import ai.agentican.blocks.llm.api.StopReason;
import ai.agentican.blocks.llm.impl.*;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class Anthropic implements Model {

    private static final Logger LOG = LoggerFactory.getLogger(Anthropic.class);

    private static final CacheControlEphemeral CACHE_CONTROL = CacheControlEphemeral.builder().build();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String modelName;
    private final long maxTokens;
    private final Double temperature;

    private final AnthropicClient client;

    public Anthropic(String apiKey, String modelName) {

        this(apiKey, modelName, DEFAULT_MAX_TOKENS, null);
    }

    public Anthropic(String apiKey, String modelName, long maxTokens, Double temperature) {

        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("API key required");
        if (modelName == null || modelName.isBlank()) throw new IllegalArgumentException("Model name required");

        this.modelName = modelName;
        this.maxTokens = maxTokens > 0 ? maxTokens : DEFAULT_MAX_TOKENS;
        this.temperature = temperature;
        this.client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
    }

    @Override
    public <T> ModelResponse<T> send(String systemPrompt, List<ModelMessage> messages,
                                     List<ToolDefinition> tools, Class<T> outputType) {

        var translated = messageParams(messages);

        var messageBuilder = MessageCreateParams.builder()
                .model(com.anthropic.models.messages.Model.of(modelName))
                .maxTokens(maxTokens)
                .messages(translated);

        if (Utils.isFound(systemPrompt)) {

            var systemPromptBlock = TextBlockParam.builder()
                    .text(systemPrompt)
                    .cacheControl(CACHE_CONTROL)
                    .build();

            messageBuilder.systemOfTextBlockParams(List.of(systemPromptBlock));
        }

        if (temperature != null) messageBuilder.temperature(temperature);

        if (!Utils.isUnstructured(outputType))
            messageBuilder.outputConfig(outputConfig(Utils.schema(outputType)));

        if (tools != null) {

            tools.forEach(tool -> {

                var schemaBuilder = com.anthropic.models.messages.Tool.InputSchema.builder().type(JsonValue.from("object"));

                if (tool.properties() != null && !tool.properties().isEmpty())
                    schemaBuilder.properties(JsonValue.from(tool.properties()));

                if (tool.required() != null && !tool.required().isEmpty())
                    schemaBuilder.required(JsonValue.from(tool.required()));

                messageBuilder.addTool(com.anthropic.models.messages.Tool.builder()
                        .name(tool.name())
                        .description(tool.description())
                        .inputSchema(schemaBuilder.build())
                        .build());
            });
        }

        messageBuilder.addTool(WebSearchTool20250305.builder().build());
        messageBuilder.addTool(WebFetchTool20250910.builder().cacheControl(CACHE_CONTROL).build());

        LOG.debug("LLM: sending request");

        var response = client.messages().create(messageBuilder.build());

        LOG.debug("LLM: received response");

        var responseText = response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(TextBlock::text)
                .collect(Collectors.joining());

        var toolCalls = response.content().stream()
                .filter(ContentBlock::isToolUse)
                .flatMap(block -> block.toolUse().stream())
                .map(toolUse -> {

                    Map<String, Object> toolArgs = toolUse._input().convert(new TypeReference<>() {});

                    return new ToolCall(toolUse.id(), toolUse.name(), toolArgs != null ? toolArgs : Map.of());

                }).toList();

        var stopReason = switch (response.stopReason().orElseThrow().asString()) {

            case "tool_use" -> StopReason.TOOL_USE;
            case "max_tokens" -> StopReason.MAX_TOKENS;
            default -> StopReason.END_TURN;
        };

        var usage = response.usage();

        long inputTokens = usage.inputTokens();
        long outputTokens = usage.outputTokens();
        long cacheWriteTokens = usage.cacheCreationInputTokens().orElse(0L);
        long cacheReadTokens = usage.cacheReadInputTokens().orElse(0L);
        long webSearchRequests =
                usage.serverToolUse().map(stu -> (long) stu.webSearchRequests()).orElse(0L);

        T output = parse(responseText, outputType);

        return new SingleResponse<>(output, responseText, toolCalls, stopReason,
                new ModelUsage(inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens, webSearchRequests));
    }

    private static <T> T parse(String text, Class<T> outputType) {

        if (Utils.isUnstructured(outputType) || text == null || text.isBlank()) return null;

        try {

            return JSON.readValue(text, outputType);
        }
        catch (Exception e) {

            throw new RuntimeException("Failed to deserialize response as "
                    + outputType.getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    private static List<MessageParam> messageParams(List<ModelMessage> modelMessages) {

        var messagesParams = new ArrayList<MessageParam>(modelMessages.size());

        var userMessageCached = false;

        for (var message : modelMessages) {

            var contentBlocks = new ArrayList<ContentBlockParam>(message.messageBlocks().size());

            var cacheUserMessage = !userMessageCached && message.messageRole() == MessageRole.USER;

            var lastUserMessageBlockIndex = message.messageBlocks().size() - 1;

            for (int blockIndex = 0; blockIndex < message.messageBlocks().size(); blockIndex++) {

                var block = message.messageBlocks().get(blockIndex);

                var applyCache = cacheUserMessage && blockIndex == lastUserMessageBlockIndex;

                contentBlocks.add(contentBlockParams(block, applyCache));
            }

            messagesParams.add(MessageParam.builder()
                    .role(message.messageRole() == MessageRole.USER ? MessageParam.Role.USER : MessageParam.Role.ASSISTANT)
                    .contentOfBlockParams(contentBlocks)
                    .build());

            if (cacheUserMessage)
                userMessageCached = true;
        }

        return messagesParams;
    }

    private static ContentBlockParam contentBlockParams(MessageBlock messageBlock, boolean applyCache) {

        return switch (messageBlock) {

            case TextMessageBlock textBlock -> {

                var block = TextBlockParam.builder().text(textBlock.text());

                if (applyCache)
                    block.cacheControl(CACHE_CONTROL);

                yield ContentBlockParam.ofText(block.build());
            }

            case ToolUseMessageBlock toolUseBlock -> {

                var toolInputs = JSON.<JsonValue>convertValue(toolUseBlock.args(), new TypeReference<JsonValue>() {});

                yield ContentBlockParam.ofToolUse(ToolUseBlockParam.builder()
                        .id(toolUseBlock.id())
                        .name(toolUseBlock.toolName())
                        .input(toolInputs != null ? toolInputs : JsonValue.from(Map.of()))
                        .build());
            }

            case ToolResultMessageBlock toolResultBlock -> {

                var block = ToolResultBlockParam.builder()
                        .toolUseId(toolResultBlock.toolUseId())
                        .content(toolResultBlock.content());

                if (toolResultBlock.isError())
                    block.isError(true);

                yield ContentBlockParam.ofToolResult(block.build());
            }
        };
    }

    private static OutputConfig outputConfig(JsonNode schema) {

        var schemaBuilder = JsonOutputFormat.Schema.builder();

        var fields = JSON.<Map<String, Object>>convertValue(schema, new TypeReference<Map<String, Object>>() {});

        fields.forEach((k, v) -> schemaBuilder.putAdditionalProperty(k, JsonValue.from(v)));

        var format = JsonOutputFormat.builder().schema(schemaBuilder.build()).build();

        return OutputConfig.builder().format(format).build();
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder implements ModelBuilder<Builder> {

        private String apiKey;
        private String modelName;
        private long maxTokens = DEFAULT_MAX_TOKENS;
        private Double temperature;

        public Builder apiKey(String apiKey) { this.apiKey = apiKey; return this; }
        @Override public Builder model(String model) { this.modelName = model; return this; }
        @Override public Builder maxTokens(long n) { this.maxTokens = n; return this; }
        @Override public Builder temperature(Double t) { this.temperature = t; return this; }

        @Override public Model build() {
            return new Anthropic(apiKey, modelName, maxTokens, temperature);
        }
    }
}
