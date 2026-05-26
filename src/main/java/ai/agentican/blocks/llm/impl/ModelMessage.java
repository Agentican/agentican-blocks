package ai.agentican.blocks.llm.impl;

import java.util.List;

public record ModelMessage(Role role, List<Block> blocks) {

    public ModelMessage {

        if (role == null) throw new IllegalArgumentException("Message role is required");

        if (blocks == null) blocks = List.of();
    }

    public static ModelMessage user(Block... blocks) {

        return new ModelMessage(Role.USER, List.of(blocks));
    }

    public static ModelMessage assistant(Block... blocks) {

        return new ModelMessage(Role.ASSISTANT, List.of(blocks));
    }

    public static ModelMessage user(List<Block> blocks) {

        return new ModelMessage(Role.USER, blocks);
    }

    public static ModelMessage assistant(List<Block> blocks) {

        return new ModelMessage(Role.ASSISTANT, blocks);
    }
}
