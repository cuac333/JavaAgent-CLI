package com.javagent.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.javagent.util.Terminal.splitLines;

/**
 * 编辑工具 —— 精确替换文件中的字符串
 *
 * old_string 必须精确匹配（包括空白字符/缩进）。
 * 如果精确匹配失败，会返回预期位置附近的实际文件内容，
 * 以便模型查看真实内容并正确重试。
 */
public class EditTool implements Tool {

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "edit",
            "精确替换文件中的文本。old_string 必须精确匹配（包括空白字符）。使用 replace_all=true 可替换所有匹配项。",
            Map.of(
                    "path", "要编辑的文件路径。",
                    "old_string", "要查找的精确文本（必须包含空白字符精确匹配）。",
                    "new_string", "替换文本。",
                    "replace_all", "为 true 时替换所有匹配项，默认为 false。"
            ),
            Map.of(
                    "path", "string",
                    "old_string", "string",
                    "new_string", "string",
                    "replace_all", "boolean"
            ),
            Set.of("path", "old_string", "new_string"),
            true,
            false,
            false,
            List.of("replace", "str_replace", "sed")
    );

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolExecutionResult execute(Map<String, Object> input) {
        String rawPath = FileToolSupport.stringValue(input.get("path"));
        if (rawPath.isBlank()) {
            return ToolExecutionResult.error("edit 需要一个非空路径。");
        }

        Path path;
        try {
            path = FileToolSupport.normalizePath(rawPath);
        } catch (InvalidPathException e) {
            return ToolExecutionResult.error("无效的路径：" + rawPath);
        }

        String wsError = FileToolSupport.checkInsideWorkspace(path);
        if (wsError != null) {
            return ToolExecutionResult.error(wsError);
        }

        if (!Files.exists(path)) {
            return ToolExecutionResult.error("文件未找到：" + path);
        }
        if (!Files.isRegularFile(path)) {
            return ToolExecutionResult.error("路径不是普通文件：" + path);
        }

        String oldString = input.get("old_string") == null ? "" : input.get("old_string").toString();
        String newString = input.get("new_string") == null ? "" : input.get("new_string").toString();

        if (oldString.isEmpty()) {
            return ToolExecutionResult.error("edit 需要一个非空的 old_string。");
        }
        if (oldString.equals(newString)) {
            return ToolExecutionResult.error("old_string 和 new_string 完全相同。");
        }

        boolean replaceAll = FileToolSupport.booleanValue(input.get("replace_all"), false);

        try {
            if (FileToolSupport.isBinary(path)) {
                return ToolExecutionResult.error("无法编辑二进制文件。");
            }

            String content = Files.readString(path, StandardCharsets.UTF_8);

            int count = countOccurrences(content, oldString);
            if (count == 0) {
                return buildContentHint(content, oldString);
            }
            if (count > 1 && !replaceAll) {
                return ToolExecutionResult.error(
                        "old_string 出现了 " + count + " 次。使用 replace_all=true 可替换所有匹配项，"
                                + "或提供更多周围上下文使其唯一。"
                );
            }

            // 精确匹配 —— 应用编辑
            String newContent;
            int changeStartLine = 0;
            if (replaceAll) {
                newContent = content.replace(oldString, newString);
            } else {
                int idx = content.indexOf(oldString);
                changeStartLine = splitLines(content.substring(0, idx)).length - 1;
                newContent = content.substring(0, idx) + newString + content.substring(idx + oldString.length());
            }
            Files.writeString(path, newContent, StandardCharsets.UTF_8);

            String[] oldLines = splitLines(oldString);
            String[] newLines = splitLines(newString);
            String nl = System.lineSeparator();
            String summary = "已编辑 " + path.toAbsolutePath()
                    + "（-" + oldLines.length + " 行，+" + newLines.length + " 行）" + nl
                    + "```diff" + nl + generateDiff(oldLines, newLines, changeStartLine) + "```";
            return ToolExecutionResult.success(summary);

        } catch (IOException e) {
            return ToolExecutionResult.error("编辑文件失败：" + e.getMessage());
        }
    }

    // ─────────── 不匹配时的内容提示 ───────────

    /**
     * 当精确匹配失败时，显示最佳猜测位置附近的文件内容，
     * 以便模型查看实际文本并构造正确的 old_string。
     */
    private ToolExecutionResult buildContentHint(String content, String oldString) {
        String[] contentLines = splitLines(content);
        String[] oldLines = splitLines(oldString);

        // 从 old_string 中找到最具特征的行
        String probe = "";
        for (String line : oldLines) {
            String stripped = line.strip();
            if (stripped.length() > probe.length()) {
                probe = stripped;
            }
        }

        // 在文件中搜索该行
        int foundLine = -1;
        if (probe.length() >= 6) {
            for (int i = 0; i < contentLines.length; i++) {
                if (contentLines[i].contains(probe)) {
                    foundLine = i;
                    break;
                }
            }
        }

        StringBuilder msg = new StringBuilder();
        msg.append("old_string 未精确找到。");

        if (foundLine >= 0) {
            int ctxStart = Math.max(0, foundLine - 2);
            int ctxEnd = Math.min(contentLines.length, foundLine + oldLines.length + 3);
            msg.append("预期位置附近的文件内容（第 ")
                .append(ctxStart + 1).append("-").append(ctxEnd).append(" 行）：\n");
            msg.append("```\n");
            for (int i = ctxStart; i < ctxEnd; i++) {
                String marker = (i == foundLine) ? " >>> " : "     ";
                msg.append(String.format("%4d%s%s%n", i + 1, marker, contentLines[i]));
            }
            msg.append("```\n");
            msg.append("请与您的 old_string 进行比较，并修复空白字符/缩进差异。");
        } else {
            // 完全找不到匹配 —— 显示前 30 行
            int show = Math.min(contentLines.length, 30);
            msg.append("文件的前 ").append(show).append(" 行：\n");
            msg.append("```\n");
            for (int i = 0; i < show; i++) {
                msg.append(String.format("%4d     %s%n", i + 1, contentLines[i]));
            }
            msg.append("```\n");
        }

        return ToolExecutionResult.error(msg.toString());
    }

    // ─────────── 差异预览 ───────────

    private String generateDiff(String[] oldLines, String[] newLines, int changeStartLine) {
        String nl = System.lineSeparator();
        StringBuilder diff = new StringBuilder();
        diff.append("@@ -").append(changeStartLine + 1).append(",").append(oldLines.length)
            .append(" +").append(changeStartLine + 1).append(",").append(newLines.length).append(" @@" + nl);

        for (String line : oldLines) {
            diff.append(com.javagent.util.Terminal.diffRemove(line)).append(nl);
        }
        for (String line : newLines) {
            diff.append(com.javagent.util.Terminal.diffAdd(line)).append(nl);
        }
        return diff.toString();
    }

    // ─────────── 辅助方法 ───────────

    private int countOccurrences(String text, String pattern) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(pattern, idx)) != -1) {
            count++;
            idx += pattern.length();
        }
        return count;
    }
}
