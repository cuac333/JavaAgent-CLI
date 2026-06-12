package com.javagent.core;

/**
 * 当前上下文窗口的 token 使用分布。
 */
public record ContextUsage(
        int systemPromptTokens,
        int toolDefinitionsTokens,
        int messagesTokens,
        int totalTokens,
        int maxTokens
) {
    /** 上下文使用百分比（0.0 - 1.0） */
    public double usagePercent() {
        return maxTokens > 0 ? (double) totalTokens / maxTokens : 0.0;
    }

    /** 剩余可用 token 数 */
    public int freeTokens() {
        return Math.max(0, maxTokens - totalTokens);
    }
}
