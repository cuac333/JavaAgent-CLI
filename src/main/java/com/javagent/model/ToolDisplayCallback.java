package com.javagent.model;

/**
 * CLI 中工具执行进度的显示回调。
 * Agent 调用这些方法，以便 UI 显示彩色旋转动画、
 * 工具名称和结果摘要。
 */
public interface ToolDisplayCallback {
    /** 工具调用开始执行时调用 */
    void onToolStart(String toolName, String summary);

    /** 工具调用完成时调用 */
    void onToolEnd(String toolName, boolean success, String resultSummary);

    /** 工具调用完成时调用，包含完整结果内容用于富文本显示 */
    default void onToolEnd(String toolName, boolean success, String resultSummary, String fullContent) {
        onToolEnd(toolName, success, resultSummary);
    }
}
