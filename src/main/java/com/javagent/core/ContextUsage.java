package com.javagent.core;

/**
 * Token usage breakdown for the current context window.
 */
public record ContextUsage(
        int systemPromptTokens,
        int toolDefinitionsTokens,
        int messagesTokens,
        int totalTokens,
        int maxTokens
) {
    /** Percentage of context used (0.0 - 1.0) */
    public double usagePercent() {
        return maxTokens > 0 ? (double) totalTokens / maxTokens : 0.0;
    }

    /** Free tokens remaining */
    public int freeTokens() {
        return Math.max(0, maxTokens - totalTokens);
    }
}
