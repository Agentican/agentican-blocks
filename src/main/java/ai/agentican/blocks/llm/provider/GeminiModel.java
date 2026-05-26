package ai.agentican.blocks.llm.provider;

import ai.agentican.blocks.llm.*;

import ai.agentican.blocks.llm.api.*;
import ai.agentican.blocks.llm.impl.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GoogleSearch;
import com.google.genai.types.Part;
import com.google.genai.types.Tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class GeminiModel implements ProviderModel {

    private static final Logger LOG = LoggerFactory.getLogger(GeminiModel.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String model;
    private final long maxTokens;
    private final Double temperature;
    private final Client client;

    public GeminiModel(String apiKey, String model) {
        this(apiKey, model, 16384L, null);
    }

    public GeminiModel(String apiKey, String model, long maxTokens, Double temperature) {

        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("apiKey is required");
        if (model == null || model.isBlank()) throw new IllegalArgumentException("model is required");

        this.model = model;
        this.maxTokens = maxTokens > 0 ? maxTokens : 16384L;
        this.temperature = temperature;
        this.client = Client.builder().apiKey(apiKey).build();
    }

    @Override
    public <T> ModelResponse<T> execute(String systemPrompt, List<ModelMessage> messages,
                                        List<ToolDefinition> tools, Class<T> outputType) {

        var systemContent = Content.fromParts(Part.fromText(systemPrompt));

        var allTools = new ArrayList<Tool>();

        if (tools != null && !tools.isEmpty()) {

            var declarations = new ArrayList<FunctionDeclaration>();

            tools.forEach(tool -> {

                var schema = new HashMap<String, Object>();

                schema.put("type", "object");
                schema.put("properties", tool.properties());
                schema.put("required", tool.required());

                declarations.add(FunctionDeclaration.builder()
                        .name(tool.name())
                        .description(tool.description())
                        .parametersJsonSchema(schema)
                        .build());
            });

            allTools.add(Tool.builder().functionDeclarations(declarations).build());
        }

        allTools.add(Tool.builder().googleSearch(GoogleSearch.builder().build()).build());

        var configBuilder = GenerateContentConfig.builder()
                .systemInstruction(systemContent)
                .maxOutputTokens((int) Math.min(maxTokens, Integer.MAX_VALUE))
                .tools(allTools);

        if (temperature != null) configBuilder.temperature(temperature.floatValue());

        if (!Utils.isUnstructured(outputType)) {

            var schemaMap = JSON.<Map<String, Object>>convertValue(Utils.schema(outputType),
                    new TypeReference<Map<String, Object>>() {});

            configBuilder.responseMimeType("application/json")
                    .responseJsonSchema(schemaMap);
        }

        var contents = translateMessages(messages);

        var response = client.models.generateContent(model, contents, configBuilder.build());

        return translate(response, outputType);
    }

    private static List<Content> translateMessages(List<ModelMessage> modelMessages) {

        var out = new ArrayList<Content>(modelMessages.size());

        for (var msg : modelMessages) {

            var parts = new ArrayList<Part>();

            for (var block : msg.messageBlocks()) {

                switch (block) {

                    case TextMessageBlock t -> parts.add(Part.fromText(t.text()));

                    case ToolUseMessageBlock tu -> parts.add(Part.builder()
                            .functionCall(FunctionCall.builder()
                                    .id(tu.id())
                                    .name(tu.toolName())
                                    .args(tu.args())
                                    .build())
                            .build());

                    case ToolResultMessageBlock tr -> parts.add(Part.builder()
                            .functionResponse(FunctionResponse.builder()
                                    .id(tr.toolUseId())
                                    .response(Map.of("content", tr.content()))
                                    .build())
                            .build());
                }
            }

            out.add(Content.builder()
                    .role(msg.messageRole() == MessageRole.USER ? "user" : "model")
                    .parts(parts)
                    .build());
        }

        return out;
    }

    private static <T> ModelResponse<T> translate(GenerateContentResponse response, Class<T> outputType) {

        var textBuilder = new StringBuilder();
        var toolCalls = new ArrayList<ToolCall>();
        long webSearchRequests = 0;

        var candidates = response.candidates().orElse(List.of());

        if (!candidates.isEmpty()) {

            var candidate = candidates.get(0);

            candidate.content().ifPresent(content -> {

                for (var part : content.parts().orElse(List.of())) {

                    part.text().ifPresent(textBuilder::append);

                    part.functionCall().ifPresent(fc -> {

                        var id = fc.id().orElseGet(() ->
                                "call_" + UUID.randomUUID().toString().substring(0, 8));
                        var name = fc.name().orElse("");
                        var args = fc.args().orElse(Map.of());

                        toolCalls.add(new ToolCall(id, name, args));
                    });
                }
            });

            webSearchRequests = candidate.groundingMetadata()
                    .flatMap(gm -> gm.webSearchQueries())
                    .map(queries -> (long) queries.size())
                    .orElse(0L);
        }

        var stopReason = resolveStopReason(candidates, !toolCalls.isEmpty());

        var usage = response.usageMetadata();

        long promptTotal = usage.flatMap(u -> u.promptTokenCount()).orElse(0).longValue();
        long cacheReadTokens = usage.flatMap(u -> u.cachedContentTokenCount()).orElse(0).longValue();
        long inputTokens = Math.max(0, promptTotal - cacheReadTokens);
        long outputTokens = usage.flatMap(u -> u.candidatesTokenCount()).orElse(0).longValue();

        var responseText = textBuilder.toString();
        T parsed = parseTyped(responseText, outputType);

        return new ModelResponse<>(parsed, responseText, toolCalls, stopReason,
                new ModelUsage(inputTokens, outputTokens, cacheReadTokens, 0L, webSearchRequests));
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

    private static StopReason resolveStopReason(List<Candidate> candidates, boolean hasToolCalls) {

        if (hasToolCalls) return StopReason.TOOL_USE;

        if (candidates.isEmpty()) return StopReason.END_TURN;

        var known = candidates.get(0).finishReason()
                .map(FinishReason::knownEnum)
                .orElse(FinishReason.Known.FINISH_REASON_UNSPECIFIED);

        return switch (known) {
            case MAX_TOKENS -> StopReason.MAX_TOKENS;
            case STOP, FINISH_REASON_UNSPECIFIED -> StopReason.END_TURN;
            default -> {
                LOG.warn("Gemini response stopped with reason {}; mapping to END_TURN", known);
                yield StopReason.END_TURN;
            }
        };
    }
}
