package ai.agentican.blocks.llm.api;

import ai.agentican.blocks.llm.impl.ModelMessage;
import ai.agentican.blocks.llm.provider.Anthropic;
import ai.agentican.blocks.llm.provider.Bedrock;
import ai.agentican.blocks.llm.provider.Cohere;
import ai.agentican.blocks.llm.provider.Gemini;
import ai.agentican.blocks.llm.provider.HuggingFace;
import ai.agentican.blocks.llm.provider.OpenAi;
import ai.agentican.blocks.llm.provider.OpenAiCompatible;

import java.util.List;

@FunctionalInterface
public interface Model {

    long DEFAULT_MAX_TOKENS = 16384L;

    <T> ModelResponse<T> send(String systemPrompt, List<ModelMessage> messages,
                              List<ToolDefinition> tools, Class<T> outputType);

    static Builder builder() { return new Builder(); }

    final class Builder {

        Builder() {}

        public Anthropic.Builder anthropic() { return Anthropic.builder(); }
        public OpenAi.Builder openai() { return OpenAi.builder(); }
        public OpenAi.Builder groq() { return OpenAi.builder().provider(OpenAi.GROQ); }
        public Gemini.Builder gemini() { return Gemini.builder(); }
        public Bedrock.Builder bedrock() { return Bedrock.builder(); }
        public HuggingFace.Builder huggingFace() { return HuggingFace.builder(); }
        public Cohere.Builder cohere() { return Cohere.builder(); }

        public OpenAiCompatible.Builder sambanova() { return OpenAiCompatible.builder().baseUrl(OpenAiCompatible.SAMBANOVA_BASE_URL); }
        public OpenAiCompatible.Builder together() { return OpenAiCompatible.builder().baseUrl(OpenAiCompatible.TOGETHER_BASE_URL); }
        public OpenAiCompatible.Builder fireworks() { return OpenAiCompatible.builder().baseUrl(OpenAiCompatible.FIREWORKS_BASE_URL); }
        public OpenAiCompatible.Builder openAiCompatible() { return OpenAiCompatible.builder(); }
    }
}
