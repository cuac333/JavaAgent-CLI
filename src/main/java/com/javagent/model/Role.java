package com.javagent.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 角色枚举 —— 对话消息的身份标识
 *
 * 在 AI 对话中，每条消息都有一个"角色"，用来区分是谁说的：
 * - SYSTEM：系统提示词，告诉 AI 如何行为（如"你是一个编程助手"）
 * - USER：用户输入的消息（如"帮我读一下 README.md"）
 * - ASSISTANT：AI 的回复（可能是文字，也可能是调用工具的请求）
 * - TOOL：工具执行后返回的结果（如文件内容、搜索结果）
 *
 * 每个枚举值都有一个 apiValue，这是发送给 OpenAI 等 API 时用的字符串。
 * 比如 Role.USER 对应 API 中的 "user"。
 */
public enum Role {
    SYSTEM("system"),       // 系统角色 —— 设定 AI 行为的指令
    USER("user"),           // 用户角色 —— 人类输入的消息
    ASSISTANT("assistant"), // AI 角色 —— 模型的回复
    TOOL("tool");           // 工具角色 —— 工具执行结果

    // 发送给 API 时用的字符串值
    private final String apiValue;

    Role(String apiValue) {
        this.apiValue = apiValue;
    }

    /** 序列化为小写 API 值（"user" 而非 "USER"） */
    @JsonValue
    public String apiValue() {
        return apiValue;
    }

    /** 从 "user" 或 "USER" 反序列化 */
    @JsonCreator
    public static Role fromValue(String value) {
        for (Role r : values()) {
            if (r.apiValue.equalsIgnoreCase(value) || r.name().equalsIgnoreCase(value)) {
                return r;
            }
        }
        throw new IllegalArgumentException("未知角色: " + value);
    }
}
