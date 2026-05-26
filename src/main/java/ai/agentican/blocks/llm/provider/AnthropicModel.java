package ai.agentican.blocks.llm.provider;

import ai.agentican.blocks.llm.*;

import ai.agentican.blocks.llm.api.*;
import ai.agentican.blocks.llm.api.StopReason;
import ai.agentican.blocks.llm.impl.*;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;
import com.anthropic.models.messages.Model;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class AnthropicModel implements ProviderModel {

    private static final CacheControlEphemeral CACHE_CONTROL = CacheControlEphemeral.builder().build();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String model;
    private final long maxTokens;
    private final Double temperature;
    private final AnthropicClient client;

    public AnthropicModel(String apiKey, String model) {

        this(apiKey, model, 16384L, null);
    }

    public AnthropicModel(String apiKey, String model, long maxTokens, Double temperature) {

        if (apiKey == null || apiKey.isBlank())
            throw new IllegalArgumentException("apiKey is required");
        if (model == null || model.isBlank())
            throw new IllegalArgumentException("model is required");

        this.model = model;
        this.maxTokens = maxTokens > 0 ? maxTokens : 16384L;
        this.temperature = temperature;
        this.client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
    }

    @Override
    public <T> ModelResponse<T> execute(String systemPrompt, List<ModelMessage> messages,
                                        List<ToolDefinition> tools, Class<T> outputType) {

        var systemPromptBlock = TextBlockParam.builder()
                .text(systemPrompt)
                .cacheControl(CACHE_CONTROL)
                .build();

        var translated = translateMessages(messages);

        var messageBuilder = MessageCreateParams.builder()
                .model(Model.of(model))
                .maxTokens(maxTokens)
                .systemOfTextBlockParams(List.of(systemPromptBlock))
                .messages(translated);

        if (temperature != null) messageBuilder.temperature(temperature);

        if (!Utils.isUnstructured(outputType))
            messageBuilder.outputConfig(buildOutputConfig(Utils.schema(outputType)));

        if (tools != null) {

            tools.forEach(tool -> {

                var schemaBuilder = Tool.InputSchema.builder().type(JsonValue.from("object"));

                if (tool.properties() != null && !tool.properties().isEmpty())
                    schemaBuilder.properties(JsonValue.from(tool.properties()));

                if (tool.required() != null && !tool.required().isEmpty())
                    schemaBuilder.required(JsonValue.from(tool.required()));

                messageBuilder.addTool(Tool.builder()
                        .name(tool.name())
                        .description(tool.description())
                        .inputSchema(schemaBuilder.build())
                        .build());
            });
        }

        messageBuilder.addTool(WebSearchTool20250305.builder().build());
        messageBuilder.addTool(WebFetchTool20250910.builder().cacheControl(CACHE_CONTROL).build());

        var response = client.messages().create(messageBuilder.build());

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

        T parsed = parseTyped(responseText, outputType);

        return new ModelResponse<>(parsed, responseText, toolCalls, stopReason,
                new ModelUsage(inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens, webSearchRequests));
    }

    private static <T> T parseTyped(String text, Class<T> outputType) {

        if (Utils.isUnstructured(outputType) || text == null || text.isBlank()) return null;

        try {

            return JSON.readValue(text, outputType);
        }
        catch (Exception e) {

            throw new RuntimeException("Failed to deserialize response as "
                    + outputType.getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    private static List<MessageParam> translateMessages(List<ModelMessage> modelMessages) {

        var out = new ArrayList<MessageParam>(modelMessages.size());

        var firstUserSeen = false;

        for (var msg : modelMessages) {

            var blocks = new ArrayList<ContentBlockParam>(msg.messageBlocks().size());

            var isFirstUser = !firstUserSeen && msg.messageRole() == MessageRole.USER;

            var lastIdx = msg.messageBlocks().size() - 1;

            for (int i = 0; i < msg.messageBlocks().size(); i++) {

                var block = msg.messageBlocks().get(i);
                var applyCache = isFirstUser && i == lastIdx;

                blocks.add(translateBlock(block, applyCache));
            }

            out.add(MessageParam.builder()
                    .role(msg.messageRole() == MessageRole.USER
                            ? MessageParam.Role.USER : MessageParam.Role.ASSISTANT)
                    .contentOfBlockParams(blocks)
                    .build());

            if (isFirstUser) firstUserSeen = true;
        }

        return out;
    }

    private static ContentBlockParam translateBlock(MessageBlock messageBlock, boolean applyCache) {

        return switch (messageBlock) {

            case TextMessageBlock t -> {

                var b = TextBlockParam.builder().text(t.text());
                if (applyCache) b.cacheControl(CACHE_CONTROL);
                yield ContentBlockParam.ofText(b.build());
            }

            case ToolUseMessageBlock tu -> {

                var inputJson = JSON.<JsonValue>convertValue(tu.args(), new TypeReference<JsonValue>() {});
                yield ContentBlockParam.ofToolUse(ToolUseBlockParam.builder()
                        .id(tu.id())
                        .name(tu.toolName())
                        .input(inputJson != null ? inputJson : JsonValue.from(Map.of()))
                        .build());
            }

            case ToolResultMessageBlock tr -> {

                var b = ToolResultBlockParam.builder()
                        .toolUseId(tr.toolUseId())
                        .content(tr.content());
                if (tr.isError()) b.isError(true);
                yield ContentBlockParam.ofToolResult(b.build());
            }
        };
    }

    private static OutputConfig buildOutputConfig(JsonNode schema) {

        var schemaBuilder = JsonOutputFormat.Schema.builder();

        var fields = JSON.<Map<String, Object>>convertValue(schema, new TypeReference<Map<String, Object>>() {});
        fields.forEach((k, v) -> schemaBuilder.putAdditionalProperty(k, JsonValue.from(v)));

        var format = JsonOutputFormat.builder().schema(schemaBuilder.build()).build();

        return OutputConfig.builder().format(format).build();
    }
}
