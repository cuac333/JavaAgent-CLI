package com.javagent.util;

import java.util.regex.Pattern;

/**
 * 敏感信息过滤器 —— 在存储到会话历史前脱敏
 *
 * 防止 API 密钥、Token 等敏感信息被持久化到 session JSON 文件中。
 */
public final class Sanitizer {
    private Sanitizer() {
    }

    // API Key 模式: sk-xxx、Bearer xxx、agent.api_key=xxx 等
    private static final Pattern API_KEY_PATTERN = Pattern.compile(
            "(?i)(sk-[a-zA-Z0-9_-]{20,}|"
            + "api[_-]?key[=:\\s]+[a-zA-Z0-9_-]{16,}|"
            + "token[=:\\s]+[a-zA-Z0-9_-]{16,}|"
            + "bearer\\s+[a-zA-Z0-9_.-]{20,}|"
            + "tp-[a-zA-Z0-9_-]{20,})",
            Pattern.CASE_INSENSITIVE
    );

    private static final String MASK = "***已脱敏***";

    /**
     * 脱敏文本中的敏感信息
     *
     * @param text 原始文本
     * @return 脱敏后的文本
     */
    public static String sanitize(String text) {
        if (text == null || text.isEmpty()) return text;
        return API_KEY_PATTERN.matcher(text).replaceAll(MASK);
    }

    /**
     * 检查文本是否包含敏感信息
     */
    public static boolean containsSensitive(String text) {
        if (text == null || text.isEmpty()) return false;
        return API_KEY_PATTERN.matcher(text).find();
    }
}
