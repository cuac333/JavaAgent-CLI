package com.javagent.util;

import java.util.regex.Pattern;

/**
 * 终端格式化工具 — 用于丰富 CLI 输出的 ANSI 颜色和样式。
 *
 * 自动检测终端支持：当输出被重定向或 TERM 为 "dumb" 时禁用所有格式化。
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
     * 用 ANSI 码包裹文本。颜色禁用时返回纯文本。
     */
    public static String colorize(String ansiCode, String text) {
        if (!ENABLED || text.isEmpty()) return text;
        return ansiCode + text + RESET;
    }

    /** 粗体文本 */
    public static String bold(String text) {
        return colorize(BOLD, text);
    }

    /** 暗淡文本 */
    public static String dim(String text) {
        return colorize(DIM, text);
    }

    /** 绿色文本（成功） */
    public static String green(String text) {
        return colorize(GREEN, text);
    }

    /** 红色文本（错误） */
    public static String red(String text) {
        return colorize(RED, text);
    }

    /** 黄色文本（警告） */
    public static String yellow(String text) {
        return colorize(YELLOW, text);
    }

    /** 青色文本（信息/强调） */
    public static String cyan(String text) {
        return colorize(CYAN, text);
    }

    /** 蓝色文本 */
    public static String blue(String text) {
        return colorize(BLUE, text);
    }

    /** 品红色文本 */
    public static String magenta(String text) {
        return colorize(MAGENTA, text);
    }

    /** 亮绿色文本 */
    public static String brightGreen(String text) {
        return colorize(BRIGHT_GREEN, text);
    }

    /** 亮黄色文本 */
    public static String brightYellow(String text) {
        return colorize(BRIGHT_YELLOW, text);
    }

    /** 亮青色文本 */
    public static String brightCyan(String text) {
        return colorize(BRIGHT_CYAN, text);
    }

    /** 亮红色文本 */
    public static String brightRed(String text) {
        return colorize(BRIGHT_RED, text);
    }

    /** 灰色文本 */
    public static String gray(String text) {
        return colorize(GRAY, text);
    }

    /** 彩色提示符 */
    public static String prompt() {
        return colorize(BRIGHT_CYAN + BOLD, "> ");
    }

    /** 终端是否支持 ANSI 颜色 */
    public static boolean isEnabled() {
        return ENABLED;
    }

    /**
     * 格式化带行号的文本行（类似 cat -n）。
     * 示例："  42 │ some code here"
     */
    public static String lineNumber(int num, String line) {
        String numStr = String.format("%4d", num);
        return dim(numStr) + dim(" │ ") + line;
    }

    /**
     * 格式化 diff 中被删除的行（红色，带 - 前缀）。
     */
    public static String diffRemove(String line) {
        return colorize(RED, "- " + line);
    }

    /**
     * 格式化 diff 中新增的行（绿色，带 + 前缀）。
     */
    public static String diffAdd(String line) {
        return colorize(GREEN, "+ " + line);
    }

    /**
     * 格式化文件路径用于显示。
     */
    public static String filePath(String path) {
        return colorize(BRIGHT_BLUE, path);
    }

    /**
     * 将文本截断到 maxLen，必要时追加 "..."。
     */
    public static String truncate(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen - 3) + "...";
    }

    /** 为文本添加深灰色背景（用于代码块）。 */
    public static String bgGray(String text) {
        return colorize(BG_DARK + BRIGHT_WHITE, text);
    }

    /** 每行前添加暗淡的 │ 管道符作为缩进。 */
    public static String unicodeBar(String text) {
        if (text == null || text.isEmpty()) return "";
        String prefix = dim("│ ");
        StringBuilder sb = new StringBuilder();
        for (String line : splitLines(text)) {
            sb.append(prefix).append(line).append("\n");
        }
        // 移除末尾换行符
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    // 预编译的跨平台换行分割模式
    private static final Pattern LINE_PATTERN = Pattern.compile("\\r\\n|\\r|\\n");

    /**
     * 将文本按行分割，支持 \n、\r\n 和 \r 换行符。
     * 请使用此方法替代 text.split("\\n") 以确保跨平台兼容性。
     */
    public static String[] splitLines(String text) {
        if (text == null) return new String[0];
        return LINE_PATTERN.split(text, -1);
    }

    /**
     * 将文本按行分割（带数量限制），支持 \n、\r\n 和 \r 换行符。
     */
    public static String[] splitLines(String text, int limit) {
        if (text == null) return new String[0];
        return LINE_PATTERN.split(text, limit);
    }

    /** 去除文本中所有的 ANSI 转义序列。 */
    public static String stripAnsi(String text) {
        if (text == null) return "";
        return text.replaceAll("\033\\[[;\\d]*m", "");
    }

    /**
     * 计算字符串在终端中的显示宽度（列数）。
     * CJK 字符占用 2 列；ASCII 及其他字符占用 1 列。
     */
    public static int displayWidth(String text) {
        if (text == null) return 0;
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            width += charWidth(ch);
        }
        return width;
    }

    private static int charWidth(char ch) {
        if (ch >= 0x1100 && (
                ch <= 0x115f || ch == 0x2329 || ch == 0x232a
                || (ch >= 0x2e80 && ch <= 0x303e) || (ch >= 0x3040 && ch <= 0x33bf)
                || (ch >= 0x3400 && ch <= 0x4dbf) || (ch >= 0x4e00 && ch <= 0xa4cf)
                || (ch >= 0xa960 && ch <= 0xa97c) || (ch >= 0xac00 && ch <= 0xd7a3)
                || (ch >= 0xf900 && ch <= 0xfaff) || (ch >= 0xfe30 && ch <= 0xfe6f)
                || (ch >= 0xff01 && ch <= 0xff60) || (ch >= 0xffe0 && ch <= 0xffe6)
                || (ch >= 0x20000 && ch <= 0x2fffd) || (ch >= 0x30000 && ch <= 0x3fffd))) {
            return 2;
        }
        return 1;
    }

    /**
     * 霓虹彩虹效果 — 逐字符循环 ANSI 颜色。
     * 每次调用将调色板偏移一个位置以实现动画效果。
     */
    private static final String[] NEON_PALETTE = {
        "\033[91m", // 亮红色
        "\033[93m", // 亮黄色
        "\033[92m", // 亮绿色
        "\033[96m", // 亮青色
        "\033[94m", // 亮蓝色
        "\033[95m", // 亮品红色
    };

    public static String neon(String text, int offset) {
        if (!ENABLED) return text;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            String color = NEON_PALETTE[(i + offset) % NEON_PALETTE.length];
            sb.append(color).append(BOLD).append(text.charAt(i)).append(RESET);
        }
        return sb.toString();
    }

    /**
     * 渲染霓虹光晕行 — 全宽彩色条带粗体文本。
     * 使用背景色循环实现"发光"效果。
     */
    private static final String[] NEON_BG = {
        "\033[101m\033[97m", // 亮红色背景 + 白色前景
        "\033[103m\033[30m", // 亮黄色背景 + 黑色前景
        "\033[102m\033[30m", // 亮绿色背景 + 黑色前景
        "\033[106m\033[30m", // 亮青色背景 + 黑色前景
        "\033[104m\033[97m", // 亮蓝色背景 + 白色前景
        "\033[105m\033[97m", // 亮品红色背景 + 白色前景
    };

    public static String neonGlow(String text, int offset) {
        if (!ENABLED) return text;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            String style = NEON_BG[(i + offset) % NEON_BG.length];
            sb.append(style).append(BOLD).append(text.charAt(i)).append(RESET);
        }
        return sb.toString();
    }

    /** 检测终端宽度，默认为 80。 */
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
        // 检查标准输出是否为终端（未被重定向）
        String term = System.getenv("TERM");
        if ("dumb".equalsIgnoreCase(term)) return false;

        // Windows Terminal、ConEmu、VS Code 终端均支持 ANSI
        String wtSession = System.getenv("WT_SESSION");
        String conEmu = System.getenv("ConEmuPID");
        String vscode = System.getenv("TERM_PROGRAM");
        if (wtSession != null || conEmu != null || "vscode".equals(vscode)) return true;

        // 检查是否在 Windows 的典型终端中运行
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            // Windows 10+ 在 cmd/powershell 中支持 ANSI
            return System.console() != null || term != null;
        }

        // Unix 系统：如果设置了 TERM 则假定支持颜色
        return System.console() != null || term != null;
    }
}
