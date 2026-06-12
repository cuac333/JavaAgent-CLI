package com.javagent.tools;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

/**
 * 工具注册中心 —— 管理所有可用工具
 *
 * 什么是"注册"？
 * 把工具对象放入一个 Map 中，以工具名为 key，
 * 之后可以通过名称查找工具。就像字典一样：
 * - "read_file"  → ReadFileTool 实例
 * - "grep"       → GrepTool 实例
 * - "bash"       → BashTool 实例
 *
 * 还支持别名查找：
 * - "read" 也是 read_file 的别名
 * - "ls" 也是 list_directory 的别名
 */
public class ToolRegistry {
    // 按工具名存储（小写键，忽略大小写）
    private final Map<String, Tool> toolsByName = new LinkedHashMap<>();
    // 按别名存储（小写键）
    private final Map<String, Tool> toolsByAlias = new LinkedHashMap<>();
    // 工作区根目录
    private Path workspaceRoot;

    /** 设置工作区根目录，所有后续注册的工具都会收到此配置 */
    public void setWorkspaceRoot(Path root) {
        this.workspaceRoot = root;
        FileToolSupport.setWorkspaceRoot(root);
        // 重新配置已注册的工具
        for (Tool tool : toolsByName.values()) {
            tool.configure(root);
        }
    }

    /**
     * 注册一个工具
     * 同时注册工具名和所有别名
     */
    public void register(Tool tool) {
        ToolDefinition definition = tool.definition();
        toolsByName.put(definition.name().toLowerCase(Locale.ROOT), tool);
        for (String alias : definition.aliases()) {
            toolsByAlias.put(alias.toLowerCase(Locale.ROOT), tool);
        }
        // 配置工作区根目录
        if (workspaceRoot != null) {
            tool.configure(workspaceRoot);
        }
    }

    /**
     * 按名称或别名查找工具
     * 先按名称找，再按别名找
     *
     * @param toolName 工具名或别名
     * @return Optional 包装的 Tool
     */
    public Optional<Tool> find(String toolName) {
        if (toolName == null) {
            return Optional.empty();
        }
        String key = toolName.toLowerCase(Locale.ROOT);
        Tool tool = toolsByName.get(key);
        if (tool != null) {
            return Optional.of(tool);
        }
        return Optional.ofNullable(toolsByAlias.get(key));
    }

    /** 获取所有工具的定义列表（用于告诉 AI 有哪些工具可用） */
    public List<ToolDefinition> definitions() {
        return toolsByName.values().stream()
                .map(Tool::definition)
                .toList();
    }

    public int count() {
        return toolsByName.size();
    }

    public Collection<Tool> all() {
        return List.copyOf(toolsByName.values());
    }

    /**
     * 通过 ServiceLoader (SPI) 自动发现并注册所有 ToolProvider 插件
     */
    public int discoverPlugins() {
        int count = 0;
        for (ToolProvider provider : ServiceLoader.load(ToolProvider.class)) {
            for (Tool tool : provider.tools()) {
                register(tool);
                count++;
            }
        }
        return count;
    }

    /** 生成工具列表的文本描述（给用户查看） */
    public String listTools() {
        List<ToolDefinition> definitions = new ArrayList<>(definitions());
        if (definitions.isEmpty()) {
            return "没有已注册的工具。";
        }

        return definitions.stream()
                .map(def -> {
                    String aliases = def.aliases().isEmpty()
                            ? ""
                            : " [" + String.join(", ", def.aliases()) + "]";
                    String flags = def.requiresApproval()
                            ? "（需要审批）"
                            : "（自动批准）";
                    return "- " + def.name() + aliases + ": " + def.description() + flags;
                })
                .collect(Collectors.joining(System.lineSeparator()));
    }
}
