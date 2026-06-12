package com.javagent.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.javagent.util.Terminal.splitLines;

/**
 * 写文件工具 —— 向文件写入文本内容
 *
 * 功能：将 UTF-8 文本写入指定文件，支持覆盖或追加模式
 *
 * 安全机制：
 * - requiresApproval=true：需要用户确认
 * - destructive=true：会修改文件系统
 * - 内容大小限制：超过 100,000 字符拒绝写入
 * - 二进制文件保护：拒绝覆盖二进制文件
 */
public class WriteFileTool implements Tool {
    private static final int MAX_CONTENT_CHARS = 100_000;

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "write_file",
            "将 UTF-8 文本写入文件，可选追加模式。",
            Map.of(
                    "path", "要写入的文件路径。",
                    "content", "要存储的 UTF-8 文本内容。",
                    "append", "是否以追加方式写入（而非覆盖）。"
            ),
            Map.of(
                    "path", "string",
                    "content", "string",
                    "append", "boolean"
            ),
            Set.of("path", "content"),
            true,
            false,
            true,
            List.of("write", "save_file", "create_file")
    );

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolExecutionResult execute(Map<String, Object> input) {
        String rawPath = FileToolSupport.stringValue(input.get("path"));
        if (rawPath.isBlank()) {
            return ToolExecutionResult.error("write_file 需要一个非空路径。");
        }

        String content = input.get("content") == null ? "" : input.get("content").toString();
        if (content.length() > MAX_CONTENT_CHARS) {
            return ToolExecutionResult.error("内容过大，无法安全写入。");
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

        boolean append = FileToolSupport.booleanValue(input.get("append"), false);

        try {
            if (Files.exists(path) && Files.isDirectory(path)) {
                return ToolExecutionResult.error("路径是目录而非文件：" + path);
            }
            if (Files.exists(path) && Files.isRegularFile(path) && FileToolSupport.isBinary(path)) {
                return ToolExecutionResult.error("拒绝覆盖二进制文件。");
            }
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }

            if (append) {
                Files.writeString(
                        path,
                        content,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                );
            } else {
                Files.writeString(
                        path,
                        content,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                );
            }

            String summary = "已写入 " + content.length() + " 个字符到 " + path.toAbsolutePath() + "（追加=" + append + "）。";
            String preview = buildPreview(content);
            if (preview != null) {
                summary += System.lineSeparator() + preview;
            }
            return ToolExecutionResult.success(summary);
        } catch (IOException e) {
            return ToolExecutionResult.error("写入文件失败：" + e.getMessage());
        }
    }

    private static final int PREVIEW_MAX_LINES = 60;

    private String buildPreview(String content) {
        String[] lines = splitLines(content);
        if (lines.length == 0) return null;

        int show = Math.min(lines.length, PREVIEW_MAX_LINES);
        StringBuilder sb = new StringBuilder();
        sb.append("预览（").append(show).append("/").append(lines.length).append(" 行）：\n");
        sb.append("```\n");
        for (int i = 0; i < show; i++) {
            sb.append(String.format("%4d│ %s%n", i + 1, lines[i]));
        }
        if (lines.length > PREVIEW_MAX_LINES) {
            sb.append("    ...（还有 ").append(lines.length - PREVIEW_MAX_LINES).append(" 行）\n");
        }
        sb.append("```");
        return sb.toString();
    }
}
