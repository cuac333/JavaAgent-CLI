package com.javagent.util;

import com.javagent.core.ContextUsage;

import java.io.PrintWriter;

import static com.javagent.util.Terminal.*;

/**
 * 渲染彩色上下文使用率条形图，类似 Claude Code 的 /context 输出。
 *
 * Layout:
 *   - One main bar (single line, wraps only when full)
 *   - Category breakdown with individual colored icons
 */
public final class ContextDisplay {

    private static final int BAR_WIDTH = 20;
    private static final int MINI_BAR_WIDTH = 10;
    // 统一的填充和空白段字符
    private static final String FILLED = "⛁ ";
    private static final String EMPTY = "⛶ ";

    private ContextDisplay() {
    }

    /**
     * 将完整的上下文使用情况显示输出到指定的 PrintWriter。
     */
    public static void display(PrintWriter out, ContextUsage usage, String modelName) {
        int total = usage.totalTokens();
        int max = usage.maxTokens();
        double pct = usage.usagePercent() * 100;

        // 标题
        out.println();
        out.println(bold("上下文使用情况"));
        out.println();

        // 主进度条 —— 单行，按使用级别着色
        String barColor = pct < 30 ? GREEN : pct < 70 ? YELLOW : RED;
        String bar = buildBar(total, max, BAR_WIDTH);
        out.println("       " + colorize(barColor, bar) + "   " + bold(TokenCounter.formatTokens(total) + "/" + TokenCounter.formatTokens(max) + " tokens") +
                " (" + String.format("%.0f", pct) + "%)" + "   " + dim(modelName));
        out.println();

        // 按类别细分 —— 每个类别有独立的彩色图标
        out.println(dim("       按类别估算使用量"));
        printCategory(out, "系统提示词", usage.systemPromptTokens(), max, GRAY);
        printCategory(out, "工具定义", usage.toolDefinitionsTokens(), max, BLUE);
        printCategory(out, "消息", usage.messagesTokens(), max, MAGENTA);
        printFreeSpace(out, usage.freeTokens(), max);
        out.println();
    }

    /**
     * Build a bar string of given width, proportional to current/max.
     */
    private static String buildBar(int current, int max, int width) {
        if (max == 0) return "";
        int filled = (int) Math.round((double) current / max * width);
        filled = Math.min(filled, width);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < filled; i++) sb.append(FILLED);
        for (int i = filled; i < width; i++) sb.append(EMPTY);
        return sb.toString();
    }

    /**
     * Print a category line: colored icon + label + token count + mini bar.
     */
    private static void printCategory(PrintWriter out, String label, int tokens, int max, String color) {
        double pct = max > 0 ? (double) tokens / max * 100 : 0;
        String miniBar = buildBar(tokens, max, MINI_BAR_WIDTH);
        out.println("       " + colorize(color, FILLED) + dim(" " + label + ": ") +
                colorize(color, TokenCounter.formatTokens(tokens)) +
                dim(" (" + String.format("%.1f", pct) + "%)  ") +
                colorize(dim(color), miniBar));
    }

    /**
     * Print free space line with a different icon style.
     */
    private static void printFreeSpace(PrintWriter out, int free, int max) {
        double pct = max > 0 ? (double) free / max * 100 : 0;
        String miniBar = buildBar(free, max, MINI_BAR_WIDTH);
        out.println("       " + dim(EMPTY + " 剩余空间: ") +
                dim(TokenCounter.formatTokens(free)) +
                dim(" (" + String.format("%.1f", pct) + "%)  "));
    }
}
