package com.javagent;

import com.javagent.core.Agent;
import com.javagent.core.ApprovalDecision;
import com.javagent.core.Config;
import com.javagent.core.ConversationManager;
import com.javagent.model.MockModelClient;
import com.javagent.model.ModelClient;
import com.javagent.model.OpenAiCompatibleModelClient;
import com.javagent.model.TextStreamHandler;
import com.javagent.model.ToolCall;
import com.javagent.model.ToolDisplayCallback;
import com.javagent.tools.BashTool;
import com.javagent.tools.DeleteFileTool;
import com.javagent.tools.EditTool;
import com.javagent.tools.GrepTool;
import com.javagent.tools.ListDirectoryTool;
import com.javagent.tools.ReadFileTool;
import com.javagent.tools.ToolDefinition;
import com.javagent.tools.ToolRegistry;
import com.javagent.tools.WriteFileTool;
import com.javagent.tools.NetworkTool;

import java.nio.file.Path;

import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.History;
import org.jline.reader.UserInterruptException;
import org.jline.reader.Widget;
import org.jline.reader.EndOfFileException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import java.util.concurrent.atomic.AtomicBoolean;

import com.javagent.util.ContextDisplay;
import com.javagent.util.MarkdownRenderer;

import static com.javagent.util.Terminal.*;

/**
 * JavaAgent CLI — main entry point.
 *
 * Claude Code-style interactive REPL with ANSI colors,
 * tool execution indicators, SSE streaming, and edit tool.
 * Uses JLine3 for line editing, history, and slash command autocomplete.
 */
public class JavaAgentCLI {
    private static final DateTimeFormatter SESSION_TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final String VERSION = "1.0.0";

    private Config config;
    private ConversationManager conversationManager;
    private ToolRegistry toolRegistry;
    private ModelClient modelClient;
    private Agent agent;
    private Terminal terminal;
    private final AtomicBoolean awaitingApproval = new AtomicBoolean(false);

    public static void main(String[] args) throws Exception {
        // Suppress JLine warnings before any JLine class loads.
        // Configure via LogManager so the level is set before the Logger is created.
        System.setProperty("java.util.logging.ConsoleHandler.level", "SEVERE");
        java.util.logging.Logger jlineLogger = java.util.logging.Logger.getLogger("org.jline");
        jlineLogger.setLevel(java.util.logging.Level.SEVERE);
        for (var h : jlineLogger.getHandlers()) {
            h.setLevel(java.util.logging.Level.SEVERE);
        }
        new JavaAgentCLI().run(args);
    }

    private void run(String[] args) throws Exception {
        config = Config.loadDefault();
        applyArgs(args);

        // 校验配置参数
        List<String> warnings = config.validate();
        if (!warnings.isEmpty()) {
            System.err.println("配置警告:");
            for (String w : warnings) {
                System.err.println("  ⚠ " + w);
            }
        }

        rebuildRuntime();
        startCli();
    }

    private void applyArgs(String[] args) throws IOException {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--mock" -> config.setMockMode(true);
                case "--real" -> config.setMockMode(false);
                case "--api-key" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--api-key 需要指定一个值");
                    }
                    config.setApiKey(args[++i]);
                }
                case "--help" -> {
                    printHelp(new PrintWriter(System.out));
                    System.exit(0);
                }
                default -> throw new IllegalArgumentException("未知参数: " + args[i]);
            }
        }
    }

    private void rebuildRuntime() {
        conversationManager = conversationManager == null ? new ConversationManager(config) : conversationManager;
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new ReadFileTool());
        toolRegistry.register(new GrepTool());
        toolRegistry.register(new ListDirectoryTool());
        toolRegistry.register(new EditTool());
        toolRegistry.register(new WriteFileTool());
        toolRegistry.register(new DeleteFileTool());
        if (config.bashEnabled()) {
            toolRegistry.register(new BashTool());
        }
        toolRegistry.register(new NetworkTool());
        int plugins = toolRegistry.discoverPlugins();
        toolRegistry.setWorkspaceRoot(config.workingDirectory());
        modelClient = config.isMockMode()
                ? new MockModelClient()
                : new OpenAiCompatibleModelClient(config);
        agent = new Agent(config, modelClient, toolRegistry, conversationManager);
    }

    private void startCli() throws Exception {
        terminal = TerminalBuilder.builder()
                .system(true)
                .jna(false)
                .jni(true)
                .build();

        SlashCommandCompleter completer = buildCompleter();

        // 持久化输入历史到文件
        java.nio.file.Path historyPath = config.stateDirectory().resolve("history.txt");
        java.nio.file.Files.createDirectories(historyPath.getParent());

        LineReaderBuilder builder = LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(completer)
                .variable(LineReader.HISTORY_FILE, historyPath)
                .variable(LineReader.LIST_MAX, 50)
                .option(LineReader.Option.AUTO_LIST, true)
                .variable(LineReader.COMPLETION_STYLE_LIST_BACKGROUND, "bg:black")
                .variable(LineReader.COMPLETION_STYLE_LIST_SELECTION, "bold,fg:bright-blue")
                .variable(LineReader.COMPLETION_STYLE_LIST_STARTING, "fg:white")
                .variable(LineReader.COMPLETION_STYLE_LIST_DESCRIPTION, "fg:bright-black");

        LineReader reader = builder.build();

        // When user types '/', show the completion list below without auto-inserting
        Widget slashWidget = () -> {
            reader.getBuffer().write('/');
            String buf = reader.getBuffer().toString();
            if (buf.equals("/")) {
                reader.callWidget(LineReader.LIST_CHOICES);
            }
            return true;
        };
        reader.getWidgets().put("magic-slash", slashWidget);
        KeyMap<Binding> mainKeyMap = reader.getKeyMaps().get(LineReader.MAIN);
        mainKeyMap.bind(slashWidget, "/");

        PrintWriter out = terminal.writer();

        // Graceful shutdown: save session on Ctrl+C or SIGTERM
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (conversationManager != null && config != null && config.autoSave()) {
                    conversationManager.saveCurrentSession();
                }
            } catch (Exception ignored) {
            }
        }));

        new BannerPrinter(config, toolRegistry, conversationManager, VERSION).print(out);

        while (true) {
            // 动态宽度分隔线
            int width = terminal.getWidth();
            if (width < 40) width = 80;
            String hLine = dim("─".repeat(width));

            // 上边框
            out.println(hLine);
            out.flush();

            String input;
            try {
                input = reader.readLine(bold(brightCyan("> ")));
            } catch (UserInterruptException e) {
                // Ctrl+C: 清理边框
                out.print("\033[2K\r");
                continue;
            } catch (EndOfFileException e) {
                out.println(hLine);
                out.println(dim("  再见。"));
                out.flush();
                break;
            }

            // 下边框
            out.println(hLine);
            out.flush();

            if (input == null) break;
            input = input.trim();
            if (input.isEmpty()) continue;

            if (input.equals("/")) {
                showCommandMenu(out, completer, "");
                continue;
            }

            if (input.startsWith("/")) {
                if (handleCommand(input, out, reader)) continue;
            }

            // Start braille spinner
            Spinner spinner = new Spinner(out, awaitingApproval);
            spinner.start();

            ConsoleTextStreamHandler streamHandler = new ConsoleTextStreamHandler();
            ConsoleToolDisplayCallback displayCallback = new ConsoleToolDisplayCallback(out);

            String response = agent.processTurn(
                    input,
                    toolCall -> {
                        awaitingApproval.set(true);
                        try {
                            return promptApproval(reader, toolCall);
                        } finally {
                            awaitingApproval.set(false);
                        }
                    },
                    streamHandler,
                    displayCallback
            );

            spinner.stop();

            String text = streamHandler.buffered();
            if (!text.isEmpty()) {
                out.print(MarkdownRenderer.render(text));
            } else if (!response.isEmpty()) {
                out.print(MarkdownRenderer.render(response));
            }
            out.println();
            out.flush();

            // Auto-compact when token usage exceeds threshold
            double usagePercent = agent.contextUsage().usagePercent();
            if (usagePercent >= config.compactThreshold()) {
                out.println(yellow("  ⚠ 上下文已达 " + String.format("%.0f", usagePercent * 100) + "%，自动压缩中"));
                Spinner compactSpinner = new Spinner(out, new AtomicBoolean(false), "压缩中...");
                compactSpinner.start();
                try {
                    String compactResult = agent.compact();
                    out.println(green("  ✓ ") + compactResult);
                } catch (Exception e) {
                    out.println(red("  ✗ ") + "压缩失败: " + e.getMessage());
                } finally {
                    compactSpinner.stop();
                }
                out.println();
            }
        }
    }

    private SlashCommandCompleter buildCompleter() {
        SlashCommandCompleter c = new SlashCommandCompleter();
        c.register("/help", "显示可用命令");
        c.register("/exit", "退出程序");
        c.register("/quit", "退出程序");
        c.register("/clear", "新建会话");
        c.register("/new", "新建会话");
        c.register("/save", "保存当前会话");
        c.register("/load", "加载已保存的会话");
        c.register("/sessions", "列出已保存的会话");
        c.register("/tools", "列出已注册的工具");
        c.register("/mode", "切换模型模式");
        c.register("/stream", "开关流式输出");
        c.register("/bash", "开关 Bash 工具");
        c.register("/prompt", "管理系统提示词");
        c.register("/approvals", "管理审批缓存");
        c.register("/bypass", "跳过所有工具审批确认");
        c.register("/status", "显示运行状态");
        c.register("/reload", "重新加载配置文件");
        c.register("/export", "导出当前对话为 Markdown");
        c.register("/stats", "查看工具执行统计");
        c.register("/network", "网络请求工具");
        c.register("/context", "查看对话上下文使用情况");
        c.register("/compact", "压缩对话历史（LLM 摘要）");
        c.register("/effort", "调节模型思考强度");
        return c;
    }

    // ─────────────────────── 命令处理 ───────────────────────

    private boolean handleCommand(String input, PrintWriter out, LineReader reader) throws IOException {
        String[] parts = input.split("\\s+", 2);
        String command = parts[0];
        String argument = parts.length > 1 ? parts[1].trim() : "";

        switch (command) {
            case "/help" -> printHelp(out);
            case "/exit", "/quit" -> {
                out.println(dim("  再见。"));
                out.flush();
                terminal.close();
                System.exit(0);
            }
            case "/clear", "/new" -> {
                conversationManager.startNewSession(argument.isBlank() ? null : argument);
                out.println(green("  新会话已创建。"));
            }
            case "/save" -> {
                conversationManager.saveCurrentSession(argument.isBlank() ? null : argument);
                out.println(green("  Saved: ") + dim(conversationManager.sessionStats()));
            }
            case "/load" -> {
                boolean loaded = argument.isBlank()
                        ? conversationManager.loadLastSession()
                        : conversationManager.loadSession(argument);
                if (loaded) {
                    out.println(green("  已加载: ") + dim(conversationManager.sessionStats()));
                } else {
                    out.println(yellow("  未找到匹配的会话。"));
                }
            }
            case "/sessions" -> printSessions(out);
            case "/tools" -> printTools(out);
            case "/mode" -> {
                if (!argument.equals("mock") && !argument.equals("real")) {
                    out.println(yellow("  用法: /mode mock|real"));
                    out.flush();
                    return true;
                }
                config.setMockMode(argument.equals("mock"));
                rebuildRuntime();
                out.println(green("  已切换到 " + argument + " 模式。"));
                if (!config.isMockMode() && config.apiKey().isBlank()) {
                    out.println(yellow("  警告: 未配置 API 密钥。"));
                }
                refreshStatusLine(out);
            }
            case "/stream" -> {
                if (!argument.equals("on") && !argument.equals("off")) {
                    out.println(yellow("  用法: /stream on|off"));
                    out.flush();
                    return true;
                }
                config.setStreamResponses(argument.equals("on"));
                out.println("  流式输出: " + (config.streamResponses() ? green("开启") : dim("关闭")));
                refreshStatusLine(out);
            }
            case "/bash" -> {
                if (!argument.equals("on") && !argument.equals("off")) {
                    out.println(yellow("  用法: /bash on|off"));
                    out.flush();
                    return true;
                }
                config.setBashEnabled(argument.equals("on"));
                rebuildRuntime();
                out.println("  Bash: " + (config.bashEnabled() ? green("已启用") : dim("已禁用")));
                refreshStatusLine(out);
            }
            case "/prompt" -> handlePromptCommand(argument, out);
            case "/approvals" -> {
                if (argument.equals("clear")) {
                    agent.clearApprovalCache();
                    out.println(green("  缓存已清除。"));
                } else {
                    out.println(dim("  缓存: ") + agent.approvalCacheSize() + " 条记录");
                    out.println(dim("  用法: /approvals clear"));
                }
            }
            case "/bypass" -> {
                if (!argument.equals("on") && !argument.equals("off")) {
                    out.println(yellow("  用法: /bypass on|off"));
                    out.flush();
                    return true;
                }
                config.setBypassPermissions(argument.equals("on"));
                out.println("  跳过审批: " + (config.bypassPermissions() ? green("已开启") : dim("已关闭")));
                refreshStatusLine(out);
            }
            case "/status" -> printStatus(out);
            case "/stats" -> {
                out.println(bold("  工具执行统计"));
                out.println(dim("  ─────────────────────────────────────────────────"));
                out.print(agent.toolStats().format());
                out.println(dim("  ─────────────────────────────────────────────────"));
            }
            case "/export" -> {
                String md = conversationManager.exportAsMarkdown();
                String filename = "session-" + conversationManager.currentSessionId().substring(0, 8) + ".md";
                Path exportPath = config.workingDirectory().resolve(filename);
                try {
                    java.nio.file.Files.writeString(exportPath, md, java.nio.charset.StandardCharsets.UTF_8);
                    out.println(green("  已导出: ") + dim(exportPath.toString()));
                } catch (IOException e) {
                    out.println(red("  导出失败: ") + e.getMessage());
                }
            }
            case "/reload" -> {
                try {
                    config.reload();
                    rebuildRuntime();
                    out.println(green("  配置已重载。"));
                    List<String> warnings = config.validate();
                    for (String w : warnings) {
                        out.println(yellow("  ⚠ ") + w);
                    }
                    refreshStatusLine(out);
                } catch (IOException e) {
                    out.println(red("  重载失败: ") + e.getMessage());
                }
            }
            case "/context" -> printContext(out);
            case "/compact" -> {
                Spinner compactSpinner = new Spinner(out, new AtomicBoolean(false), "压缩中...");
                compactSpinner.start();
                try {
                    String result = agent.compact();
                    out.println(green("  ✓ ") + result);
                } catch (Exception e) {
                    out.println(red("  ✗ ") + "压缩失败: " + e.getMessage());
                } finally {
                    compactSpinner.stop();
                }
            }
            case "/effort" -> handleEffortCommand(argument, out);
            default -> {
                out.println(red("  ✗ ") + dim("未知命令。输入 /help 查看可用命令。"));
            }
        }
        out.flush();
        return true;
    }

    private void handlePromptCommand(String argument, PrintWriter out) throws IOException {
        if (argument.isBlank() || argument.equals("show")) {
            if (config.customSystemPrompt().isBlank()) {
                out.println(dim("  未设置自定义系统提示词。"));
            } else {
                out.println(bold("  系统提示词:"));
                out.println("  " + config.customSystemPrompt());
            }
            return;
        }
        if (argument.equals("reset")) {
            config.clearCustomSystemPrompt();
            out.println(green("  系统提示词已清除。"));
            return;
        }
        if (argument.startsWith("set ")) {
            config.setCustomSystemPrompt(argument.substring(4).trim());
            out.println(green("  系统提示词已更新。"));
            return;
        }
        out.println(dim("  用法: /prompt show | /prompt set <text> | /prompt reset"));
    }

    // ─────────────────────── 审批界面 ───────────────────────

    private ApprovalDecision promptApproval(LineReader reader, ToolCall toolCall) {
        PrintWriter out = terminal.writer();

        // Show tool summary above the prompt
        String toolSummary = summarizeToolForApproval(toolCall);
        out.println();
        out.println(bold(yellow("  允许执行?")) + dim(" [Y/n]"));
        out.println(dim("    ") + cyan(toolCall.name()) + dim(" → ") + toolSummary);

        // For edit tool, show diff preview with pipe indentation
        if ("edit".equals(toolCall.name())) {
            printEditPreview(toolCall, out);
        }
        out.flush();

        try {
            String decision = reader.readLine(dim("    "));
            if (decision == null) return ApprovalDecision.CANCELLED;
            ApprovalDecision result = switch (decision.trim().toLowerCase(Locale.ROOT)) {
                case "y", "yes", "" -> ApprovalDecision.APPROVED;
                case "n", "no" -> ApprovalDecision.DENIED;
                default -> ApprovalDecision.CANCELLED;
            };
            switch (result) {
                case APPROVED -> out.println(green("  ✓ 已允许"));
                case DENIED -> out.println(red("  ✗ 已拒绝"));
                case CANCELLED -> out.println(dim("  已取消"));
            }
            out.flush();
            return result;
        } catch (UserInterruptException | EndOfFileException e) {
            return ApprovalDecision.CANCELLED;
        }
    }

    private String summarizeToolForApproval(ToolCall toolCall) {
        Map<String, Object> input = toolCall.input();
        // Show the most relevant argument
        if (input.containsKey("path")) return filePath(input.get("path").toString());
        if (input.containsKey("command")) return filePath(input.get("command").toString());
        if (input.containsKey("pattern")) return filePath(input.get("pattern").toString());
        // Fallback: show first key=value
        for (var entry : input.entrySet()) {
            if (entry.getValue() != null) {
                return dim(entry.getKey() + "=") + truncate(entry.getValue().toString(), 50);
            }
        }
        return dim("...");
    }

    /** Show a colored diff preview for edit tool approval */
    private void printEditPreview(ToolCall toolCall, PrintWriter out) {
        Object oldObj = toolCall.input().get("old_string");
        Object newObj = toolCall.input().get("new_string");
        if (oldObj == null || newObj == null) return;

        String[] oldLines = splitLines(oldObj.toString());
        String[] newLines = splitLines(newObj.toString());

        out.println(dim("    ") + dim("preview:"));
        int maxPreview = 6;
        int shown = 0;
        for (String line : oldLines) {
            if (shown++ >= maxPreview) {
                out.println(dim("    │ ") + dim("... (-" + (oldLines.length - maxPreview) + " more lines)"));
                break;
            }
            out.println(dim("    │ ") + diffRemove(truncate(line, 68)));
        }
        shown = 0;
        for (String line : newLines) {
            if (shown++ >= maxPreview) {
                out.println(dim("    │ ") + dim("... (+" + (newLines.length - maxPreview) + " more lines)"));
                break;
            }
            out.println(dim("    │ ") + diffAdd(truncate(line, 68)));
        }
    }

    /**
     * Show a bordered command menu (Claude Code style).
     * Renders a box with matching commands and descriptions.
     */
    private void showCommandMenu(PrintWriter out, SlashCommandCompleter completer, String typed) {
        String filter = typed.trim().toLowerCase();
        List<SlashCommandCompleter.CommandDef> commands = completer.allCommands().stream()
                .filter(cmd -> cmd.name().startsWith(filter))
                .toList();

        if (commands.isEmpty()) return;

        // Find the longest command name for alignment
        int maxCmdLen = commands.stream()
                .mapToInt(cmd -> cmd.name().length())
                .max().orElse(10);
        int colWidth = maxCmdLen + 4; // padding

        String border = "─".repeat(colWidth + 24);

        String str = "==========================================\r\n" + //
                        "四川农业大学\r\n" + //
                        "信息工程学院\r\n" + //
                        "JavaAgent CLI 课程项目\r\n" + //
                        "作者：莫承潜 黄麟淞 王郅为 黄春云 胡鸿扬\r\n" + //
                        "==========================================";
        out.println();
        out.println(str);
        out.println(dim("  ┌" + border + "┐"));
        for (SlashCommandCompleter.CommandDef cmd : commands) {
            String name = String.format("%-" + colWidth + "s", cmd.name());
            String desc = dim(truncate(cmd.description(), 22));
            out.println(dim("  │ ") + cyan(bold(name)) + desc + dim(" │"));
        }
        out.println(dim("  └" + border + "┘"));
        out.flush();
    }

    // ─────────────────────── 显示方法 ───────────────────────

    private void printSessions(PrintWriter out) throws IOException {
        List<ConversationManager.SessionSummary> sessions = conversationManager.listSessions();
        if (sessions.isEmpty()) {
            out.println(dim("  没有已保存的会话。"));
            return;
        }
        out.println(bold("  会话列表"));
        out.println(dim("  ─────────────────────────────────────────────────"));
        for (ConversationManager.SessionSummary session : sessions) {
            boolean isCurrent = session.id().equals(conversationManager.currentSessionId());
            String marker = isCurrent ? brightGreen(" ● ") : dim("   ");
            String id = isCurrent ? brightCyan(shortId(session.id())) : cyan(shortId(session.id()));
            String title = isCurrent ? bold(session.title()) : session.title();
            String time = dim(SESSION_TIME_FORMAT.format(session.lastUpdated()));
            String msgs = dim(session.messageCount() + " msgs");

            out.println(marker + id + "  " + title + "  " + time + "  " + msgs);
        }
        out.println(dim("  ─────────────────────────────────────────────────"));
    }

    private void printTools(PrintWriter out) {
        List<ToolDefinition> definitions = toolRegistry.definitions();
        if (definitions.isEmpty()) {
            out.println(dim("  没有已注册的工具。"));
            return;
        }
        out.println(bold("  工具 (" + definitions.size() + ")"));
        out.println(dim("  ─────────────────────────────────────────────────"));
        for (ToolDefinition def : definitions) {
            String aliases = def.aliases().isEmpty()
                    ? ""
                    : dim(" [") + dim(String.join(", ", def.aliases())) + dim("]");
            String tag = def.requiresApproval()
                    ? yellow("approval")
                    : green("auto");
            String rw = def.readOnly() ? dim("r/o") : dim("r/w");
            out.println("  " + bold(cyan(String.format("%-16s", def.name()))) + aliases
                    + dim("  [") + tag + dim("] [") + rw + dim("]"));
            out.println("  " + dim("  ") + dim(def.description()));
        }
        out.println(dim("  ─────────────────────────────────────────────────"));
    }

    /** Re-render the compact status line (called after config changes). */
    private void refreshStatusLine(PrintWriter out) {
        BannerPrinter.printStatusLine(config, toolRegistry, out);
    }

    private void printContext(PrintWriter out) {
        var usage = agent.contextUsage();
        String modelName = config.model();
        ContextDisplay.display(out, usage, modelName);

        // Session info
        out.println("    " + dim("会话: ") + cyan(conversationManager.currentSessionTitle()));
        out.println("    " + dim("ID: ") + dim(shortId(conversationManager.currentSessionId())));
        if (usage.usagePercent() >= 0.8) {
            out.println("    " + yellow("⚠ 上下文即将满，建议使用 /clear 开始新会话"));
        }
    }

    private void handleEffortCommand(String argument, PrintWriter out) throws IOException {
        if (argument.isBlank()) {
            String current = config.effort();
            String display = switch (current) {
                case "low" -> dim("low") + dim(" — 简短直接，跳过解释");
                case "medium" -> cyan("medium") + dim(" — 平衡详细度");
                case "high" -> yellow("high") + dim(" — 逐步推理，详细解释");
                case "max" -> red("max") + dim(" — 最大深度推理，考虑所有边界情况");
                default -> dim(current);
            };
            out.println("  当前思考强度: " + display);
            out.println(dim("  用法: /effort low|medium|high|max"));
            out.flush();
            return;
        }
        String level = argument.toLowerCase();
        if (!level.equals("low") && !level.equals("medium") && !level.equals("high") && !level.equals("max")) {
            out.println(yellow("  用法: /effort low|medium|high|max"));
            out.flush();
            return;
        }
        config.setEffort(level);
        String display = switch (level) {
            case "low" -> dim("low") + dim(" — 简短直接");
            case "medium" -> cyan("medium") + dim(" — 平衡详细度");
            case "high" -> yellow("high") + dim(" — 详细推理");
            case "max" -> red("max") + dim(" — 最大深度");
            default -> level;
        };
        out.println("  思考强度已设为: " + display);
        refreshStatusLine(out);
    }

    private void printStatus(PrintWriter out) {
        out.println();
        out.println(bold("  运行状态"));
        out.println(dim("  ─────────────────────────────────────────────────"));
        printRow(out, "模式", config.isMockMode() ? yellow("mock") : green("real"));
        printRow(out, "模型", cyan(config.model()));
        printRow(out, "流式输出", config.streamResponses() ? green("开启") : dim("关闭"));
        printRow(out, "Bash", config.bashEnabled() ? green("已启用") : dim("已禁用"));
        printRow(out, "跳过审批", config.bypassPermissions() ? green("已开启") : dim("已关闭"));
        printRow(out, "审批缓存", agent.approvalCacheSize() + " 条记录");
        String effortDisplay = switch (config.effort()) {
            case "low" -> dim("low");
            case "medium" -> cyan("medium");
            case "high" -> yellow("high");
            case "max" -> red("max");
            default -> dim(config.effort());
        };
        printRow(out, "思考强度", effortDisplay);
        out.println(dim("  ─────────────────────────────────────────────────"));
        printRow(out, "当前会话", cyan(conversationManager.currentSessionTitle()));
        printRow(out, "会话 ID", dim(shortId(conversationManager.currentSessionId())));
        printRow(out, "消息数", String.valueOf(conversationManager.messageCount()));
        out.println(dim("  ─────────────────────────────────────────────────"));
        printRow(out, "工具", toolRegistry.definitions().size() + " 个已注册");
        printRow(out, "配置文件", dim(config.configPath().toString()));
        printRow(out, "会话目录", dim(config.sessionDirectory().toString()));
        out.println();
    }

    private void printRow(PrintWriter out, String label, String value) {
        out.println("    " + dim(String.format("%-16s", label)) + value);
    }

    private void printHelp(PrintWriter out) {
        out.println();
        out.println(bold("  命令列表"));
        out.println(dim("  ─────────────────────────────────────────────────"));
        printCmd(out, "/help", "显示帮助信息");
        printCmd(out, "/exit | /quit", "退出程序");
        printCmd(out, "/clear | /new [title]", "新建会话");
        printCmd(out, "/save [title]", "保存当前会话");
        printCmd(out, "/load [id|title|latest]", "加载已保存的会话");
        printCmd(out, "/sessions", "列出已保存的会话");
        printCmd(out, "/tools", "列出已注册的工具");
        printCmd(out, "/mode mock|real", "切换模型模式");
        printCmd(out, "/stream on|off", "开关流式输出");
        printCmd(out, "/bash on|off", "开关 Bash 工具");
        printCmd(out, "/prompt show|set|reset", "管理系统提示词");
        printCmd(out, "/approvals clear", "清除审批缓存");
        printCmd(out, "/bypass on|off", "跳过所有工具审批确认");
        printCmd(out, "/status", "显示运行状态");
        printCmd(out, "/reload", "重新加载配置文件");
        printCmd(out, "/export", "导出当前对话为 Markdown");
        printCmd(out, "/stats", "查看工具执行统计");
        printCmd(out, "/context", "查看对话上下文使用情况");
        printCmd(out, "/compact", "压缩对话历史（LLM 摘要，节省 token）");
        printCmd(out, "/effort low|medium|high|max", "调节模型思考强度");
        out.println(dim("  ─────────────────────────────────────────────────"));
        out.println();
        out.println(dim("  提示:"));
        out.println(dim("  • 直接输入你的问题 — Agent 会自动使用工具帮你完成。"));
        out.println(dim("  • 只读工具 (read, grep, ls) 无需审批即可运行。"));
        out.println(dim("  • 文件编辑 (edit, write, delete) 需要你确认。"));
        out.println(dim("  • 输入 / 然后按 Enter 查看所有命令。"));
        out.println(dim("  • 输入 /h 然后按 Tab 自动补全。"));
        out.println();
    }

    private void printCmd(PrintWriter out, String cmd, String desc) {
        out.println("    " + cyan(String.format("%-28s", cmd)) + dim(desc));
    }

    private String shortId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return "n/a";
        return sessionId.length() <= 8 ? sessionId : sessionId.substring(0, 8);
    }

    // ─────────────────────── 内部类 ───────────────────────

    /**
     * Braille spinner — animated "⠋ Thinking..." indicator.
     */
    private static final class Spinner {
        private static final String[] FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
        private final AtomicBoolean running = new AtomicBoolean(false);
        private final AtomicBoolean paused;
        private final PrintWriter out;
        private final String text;
        private Thread thread;

        Spinner(PrintWriter out, AtomicBoolean paused) {
            this(out, paused, "思考中...");
        }

        Spinner(PrintWriter out, AtomicBoolean paused, String text) {
            this.out = out;
            this.paused = paused;
            this.text = text;
        }

        void start() {
            running.set(true);
            thread = new Thread(() -> {
                int i = 0;
                while (running.get()) {
                    if (!paused.get()) {
                        out.print("\r  " + cyan(FRAMES[i % FRAMES.length]) + dim(" " + text));
                        out.flush();
                        i++;
                    }
                    try { Thread.sleep(80); } catch (InterruptedException e) { break; }
                }
            }, "spinner");
            thread.setDaemon(true);
            thread.start();
        }

        void stop() {
            running.set(false);
            // braille(2) + space(1) + text(中文×2列宽 + ASCII×1) + margin
            int clearWidth = 2 + 1 + text.length() * 2 + 4;
            out.print("\r" + " ".repeat(clearWidth) + "\r");
            out.flush();
        }
    }

    /**
     * Streaming handler — buffers chunks for Markdown rendering.
     */
    private static final class ConsoleTextStreamHandler implements TextStreamHandler {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onChunk(String chunk) {
            buffer.append(chunk);
        }

        private String buffered() {
            return buffer.toString();
        }
    }

    /**
     * Tool display — bordered blocks with │ pipe indentation.
     *
     * ┌ read_file ─────────────────────────
     * │ src/main/java/Foo.java
     * │ ✓ done
     * │   1 │ package com.example;
     * │   2 │ public class Foo {
     * └─────────────────────────────────────
     */
    private static final class ConsoleToolDisplayCallback implements ToolDisplayCallback {
        private final String border = "─────────────────────────────────────────────";
        private final PrintWriter out;

        ConsoleToolDisplayCallback(PrintWriter out) {
            this.out = out;
        }

        @Override
        public void onToolStart(String toolName, String summary) {
            String summaryText = truncate(summary, 40);
            out.println();
            out.println(dim("  ┌ ") + cyan(bold(toolName)) + dim(" ─ ") + dim(summaryText));
            out.println(dim("  │ ") + dim("执行工具中..."));
            out.flush();
        }

        @Override
        public void onToolEnd(String toolName, boolean success, String resultSummary) {
            onToolEnd(toolName, success, resultSummary, null);
        }

        @Override
        public void onToolEnd(String toolName, boolean success, String resultSummary, String fullContent) {
            // Clear "Running tool..." line
            out.print("\r" + " ".repeat(50) + "\r");

            String icon = success ? green("✓") : red("✗");
            String status = success ? green("完成") : red("错误");
            out.println(dim("  │ ") + icon + " " + status);

            if (success && fullContent != null) {
                printCompactResult(toolName, fullContent);
            } else if (!success && resultSummary != null && !resultSummary.isEmpty()) {
                out.println(dim("  │ ") + red("错误: ") + dim(truncate(resultSummary, 80)));
            }

            out.println(dim("  └─") + border);
            out.flush();
        }

        private void printCompactResult(String toolName, String content) {
            switch (toolName) {
                case "read_file" -> printReadResult(content);
                case "grep" -> printGrepResult(content);
                case "edit" -> printEditResult(content);
                case "write_file" -> printWriteResult(content);
                case "list_directory" -> printListDirResult(content);
                case "bash" -> printBashResult(content);
                default -> {
                    String firstLine = splitLines(content, 2)[0];
                    out.println(dim("  │ ") + dim(truncate(firstLine, 80)));
                }
            }
        }

        private void printReadResult(String content) {
            String[] lines = splitLines(content);
            String summaryLine = "";
            int contentStart = 0;
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].startsWith("Lines:")) summaryLine = lines[i];
                if (lines[i].startsWith("-----")) { contentStart = i + 1; break; }
            }
            if (!summaryLine.isEmpty()) {
                out.println(dim("  │ ") + dim(summaryLine));
            }
            int shown = 0;
            for (int i = contentStart; i < lines.length && shown < 5; i++) {
                String line = lines[i];
                int colonIdx = line.indexOf(": ");
                if (colonIdx > 0) {
                    try {
                        int lineNum = Integer.parseInt(line.substring(0, colonIdx));
                        String code = line.substring(colonIdx + 2);
                        out.println(dim("  │ ") + lineNumber(lineNum, code));
                    } catch (NumberFormatException e) {
                        out.println(dim("  │ ") + dim(line));
                    }
                } else {
                    out.println(dim("  │ ") + dim(line));
                }
                shown++;
            }
            if (lines.length - contentStart > 5) {
                out.println(dim("  │ ") + dim("... (还有 " + (lines.length - contentStart - 5) + " 行)"));
            }
        }

        private void printGrepResult(String content) {
            String[] lines = splitLines(content);
            String summaryLine = "";
            for (int i = lines.length - 1; i >= 0; i--) {
                if (lines[i].contains("files_scanned=")) { summaryLine = lines[i]; break; }
            }
            int shown = 0;
            for (String line : lines) {
                if (line.startsWith("Pattern:") || line.startsWith("Path:") || line.startsWith("-----")
                        || line.contains("files_scanned=")) continue;
                if (line.isBlank()) continue;
                if (shown++ >= 5) {
                    out.println(dim("  │ ") + dim("... (更多匹配)"));
                    break;
                }
                String[] parts = line.split(":", 3);
                if (parts.length >= 3) {
                    out.println(dim("  │ ") + filePath(parts[0])
                            + dim(":") + cyan(parts[1]) + dim(": ") + parts[2]);
                } else {
                    out.println(dim("  │ ") + dim(line));
                }
            }
            if (!summaryLine.isEmpty()) {
                out.println(dim("  │ ") + dim(summaryLine));
            }
        }

        private void printEditResult(String content) {
            out.println(dim("  │ ") + dim(content));
        }

        private void printWriteResult(String content) {
            out.println(dim("  │ ") + dim(content));
        }

        private void printListDirResult(String content) {
            String[] lines = splitLines(content);
            int shown = 0;
            for (String line : lines) {
                if (line.startsWith("Directory:") || line.startsWith("-----") || line.contains("entries")) continue;
                if (shown++ >= 8) {
                    out.println(dim("  │ ") + dim("... (更多条目)"));
                    break;
                }
                if (line.contains("[D]")) {
                    out.println(dim("  │ ") + blue(line));
                } else {
                    out.println(dim("  │ ") + dim(line));
                }
            }
            for (String line : lines) {
                if (line.contains("entries")) {
                    out.println(dim("  │ ") + dim(line));
                    break;
                }
            }
        }

        private void printBashResult(String content) {
            String[] lines = splitLines(content);
            int shown = 0;
            for (String line : lines) {
                if (shown++ >= 6) {
                    out.println(dim("  │ ") + dim("... (更多输出)"));
                    break;
                }
                if (line.startsWith("$ ")) {
                    out.println(dim("  │ ") + cyan(line));
                } else if (line.startsWith("[exit=")) {
                    out.println(dim("  │ ") + dim(line));
                } else {
                    out.println(dim("  │ ") + dim(line));
                }
            }
        }
    }
}
