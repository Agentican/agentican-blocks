package ai.agentican.blocks.llm.provider;

import ai.agentican.blocks.llm.*;

import ai.agentican.blocks.llm.api.*;
import ai.agentican.blocks.llm.impl.*;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.StopReason;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.Tool;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultStatus;
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ToolConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.ToolInputSchema;
import software.amazon.awssdk.services.bedrockruntime.model.ToolSpecification;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Bedrock implements Provider {

    private static final Logger LOG = LoggerFactory.getLogger(Bedrock.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String modelName;
    private final long maxTokens;
    private final Double temperature;

    private final BedrockRuntimeClient client;

    public Bedrock(String modelName) {

        this(null, null, null, modelName, DEFAULT_MAX_TOKENS, null);
    }

    public Bedrock(String accessKeyId, String secretAccessKey, String region, String modelName,
                   long maxTokens, Double temperature) {

        if (Utils.isMissing(modelName))
            throw new IllegalArgumentException("Model name required");

        this.modelName = modelName;
        this.maxTokens = maxTokens > 0 ? maxTokens : DEFAULT_MAX_TOKENS;
        this.temperature = temperature;

        var builder = BedrockRuntimeClient.builder();

        if (region != null && !region.isBlank())
            builder.region(Region.of(region));

        if (Utils.isFound(accessKeyId)) {

            if (Utils.isMissing(secretAccessKey))
                throw new IllegalArgumentException("Secret access key required");

            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKeyId, secretAccessKey)));
        }
        else {

            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }

        this.client = builder.build();
    }

    @Override
    public <T> ModelResponse<T> send(String systemPrompt, List<ModelMessage> messages,
                                     List<ToolDefinition> tools, Class<T> outputType) {

        var effectiveSystemPrompt = Utils.isUnstructured(outputType)
                ? systemPrompt
                : appendSchemaInstructions(systemPrompt, outputType);

        var systemBlock = SystemContentBlock.builder().text(effectiveSystemPrompt).build();

        var translated = translateMessages(messages);

        var inferenceBuilder = InferenceConfiguration.builder().maxTokens((int) Math.min(maxTokens, Integer.MAX_VALUE));

        if (temperature != null)
            inferenceBuilder.temperature(temperature.floatValue());

        var converseBuilder = ConverseRequest.builder()
                .modelId(modelName)
                .system(systemBlock)
                .messages(translated)
                .inferenceConfig(inferenceBuilder.build());

        if (tools != null && !tools.isEmpty()) {

            var awsTools = new ArrayList<Tool>();

            tools.forEach(tool -> {

                var schema = new LinkedHashMap<String, Object>();

                schema.put("type", "object");
                schema.put("properties", tool.properties() != null ? tool.properties() : Map.of());
                schema.put("required", tool.required() != null ? tool.required() : List.of());

                awsTools.add(Tool.builder()
                        .toolSpec(ToolSpecification.builder()
                                .name(tool.name())
                                .description(tool.description())
                                .inputSchema(ToolInputSchema.builder()
                                        .json(toDocument(schema))
                                        .build())
                                .build())
                        .build());
            });

            converseBuilder.toolConfig(ToolConfiguration.builder().tools(awsTools).build());
        }

        var response = client.converse(converseBuilder.build());

        return translate(response, outputType);
    }

    private static <T> String appendSchemaInstructions(String systemPrompt, Class<T> outputType) {

        String schemaJson;

        try {

            schemaJson = JSON.writeValueAsString(Utils.schema(outputType));
        }
        catch (Exception e) {

            throw new RuntimeException("Failed to render schema for " + outputType.getSimpleName(), e);
        }

        return systemPrompt
                + "\n\nRespond with a single JSON object matching this schema. No prose, no markdown fences:\n"
                + schemaJson;
    }

    private static List<Message> translateMessages(List<ModelMessage> modelMessages) {

        var out = new ArrayList<Message>(modelMessages.size());

        for (var msg : modelMessages) {

            var contents = new ArrayList<ContentBlock>();

            for (var block : msg.messageBlocks()) {

                switch (block) {

                    case TextMessageBlock t ->
                            contents.add(ContentBlock.fromText(t.text()));

                    case ToolUseMessageBlock tu ->
                            contents.add(ContentBlock.fromToolUse(ToolUseBlock.builder()
                                    .toolUseId(tu.id())
                                    .name(tu.toolName())
                                    .input(toDocument(tu.args()))
                                    .build()));

                    case ToolResultMessageBlock tr ->
                            contents.add(ContentBlock.fromToolResult(ToolResultBlock.builder()
                                    .toolUseId(tr.toolUseId())
                                    .content(ToolResultContentBlock.fromText(tr.content()))
                                    .status(tr.isError() ? ToolResultStatus.ERROR : ToolResultStatus.SUCCESS)
                                    .build()));
                }
            }

            out.add(Message.builder()
                    .role(msg.messageRole() == MessageRole.USER
                            ? ConversationRole.USER : ConversationRole.ASSISTANT)
                    .content(contents)
                    .build());
        }

        return out;
    }

    private static <T> ModelResponse<T> translate(ConverseResponse response, Class<T> outputType) {

        var textBuilder = new StringBuilder();
        var toolCalls = new ArrayList<ToolCall>();

        if (response.output() != null && response.output().message() != null) {

            for (var block : response.output().message().content()) {

                if (block.text() != null) {

                    textBuilder.append(block.text());

                } else if (block.toolUse() != null) {

                    var use = block.toolUse();
                    toolCalls.add(new ToolCall(use.toolUseId(), use.name(), asMap(use.input())));
                }
            }
        }

        var stopReason = resolveStopReason(response.stopReason(), !toolCalls.isEmpty());

        var usage = response.usage();

        long inputTotal = usage != null && usage.inputTokens() != null ? usage.inputTokens() : 0L;
        long cacheRead = usage != null && usage.cacheReadInputTokens() != null ? usage.cacheReadInputTokens() : 0L;
        long cacheWrite = usage != null && usage.cacheWriteInputTokens() != null ? usage.cacheWriteInputTokens() : 0L;
        long outputTokens = usage != null && usage.outputTokens() != null ? usage.outputTokens() : 0L;
        long inputTokens = Math.max(0, inputTotal - cacheRead);

        var responseText = textBuilder.toString();
        T parsed = parseTyped(responseText, outputType);

        return new SingleResponse<>(parsed, responseText, toolCalls, stopReason,
                new ModelUsage(inputTokens, outputTokens, cacheRead, cacheWrite, 0L));
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

    private static ai.agentican.blocks.llm.api.StopReason resolveStopReason(StopReason reason, boolean hasToolCalls) {

        if (hasToolCalls) return ai.agentican.blocks.llm.api.StopReason.TOOL_USE;

        if (reason == null) return ai.agentican.blocks.llm.api.StopReason.END_TURN;

        return switch (reason) {
            case END_TURN, STOP_SEQUENCE -> ai.agentican.blocks.llm.api.StopReason.END_TURN;
            case TOOL_USE -> ai.agentican.blocks.llm.api.StopReason.TOOL_USE;
            case MAX_TOKENS -> ai.agentican.blocks.llm.api.StopReason.MAX_TOKENS;
            default -> {
                LOG.warn("Bedrock response stopped with reason {}; mapping to END_TURN", reason);
                yield ai.agentican.blocks.llm.api.StopReason.END_TURN;
            }
        };
    }

    private static Document toDocument(Object value) {

        if (value == null) return Document.fromNull();

        if (value instanceof Map<?, ?> m) {

            var builder = Document.mapBuilder();

            m.forEach((k, v) -> builder.putDocument(String.valueOf(k), toDocument(v)));

            return builder.build();
        }

        if (value instanceof List<?> l) {

            var items = new ArrayList<Document>();

            for (var item : l) items.add(toDocument(item));

            return Document.fromList(items);
        }

        if (value instanceof String s) return Document.fromString(s);
        if (value instanceof Boolean b) return Document.fromBoolean(b);
        if (value instanceof Integer i) return Document.fromNumber(i);
        if (value instanceof Long lng) return Document.fromNumber(lng);
        if (value instanceof Double d) return Document.fromNumber(d);
        if (value instanceof Float f) return Document.fromNumber(f);
        if (value instanceof Number n) return Document.fromNumber(String.valueOf(n));

        return Document.fromString(String.valueOf(value));
    }

    private static Map<String, Object> asMap(Document document) {

        if (document == null || document.isNull()) return Map.of();

        var obj = fromDocument(document);

        if (obj instanceof Map<?, ?> m) {
            var out = new LinkedHashMap<String, Object>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }

        return Map.of();
    }

    private static Object fromDocument(Document document) {

        if (document == null || document.isNull()) return null;
        if (document.isMap()) {
            var out = new LinkedHashMap<String, Object>();
            document.asMap().forEach((k, v) -> out.put(k, fromDocument(v)));
            return out;
        }
        if (document.isList()) {
            var out = new ArrayList<>();
            document.asList().forEach(item -> out.add(fromDocument(item)));
            return out;
        }
        if (document.isString()) return document.asString();
        if (document.isBoolean()) return document.asBoolean();
        if (document.isNumber()) return document.asNumber().bigDecimalValue();

        return null;
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder implements ProviderBuilder<Builder> {

        private String accessKeyId;
        private String secretAccessKey;
        private String region;
        private String modelName;
        private long maxTokens = DEFAULT_MAX_TOKENS;
        private Double temperature;

        public Builder accessKeyId(String s)            { this.accessKeyId = s; return this; }
        public Builder secretAccessKey(String s)        { this.secretAccessKey = s; return this; }
        public Builder region(String region)            { this.region = region; return this; }
        @Override public Builder model(String model)    { this.modelName = model; return this; }
        @Override public Builder maxTokens(long n)      { this.maxTokens = n; return this; }
        @Override public Builder temperature(Double t)  { this.temperature = t; return this; }

        @Override public Provider build() {
            return new Bedrock(accessKeyId, secretAccessKey, region, modelName, maxTokens, temperature);
        }
    }
}
