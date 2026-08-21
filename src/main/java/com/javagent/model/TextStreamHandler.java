package com.javagent.model;

/**
 * 流式文本处理器 —— 用于逐块输出 AI 的回复
 *
 * 什么是"流式输出"？
 * 当 AI 生成一段长文本时，不用等全部生成完再显示，
 * 而是一边生成一边显示，就像打字机一样逐字出现。
 * 这种方式用户体验更好，不用长时间等待。
 *
 * 使用方式：
 *   TextStreamHandler handler = chunk -> System.out.print(chunk);
 *   handler.onChunk("你");  // 立刻显示"你"
 *   handler.onChunk("好");  // 立刻显示"好"
 *
 * @FunctionalInterface 表示这是一个函数式接口，可以用 Lambda 表达式创建
 */
@FunctionalInterface
public interface TextStreamHandler {
    /**
     * 收到一块文本时的回调方法
     *
     * @param chunk 本次收到的一小段文本（通常几个字到几十个字）
     */
    void onChunk(String chunk);

    /**
     * 一次 SSE 流开始时的回调（只调用一次）。
     * 用于让 UI 暂停加载动画，让流式文本不被覆盖。
     */
    default void onStreamStart() {
    }

    /**
     * 一次 SSE 流结束时的回调（只调用一次），无论流正常结束或出错都会调用。
     * 用于让 UI 恢复加载动画。
     */
    default void onStreamEnd() {
    }

    /**
     * 收到推理内容增量时的回调（思维模型的 reasoning_content）。
     * 默认与普通文本同样处理；UI 层可覆盖此方法以不同样式（如暗淡斜体）显示。
     *
     * @param reasoningChunk 本次收到的推理内容片段
     */
    default void onReasoningChunk(String reasoningChunk) {
        onChunk(reasoningChunk);
    }
}
