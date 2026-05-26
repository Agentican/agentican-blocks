package ai.agentican.blocks.llm.impl;

public record TextBlock(String text) implements Block {

    public TextBlock {

        if (text == null) text = "";
    }
}
