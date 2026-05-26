package ai.agentican.blocks.llm.provider;

import ai.agentican.blocks.llm.*;

import ai.agentican.blocks.llm.api.*;
import ai.agentican.blocks.llm.impl.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.ResponseFormatJsonSchema;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionTool;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class OpenAiCompatible implements Provider {

    private static final Logger LOG = LoggerFactory.getLogger(OpenAiCompatible.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    public static final String SAMBANOVA_BASE_URL = "https://api.sambanova.ai/v1";
    public static final String TOGETHER_BASE_URL = "https://api.together.xyz/v1";
    public static final String FIREWORKS_BASE_URL = "https://api.fireworks.ai/inference/v1";

    private final String baseUrl;
    private final String modelName;
    private final long maxTokens;
    private final Double temperature;

    private final OpenAIClient client;

    public OpenAiCompatible(String apiKey, String baseUrl, String modelName) {
        this(apiKey, baseUrl, modelName, DEFAULT_MAX_TOKENS, null);
    }

    public OpenAiCompatible(String apiKey, String baseUrl, String modelName,
                            long maxTokens, Double temperature) {

        if (Utils.isMissing(apiKey)) throw new IllegalArgumentException("API key required");
        if (Utils.isMissing(baseUrl)) throw new IllegalArgumentException("Base URL required");
        if (Utils.isMissing(modelName)) throw new IllegalArgumentException("Model name required");

        this.baseUrl = baseUrl;
        this.modelName = modelName;
        this.maxTokens = maxTokens > 0 ? maxTokens : DEFAULT_MAX_TOKENS;
        this.temperature = temperature;

        this.client = OpenAIOkHttpClient.builder().baseUrl(baseUrl).apiKey(apiKey).build();
    }

    @Override
    public <T> ModelResponse<T> send(String systemPrompt, List<ModelMessage> messages,
                                     List<ToolDefinition> tools, Class<T> outputType) {

        var paramsBuilder = ChatCompletionCreateParams.builder()
                .model(modelName)
                .maxCompletionTokens(maxTokens)
                .addMessage(ChatCompletionSystemMessageParam.builder().content(systemPrompt).build());

        translateMessages(messages).forEach(paramsBuilder::addMessage);

        if (temperature != null)
            paramsBuilder.temperature(temperature);

        if (!Utils.isUnstructured(outputType))
            paramsBuilder.responseFormat(buildJsonSchemaFormat(outputType));

        if (tools != null) {

            tools.forEach(tool -> {

                var parameters = FunctionParameters.builder()
                        .putAdditionalProperty("type", JsonValue.from("object"))
                        .putAdditionalProperty("properties", JsonValue.from(tool.properties()))
                        .putAdditionalProperty("required", JsonValue.from(tool.required()))
                        .build();

                var functionTool = ChatCompletionFunctionTool.builder()
                        .function(FunctionDefinition.builder()
                                .name(tool.name())
                                .description(tool.description())
                                .parameters(parameters)
                                .strict(false)
                                .build())
                        .build();

                paramsBuilder.addTool(ChatCompletionTool.ofFunction(functionTool));
            });
        }

        var completion = client.chat().completions().create(paramsBuilder.build());

        return translate(completion, outputType);
    }

    private static <T> ResponseFormatJsonSchema buildJsonSchemaFormat(Class<T> outputType) {

        var schemaBuilder = ResponseFormatJsonSchema.JsonSchema.Schema.builder();

        JsonNode schema = Utils.schema(outputType);

        var fields = MAPPER.<Map<String, Object>>convertValue(schema, new TypeReference<Map<String, Object>>() {});
        fields.forEach((k, v) -> schemaBuilder.putAdditionalProperty(k, JsonValue.from(v)));

        var jsonSchema = ResponseFormatJsonSchema.JsonSchema.builder()
                .name(outputType.getSimpleName())
                .schema(schemaBuilder.build())
                .strict(true)
                .build();

        return ResponseFormatJsonSchema.builder().jsonSchema(jsonSchema).build();
    }

    private static List<ChatCompletionMessageParam> translateMessages(List<ModelMessage> modelMessages) {

        var out = new ArrayList<ChatCompletionMessageParam>(modelMessages.size());

        for (var msg : modelMessages) {

            if (msg.messageRole() == MessageRole.USER) {

                var toolResultBlocks = msg.messageBlocks().stream()
                        .filter(b -> b instanceof ToolResultMessageBlock)
                        .map(b -> (ToolResultMessageBlock) b)
                        .toList();

                for (var tr : toolResultBlocks)
                    out.add(ChatCompletionMessageParam.ofTool(ChatCompletionToolMessageParam.builder()
                            .toolCallId(tr.toolUseId())
                            .content(tr.content())
                            .build()));

                var text = msg.messageBlocks().stream()
                        .filter(b -> b instanceof TextMessageBlock)
                        .map(b -> ((TextMessageBlock) b).text())
                        .filter(t -> !t.isBlank())
                        .reduce((a, b) -> a + "\n\n" + b)
                        .orElse(null);

                if (text != null)
                    out.add(ChatCompletionMessageParam.ofUser(ChatCompletionUserMessageParam.builder()
                            .content(text)
                            .build()));
            }
            else {

                var assistantBuilder = ChatCompletionAssistantMessageParam.builder();

                var text = msg.messageBlocks().stream()
                        .filter(b -> b instanceof TextMessageBlock)
                        .map(b -> ((TextMessageBlock) b).text())
                        .filter(t -> !t.isBlank())
                        .reduce((a, b) -> a + "\n\n" + b)
                        .orElse(null);

                if (text != null) assistantBuilder.content(text);

                msg.messageBlocks().stream()
                        .filter(b -> b instanceof ToolUseMessageBlock)
                        .map(b -> (ToolUseMessageBlock) b)
                        .forEach(tu -> {
                            var argsJson = toJson(tu.args());
                            var fn = ChatCompletionMessageFunctionToolCall.Function.builder()
                                    .name(tu.toolName())
                                    .arguments(argsJson)
                                    .build();
                            var fnCall = ChatCompletionMessageFunctionToolCall.builder()
                                    .id(tu.id())
                                    .function(fn)
                                    .build();
                            assistantBuilder.addToolCall(ChatCompletionMessageToolCall.ofFunction(fnCall));
                        });

                out.add(ChatCompletionMessageParam.ofAssistant(assistantBuilder.build()));
            }
        }

        return out;
    }

    private static String toJson(Map<String, Object> args) {

        try {
            return MAPPER.writeValueAsString(args != null ? args : Map.of());
        }
        catch (Exception e) {
            return "{}";
        }
    }

    private <T> ModelResponse<T> translate(ChatCompletion completion, Class<T> outputType) {

        if (completion.choices().isEmpty())
            return new SingleResponse<>(null, "", List.of(), StopReason.END_TURN, ModelUsage.ZERO);

        var choice = completion.choices().get(0);
        var message = choice.message();

        var text = message.content().orElse("");

        var toolCalls = new ArrayList<ToolCall>();

        message.toolCalls().ifPresent(calls -> calls.forEach(call -> {

            if (!call.isFunction()) return;

            var fn = call.asFunction();
            var args = parseArgs(fn.function().arguments());

            toolCalls.add(new ToolCall(fn.id(), fn.function().name(), args));
        }));

        var stopReason = resolveStopReason(choice, !toolCalls.isEmpty());

        var usage = completion.usage();

        long promptTotal = usage.map(u -> u.promptTokens()).orElse(0L);
        long cacheReadTokens = usage
                .flatMap(u -> u.promptTokensDetails())
                .flatMap(d -> d.cachedTokens())
                .orElse(0L);
        long inputTokens = Math.max(0, promptTotal - cacheReadTokens);
        long outputTokens = usage.map(u -> u.completionTokens()).orElse(0L);

        T parsed = parseTyped(text, outputType);

        return new SingleResponse<>(parsed, text, toolCalls, stopReason,
                new ModelUsage(inputTokens, outputTokens, cacheReadTokens, 0L, 0L));
    }

    private static <T> T parseTyped(String text, Class<T> outputType) {

        if (Utils.isUnstructured(outputType) || text == null || text.isBlank()) return null;

        try {
            return MAPPER.readValue(text, outputType);
        }
        catch (Exception e) {
            throw new RuntimeException("Failed to deserialize response as "
                    + outputType.getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    private StopReason resolveStopReason(ChatCompletion.Choice choice, boolean hasToolCalls) {

        if (hasToolCalls) return StopReason.TOOL_USE;

        var reason = choice.finishReason().asString();

        return switch (reason) {
            case "length" -> StopReason.MAX_TOKENS;
            case "stop" -> StopReason.END_TURN;
            case "tool_calls" -> StopReason.TOOL_USE;
            default -> {
                LOG.warn("{} response finished with reason '{}'; mapping to END_TURN", baseUrl, reason);
                yield StopReason.END_TURN;
            }
        };
    }

    private Map<String, Object> parseArgs(String arguments) {

        if (arguments == null || arguments.isBlank()) return Map.of();

        try {

            var parsed = MAPPER.readValue(arguments, MAP_TYPE);
            return parsed != null ? parsed : Map.of();

        } catch (Exception e) {

            LOG.warn("Failed to parse {} tool-call arguments as JSON: {}", baseUrl, arguments, e);
            return Map.of();
        }
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder implements ProviderBuilder<Builder> {

        private String apiKey;
        private String baseUrl;
        private String modelName;
        private long maxTokens = DEFAULT_MAX_TOKENS;
        private Double temperature;

        public Builder apiKey(String apiKey) { this.apiKey = apiKey; return this; }
        public Builder baseUrl(String baseUrl) { this.baseUrl = baseUrl; return this; }
        @Override public Builder model(String model) { this.modelName = model; return this; }
        @Override public Builder maxTokens(long n) { this.maxTokens = n; return this; }
        @Override public Builder temperature(Double t) { this.temperature = t; return this; }

        @Override public Provider build() {
            return new OpenAiCompatible(apiKey, baseUrl, modelName, maxTokens, temperature);
        }
    }
}
