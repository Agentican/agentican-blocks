package ai.agentican.blocks.llm.impl;

public sealed interface MessageBlock permits TextMessageBlock, ToolUseMessageBlock, ToolResultMessageBlock {}
