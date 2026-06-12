package com.javagent.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 读文件工具 —— 读取文本文件的内容
 *
 * 功能：读取指定路径的文本文件，支持 offset（起始行）和 limit（行数限制）
 *
 * 安全机制：
 * - requiresApproval=false：读文件不需要审批
 * - readOnly=true：只读操作，不会修改文件
 * - 文件大小限制：超过 256KB 拒绝读取
 * - 二进制文件检测：自动跳过二进制文件
 */
public class ReadFileTool implements Tool {
    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 1000;
    private static final long MAX_SIZE_BYTES = 256 * 1024L;

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "read_file",
            "读取文本文件，支持偏移量和行数限制。",
            Map.of(
                    "path", "要读取的文件路径。",
                    "offset", "从零开始的起始行偏移量。",
                    "limit", "返回的最大行数。"
            ),
            Map.of(
                    "path", "string",
                    "offset", "integer",
                    "limit", "integer"
            ),
            Set.of("path"),
            false,
            true,
            false,
            List.of("read", "cat", "open_file")
    );

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolExecutionResult execute(Map<String, Object> input) {
        String rawPath = FileToolSupport.stringValue(input.get("path"));
        if (rawPath.isBlank()) {
            return ToolExecutionResult.error("read_file 需要一个非空路径。");
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

        try {
            long size = Files.size(path);
            if (size > MAX_SIZE_BYTES) {
                return ToolExecutionResult.error("文件过大，无法安全读取（" + size + " 字节）。");
            }
            if (FileToolSupport.isBinary(path)) {
                return ToolExecutionResult.error("read_file 不支持二进制文件。");
            }

            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            int offset = Math.max(0, FileToolSupport.intValue(input.get("offset"), 0));
            int limit = Math.min(MAX_LIMIT, Math.max(1, FileToolSupport.intValue(input.get("limit"), DEFAULT_LIMIT)));
            int start = Math.min(offset, lines.size());
            int end = Math.min(start + limit, lines.size());

            StringBuilder builder = new StringBuilder();
            builder.append("文件：").append(path.toAbsolutePath()).append(System.lineSeparator());
            builder.append("行：").append(start + 1).append("-").append(end).append("，共 ").append(lines.size()).append(" 行").append(System.lineSeparator());
            builder.append("-----").append(System.lineSeparator());
            for (int i = start; i < end; i++) {
                builder.append(i + 1).append(": ").append(lines.get(i)).append(System.lineSeparator());
            }
            if (end < lines.size()) {
                builder.append("...（省略了 ").append(lines.size() - end).append(" 行）");
            }

            return ToolExecutionResult.success(builder.toString().trim());
        } catch (IOException e) {
            return ToolExecutionResult.error("读取文件失败：" + e.getMessage());
        }
    }
}
