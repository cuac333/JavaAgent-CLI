package com.javagent.model;

import com.javagent.tools.ToolDefinition;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 模型客户端接口 —— 与 AI 模型通信的统一入口
 *
 * 为什么是接口而不是具体类？
 * 因为系统需要支持多种 AI 模型：
 * - MockModelClient：本地模拟，不需要 API Key，用于测试
 * - OpenAiCompatibleModelClient：调用真实的 OpenAI API
 *
 * 通过接口，Agent 不需要知道具体用的是哪个模型，
 * 只需要调用 chat() 方法就能得到回复。
 * 这就是"面向接口编程"——降低耦合，方便扩展。
 *
 * 接口中的 default 方法：
 * Java 8+ 允许在接口中提供默认实现。
 * 下面的 chat(systemPrompt, messages, tools, streamHandler) 就是一个 default 方法，
 * 它调用无流式的 chat()，然后手动模拟流式输出。
 * 这样子类只需实现无流式版本，就自动支持流式输出。
 */
public interface ModelClient {
    /**
     * 发送对话请求给 AI 模型（无流式输出）
     *
     * @param systemPrompt 系统提示词（告诉 AI 如何行为）
     * @param messages     对话历史（用户和 AI 之前的交流）
     * @param tools        可用的工具列表（AI 可以选择调用）
     * @return 模型的响应（文本回复 / 工具调用 / 错误）
     */
    ModelResponse chat(String systemPrompt, List<Message> messages, List<ToolDefinition> tools);

    /**
     * 发送对话请求给 AI 模型（支持流式输出）
     *
     * 这是 default 方法，提供默认实现：
     * 1. 先调用无流式的 chat() 获取完整响应
     * 2. 如果响应是文本且 streamHandler 不为空，就把文本分块逐个发送
     *
     * 子类可以覆盖此方法，实现真正的流式 API 调用。
     *
     * @param streamHandler 流式输出回调，每收到一块文本就调用一次
     */
    default ModelResponse chat(
            String systemPrompt,
            List<Message> messages,
            List<ToolDefinition> tools,
            TextStreamHandler streamHandler
    ) {
        // 先获取完整响应
        ModelResponse response = chat(systemPrompt, messages, tools);
        // 需要流式输出且响应包含正文时，就分块发送。
        // TEXT 响应对应最终结论；TOOL_CALLS 响应对应调用工具旁的叙述文字
        //（真实模式通过 SSE 流直接逐块到达，不受此默认实现约束）。
        if (streamHandler != null && !response.isError() && !response.content().isEmpty()) {
            streamHandler.onStreamStart();
            try {
                emitTextChunks(response.content(), streamHandler);
            } finally {
                streamHandler.onStreamEnd();
            }
        }
        return response;
    }

    /** 获取模型客户端的名称（用于显示） */
    String name();

    /**
     * 设置取消标志 —— 用于在重试退避等阻塞操作中检查用户是否请求打断。
     * 默认空实现，子类可按需重写。
     */
    default void setCancelFlag(AtomicBoolean cancelFlag) {
    }

    /**
     * 将文本分块发送给流式处理器
     * 模拟流式输出效果：把完整文本切成小块，逐块输出
     *
     * @param text          完整文本
     * @param streamHandler 流式处理器
     */
    private void emitTextChunks(String text, TextStreamHandler streamHandler) {
        if (text == null || text.isEmpty()) {
            return;
        }
        // 每块大小：文本长度的 1/6，最少 12 字符，最多 32 字符
        int chunkSize = Math.max(12, Math.min(32, text.length() / 6 == 0 ? text.length() : text.length() / 6));
        for (int start = 0; start < text.length(); start += chunkSize) {
            int end = Math.min(text.length(), start + chunkSize);
            streamHandler.onChunk(text.substring(start, end));
        }
    }
}
