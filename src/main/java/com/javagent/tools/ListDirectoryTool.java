package com.javagent.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 目录列表工具 —— 列出指定目录下的文件和子目录
 *
 * 功能：列出目录内容，可选递归遍历子目录
 *
 * 输出格式：
 * - [D] 表示目录
 * - [F] 表示文件
 *
 * 安全机制：
 * - requiresApproval=false：列出目录不需要审批
 * - readOnly=true：只读操作
 */
public class ListDirectoryTool implements Tool {
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "list_directory",
            "列出文件和目录，支持递归遍历。",
            Map.of(
                    "path", "要查看的目录路径，默认为当前目录。",
                    "recursive", "是否递归遍历子目录。",
                    "limit", "返回的最大条目数。"
            ),
            Map.of(
                    "path", "string",
                    "recursive", "boolean",
                    "limit", "integer"
            ),
            Set.of(),
            false,
            true,
            false,
            List.of("ls", "dir", "list_files")
    );

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolExecutionResult execute(Map<String, Object> input) {
        String rawPath = FileToolSupport.stringValue(input.get("path"));
        if (rawPath.isBlank()) {
            rawPath = ".";
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
            return ToolExecutionResult.error("路径未找到：" + path);
        }
        if (!Files.isDirectory(path)) {
            return ToolExecutionResult.error("路径不是目录：" + path);
        }

        boolean recursive = FileToolSupport.booleanValue(input.get("recursive"), false);
        int limit = Math.min(MAX_LIMIT, Math.max(1, FileToolSupport.intValue(input.get("limit"), DEFAULT_LIMIT)));

        try (Stream<Path> stream = recursive ? Files.walk(path) : Files.list(path)) {
            List<Path> entries = stream
                    .filter(candidate -> !candidate.equals(path))
                    .sorted(Comparator.comparing(Path::toString))
                    .limit(limit)
                    .toList();

            StringBuilder builder = new StringBuilder();
            builder.append("目录：").append(path.toAbsolutePath()).append(System.lineSeparator());
            builder.append("递归=").append(recursive).append(System.lineSeparator());
            builder.append("-----").append(System.lineSeparator());
            for (Path entry : entries) {
                String marker = Files.isDirectory(entry) ? "[D] " : "[F] ";
                String display = recursive
                        ? path.toAbsolutePath().normalize().relativize(entry.toAbsolutePath().normalize()).toString()
                        : entry.getFileName().toString();
                builder.append(marker).append(display).append(System.lineSeparator());
            }
            builder.append("-----").append(System.lineSeparator())
                    .append("条目数=").append(entries.size());
            if (entries.size() == limit) {
                builder.append("（已达上限）");
            }
            return ToolExecutionResult.success(builder.toString().trim());
        } catch (IOException e) {
            return ToolExecutionResult.error("列出目录失败：" + e.getMessage());
        }
    }
}
