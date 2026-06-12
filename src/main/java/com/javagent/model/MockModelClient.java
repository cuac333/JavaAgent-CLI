package com.javagent.model;

import com.javagent.tools.ToolDefinition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模拟模型客户端 —— 不需要 API Key 的本地测试用客户端
 *
 * 为什么需要 Mock？
 * 开发和测试时，不一定有 API Key 或网络连接。
 * MockModelClient 通过简单的关键词匹配来模拟 AI 的行为，
 * 让你可以离线测试整个 Agent 循环（输入 → AI 回复 → 工具调用 → 结果 → AI 总结）。
 *
 * 工作原理：
 * 1. 检查用户输入中的关键词（如"读取"→ read_file）
 * 2. 返回对应的 ToolCall，让 Agent 去执行工具
 * 3. 工具执行后，AI 总结结果
 * 4. 如果没有匹配到关键词，返回普通文本回复
 */
public class MockModelClient implements ModelClient {
    private static final Pattern FILE_PATTERN = Pattern.compile("([A-Za-z]:[\\\\/][^\\s]*?(?:README(?:\\.md)?|pom\\.xml|[A-Za-z0-9_./\\\\-]+\\.(?:java|xml|md|txt|json|ya?ml|properties))|README(?:\\.md)?|pom\\.xml|[A-Za-z0-9_./\\\\-]+\\.(?:java|xml|md|txt|json|ya?ml|properties))");
    private static final Pattern QUOTED_TEXT_PATTERN = Pattern.compile("[\"']([^\"']+)[\"']");

    @Override
    public ModelResponse chat(String systemPrompt, List<Message> messages, List<ToolDefinition> tools) {
        if (messages.isEmpty()) {
            return ModelResponse.text("模拟模式已就绪。");
        }

        Message lastMessage = messages.get(messages.size() - 1);
        if (lastMessage.role() == Role.TOOL && lastMessage.toolResult() != null) {
            return summarizeToolResult(lastMessage.toolResult());
        }

        Optional<Message> maybeLastUser = messages.stream()
                .filter(message -> message.role() == Role.USER)
                .reduce((first, second) -> second);

        if (maybeLastUser.isEmpty()) {
            return ModelResponse.text("模拟模式未找到用户消息。");
        }

        String input = maybeLastUser.get().content().trim();
        String lower = input.toLowerCase(Locale.ROOT);

        if (hasTool(tools, "edit") && containsAny(lower, "edit", "replace", "修改", "替换", "str_replace")) {
            String path = extractPathToken(input).orElse("test.txt");
            String oldStr = extractQuotedText(input).orElse("old_text");
            String newStr = extractQuotedText(input).orElse("new_text");
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("path", path);
            args.put("old_string", oldStr);
            args.put("new_string", newStr);
            return ModelResponse.toolCalls(
                    "我应该使用编辑工具来修改文件。",
                    List.of(ToolCall.of("edit", args))
            );
        }

        if (hasTool(tools, "delete_file") && containsAny(lower, "删除", "delete", "remove")) {
            String path = extractPathToken(input).orElse("notes.txt");
            return ModelResponse.toolCalls(
                    "我应该先删除请求的文件再回答。",
                    List.of(ToolCall.of("delete_file", Map.of("path", path)))
            );
        }

        if (hasTool(tools, "write_file") && containsAny(lower, "写入", "写到", "create file", "write", "save to", "保存到")) {
            String path = extractPathToken(input).orElse("notes.txt");
            String content = extractWriteContent(input, path);
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("path", path);
            args.put("content", content);
            args.put("append", lower.contains("append") || lower.contains("追加"));
            return ModelResponse.toolCalls(
                    "我应该先将请求的内容写入磁盘。",
                    List.of(ToolCall.of("write_file", args))
            );
        }

        if (hasTool(tools, "list_directory") && containsAny(lower, "列出", "目录", "list", "ls", "files")) {
            String path = extractPathToken(input).orElse(".");
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("path", path);
            args.put("recursive", lower.contains("递归") || lower.contains("recursive"));
            args.put("limit", 50);
            return ModelResponse.toolCalls(
                    "我应该先查看目录内容。",
                    List.of(ToolCall.of("list_directory", args))
            );
        }

        if (hasTool(tools, "read_file") && containsAny(lower, "读取", "read", "open", "查看文件", "show")) {
            String path = extractFilePath(input).orElseGet(() -> extractPathToken(input).orElse("README.md"));
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("path", path);
            args.put("limit", 200);
            return ModelResponse.toolCalls(
                    "我应该先查看文件再回答。",
                    List.of(ToolCall.of("read_file", args))
            );
        }

        if (hasTool(tools, "grep") && containsAny(lower, "搜索", "grep", "search", "find")) {
            String pattern = extractSearchPattern(input);
            String path = extractSearchPath(input).orElse(".");
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("pattern", pattern);
            args.put("path", path);
            args.put("caseSensitive", false);
            return ModelResponse.toolCalls(
                    "我应该先在项目中搜索匹配的文本。",
                    List.of(ToolCall.of("grep", args))
            );
        }

        if (hasTool(tools, "bash") && containsAny(lower, "执行命令", "run command", "bash", "shell")) {
            String command = extractQuotedText(input).orElse("pwd");
            return ModelResponse.toolCalls(
                    "我应该先执行请求的命令。",
                    List.of(ToolCall.of("bash", Map.of("command", command))))
                    ;
        }

        if (containsAny(lower, "help", "工具", "tools")) {
            return ModelResponse.text("模拟模式可以读取文件、搜索文本、列出目录、写入文件、删除文件，并演示完整的 Agent 循环。");
        }

        return ModelResponse.text("[模拟] 我理解了你的请求，但不需要调用工具。请让我读取、搜索、列出、写入或删除某些内容。");
    }

    @Override
    public String name() {
        return "mock-model";
    }

    /** 总结工具执行结果 —— 根据工具名称生成不同的总结模板 */
    private ModelResponse summarizeToolResult(ToolResultMessage toolResult) {
        if (toolResult.error()) {
            return ModelResponse.text("工具调用失败: " + toolResult.content());
        }

        return switch (toolResult.toolName()) {
            case "read_file" -> ModelResponse.text("已成功读取请求的文件。以下是基于工具输出的简要摘要:\n\n"
                    + firstLines(toolResult.content(), 12));
            case "grep" -> ModelResponse.text("已搜索请求的路径。以下是最相关的匹配结果:\n\n"
                    + firstLines(toolResult.content(), 14));
            case "list_directory" -> ModelResponse.text("已列出请求的目录。以下是最相关的条目:\n\n"
                    + firstLines(toolResult.content(), 16));
            case "write_file" -> ModelResponse.text("文件写入成功。\n\n" + firstLines(toolResult.content(), 8));
            case "edit" -> ModelResponse.text("文件编辑成功。\n\n" + firstLines(toolResult.content(), 8));
            case "delete_file" -> ModelResponse.text("请求的文件已成功删除。\n\n" + firstLines(toolResult.content(), 8));
            case "bash" -> ModelResponse.text("命令执行完成。摘要:\n\n" + firstLines(toolResult.content(), 12));
            default -> ModelResponse.text("工具执行完成:\n\n" + firstLines(toolResult.content(), 12));
        };
    }

    /** 检查工具列表中是否有指定名称的工具 */
    private boolean hasTool(List<ToolDefinition> tools, String name) {
        return tools.stream().anyMatch(tool -> tool.name().equals(name));
    }

    /** 检查输入字符串是否包含任一关键词 */
    private boolean containsAny(String input, String... values) {
        for (String value : values) {
            if (input.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private Optional<String> extractFilePath(String input) {
        Matcher matcher = FILE_PATTERN.matcher(input);
        if (matcher.find()) {
            return Optional.of(matcher.group(1));
        }
        return Optional.empty();
    }

    private Optional<String> extractPathToken(String input) {
        String[] tokens = input.split("\\s+");
        for (String token : tokens) {
            String cleaned = token
                    .replace("，", "")
                    .replace(",", "")
                    .replace("。", "")
                    .replace("：", "")
                    .replace(":", "")
                    .replace("\"", "")
                    .replace("'", "");
            if (cleaned.isBlank()) {
                continue;
            }
            if (cleaned.equals(".") || cleaned.equals("..") || cleaned.startsWith("./") || cleaned.startsWith("../")
                    || cleaned.startsWith("/") || cleaned.contains("/") || cleaned.contains("\\")
                    || FILE_PATTERN.matcher(cleaned).matches()) {
                return Optional.of(cleaned);
            }
        }
        return Optional.empty();
    }

    private Optional<String> extractQuotedText(String input) {
        Matcher matcher = QUOTED_TEXT_PATTERN.matcher(input);
        if (matcher.find()) {
            return Optional.of(matcher.group(1));
        }
        return Optional.empty();
    }

    private String extractWriteContent(String input, String path) {
        Matcher matcher = QUOTED_TEXT_PATTERN.matcher(input);
        while (matcher.find()) {
            String candidate = matcher.group(1);
            if (!candidate.equals(path)) {
                return candidate;
            }
        }

        String normalized = input
                .replace(path, "")
                .replace("请", "")
                .replace("帮我", "")
                .replace("写入", "")
                .replace("写到", "")
                .replace("保存到", "")
                .replace("create file", "")
                .replace("write", "")
                .replace("save to", "")
                .trim();

        if (normalized.isBlank()) {
            return "由 JavaAgent CLI 生成。";
        }
        return normalized;
    }

    private String extractSearchPattern(String input) {
        Matcher quotedMatcher = QUOTED_TEXT_PATTERN.matcher(input);
        if (quotedMatcher.find()) {
            return quotedMatcher.group(1);
        }

        String normalized = input
                .replace("请", "")
                .replace("帮我", "")
                .replace("搜索", "")
                .replace("search", "")
                .replace("grep", "")
                .replace("find", "")
                .trim();

        String[] parts = normalized.split("\\s+");
        if (parts.length == 0 || parts[0].isBlank()) {
            return "agent";
        }

        String token = parts[0];
        if (token.contains("/") && !token.startsWith("/") && !token.startsWith("./") && !token.contains(".")) {
            return token.replace("/", "|");
        }
        return token;
    }

    private Optional<String> extractSearchPath(String input) {
        Matcher matcher = FILE_PATTERN.matcher(input);
        if (matcher.find()) {
            String fileLike = matcher.group(1);
            if (!fileLike.equalsIgnoreCase("README") && !fileLike.equalsIgnoreCase("README.md")) {
                return Optional.of(fileLike);
            }
        }
        String[] parts = input.split("\\s+");
        for (String part : parts) {
            if (part.startsWith("./") || part.startsWith("/") || part.contains("\\")) {
                return Optional.of(part);
            }
        }
        return Optional.empty();
    }

    private String firstLines(String input, int maxLines) {
        String[] lines = input.split("\\R");
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < Math.min(lines.length, maxLines); i++) {
            builder.append(lines[i]).append(System.lineSeparator());
        }
        if (lines.length > maxLines) {
            builder.append("...").append(System.lineSeparator());
        }
        return builder.toString().trim();
    }
}
