package com.javagent.tools;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 文件工具辅助类 —— 各文件工具共用的工具方法
 *
 * 为什么需要这个类？
 * ReadFileTool、WriteFileTool、DeleteFileTool、ListDirectoryTool 等
 * 都需要从 Map 中提取参数值、处理路径等操作。
 * 把这些公共逻辑提取到一个类中，避免代码重复。
 *
 * 这个类是包私有的（final class，构造器私有），
 * 只能被同包下的工具类使用，外部不能实例化。
 */
final class FileToolSupport {
    // 工作区根目录 —— 由 ToolRegistry 在注册时设置
    private static volatile Path workspaceRoot;

    // 私有构造器 —— 纯工具类，不允许实例化
    private FileToolSupport() {
    }

    /** 设置工作区根目录（由 ToolRegistry 调用） */
    static void setWorkspaceRoot(Path root) {
        workspaceRoot = root != null ? root.toAbsolutePath().normalize() : null;
    }

    /** 检查路径是否在工作区内，不在则返回错误信息，null 表示通过 */
    static String checkInsideWorkspace(Path path) {
        if (workspaceRoot == null) return null;
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(workspaceRoot)) {
            return "访问被拒绝：路径在工作区之外：" + path;
        }
        return null;
    }

    /** 从 Map 中提取字符串值，null 返回 "" */
    static String stringValue(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    /** 从 Map 中提取整数值（兼容 Number 和 String 类型） */
    static int intValue(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String raw) {
            try {
                return Integer.parseInt(raw);
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /** 从 Map 中提取布尔值（兼容 Boolean 和 String 类型） */
    static boolean booleanValue(Object value, boolean defaultValue) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String raw) {
            return Boolean.parseBoolean(raw);
        }
        return defaultValue;
    }

    /** 规范化路径 —— 去除冗余的 "." 和 ".." */
    static Path normalizePath(String rawPath) {
        try {
            return Paths.get(rawPath).normalize();
        } catch (InvalidPathException e) {
            throw e;
        }
    }

    /**
     * 检测文件是否为二进制文件。
     * 只读取前 8KB，通过 null 字节判断 — 有 null 字节就是二进制，没有就当文本。
     * 不再用 UTF-8 严格解码，避免中文/emoji 等合法文本被误判。
     */
    static boolean isBinary(Path path) throws IOException {
        int maxInspect = 8192;
        long fileSize = java.nio.file.Files.size(path);
        int toRead = (int) Math.min(fileSize, maxInspect);
        byte[] buf = new byte[toRead];
        try (var in = java.nio.file.Files.newInputStream(path)) {
            int totalRead = 0;
            while (totalRead < toRead) {
                int n = in.read(buf, totalRead, toRead - totalRead);
                if (n < 0) break;
                totalRead += n;
            }
            for (int i = 0; i < totalRead; i++) {
                if (buf[i] == 0) {
                    return true;
                }
            }
        }
        return false;
    }
}
