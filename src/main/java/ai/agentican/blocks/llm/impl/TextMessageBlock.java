package ai.agentican.blocks.llm.impl;

public record TextMessageBlock(String text) implements MessageBlock {

    public TextMessageBlock {

        if (text == null) text = "";
    }

    public static TextMessageBlock of(String text) {

        return new TextMessageBlock(text);
    }

    public static TextMessageBlock of(String format, Object... args) {

        return new TextMessageBlock(String.format(format, args));
    }
}
