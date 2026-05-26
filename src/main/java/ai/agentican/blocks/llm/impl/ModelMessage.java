package ai.agentican.blocks.llm.impl;

import java.util.List;

public record ModelMessage(MessageRole messageRole, List<MessageBlock> messageBlocks) {

    public ModelMessage {

        if (messageRole == null) throw new IllegalArgumentException("Message role is required");

        if (messageBlocks == null)
            messageBlocks = List.of();
    }

    public static ModelMessage user(MessageBlock... messageBlocks) {

        return new ModelMessage(MessageRole.USER, List.of(messageBlocks));
    }

    public static ModelMessage assistant(MessageBlock... messageBlocks) {

        return new ModelMessage(MessageRole.ASSISTANT, List.of(messageBlocks));
    }

    public static ModelMessage user(List<MessageBlock> messageBlocks) {

        return new ModelMessage(MessageRole.USER, messageBlocks);
    }

    public static ModelMessage assistant(List<MessageBlock> messageBlocks) {

        return new ModelMessage(MessageRole.ASSISTANT, messageBlocks);
    }
}
