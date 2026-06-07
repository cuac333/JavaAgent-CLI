package com.javagent.util;

import java.util.regex.Pattern;

/**
 * Terminal formatting utility — ANSI colors and styles for rich CLI output.
 *
 * Auto-detects terminal support: disables all formatting when output is
 * redirected or the TERM is "dumb".
 */
public final class Terminal {
    private static final boolean ENABLED = detectColorSupport();

    // Reset
    public static final String RESET = "\033[0m";

    // Styles
    public static final String BOLD = "\033[1m";
    public static final String DIM = "\033[2m";
    public static final String ITALIC = "\033[3m";

    // Foreground colors
    public static final String RED = "\033[31m";
    public static final String GREEN = "\033[32m";
    public static final String YELLOW = "\033[33m";
    public static final String BLUE = "\033[34m";
    public static final String MAGENTA = "\033[35m";
    public static final String CYAN = "\033[36m";
    public static final String WHITE = "\033[37m";
    public static final String GRAY = "\033[90m";

    // Bright foreground
    public static final String BRIGHT_RED = "\033[91m";
    public static final String BRIGHT_GREEN = "\033[92m";
    public static final String BRIGHT_YELLOW = "\033[93m";
    public static final String BRIGHT_BLUE = "\033[94m";
    public static final String BRIGHT_CYAN = "\033[96m";

    // Background
    public static final String BG_RED = "\033[41m";
    public static final String BG_GREEN = "\033[42m";
    public static final String BG_YELLOW = "\033[43m";
    public static final String BG_DARK = "\033[48;5;236m";
    public static final String BRIGHT_WHITE = "\033[97m";

    private Terminal() {
    }

    /**
     * Wrap text with ANSI codes. Returns plain text if colors are disabled.
     */
    public static String colorize(String ansiCode, String text) {
        if (!ENABLED || text.isEmpty()) return text;
        return ansiCode + text + RESET;
    }

    /** Bold text */
    public static String bold(String text) {
        return colorize(BOLD, text);
    }

    /** Dim/muted text */
    public static String dim(String text) {
        return colorize(DIM, text);
    }

    /** Green text (success) */
    public static String green(String text) {
        return colorize(GREEN, text);
    }

    /** Red text (error) */
    public static String red(String text) {
        return colorize(RED, text);
    }

    /** Yellow text (warning) */
    public static String yellow(String text) {
        return colorize(YELLOW, text);
    }

    /** Cyan text (info/accent) */
    public static String cyan(String text) {
        return colorize(CYAN, text);
    }

    /** Blue text */
    public static String blue(String text) {
        return colorize(BLUE, text);
    }

    /** Magenta text */
    public static String magenta(String text) {
        return colorize(MAGENTA, text);
    }

    /** Bright green text */
    public static String brightGreen(String text) {
        return colorize(BRIGHT_GREEN, text);
    }

    /** Bright yellow text */
    public static String brightYellow(String text) {
        return colorize(BRIGHT_YELLOW, text);
    }

    /** Bright cyan text */
    public static String brightCyan(String text) {
        return colorize(BRIGHT_CYAN, text);
    }

    /** Bright red text */
    public static String brightRed(String text) {
        return colorize(BRIGHT_RED, text);
    }

    /** Gray text */
    public static String gray(String text) {
        return colorize(GRAY, text);
    }

    /** Colored prompt symbol */
    public static String prompt() {
        return colorize(BRIGHT_CYAN + BOLD, "> ");
    }

    /** Whether terminal supports ANSI colors */
    public static boolean isEnabled() {
        return ENABLED;
    }

    /**
     * Format a line with line number (like cat -n).
     * Example: "  42 │ some code here"
     */
    public static String lineNumber(int num, String line) {
        String numStr = String.format("%4d", num);
        return dim(numStr) + dim(" │ ") + line;
    }

    /**
     * Format a removed line for diff display (red, with - prefix).
     */
    public static String diffRemove(String line) {
        return colorize(RED, "- " + line);
    }

    /**
     * Format an added line for diff display (green, with + prefix).
     */
    public static String diffAdd(String line) {
        return colorize(GREEN, "+ " + line);
    }

    /**
     * Format a file path for display.
     */
    public static String filePath(String path) {
        return colorize(BRIGHT_BLUE, path);
    }

    /**
     * Truncate text to maxLen, appending "..." if needed.
     */
    public static String truncate(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen - 3) + "...";
    }

    /** Wrap text with a dark gray background (for code blocks). */
    public static String bgGray(String text) {
        return colorize(BG_DARK + BRIGHT_WHITE, text);
    }

    /** Prefix each line with a dim │ pipe for indentation. */
    public static String unicodeBar(String text) {
        if (text == null || text.isEmpty()) return "";
        String prefix = dim("│ ");
        StringBuilder sb = new StringBuilder();
        for (String line : splitLines(text)) {
            sb.append(prefix).append(line).append("\n");
        }
        // Remove trailing newline
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    // Pre-compiled pattern for cross-platform line splitting
    private static final Pattern LINE_PATTERN = Pattern.compile("\\r\\n|\\r|\\n");

    /**
     * Split text into lines, handling \n, \r\n, and \r line endings.
     * Use this instead of text.split("\\n") for cross-platform compatibility.
     */
    public static String[] splitLines(String text) {
        if (text == null) return new String[0];
        return LINE_PATTERN.split(text, -1);
    }

    /**
     * Split text into lines with a limit, handling \n, \r\n, and \r line endings.
     */
    public static String[] splitLines(String text, int limit) {
        if (text == null) return new String[0];
        return LINE_PATTERN.split(text, limit);
    }

    /** Strip all ANSI escape sequences from text. */
    public static String stripAnsi(String text) {
        if (text == null) return "";
        return text.replaceAll("\033\\[[;\\d]*m", "");
    }

    /** Detect terminal width, defaulting to 80. */
    public static int terminalWidth() {
        String cols = System.getenv("COLUMNS");
        if (cols != null) {
            try {
                int w = Integer.parseInt(cols);
                if (w > 20 && w < 500) return w;
            } catch (NumberFormatException ignored) {
            }
        }
        return 80;
    }

    private static boolean detectColorSupport() {
        // Check if stdout is a terminal (not redirected)
        String term = System.getenv("TERM");
        if ("dumb".equalsIgnoreCase(term)) return false;

        // Windows Terminal, ConEmu, VS Code terminal all support ANSI
        String wtSession = System.getenv("WT_SESSION");
        String conEmu = System.getenv("ConEmuPID");
        String vscode = System.getenv("TERM_PROGRAM");
        if (wtSession != null || conEmu != null || "vscode".equals(vscode)) return true;

        // Check if running in a typical terminal on Windows
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            // Windows 10+ supports ANSI in cmd/powershell
            return System.console() != null || term != null;
        }

        // Unix-like: assume color support if TERM is set
        return System.console() != null || term != null;
    }
}
