package com.javagent.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.javagent.util.Terminal.*;

/**
 * 轻量级 Markdown 转 ANSI 渲染器，用于终端输出。
 *
 * 支持: 标题、带语法高亮的代码块、
 * 行内代码、粗体、列表和纯文本自动换行。
 */
public final class MarkdownRenderer {

    private static final Set<String> JAVA_KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
            "class", "const", "continue", "default", "do", "double", "else", "enum",
            "extends", "final", "finally", "float", "for", "goto", "if", "implements",
            "import", "instanceof", "int", "interface", "long", "native", "new",
            "package", "private", "protected", "public", "return", "short", "static",
            "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
            "transient", "try", "void", "volatile", "while", "var", "record", "sealed",
            "permits", "yield", "with", "true", "false", "null"
    );

    private static final Set<String> COMMON_KEYWORDS = Set.of(
            "function", "const", "let", "var", "return", "if", "else", "for", "while",
            "class", "import", "from", "export", "default", "async", "await", "def",
            "self", "None", "True", "False", "and", "or", "not", "in", "is", "lambda",
            "try", "except", "finally", "raise", "with", "as", "yield", "pass", "break",
            "continue", "elif", "print", "struct", "func", "type", "interface", "package",
            "go", "chan", "select", "case", "switch", "defer", "map", "make", "new"
    );

    private static final Pattern BOLD_PATTERN = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern INLINE_CODE_PATTERN = Pattern.compile("`([^`]+)`");
    private static final Pattern HEADER_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern BULLET_PATTERN = Pattern.compile("^([\\s]*)([-*+]|\\d+\\.)\\s+(.+)$");

    // 链接: [text](url) —— 文本加下划线，URL 用蓝色显示
    private static final Pattern LINK_PATTERN = Pattern.compile("\\[([^\\]]+)\\]\\(([^)\\s]+)\\)");
    // 裸 URL: https://... 或 http://... — 蓝色下划线显示，排除已位于 OSC 8 序列内的
    private static final Pattern BARE_URL_PATTERN = Pattern.compile("(?<!\033\\]8;;)https?://[^\\s)\\]>]+");
    // 高亮: ==text== —— 黄色背景高亮显示
    private static final Pattern HIGHLIGHT_PATTERN = Pattern.compile("==([^=]+?)==");
    // 引用块: > text
    private static final Pattern BLOCKQUOTE_PATTERN = Pattern.compile("^(?:>\\s?)+(.*)$");
    // 水平线: --- 或 *** 或 ___
    private static final Pattern HR_PATTERN = Pattern.compile("^\\s{0,3}(-{3,}|\\*{3,}|_{3,})\\s*$");
    // 表格行: | a | b | 或 | a | b（不闭合也行）
    private static final Pattern TABLE_ROW_PATTERN = Pattern.compile("^\\s*\\|.*\\|\\s*$");
    // 表格分隔行: | --- | :--: | ---: |
    private static final Pattern TABLE_SEPARATOR_PATTERN = Pattern.compile("^\\s*\\|?\\s*:?-{2,}\\s*(\\|\\s*:?-{2,}\\s*)*\\|?\\s*$");

    private MarkdownRenderer() {
    }

    /**
     * 将 Markdown 文本渲染为 ANSI 样式字符串。
     */
    public static String render(String markdown) {
        if (markdown == null || markdown.isEmpty()) return "";

        int width = terminalWidth() - 2;
        StringBuilder out = new StringBuilder();
        String[] lines = splitLines(markdown);

        // splitLines 对以 \n 结尾的文本会产生一个尾随空串，
        // 若把它当空行渲染会多输出一个换行（导致行距翻倍）。去掉它。
        if (lines.length > 0 && lines[lines.length - 1].isEmpty()) {
            String[] trimmed = new String[lines.length - 1];
            System.arraycopy(lines, 0, trimmed, 0, trimmed.length);
            lines = trimmed;
        }

        boolean inCodeBlock = false;
        String codeLang = "";
        int codeLineNum = 0;
        StringBuilder codeBuffer = new StringBuilder();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            // 代码块围栏
            if (line.stripLeading().startsWith("```")) {
                if (!inCodeBlock) {
                    inCodeBlock = true;
                    codeLang = line.trim().substring(3).trim();
                    codeLineNum = 0;
                    codeBuffer.setLength(0);
                } else {
                    // 结束代码块 —— 渲染缓冲的代码
                    inCodeBlock = false;
                    renderCodeBlock(out, codeBuffer.toString(), codeLang, width);
                    codeLang = "";
                }
                continue;
            }

            if (inCodeBlock) {
                if (codeBuffer.length() > 0) codeBuffer.append('\n');
                codeBuffer.append(line);
                continue;
            }

            // 标题
            Matcher headerMatcher = HEADER_PATTERN.matcher(line);
            if (headerMatcher.matches()) {
                String text = headerMatcher.group(2);
                out.append(bold(renderInline(text))).append("\n");
                continue;
            }

            // 列表项 / 有序列表
            Matcher bulletMatcher = BULLET_PATTERN.matcher(line);
            if (bulletMatcher.matches()) {
                String indent = bulletMatcher.group(1);
                String text = bulletMatcher.group(3);
                out.append(indent).append(dim("• ")).append(renderInline(text)).append("\n");
                continue;
            }

            // 引用块: > text
            Matcher blockquoteMatcher = BLOCKQUOTE_PATTERN.matcher(line);
            if (blockquoteMatcher.matches()) {
                String text = blockquoteMatcher.group(1);
                out.append(cyan(dim("│ "))).append(renderInline(text)).append("\n");
                continue;
            }

            // 水平线: --- / *** / ___
            if (HR_PATTERN.matcher(line).matches()) {
                out.append(dim("─".repeat(Math.min(width, terminalWidth() - 4)))).append("\n");
                continue;
            }

            // 表格（需要整表上下文，先收集再渲染）
            if (TABLE_ROW_PATTERN.matcher(line).matches()) {
                // 收集表格所有行
                List<String> tableLines = new ArrayList<>();
                tableLines.add(line);
                int j = i + 1;
                while (j < lines.length && TABLE_ROW_PATTERN.matcher(lines[j]).matches()) {
                    tableLines.add(lines[j]);
                    j++;
                }
                renderTable(out, tableLines);
                i = j - 1;
                continue;
            }

            // 空行
            if (line.isBlank()) {
                out.append("\n");
                continue;
            }

            // 普通文本 —— 换行并渲染行内格式
            String rendered = renderInline(line);
            wrapInto(out, rendered, width);
            out.append("\n");
        }

        // 未关闭的代码块
        if (inCodeBlock && codeBuffer.length() > 0) {
            renderCodeBlock(out, codeBuffer.toString(), codeLang, width);
        }

        return out.toString();
    }

    /**
     * 将渲染后的 Markdown 直接打印到标准输出。
     */
    public static void printRendered(String markdown) {
        System.out.print(render(markdown));
    }

    /** 渲染行内格式: 粗体、斜体、粗斜体、删除线、下划线、高亮、行内代码、链接(可点击OSC8)、裸URL。 */
    private static String renderInline(String text) {
        // 顺序很重要：从最长模式到最短，避免 *** 被 ** 和 * 抢走
        // 1. 粗斜体 ***text*** — 粗体+斜体
        String result = text
                .replaceAll("\\*\\*\\*(.+?)\\*\\*\\*", "\033[1m\033[3m$1\033[0m")
                // 2. 粗体 **text**
                .replaceAll("\\*\\*(.+?)\\*\\*", "\033[1m$1\033[0m")
                // 3. 斜体 *text*（注意：前后不能紧贴字母，避免误伤下划线等）
                .replaceAll("(?<![\\w\\*])\\*([^\\*\\s].*?)\\*(?![\\w\\*])", "\033[3m$1\033[0m")
                // 4. 删除线 ~~text~~
                .replaceAll("~~(.+?)~~", "\033[9m$1\033[0m")
                // 5. HTML 下划线 <u>text</u>
                .replaceAll("(?i)<u>(.+?)</u>", "\033[4m$1\033[0m")
                // 6. 高亮 ==text== — 浅蓝紫（Claude Code 风格），黑底
                .replaceAll("==([^=]+?)==", "\033[38;5;147m$1\033[0m");

        // 7. 链接 [text](url) — 蓝色下划线 + OSC 8 超链接（终端可点击）
        Matcher linkMatcher = LINK_PATTERN.matcher(result);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (linkMatcher.find()) {
            sb.append(result, last, linkMatcher.start());
            String linkText = linkMatcher.group(1);
            String url = linkMatcher.group(2);
            sb.append("\033]8;;").append(url).append("\033\\");
            sb.append(colorize(UNDERLINE, linkText));
            sb.append("\033]8;;\033\\");
            last = linkMatcher.end();
        }
        sb.append(result.substring(last));
        result = sb.toString();

        // 8. 裸 URL — 尚未被处理的 http/https 链接
        Matcher urlMatcher = BARE_URL_PATTERN.matcher(result);
        sb = new StringBuilder();
        last = 0;
        while (urlMatcher.find()) {
            sb.append(result, last, urlMatcher.start());
            String url = urlMatcher.group();
            sb.append("\033]8;;").append(url).append("\033\\");
            sb.append(colorize(BLUE + UNDERLINE, url));
            sb.append("\033]8;;\033\\");
            last = urlMatcher.end();
        }
        sb.append(result.substring(last));
        result = sb.toString();

        // 9. 行内代码 `code`
        Matcher m = INLINE_CODE_PATTERN.matcher(result);
        sb = new StringBuilder();
        last = 0;
        while (m.find()) {
            sb.append(result, last, m.start());
            sb.append(colorize(BG_DARK + BRIGHT_WHITE, m.group(1)));
            last = m.end();
        }
        sb.append(result.substring(last));
        result = sb.toString();

        return result;
    }

    /**
     * 渲染 Markdown 表格。
     * 输入形如:
     *   | 名称 | 类型 |
     *   | ---- | ---- |
     *   | a    | b    |
     * 输出带边框、表头加粗、列宽自动对齐的表格。
     */
    private static void renderTable(StringBuilder out, List<String> tableLines) {
        // 解析每个单元格（去掉首尾 |，按 | 切分）
        List<List<String>> rows = new ArrayList<>();
        for (String line : tableLines) {
            String trimmed = line.trim();
            // 去掉首尾的 |（若有）
            if (trimmed.startsWith("|")) trimmed = trimmed.substring(1);
            if (trimmed.endsWith("|")) trimmed = trimmed.substring(0, trimmed.length() - 1);
            String[] cells = trimmed.split("\\s*\\|\\s*", -1);
            List<String> row = new ArrayList<>();
            for (String cell : cells) {
                row.add(cell.trim());
            }
            rows.add(row);
        }
        if (rows.isEmpty()) return;

        int colCount = rows.stream().mapToInt(List::size).max().orElse(1);
        int[] widths = new int[colCount];
        for (List<String> row : rows) {
            for (int c = 0; c < row.size() && c < colCount; c++) {
                String rendered = renderInline(row.get(c));
                widths[c] = Math.max(widths[c], displayWidth(stripAnsi(rendered)));
            }
        }

        // 找到分隔行（--- 那行）索引，其上一行即表头
        int headerIndex = -1;
        for (int i = 0; i < rows.size(); i++) {
            boolean isSep = rows.get(i).stream()
                    .allMatch(cell -> cell.matches(":?-{2,}:?") || cell.isBlank());
            if (isSep) { headerIndex = i; break; }
        }

        String border = dim("┌" + joinBorder(widths) + "┐");
        out.append("  ").append(border).append("\n");

        for (int r = 0; r < rows.size(); r++) {
            if (r == headerIndex) continue; // 跳过分隔行
            // 行首竖线也用 dim，与横线、行尾竖线亮度一致
            StringBuilder line = new StringBuilder("  ").append(dim("│"));
            List<String> row = rows.get(r);
            for (int c = 0; c < colCount; c++) {
                String cell = c < row.size() ? row.get(c) : "";
                String renderedCell = renderInline(cell);
                int cellWidth = displayWidth(stripAnsi(renderedCell));
                int pad = Math.max(0, widths[c] - cellWidth);
                String padded = renderedCell + " ".repeat(pad);
                if (r == headerIndex - 1 || (headerIndex < 0 && r == 0)) {
                    line.append(" ").append(bold(padded));
                } else {
                    line.append(" ").append(padded);
                }
                if (c < colCount - 1) {
                    line.append(" ").append(dim("│"));
                }
            }
            out.append(line).append(" ").append(dim("│")).append("\n");
            if (r == headerIndex - 1 && headerIndex >= 0) {
                out.append("  ").append(dim("├" + joinBorder(widths) + "┤")).append("\n");
            }
        }
        out.append("  ").append(dim("└" + joinBorder(widths) + "┘")).append("\n");
    }

    /**
     * 生成边框横线，段长与内容行精确对应。
     * 内容行结构：│ + (空格+内容+空格) + │ + (空格+内容+空格) + ... + │
     * 即每列占 width+2（左右各 1 空格），列间有 1 个 │（边框里用 1 个 ─ 模拟连接）。
     * 所以边框 = 每列 (w+2) 个 ─，列间 1 个 ─，行首行尾的 ┌┐ 由调用方补。
     */
    private static String joinBorder(int[] widths) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < widths.length; i++) {
            sb.append("─".repeat(widths[i] + 2));
            if (i < widths.length - 1) {
                sb.append("─"); // 列间连接（对应内容行的 │）
            }
        }
        return sb.toString();
    }

    /** 渲染带语法高亮和行号的代码块。 */
    private static void renderCodeBlock(StringBuilder out, String code, String lang, int width) {
        String[] codeLines = splitLines(code);
        boolean isJavaLike = lang.isEmpty() || lang.equalsIgnoreCase("java")
                || lang.equalsIgnoreCase("js") || lang.equalsIgnoreCase("javascript")
                || lang.equalsIgnoreCase("ts") || lang.equalsIgnoreCase("typescript")
                || lang.equalsIgnoreCase("py") || lang.equalsIgnoreCase("python")
                || lang.equalsIgnoreCase("go") || lang.equalsIgnoreCase("rust")
                || lang.equalsIgnoreCase("c") || lang.equalsIgnoreCase("cpp")
                || lang.equalsIgnoreCase("cs") || lang.equalsIgnoreCase("csharp");

        for (int i = 0; i < codeLines.length; i++) {
            String numStr = String.format("%3d", i + 1);
            String highlighted = isJavaLike ? highlightCode(codeLines[i]) : codeLines[i];
            out.append(dim(numStr)).append(dim(" │")).append(" ").append(highlighted).append("\n");
        }
    }

    /** 代码行的基础语法高亮。 */
    private static String highlightCode(String line) {
        // 先处理注释
        int commentIdx = indexOfUnquoted(line, "//");
        String codePart = commentIdx >= 0 ? line.substring(0, commentIdx) : line;
        String commentPart = commentIdx >= 0 ? line.substring(commentIdx) : "";

        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < codePart.length()) {
            char c = codePart.charAt(i);

            // 字符串字面量
            if (c == '"' || c == '\'') {
                char quote = c;
                int end = findStringEnd(codePart, i + 1, quote);
                result.append(GREEN).append(codePart, i, end + 1).append(RESET);
                i = end + 1;
                continue;
            }

            // 标识符 / 关键字
            if (Character.isJavaIdentifierStart(c)) {
                int end = i + 1;
                while (end < codePart.length() && Character.isJavaIdentifierPart(codePart.charAt(end))) {
                    end++;
                }
                String word = codePart.substring(i, end);
                if (JAVA_KEYWORDS.contains(word) || COMMON_KEYWORDS.contains(word)) {
                    result.append(BLUE).append(word).append(RESET);
                } else {
                    result.append(word);
                }
                i = end;
                continue;
            }

            result.append(c);
            i++;
        }

        if (!commentPart.isEmpty()) {
            result.append(GRAY).append(commentPart).append(RESET);
        }
        return result.toString();
    }

    private static int indexOfUnquoted(String s, String target) {
        boolean inSingle = false, inDouble = false;
        for (int i = 0; i <= s.length() - target.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' && !inDouble) inSingle = !inSingle;
            if (c == '"' && !inSingle) inDouble = !inDouble;
            if (!inSingle && !inDouble && s.startsWith(target, i)) return i;
        }
        return -1;
    }

    private static int findStringEnd(String s, int start, char quote) {
        for (int i = start; i < s.length(); i++) {
            if (s.charAt(i) == '\\') { i++; continue; }
            if (s.charAt(i) == quote) return i;
        }
        return s.length() - 1;
    }

    /** 将带样式的文本自动换行到 StringBuilder。 */
    private static void wrapInto(StringBuilder out, String styled, int maxWidth) {
        String plain = stripAnsi(styled);
        if (plain.length() <= maxWidth) {
            out.append(styled);
            return;
        }

        // 简单换行，保留 ANSI 码
        String[] words = styled.split(" ");
        int lineLen = 0;
        for (int i = 0; i < words.length; i++) {
            int wordLen = stripAnsi(words[i]).length();
            if (lineLen + wordLen + 1 > maxWidth && lineLen > 0) {
                out.append("\n");
                lineLen = 0;
            }
            if (lineLen > 0) {
                out.append(" ");
                lineLen++;
            }
            out.append(words[i]);
            lineLen += wordLen;
        }
    }
}
