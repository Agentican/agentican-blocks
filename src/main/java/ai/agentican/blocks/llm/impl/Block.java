package ai.agentican.blocks.llm.impl;

public sealed interface Block permits TextBlock, ToolUseBlock, ToolResultBlock {}
