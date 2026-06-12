package com.javagent.util;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;

/**
 * 基于 jtokkit（tiktoken 的 Java 移植版）的 Token 计数工具。
 *
 * 使用 cl100k_base 编码，兼容 GPT-4 和 Claude 模型。
 * 提供精确的字符串 token 计数。
 */
public final class TokenCounter {

    private static final EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
    private static final Encoding encoding = registry.getEncoding(EncodingType.CL100K_BASE);

    private TokenCounter() {
    }

    /**
     * 计算字符串的 token 数。
     */
    public static int countTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return encoding.encode(text).size();
    }

    /**
     * 将 token 数格式化为易读字符串（如 "12.5k"）。
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
