package com.javagent.util;

import com.javagent.core.ContextUsage;

import java.io.PrintWriter;

import static com.javagent.util.Terminal.*;

/**
 * Renders a colored context usage bar chart, similar to Claude Code's /context output.
 *
 * Layout:
 *   - One main bar (single line, wraps only when full)
 *   - Category breakdown with individual colored icons
 */
public final class ContextDisplay {

    private static final int BAR_WIDTH = 20;
    private static final int MINI_BAR_WIDTH = 10;
    // Unified characters for filled and empty segments
    private static final String FILLED = "⛁ ";
    private static final String EMPTY = "⛶ ";

    private ContextDisplay() {
    }

    /**
     * Print the full context usage display to the given PrintWriter.
     */
    public static void display(PrintWriter out, ContextUsage usage, String modelName) {
        int total = usage.totalTokens();
        int max = usage.maxTokens();
        double pct = usage.usagePercent() * 100;

        // Header
        out.println();
        out.println(bold("Context Usage"));
        out.println();

        // Main progress bar — single line, color by usage level
        String barColor = pct < 30 ? GREEN : pct < 70 ? YELLOW : RED;
        String bar = buildBar(total, max, BAR_WIDTH);
        out.println("       " + colorize(barColor, bar) + "   " + bold(TokenCounter.formatTokens(total) + "/" + TokenCounter.formatTokens(max) + " tokens") +
                " (" + String.format("%.0f", pct) + "%)" + "   " + dim(modelName));
        out.println();

        // Category breakdown — each with own color icon
        out.println(dim("       Estimated usage by category"));
        printCategory(out, "System prompt", usage.systemPromptTokens(), max, GRAY);
        printCategory(out, "Tool definitions", usage.toolDefinitionsTokens(), max, BLUE);
        printCategory(out, "Messages", usage.messagesTokens(), max, MAGENTA);
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
        out.println("       " + dim(EMPTY + " Free space: ") +
                dim(TokenCounter.formatTokens(free)) +
                dim(" (" + String.format("%.1f", pct) + "%)  "));
    }
}
