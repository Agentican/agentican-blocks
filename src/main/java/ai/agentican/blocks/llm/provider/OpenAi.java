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
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFormatTextConfig;
import com.openai.models.responses.ResponseFormatTextJsonSchemaConfig;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseStatus;
import com.openai.models.responses.ResponseTextConfig;
import com.openai.models.responses.Tool;
import com.openai.models.responses.WebSearchTool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class OpenAi implements Provider {

    private static final Logger LOG = LoggerFactory.getLogger(OpenAi.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    public static final String OPENAI = "openai";
    public static final String GROQ = "groq";

    private static final Map<String, String> BASE_URLS = new HashMap<>();

    static {
        BASE_URLS.put(OPENAI, null);
        BASE_URLS.put(GROQ, "https://api.groq.com/openai/v1");
    }

    private static final String GROQ_GPT_OSS_PREFIX = "openai/gpt-oss-";

    private final String provider;
    private final String modelName;
    private final long maxTokens;
    private final Double temperature;

    private final OpenAIClient client;

    public OpenAi(String apiKey, String modelName) {

        this(apiKey, OPENAI, modelName, DEFAULT_MAX_TOKENS, null);
    }

    public OpenAi(String apiKey, String modelName, long maxTokens, Double temperature) {

        this(apiKey, OPENAI, modelName, maxTokens, temperature);
    }

    public OpenAi(String apiKey, String provider, String modelName, long maxTokens, Double temperature) {

        if (Utils.isMissing(apiKey)) throw new IllegalArgumentException("API key required");
        if (Utils.isMissing(modelName)) throw new IllegalArgumentException("Model name required");

        if (!BASE_URLS.containsKey(provider))
            throw new IllegalArgumentException(
                    "Unsupported Responses-API provider: " + provider
                    + " (expected one of " + BASE_URLS.keySet() + ")");

        this.provider = provider;
        this.modelName = modelName;
        this.maxTokens = maxTokens > 0 ? maxTokens : DEFAULT_MAX_TOKENS;
        this.temperature = temperature;

        var clientBuilder = OpenAIOkHttpClient.builder().apiKey(apiKey);
        var baseUrl = BASE_URLS.get(provider);
        if (baseUrl != null) clientBuilder.baseUrl(baseUrl);
        this.client = clientBuilder.build();
    }

    @Override
    public <T> ModelResponse<T> send(String systemPrompt,
                                     List<ModelMessage> messages,
                                     List<ToolDefinition> tools,
                                     Class<T> outputType) {

        var paramsBuilder = ResponseCreateParams.builder()
                .model(modelName)
                .instructions(systemPrompt)
                .maxOutputTokens(maxTokens)
                .inputOfResponse(translateMessages(messages));

        if (temperature != null) paramsBuilder.temperature(temperature);

        // OpenAI's native JSON-schema response_format only works on the "openai" provider, not on Groq.
        if (!Utils.isUnstructured(outputType) && "openai".equals(provider))
            paramsBuilder.text(buildTextConfig(outputType));

        if (tools != null) {

            tools.forEach(tool -> {

                var parameters = FunctionTool.Parameters.builder()
                        .putAdditionalProperty("type", JsonValue.from("object"))
                        .putAdditionalProperty("properties", JsonValue.from(tool.properties()))
                        .putAdditionalProperty("required", JsonValue.from(tool.required()))
                        .build();

                paramsBuilder.addTool(FunctionTool.builder()
                        .name(tool.name())
                        .description(tool.description())
                        .parameters(parameters)
                        .strict(false)
                        .build());
            });
        }

        addBuiltInSearchTool(paramsBuilder);

        var response = client.responses().create(paramsBuilder.build());

        return translate(response, outputType);
    }

    private static <T> ResponseTextConfig buildTextConfig(Class<T> outputType) {

        var schemaBuilder = ResponseFormatTextJsonSchemaConfig.Schema.builder();

        JsonNode schema = Utils.schema(outputType);

        var fields = MAPPER.<Map<String, Object>>convertValue(schema, new TypeReference<Map<String, Object>>() {});
        fields.forEach((k, v) -> schemaBuilder.putAdditionalProperty(k, JsonValue.from(v)));

        var jsonSchema = ResponseFormatTextJsonSchemaConfig.builder()
                .name(outputType.getSimpleName())
                .schema(schemaBuilder.build())
                .strict(true)
                .build();

        return ResponseTextConfig.builder()
                .format(ResponseFormatTextConfig.ofJsonSchema(jsonSchema))
                .build();
    }

    private void addBuiltInSearchTool(ResponseCreateParams.Builder paramsBuilder) {

        switch (provider) {
            case "openai" -> paramsBuilder.addTool(
                    WebSearchTool.builder().type(WebSearchTool.Type.WEB_SEARCH).build());

            case "groq" -> {
                if (modelName.startsWith(GROQ_GPT_OSS_PREFIX)) {
                    paramsBuilder.addTool(GROQ_BROWSER_SEARCH_TOOL);
                }
            }
        }
    }

    private static List<ResponseInputItem> translateMessages(List<ModelMessage> modelMessages) {

        var out = new ArrayList<ResponseInputItem>();

        for (var msg : modelMessages) {

            if (msg.messageRole() == MessageRole.USER) {

                msg.messageBlocks().stream()
                        .filter(b -> b instanceof ToolResultMessageBlock)
                        .map(b -> (ToolResultMessageBlock) b)
                        .forEach(tr -> out.add(ResponseInputItem.ofFunctionCallOutput(
                                ResponseInputItem.FunctionCallOutput.builder()
                                        .callId(tr.toolUseId())
                                        .output(tr.content())
                                        .build())));

                var text = msg.messageBlocks().stream()
                        .filter(b -> b instanceof TextMessageBlock)
                        .map(b -> ((TextMessageBlock) b).text())
                        .filter(t -> !t.isBlank())
                        .reduce((a, b) -> a + "\n\n" + b)
                        .orElse(null);

                if (text != null)
                    out.add(ResponseInputItem.ofEasyInputMessage(EasyInputMessage.builder()
                            .role(EasyInputMessage.Role.USER)
                            .content(text)
                            .build()));
            }
            else {

                var text = msg.messageBlocks().stream()
                        .filter(b -> b instanceof TextMessageBlock)
                        .map(b -> ((TextMessageBlock) b).text())
                        .filter(t -> !t.isBlank())
                        .reduce((a, b) -> a + "\n\n" + b)
                        .orElse(null);

                if (text != null)
                    out.add(ResponseInputItem.ofEasyInputMessage(EasyInputMessage.builder()
                            .role(EasyInputMessage.Role.ASSISTANT)
                            .content(text)
                            .build()));

                msg.messageBlocks().stream()
                        .filter(b -> b instanceof ToolUseMessageBlock)
                        .map(b -> (ToolUseMessageBlock) b)
                        .forEach(tu -> {
                            String argsJson;
                            try {
                                argsJson = MAPPER.writeValueAsString(tu.args());
                            } catch (Exception e) {
                                argsJson = "{}";
                            }
                            out.add(ResponseInputItem.ofFunctionCall(ResponseFunctionToolCall.builder()
                                    .callId(tu.id())
                                    .name(tu.toolName())
                                    .arguments(argsJson)
                                    .build()));
                        });
            }
        }

        return out;
    }

    private static <T> ModelResponse<T> translate(Response response, Class<T> outputType) {

        var textBuilder = new StringBuilder();
        var toolCalls = new ArrayList<ToolCall>();
        long webSearchRequests = 0;

        for (var item : response.output()) {

            if (item.isMessage()) {

                for (var content : item.asMessage().content()) {

                    if (content.isOutputText())
                        textBuilder.append(content.asOutputText().text());
                }
            } else if (item.isFunctionCall()) {

                var call = item.asFunctionCall();
                var args = parseArgs(call.arguments());

                toolCalls.add(new ToolCall(call.callId(), call.name(), args));

            } else if (item.isWebSearchCall()) {

                webSearchRequests++;
            }
        }

        var stopReason = resolveStopReason(response, !toolCalls.isEmpty());

        var usage = response.usage();

        long inputTotal = usage.map(u -> u.inputTokens()).orElse(0L);
        long cacheReadTokens = usage
                .map(u -> u.inputTokensDetails().cachedTokens())
                .orElse(0L);
        long inputTokens = Math.max(0, inputTotal - cacheReadTokens);
        long outputTokens = usage.map(u -> u.outputTokens()).orElse(0L);

        var responseText = textBuilder.toString();
        T parsed = parseTyped(responseText, outputType);

        return new SingleResponse<>(parsed, responseText, toolCalls, stopReason,
                new ModelUsage(inputTokens, outputTokens, cacheReadTokens, 0L, webSearchRequests));
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

    private static StopReason resolveStopReason(Response response, boolean hasToolCalls) {

        if (hasToolCalls) return StopReason.TOOL_USE;

        if (response.status().map(s -> s.equals(ResponseStatus.INCOMPLETE)).orElse(false)) {

            var reason = response.incompleteDetails()
                    .flatMap(d -> d.reason())
                    .map(Object::toString)
                    .orElse("");

            if (reason.contains("max_output_tokens")) return StopReason.MAX_TOKENS;

            LOG.warn("OpenAI response was incomplete (reason={}), mapping to END_TURN", reason);
        }

        return StopReason.END_TURN;
    }

    private static Map<String, Object> parseArgs(String arguments) {

        if (arguments == null || arguments.isBlank()) return Map.of();

        try {

            var parsed = MAPPER.readValue(arguments, MAP_TYPE);
            return parsed != null ? parsed : Map.of();

        } catch (Exception e) {

            LOG.warn("Failed to parse OpenAI tool-call arguments as JSON: {}", arguments, e);
            return Map.of();
        }
    }

    private static final Tool GROQ_BROWSER_SEARCH_TOOL = buildRawTool("browser_search");

    private static Tool buildRawTool(String typeName) {

        try {

            Constructor<?> ctor = Arrays.stream(Tool.class.getDeclaredConstructors())
                    .max(Comparator.comparingInt(Constructor::getParameterCount))
                    .orElseThrow(() -> new IllegalStateException("no Tool constructor"));
            ctor.setAccessible(true);

            var args = new Object[ctor.getParameterCount()];
            args[args.length - 1] = JsonValue.from(Map.of("type", typeName));

            return (Tool) ctor.newInstance(args);

        } catch (ReflectiveOperationException t) {
            throw new IllegalStateException("Failed to build raw Tool for type=" + typeName, t);
        }
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder implements ProviderBuilder<Builder> {

        private String apiKey;
        private String provider = OPENAI;
        private String modelName;
        private long maxTokens = DEFAULT_MAX_TOKENS;
        private Double temperature;

        public Builder apiKey(String apiKey) { this.apiKey = apiKey; return this; }
        public Builder provider(String provider) { this.provider = provider; return this; }
        @Override public Builder model(String model) { this.modelName = model; return this; }
        @Override public Builder maxTokens(long n) { this.maxTokens = n; return this; }
        @Override public Builder temperature(Double t) { this.temperature = t; return this; }

        @Override public Provider build() {
            return new OpenAi(apiKey, provider, modelName, maxTokens, temperature);
        }
    }
}
