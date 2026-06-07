package com.javagent.util;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.javagent.util.Terminal.*;

/**
 * Lightweight Markdown-to-ANSI renderer for terminal output.
 *
 * Handles: headers, code blocks with syntax highlighting,
 * inline code, bold, bullets, and plain text wrapping.
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

    private MarkdownRenderer() {
    }

    /**
     * Render Markdown text to ANSI-styled string.
     */
    public static String render(String markdown) {
        if (markdown == null || markdown.isEmpty()) return "";

        int width = terminalWidth() - 2;
        StringBuilder out = new StringBuilder();
        String[] lines = splitLines(markdown);

        boolean inCodeBlock = false;
        String codeLang = "";
        int codeLineNum = 0;
        StringBuilder codeBuffer = new StringBuilder();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            // Code block fences
            if (line.stripLeading().startsWith("```")) {
                if (!inCodeBlock) {
                    inCodeBlock = true;
                    codeLang = line.trim().substring(3).trim();
                    codeLineNum = 0;
                    codeBuffer.setLength(0);
                } else {
                    // End code block — render buffered code
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

            // Headers
            Matcher headerMatcher = HEADER_PATTERN.matcher(line);
            if (headerMatcher.matches()) {
                String text = headerMatcher.group(2);
                out.append(bold(renderInline(text))).append("\n");
                continue;
            }

            // Bullet / numbered lists
            Matcher bulletMatcher = BULLET_PATTERN.matcher(line);
            if (bulletMatcher.matches()) {
                String indent = bulletMatcher.group(1);
                String text = bulletMatcher.group(3);
                out.append(indent).append(dim("• ")).append(renderInline(text)).append("\n");
                continue;
            }

            // Blank line
            if (line.isBlank()) {
                out.append("\n");
                continue;
            }

            // Regular text — wrap and render inline
            String rendered = renderInline(line);
            wrapInto(out, rendered, width);
            out.append("\n");
        }

        // Unclosed code block
        if (inCodeBlock && codeBuffer.length() > 0) {
            renderCodeBlock(out, codeBuffer.toString(), codeLang, width);
        }

        return out.toString();
    }

    /**
     * Print rendered Markdown directly to stdout.
     */
    public static void printRendered(String markdown) {
        System.out.print(render(markdown));
    }

    /** Render inline formatting: bold and inline code. */
    private static String renderInline(String text) {
        // Inline code first
        Matcher m = INLINE_CODE_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(text, last, m.start());
            sb.append(colorize(BG_DARK + BRIGHT_WHITE, m.group(1)));
            last = m.end();
        }
        sb.append(text.substring(last));
        text = sb.toString();

        // Bold
        m = BOLD_PATTERN.matcher(text);
        sb = new StringBuilder();
        last = 0;
        while (m.find()) {
            sb.append(text, last, m.start());
            sb.append(bold(m.group(1)));
            last = m.end();
        }
        sb.append(text.substring(last));
        return sb.toString();
    }

    /** Render a code block with syntax highlighting and line numbers. */
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

    /** Basic syntax highlighting for code lines. */
    private static String highlightCode(String line) {
        // Handle comments first
        int commentIdx = indexOfUnquoted(line, "//");
        String codePart = commentIdx >= 0 ? line.substring(0, commentIdx) : line;
        String commentPart = commentIdx >= 0 ? line.substring(commentIdx) : "";

        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < codePart.length()) {
            char c = codePart.charAt(i);

            // String literals
            if (c == '"' || c == '\'') {
                char quote = c;
                int end = findStringEnd(codePart, i + 1, quote);
                result.append(GREEN).append(codePart, i, end + 1).append(RESET);
                i = end + 1;
                continue;
            }

            // Identifiers / keywords
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

    /** Word-wrap styled text into a StringBuilder. */
    private static void wrapInto(StringBuilder out, String styled, int maxWidth) {
        String plain = stripAnsi(styled);
        if (plain.length() <= maxWidth) {
            out.append(styled);
            return;
        }

        // Simple word wrap preserving ANSI codes
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
