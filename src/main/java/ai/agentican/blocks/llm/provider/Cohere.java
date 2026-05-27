package ai.agentican.blocks.llm.provider;

import ai.agentican.blocks.llm.Utils;
import ai.agentican.blocks.llm.api.Model;
import ai.agentican.blocks.llm.api.ModelBuilder;
import ai.agentican.blocks.llm.api.ModelResponse;
import ai.agentican.blocks.llm.api.ModelUsage;
import ai.agentican.blocks.llm.api.SingleResponse;
import ai.agentican.blocks.llm.api.StopReason;
import ai.agentican.blocks.llm.api.ToolCall;
import ai.agentican.blocks.llm.api.ToolDefinition;
import ai.agentican.blocks.llm.impl.MessageRole;
import ai.agentican.blocks.llm.impl.ModelMessage;
import ai.agentican.blocks.llm.impl.TextMessageBlock;
import ai.agentican.blocks.llm.impl.ToolResultMessageBlock;
import ai.agentican.blocks.llm.impl.ToolUseMessageBlock;

import com.cohere.api.resources.v2.requests.V2ChatRequest;
import com.cohere.api.resources.v2.types.V2ChatResponse;
import com.cohere.api.types.AssistantMessage;
import com.cohere.api.types.AssistantMessageV2Content;
import com.cohere.api.types.ChatFinishReason;
import com.cohere.api.types.ChatMessageV2;
import com.cohere.api.types.JsonResponseFormatV2;
import com.cohere.api.types.ResponseFormatV2;
import com.cohere.api.types.SystemMessageV2;
import com.cohere.api.types.SystemMessageV2Content;
import com.cohere.api.types.ToolCallV2;
import com.cohere.api.types.ToolCallV2Function;
import com.cohere.api.types.ToolMessageV2;
import com.cohere.api.types.ToolMessageV2Content;
import com.cohere.api.types.ToolV2;
import com.cohere.api.types.ToolV2Function;
import com.cohere.api.types.UserMessageV2;
import com.cohere.api.types.UserMessageV2Content;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Cohere implements Model {

    private static final Logger LOG = LoggerFactory.getLogger(Cohere.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final String modelName;
    private final long maxTokens;
    private final Double temperature;

    private final com.cohere.api.Cohere client;

    public Cohere(String apiKey, String modelName) {

        this(apiKey, modelName, DEFAULT_MAX_TOKENS, null);
    }

    public Cohere(String apiKey, String modelName, long maxTokens, Double temperature) {

        if (Utils.isMissing(apiKey)) throw new IllegalArgumentException("API key required");
        if (Utils.isMissing(modelName)) throw new IllegalArgumentException("Model name required");

        this.modelName = modelName;
        this.maxTokens = maxTokens > 0 ? maxTokens : DEFAULT_MAX_TOKENS;
        this.temperature = temperature;

        this.client = com.cohere.api.Cohere.builder().token(apiKey).build();
    }

    @Override
    public <T> ModelResponse<T> send(String systemPrompt, List<ModelMessage> messages,
                                     List<ToolDefinition> tools, Class<T> outputType) {

        var requestBuilder = V2ChatRequest.builder()
                .model(modelName)
                .messages(translateMessages(systemPrompt, messages))
                .maxTokens((int) Math.min(maxTokens, Integer.MAX_VALUE));

        if (temperature != null)
            requestBuilder.temperature(temperature.floatValue());

        if (tools != null && !tools.isEmpty())
            requestBuilder.tools(translateTools(tools));

        if (!Utils.isUnstructured(outputType))
            requestBuilder.responseFormat(buildResponseFormat(outputType));

        var response = client.v2().chat(requestBuilder.build());

        return translate(response, outputType);
    }

    private static List<ChatMessageV2> translateMessages(String systemPrompt, List<ModelMessage> messages) {

        var out = new ArrayList<ChatMessageV2>();

        if (Utils.isFound(systemPrompt)) {

            out.add(ChatMessageV2.system(SystemMessageV2.builder()
                    .content(SystemMessageV2Content.of(systemPrompt))
                    .build()));
        }

        for (var msg : messages) {

            if (msg.messageRole() == MessageRole.USER) {

                // Tool results in our model live in USER-role messages; in Cohere they get their own role.
                msg.messageBlocks().stream()
                        .filter(b -> b instanceof ToolResultMessageBlock)
                        .map(b -> (ToolResultMessageBlock) b)
                        .forEach(tr -> out.add(ChatMessageV2.tool(ToolMessageV2.builder()
                                .toolCallId(tr.toolUseId())
                                .content(ToolMessageV2Content.of(tr.content()))
                                .build())));

                var text = collectText(msg);

                if (text != null)
                    out.add(ChatMessageV2.user(UserMessageV2.builder()
                            .content(UserMessageV2Content.of(text))
                            .build()));
            }
            else {

                var text = collectText(msg);

                var toolCalls = msg.messageBlocks().stream()
                        .filter(b -> b instanceof ToolUseMessageBlock)
                        .map(b -> (ToolUseMessageBlock) b)
                        .map(Cohere::toToolCallV2)
                        .toList();

                var asst = AssistantMessage.builder();

                if (text != null) asst.content(AssistantMessageV2Content.of(text));
                if (!toolCalls.isEmpty()) asst.toolCalls(toolCalls);

                out.add(ChatMessageV2.assistant(asst.build()));
            }
        }

        return out;
    }

    private static String collectText(ModelMessage msg) {

        return msg.messageBlocks().stream()
                .filter(b -> b instanceof TextMessageBlock)
                .map(b -> ((TextMessageBlock) b).text())
                .filter(t -> !t.isBlank())
                .reduce((a, b) -> a + "\n\n" + b)
                .orElse(null);
    }

    private static ToolCallV2 toToolCallV2(ToolUseMessageBlock tu) {

        String argsJson;

        try {

            argsJson = MAPPER.writeValueAsString(tu.args());
        }
        catch (Exception e) {

            argsJson = "{}";
        }

        return ToolCallV2.builder()
                .id(tu.id())
                .function(ToolCallV2Function.builder()
                        .name(tu.toolName())
                        .arguments(argsJson)
                        .build())
                .build();
    }

    private static List<ToolV2> translateTools(List<ToolDefinition> tools) {

        var out = new ArrayList<ToolV2>();

        for (var t : tools) {

            var params = new HashMap<String, Object>();
            params.put("type", "object");
            params.put("properties", t.properties());
            params.put("required", t.required());

            var fn = ToolV2Function.builder()
                    .name(t.name())
                    .parameters(params)
                    .description(t.description())
                    .build();

            out.add(ToolV2.builder()
                    .function(fn)
                    .build());
        }

        return out;
    }

    private static <T> ResponseFormatV2 buildResponseFormat(Class<T> outputType) {

        JsonNode schema = Utils.schema(outputType);
        Map<String, Object> schemaMap = MAPPER.convertValue(schema, MAP_TYPE);

        return ResponseFormatV2.jsonObject(JsonResponseFormatV2.builder()
                .jsonSchema(schemaMap)
                .build());
    }

    private static <T> ModelResponse<T> translate(V2ChatResponse response, Class<T> outputType) {

        var assistant = response.getMessage();

        var textBuilder = new StringBuilder();

        assistant.getContent().orElse(List.of()).forEach(item -> {

            if (item.isText())
                item.getText().ifPresent(t -> textBuilder.append(t.getText()));
        });

        var toolCalls = new ArrayList<ToolCall>();

        assistant.getToolCalls().orElse(List.of()).forEach(tc -> {

            var fn = tc.getFunction().orElse(null);

            if (fn == null) return;

            var name = fn.getName().orElse("");
            var argsJson = fn.getArguments().orElse("");

            toolCalls.add(new ToolCall(tc.getId(), name, parseArgs(argsJson)));
        });

        var stopReason = mapStopReason(response.getFinishReason(), !toolCalls.isEmpty());

        var usage = response.getUsage();

        long inputTokens = usage
                .flatMap(u -> u.getTokens())
                .flatMap(t -> t.getInputTokens())
                .map(Double::longValue)
                .orElse(0L);
        long outputTokens = usage
                .flatMap(u -> u.getTokens())
                .flatMap(t -> t.getOutputTokens())
                .map(Double::longValue)
                .orElse(0L);
        long cachedTokens = usage
                .flatMap(u -> u.getCachedTokens())
                .map(Double::longValue)
                .orElse(0L);

        var responseText = textBuilder.toString();
        T parsed = parseTyped(responseText, outputType);

        return new SingleResponse<>(parsed, responseText, toolCalls, stopReason,
                new ModelUsage(Math.max(0, inputTokens - cachedTokens), outputTokens, cachedTokens, 0L, 0L));
    }

    private static StopReason mapStopReason(ChatFinishReason reason, boolean hasToolCalls) {

        if (hasToolCalls) return StopReason.TOOL_USE;

        var val = reason.getEnumValue();

        return switch (val) {
            case TOOL_CALL -> StopReason.TOOL_USE;
            case MAX_TOKENS -> StopReason.MAX_TOKENS;
            case COMPLETE, STOP_SEQUENCE -> StopReason.END_TURN;
            case ERROR, TIMEOUT -> {

                LOG.warn("Cohere finish_reason={} mapped to END_TURN", val);

                yield StopReason.END_TURN;
            }
            default -> StopReason.END_TURN;
        };
    }

    private static <T> T parseTyped(String text, Class<T> outputType) {

        if (Utils.isUnstructured(outputType) || text == null || text.isBlank()) return null;

        try {

            return MAPPER.readValue(text, outputType);
        }
        catch (Exception e) {

            throw new RuntimeException("Failed to deserialize Cohere response as "
                    + outputType.getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    private static Map<String, Object> parseArgs(String arguments) {

        if (arguments == null || arguments.isBlank()) return Map.of();

        try {

            var parsed = MAPPER.readValue(arguments, MAP_TYPE);
            return parsed != null ? parsed : Map.of();
        }
        catch (Exception e) {

            LOG.warn("Failed to parse Cohere tool-call arguments as JSON: {}", arguments, e);
            return Map.of();
        }
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

            return new Cohere(apiKey, modelName, maxTokens, temperature);
        }
    }
}
