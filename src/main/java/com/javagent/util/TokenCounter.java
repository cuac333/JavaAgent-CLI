package com.javagent.util;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;

/**
 * Token counting utility using jtokkit (tiktoken Java port).
 *
 * Uses cl100k_base encoding which is compatible with GPT-4 and Claude models.
 * Provides accurate token counts for strings.
 */
public final class TokenCounter {

    private static final EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
    private static final Encoding encoding = registry.getEncoding(EncodingType.CL100K_BASE);

    private TokenCounter() {
    }

    /**
     * Count tokens in a string.
     */
    public static int countTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return encoding.encode(text).size();
    }

    /**
     * Format a token count as a human-readable string (e.g., "12.5k").
     */
    public static String formatTokens(int tokens) {
        if (tokens >= 1_000_000) {
            return String.format("%.1fM", tokens / 1_000_000.0);
        } else if (tokens >= 1_000) {
            return String.format("%.1fk", tokens / 1_000.0);
        }
        return String.valueOf(tokens);
    }
}
