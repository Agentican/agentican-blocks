package ai.agentican.blocks.llm.impl;

import ai.agentican.blocks.llm.Templates;
import ai.agentican.blocks.llm.Utils;
import ai.agentican.blocks.llm.api.Client;
import ai.agentican.blocks.llm.api.Fn;
import ai.agentican.blocks.llm.api.ModelResponse;
import ai.agentican.blocks.llm.api.ToolDefinition;

import io.quarkus.qute.Template;

import java.util.List;

public final class DefaultFn<I, O> implements Fn<I, O> {

    private final Client client;
    private final Template systemTemplate;
    private final Template userTemplate;
    private final List<ToolDefinition> tools;
    private final Class<O> outputType;

    public DefaultFn(Client client, String systemPrompt, String userPrompt,
                     List<ToolDefinition> tools, Class<I> inputType, Class<O> outputType) {

        if (client == null) throw new IllegalArgumentException("Client required");
        if (inputType == null) throw new IllegalArgumentException("Input type required");
        if (outputType == null) throw new IllegalArgumentException("Output type required");

        this.client = client;
        this.systemTemplate = Templates.parse(systemPrompt);
        this.userTemplate = Templates.parse(userPrompt);
        this.tools = tools == null ? List.of() : List.copyOf(tools);
        this.outputType = outputType;
    }

    @Override
    public O run(I input) {

        return respond(input).output();
    }

    @Override
    public ModelResponse<O> respond(I input) {

        if (input == null) throw new IllegalArgumentException("Input required");

        var renderedSystem = Templates.render(systemTemplate, input);

        String renderedUser = userTemplate != null
                ? Templates.render(userTemplate, input)
                : (String) input;

        if (Utils.isMissing(renderedUser))
            throw new IllegalArgumentException("User message is empty");

        var messages = List.of(ModelMessage.user(TextMessageBlock.of(renderedUser)));

        return client.send(renderedSystem, messages, tools, outputType);
    }
}
