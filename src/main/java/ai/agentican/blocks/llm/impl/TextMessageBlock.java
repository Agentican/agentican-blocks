package ai.agentican.blocks.llm.impl;

public record TextMessageBlock(String text) implements MessageBlock {

    public TextMessageBlock {

        if (text == null) text = "";
    }
}
