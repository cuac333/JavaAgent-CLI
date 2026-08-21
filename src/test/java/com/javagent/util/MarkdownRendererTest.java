package com.javagent.util;

import com.javagent.util.Terminal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static com.javagent.util.Terminal.splitLines;

class MarkdownRendererTest {

    @Test
    void rendersTableWithBorder() {
        String md = "| 名称 | 数量 |\n"
                + "| ---- | ---- |\n"
                + "| 苹果 | 3    |\n"
                + "| 香蕉 | 5    |\n";
        String rendered = MarkdownRenderer.render(md);
        String plain = stripAnsi(rendered);
        // 表格应带边框字符
        assertTrue(rendered.contains("┌"), "表格应渲染出顶部边框，实际: " + escaped(rendered));
        assertTrue(rendered.contains("└"), "表格应渲染出底部边框，实际: " + escaped(rendered));
        // 单元格内容应保留
        assertTrue(plain.contains("苹果"), "应包含单元格内容，实际: " + escaped(rendered));
        // 不应出现双竖线 ||（行尾拼接 bug）
        assertTrue(!plain.contains("││"), "行尾不应出现双竖线，实际: " + escaped(rendered));
    }

    @Test
    void rendersInlineCodeInsideTableCell() {
        String md = "| 工具 | 说明 |\n"
                + "| --- | --- |\n"
                + "| `read_file` | 读取文本文件 |\n";
        String rendered = MarkdownRenderer.render(md);
        // 行内代码应被渲染（BG_DARK+BRIGHT_WHITE 包裹），而非原样 `read_file`
        assertTrue(rendered.contains("\033[48;5;236m\033[97mread_file\033[0m"),
                "表格内行内代码应渲染高亮，实际: " + escaped(rendered));
        // 行内代码的反引号不应再出现
        assertTrue(!stripAnsi(rendered).contains("`read_file`"),
                "行内代码反引号应被消费，实际: " + escaped(rendered));
    }

    @Test
    void rendersNoDoublePipeAtRowEnd() {
        String md = "| a | b |\n| --- | --- |\n| 1 | 2 |\n";
        String rendered = MarkdownRenderer.render(md);
        for (String line : splitLines(stripAnsi(rendered))) {
            int pipes = countOccurrences(line, '│');
            // 每行内容行的竖线数应为列数+1（表头/数据行），边框行 2（┌┐├┤└┘那类）
            assertTrue(!line.contains("││"), "行尾不应有双竖线: [" + line + "]");
        }
    }

    @Test
    void tableColumnsAlignedWithInlineCode() {
        // 含行内代码的表格：每列竖线应落在相同列位置（对齐）
        String md = "| 工具 | 别名 | 说明 |\n"
                + "| --- | --- | --- |\n"
                + "| `read_file` | read, cat | 读取文件 |\n"
                + "| `grep` | search | 搜索 |\n";
        String rendered = MarkdownRenderer.render(md);
        String plain = stripAnsi(rendered);
        String[] lines = splitLines(plain);

        // 抽取数据行的每个 │ 所在显示列（用 displayWidth 计算，CJK 占 2 列）
        int[] prevSep = null;
        for (String line : lines) {
            if (!line.trim().startsWith("│")) continue;
            int[] seps = new int[]{0,0,0,0};
            int idx = 0;
            int col = 0;
            for (int i = 0; i < line.length(); i++) {
                if (line.charAt(i) == '│' && idx < seps.length) {
                    seps[idx] = col;
                    idx++;
                }
                col += charDisplayWidth(line.charAt(i));
            }
            System.out.println("displSe=" + java.util.Arrays.toString(seps) + " | " + line);
            if (prevSep != null) {
                assertTrue(java.util.Arrays.equals(prevSep, seps),
                        "竖线显示列应一致: " + escaped(line));
            }
            prevSep = seps;
        }
    }

    private static int charDisplayWidth(char c) {
        return Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ? 2 : 1;
    }

    @Test
    void tableBordersMatchRowWidth() {
        String md = "| a | b |\n| --- | --- |\n| 1 | 2 |\n";
        String rendered = MarkdownRenderer.render(md);
        String plain = stripAnsi(rendered);
        String[] lines = splitLines(plain);

        // 所有非空行的显示宽度应一致（边框行与内容行同宽）
        int expectedWidth = -1;
        for (String line : lines) {
            if (line.isBlank()) continue;
            int w = 0;
            for (char c : line.toCharArray()) {
                w += (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) ? 2 : 1;
            }
            if (expectedWidth < 0) {
                expectedWidth = w;
            } else {
                assertEquals(expectedWidth, w,
                        "表格各行宽度应一致（边框与内容同宽）: [" + line + "] w=" + w + " expect=" + expectedWidth);
            }
        }
    }

    @Test
    void noExtraBlankLineAfterTrailingNewline() {
        // 以 \n 结尾的 markdown 不应产生额外的尾随空行（行距翻倍的源头）
        String rendered = MarkdownRenderer.render("第一行\n第二行\n");
        String plain = stripAnsi(rendered);
        String[] lines = splitLines(plain);
        int emptyAtEnd = 0;
        for (int i = lines.length - 1; i >= 0 && lines[i].isBlank(); i--) {
            emptyAtEnd++;
        }
        assertTrue(emptyAtEnd <= 1,
                "以 \\n 结尾不应有多个尾随空行，实际空行数=" + emptyAtEnd + ": " + escaped(rendered));
    }

    @Test
    void rendersLinkWithOsc8Hyperlink() {
        String md = "访问[官方网站](https://example.com)了解更多";
        String rendered = MarkdownRenderer.render(md);
        // OSC 8 超链接序列: ESC]8;;url\ESC\ 文本 ESC]8;;\ESC\
        assertTrue(rendered.contains("\033]8;;https://example.com\033\\"),
                "链接应包含 OSC 8 超链接序列，URL 可点击，实际: " + escaped(rendered));
        // 文本部分应存在（下划线）
        assertTrue(rendered.contains("\033[4m官方网站\033[0m"),
                "链接文本应加下划线，实际: " + escaped(rendered));
    }

    @Test
    void rendersBareUrlAsClickable() {
        String md = "访问 https://www.google.com 了解更多";
        String rendered = MarkdownRenderer.render(md);
        // 裸 URL 也应被 OSC 8 包裹
        assertTrue(rendered.contains("\033]8;;https://www.google.com\033\\"),
                "裸 URL 应包含 OSC 8 超链接序列，实际: " + escaped(rendered));
        // URL 蓝色下划线
        assertTrue(rendered.contains("\033[34m\033[4mhttps://www.google.com\033[0m"),
                "裸 URL 应为蓝色下划线，实际: " + escaped(rendered));
    }

    @Test
    void rendersHighlight() {
        String md = "这是==高亮文本==样式";
        String rendered = MarkdownRenderer.render(md);
        // 高亮用浅蓝紫（Claude Code 风格）
        assertTrue(rendered.contains("\033[38;5;147m"),
                "高亮应有浅蓝紫色，实际: " + escaped(rendered));
        assertTrue(rendered.contains("高亮文本"),
                "高亮文本内容应保留，实际: " + escaped(rendered));
    }

    @Test
    void rendersBlockquoteWithBar() {
        String md = "> 这是一段引用";
        String rendered = MarkdownRenderer.render(md);
        assertTrue(stripAnsi(rendered).contains("│"), "引用块应有 │ 标记，实际: " + escaped(rendered));
        assertTrue(stripAnsi(rendered).contains("这是一段引用"), "引用内容应保留");
    }

    @Test
    void rendersHorizontalRule() {
        String md = "---";
        String rendered = MarkdownRenderer.render(md);
        assertTrue(stripAnsi(rendered).contains("─"), "水平线应渲染为 ─，实际: " + escaped(rendered));
    }

    private static String stripAnsi(String s) {
        return s.replaceAll("\033\\[[;\\d]*m", "");
    }

    private static String escaped(String s) {
        return s.replace("\033", "\\e").replace("\n", "\\n");
    }

    private static int countOccurrences(String s, char c) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) count++;
        }
        return count;
    }
}