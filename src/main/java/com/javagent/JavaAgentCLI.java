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
 * JavaAgent CLI —— 主入口。
 *
 * Claude Code 风格的交互式 REPL，支持 ANSI 颜色、
 * 工具执行指示器、SSE 流式输出和编辑工具。
 * 使用 JLine3 实现行编辑、历史记录和斜杠命令自动补全。
 */
public class JavaAgentCLI {
    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(JavaAgentCLI.class.getName());
    private static final DateTimeFormatter SESSION_TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final String VERSION = "1.0.0";

    private Config config;
    private ConversationManager conversationManager;
    private ToolRegistry toolRegistry;
    private ModelClient modelClient;
    private Agent agent;
    private Terminal terminal;
    private final AtomicBoolean awaitingApproval = new AtomicBoolean(false);
    /** 当前对话轮次的打断标志，由 Ctrl+C 信号处理器设置。 */
    private volatile AtomicBoolean currentCancelFlag = new AtomicBoolean(false);

    public static void main(String[] args) throws Exception {
        // 在 JLine 类加载前抑制警告。
        // 通过 LogManager 配置，确保在 Logger 创建前设置日志级别。
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
                .nativeSignals(true)
                .signalHandler(Terminal.SignalHandler.SIG_IGN)
                .build();

        // 注册 Ctrl+C 信号处理器 —— 拦截信号，不退出程序，只设置打断标志
        terminal.handle(Terminal.Signal.INT, signal -> {
            AtomicBoolean flag = currentCancelFlag;
            if (flag != null) {
                flag.set(true);
            }
        });

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

        // 用户输入 '/' 时，在下方显示补全列表但不自动插入
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

        // 优雅关闭: Ctrl+C 或 SIGTERM 时保存会话
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

            // 共享的"流式输出进行中"标记，用于暂停 spinner
            AtomicBoolean streamingActive = new AtomicBoolean(false);
            // 共享的"本轮流刚结束且有正文输出"标记，用于工具框避免双空行
            AtomicBoolean streamEndedWithContent = new AtomicBoolean(false);
            // 打断标志（Ctrl+C 信号处理器会设置此标志）
            currentCancelFlag = new AtomicBoolean(false);
            AtomicBoolean cancelFlag = currentCancelFlag;

            // 启动盲文旋转动画
            Spinner spinner = new Spinner(out, awaitingApproval, streamingActive);
            spinner.start();

            ConsoleTextStreamHandler streamHandler = new ConsoleTextStreamHandler(out, streamingActive, spinner, streamEndedWithContent, config.showThinking());
            ConsoleToolDisplayCallback displayCallback = new ConsoleToolDisplayCallback(out, streamEndedWithContent);

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
                    displayCallback,
                    cancelFlag
            );

            spinner.stop();
            if (cancelFlag.get()) {
                out.println(yellow("  ⏹ ") + dim("已打断本轮回答。"));
                out.flush();
            }

            // 如果流式未启用（buffer 为空），用 Markdown 渲染最终响应
            // 如果流式已启用，文本已在 onChunk 中实时输出
            if (streamHandler.isEmpty()) {
                if (!response.isEmpty()) {
                    out.print(MarkdownRenderer.render(response));
                }
            } else {
                // 流式输出时文本已实时打印，末尾可能缺换行，补一个
                out.println();
            }
            out.flush();

            // token 使用超过阈值时自动压缩
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
        c.register("/thinking", "开关思考内容显示");
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
                out.println(green("  已保存: ") + dim(conversationManager.sessionStats()));
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
            case "/thinking" -> {
                if (!argument.equals("on") && !argument.equals("off")) {
                    out.println(yellow("  用法: /thinking on|off"));
                    out.flush();
                    return true;
                }
                config.setShowThinking(argument.equals("on"));
                out.println("  思考显示: " + (config.showThinking() ? green("开启") : dim("关闭")));
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

        // 在提示上方显示工具摘要
        String toolSummary = summarizeToolForApproval(toolCall);
        out.println();
        out.println(bold(yellow("  允许执行?")) + dim(" [Y/n]"));
        out.println(dim("    ") + cyan(toolCall.name()) + dim(" → ") + toolSummary);

        // 对编辑工具，显示带管道缩进的 diff 预览
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
        // 显示最相关的参数
        if (input.containsKey("path")) return filePath(input.get("path").toString());
        if (input.containsKey("command")) return filePath(input.get("command").toString());
        if (input.containsKey("pattern")) return filePath(input.get("pattern").toString());
        // 回退: 显示第一个 key=value
        for (var entry : input.entrySet()) {
            if (entry.getValue() != null) {
                return dim(entry.getKey() + "=") + truncate(entry.getValue().toString(), 50);
            }
        }
        return dim("...");
    }

    /** 为编辑工具审批显示彩色 diff 预览 */
    private void printEditPreview(ToolCall toolCall, PrintWriter out) {
        Object oldObj = toolCall.input().get("old_string");
        Object newObj = toolCall.input().get("new_string");
        if (oldObj == null || newObj == null) return;

        String[] oldLines = splitLines(oldObj.toString());
        String[] newLines = splitLines(newObj.toString());

        out.println(dim("    ") + dim("预览:"));
        int maxPreview = 6;
        int shown = 0;
        for (String line : oldLines) {
            if (shown++ >= maxPreview) {
                out.println(dim("    │ ") + dim("... (-" + (oldLines.length - maxPreview) + " 行)"));
                break;
            }
            out.println(dim("    │ ") + diffRemove(truncate(line, 68)));
        }
        shown = 0;
        for (String line : newLines) {
            if (shown++ >= maxPreview) {
                out.println(dim("    │ ") + dim("... (+" + (newLines.length - maxPreview) + " 行)"));
                break;
            }
            out.println(dim("    │ ") + diffAdd(truncate(line, 68)));
        }
    }

    /**
     * 显示带边框的命令菜单（Claude Code 风格）。
     * 渲染包含匹配命令和描述的方框。
     */
    private void showCommandMenu(PrintWriter out, SlashCommandCompleter completer, String typed) {
        String filter = typed.trim().toLowerCase();
        List<SlashCommandCompleter.CommandDef> commands = completer.allCommands().stream()
                .filter(cmd -> cmd.name().startsWith(filter))
                .toList();

        if (commands.isEmpty()) return;

        // 找出最长命令名以对齐
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
            String msgs = dim(session.messageCount() + " 条消息");

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

    /** 重新渲染紧凑状态行（配置变更后调用）。 */
    private void refreshStatusLine(PrintWriter out) {
        BannerPrinter.printStatusLine(config, toolRegistry, out);
    }

    private void printContext(PrintWriter out) {
        var usage = agent.contextUsage();
        String modelName = config.model();
        ContextDisplay.display(out, usage, modelName);

        // 会话信息
        out.println("    " + dim("会话: ") + cyan(conversationManager.currentSessionTitle()));
        out.println("    " + dim("ID: ") + dim(shortId(conversationManager.currentSessionId())));
        if (usage.usagePercent() >= 0.8) {
            out.println("    " + yellow("⚠ 上下文即将满，建议使用 /clear 开始新会话"));
        }
    }

    private static final String[] EFFORT_LEVELS = {"low", "high", "xhigh", "max", "ultra"};
    private static final String[] EFFORT_DESC = {"简短直接，跳过解释", "逐步推理，详细解释", "深度分析，多角度探索", "最大深度，考虑所有边界", "极限推理，穷举一切可能"};
    // 进度条布局: "Speed ════════════════════════════════════════ Intelligence"
    //              ^4    ^15       ^27       ^39       ^51
    // 标签放置在固定位置以对齐到进度条下方
    private static final int BAR_WIDTH = 57;
    private static final int[] LEVEL_POS = {4, 15, 27, 39, 51};

    private void handleEffortCommand(String argument, PrintWriter out) throws IOException {
        // 直接参数模式: /effort <level>
        if (!argument.isBlank()) {
            String level = argument.toLowerCase();
            if (!Config.isValidEffort(level)) {
                out.println(yellow("  用法: /effort [low|high|xhigh|max|ultra]"));
                out.flush();
                return;
            }
            config.setEffort(level);
            printEffortSet(out, level);
            refreshStatusLine(out);
            return;
        }

        // 交互式选择器模式: /effort（无参数）
        int selected = java.util.Arrays.asList(EFFORT_LEVELS).indexOf(config.effort());
        if (selected < 0) selected = 1; // default to "high"

        out.println();
        printEffortBar(out, selected);
        out.flush();

        // 进入原始模式以支持方向键导航
        org.jline.terminal.Attributes savedAttrs = terminal.enterRawMode();
        try {
            java.io.Reader reader = terminal.reader();
            while (true) {
                int ch = reader.read();
                if (ch == -1) break;

                if (ch == 27) { // ESC 序列
                    int next = reader.read();
                    if (next == -1) break;
                    if (next == '[') {
                        int arrow = reader.read();
                        if (arrow == 'D') { // 左
                            selected = (selected - 1 + EFFORT_LEVELS.length) % EFFORT_LEVELS.length;
                        } else if (arrow == 'C') { // 右
                            selected = (selected + 1) % EFFORT_LEVELS.length;
                        }
                    } else {
                        break; // 单独 ESC —— 取消
                    }
                } else if (ch == 'h' || ch == 'H') { // vim left
                    selected = (selected - 1 + EFFORT_LEVELS.length) % EFFORT_LEVELS.length;
                } else if (ch == 'l' || ch == 'L') { // vim right
                    selected = (selected + 1) % EFFORT_LEVELS.length;
                } else if (ch == '\n' || ch == '\r') { // Enter —— 确认
                    String level = EFFORT_LEVELS[selected];
                    config.setEffort(level);
                    clearBarLines(out, 5);
                    if ("ultra".equals(level)) {
                        printNeonConfirm(out);
                    } else {
                        printEffortSet(out, level);
                    }
                    refreshStatusLine(out);
                    return;
                } else if (ch == 'q' || ch == 'Q' || ch == 3 || ch == 4) { // q/Ctrl-C/Ctrl-D
                    break;
                } else if (ch >= '1' && ch <= '5') { // number shortcut
                    selected = ch - '1';
                    String level = EFFORT_LEVELS[selected];
                    config.setEffort(level);
                    clearBarLines(out, 5);
                    if ("ultra".equals(level)) {
                        printNeonConfirm(out);
                    } else {
                        printEffortSet(out, level);
                    }
                    refreshStatusLine(out);
                    return;
                }

                // 重新渲染进度条
                clearBarLines(out, 5);
                printEffortBar(out, selected);
                out.flush();
            }
        } finally {
            terminal.setAttributes(savedAttrs);
        }
    }

    /** 渲染带标记的水平推理深度条,超级酷😄。 */
    private void printEffortBar(PrintWriter out, int selected) {
        // 第 1 行: 左侧 "Speed"，右侧 "Intelligence"
        String header = "  " + dim("Speed")
                + " ".repeat(Math.max(0, BAR_WIDTH - 5 - 11))
                + dim("Intelligence");
        out.println(header);

        // 第 2 行: ════════════════════════════════════════▲══
        int markerPos = LEVEL_POS[selected];
        StringBuilder bar = new StringBuilder("  ");
        for (int i = 0; i < BAR_WIDTH; i++) {
            if (i == markerPos) {
                bar.append(bold(cyan("▲")));
            } else {
                bar.append(dim("═"));
            }
        }
        out.println(bar.toString());

        // 第 3 行: 级别标签对齐到位置（支持 CJK 宽字符）
        StringBuilder labels = new StringBuilder("  ");
        int col = 2;
        for (int i = 0; i < EFFORT_LEVELS.length; i++) {
            int pos = LEVEL_POS[i];
            while (col < pos) { labels.append(' '); col++; }
            String name = EFFORT_LEVELS[i];
            if (i == selected) {
                if ("ultra".equals(name)) labels.append(neon(name, 0));
                else labels.append(bold(formatEffortColor(name, true)));
            } else {
                labels.append(formatEffortColor(name, false));
            }
            col += displayWidth(name);
        }
        out.println(labels.toString());

        // 第 4 行: 选中级别的描述
        String desc = EFFORT_DESC[selected];
        String levelColor = switch (EFFORT_LEVELS[selected]) {
            case "low" -> dim("low");
            case "high" -> yellow("high");
            case "xhigh" -> magenta("xhigh");
            case "max" -> red("max");
            case "ultra" -> neon("ultra", 0);
            default -> EFFORT_LEVELS[selected];
        };
        out.println("  " + bold(levelColor) + dim(" — " + desc));

        // 第 5 行: 提示
        out.println("  " + dim("← → 选择  1-5 快选  Enter 确认  Esc 取消"));
    }

    /** 获取彩色推理深度名称（不含粗体）。 */
    private String formatEffortColor(String level, boolean active) {
        return switch (level) {
            case "low" -> dim("low");
            case "high" -> active ? yellow("high") : dim("high");
            case "xhigh" -> active ? magenta("xhigh") : dim("xhigh");
            case "max" -> active ? red("max") : dim("max");
            case "ultra" -> active ? neon("ultra", 0) : dim("ultra");
            default -> level;
        };
    }

    /** 打印 ultra 选择的霓虹确认动画。 */
    private void printNeonConfirm(PrintWriter out) {
        String label = "⚡⚡⚡ ULTRA 模式 ⚡⚡⚡";
        for (int frame = 0; frame < 40; frame++) {
            out.print("\r  思考强度已设为: " + neonGlow(label, frame));
            out.flush();
            try { Thread.sleep(80); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        out.println("\r  思考强度已设为: " + neon(label, 0) + dim("  — 极限推理，穷举一切可能!"));
        out.flush();
    }

    /** 清除光标上方 N 行以重新渲染进度条。 */
    private void clearBarLines(PrintWriter out, int lines) {
        for (int i = 0; i < lines; i++) {
            out.print("\033[1A\033[2K");
        }
        out.flush();
    }

    /** 打印非 ultra 级别的推理深度设置确认。 */
    private void printEffortSet(PrintWriter out, String level) {
        String display = switch (level) {
            case "low" -> dim("low") + dim(" — 简短直接");
            case "high" -> yellow("high") + dim(" — 详细推理");
            case "xhigh" -> magenta("xhigh") + dim(" — 深度分析");
            case "max" -> red("max") + dim(" — 最大深度");
            case "ultra" -> neon("ultra", 0) + dim(" — 极限推理");
            default -> level;
        };
        out.println("  思考强度已设为: " + display);
    }

    private void printStatus(PrintWriter out) {
        out.println();
        out.println(bold("  运行状态"));
        out.println(dim("  ─────────────────────────────────────────────────"));
        printRow(out, "模式", config.isMockMode() ? yellow("mock") : green("real"));
        printRow(out, "模型", cyan(config.model()));
        printRow(out, "流式输出", config.streamResponses() ? green("开启") : dim("关闭"));
        printRow(out, "思考显示", config.showThinking() ? green("开启") : dim("关闭"));
        printRow(out, "Bash", config.bashEnabled() ? green("已启用") : dim("已禁用"));
        printRow(out, "跳过审批", config.bypassPermissions() ? green("已开启") : dim("已关闭"));
        printRow(out, "审批缓存", agent.approvalCacheSize() + " 条记录");
        String effortDisplay = switch (config.effort()) {
            case "low" -> dim("low");
            case "high" -> yellow("high");
            case "xhigh" -> magenta("xhigh");
            case "max" -> red("max");
            case "ultra" -> neon("ultra", 0);
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
        printCmd(out, "/thinking on|off", "开关思考内容显示");
        printCmd(out, "/approvals clear", "清除审批缓存");
        printCmd(out, "/bypass on|off", "跳过所有工具审批确认");
        printCmd(out, "/status", "显示运行状态");
        printCmd(out, "/reload", "重新加载配置文件");
        printCmd(out, "/export", "导出当前对话为 Markdown");
        printCmd(out, "/stats", "查看工具执行统计");
        printCmd(out, "/context", "查看对话上下文使用情况");
        printCmd(out, "/compact", "压缩对话历史（LLM 摘要，节省 token）");
        printCmd(out, "/effort [low|high|xhigh|max|ultra]", "调节模型思考强度");
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
        if (sessionId == null || sessionId.isBlank()) return "无";
        return sessionId.length() <= 8 ? sessionId : sessionId.substring(0, 8);
    }

    // ─────────────────────── 内部类 ───────────────────────

    /**
     * 盲文旋转动画 —— 动画 "⠋ 思考中..." 指示器。
     * {@code paused} 用于审批等待；{@code streamingActive} 用于流式输出期间暂停，
     * 避免 spinner 的 \r 覆盖正在实时输出的文本。
     */
    private static final class Spinner {
        private static final String[] FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
        private final AtomicBoolean running = new AtomicBoolean(false);
        private final AtomicBoolean paused;
        private final AtomicBoolean streamingActive;
        private final PrintWriter out;
        private final String text;
        private Thread thread;

        Spinner(PrintWriter out, AtomicBoolean paused) {
            this(out, paused, null, "思考中...");
        }

        Spinner(PrintWriter out, AtomicBoolean paused, AtomicBoolean streamingActive) {
            this(out, paused, streamingActive, "思考中...");
        }

        Spinner(PrintWriter out, AtomicBoolean paused, String text) {
            this(out, paused, null, text);
        }

        Spinner(PrintWriter out, AtomicBoolean paused, AtomicBoolean streamingActive, String text) {
            this.out = out;
            this.paused = paused;
            this.streamingActive = streamingActive;
            this.text = text;
        }

        void start() {
            running.set(true);
            thread = new Thread(() -> {
                int i = 0;
                while (running.get()) {
                    if (!paused.get() && (streamingActive == null || !streamingActive.get())) {
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
            clearLine();
        }

        /** 清除 spinner 所在行（不停止线程），供流式输出开始时调用。 */
        void clearLine() {
            // braille(2) + space(1) + text(中文×2列宽 + ASCII×1) + margin
            int clearWidth = 2 + 1 + text.length() * 2 + 4;
            out.print("\r" + " ".repeat(clearWidth) + "\r");
            out.flush();
        }
    }

    /**
     * ESC 键打断监听 —— 用 NonBlockingReader 带超时读取，不 enterRawMode。
     * 不改变终端属性（raw mode 由 JLine LineReader 管理），
     * 不会干扰输入流，不会导致下一轮 readLine 异常。
     */
    // Ctrl+C 打断由 terminal.handle(Signal.INT, ...) 在 startCli 中统一注册，
    // 不再需要独立的 EscKeyListener 线程。

    /**
     * 流式处理器 —— 对正文做增量 Markdown 渲染（业界标准行缓冲做法）。
     *
     * 把 SSE 到达的 chunk 累积到行缓冲，遇到完整行才处理：
     * - 普通行（标题/粗体/行内代码/列表/引用/水平线/链接）完整后立即渲染输出；
     * - 代码块（``` 围栏）和表格（| 开头）需要完整上下文，收集完整后再整体渲染；
     * - 半行（未遇换行）静默累积，等其他 chunk 补全后再渲染。
     * 不逐字回显、不 \r 清除重绘，从根本上避免文本重叠。
     *
     * 思考内容（reasoning_content）不经过 Markdown 渲染，以暗淡斜体直出。
     */
    private static final class ConsoleTextStreamHandler implements TextStreamHandler {
        private final StringBuilder buffer = new StringBuilder();
        private final PrintWriter out;
        private final AtomicBoolean streamingActive;
        private final Spinner spinner;
        private final AtomicBoolean streamEndedWithContent;
        private final boolean showThinking;
        /** 正在累积、尚未遇到换行的文本（用于逐行渲染） */
        private final StringBuilder currentLine = new StringBuilder();
        /** 代码块累积（进入 ``` 围栏后收集，直到闭合围栏） */
        private StringBuilder codeBlockBuffer;
        private String codeBlockLang;
        /** 表格累积（遇到 | 开头行后收集，直到非表格行） */
        private StringBuilder tableBuffer;
        /** 本次流内是否输出过任何内容（决定流结束时是否补换行） */
        private boolean chunkReceivedThisStream = false;
        /** 本次流内是否已经输出过思考内容（正文开始前需要补换行分隔） */
        private boolean reasoningPrinted = false;

        ConsoleTextStreamHandler(PrintWriter out, AtomicBoolean streamingActive, Spinner spinner,
                                 AtomicBoolean streamEndedWithContent, boolean showThinking) {
            this.out = out;
            this.streamingActive = streamingActive;
            this.spinner = spinner;
            this.streamEndedWithContent = streamEndedWithContent;
            this.showThinking = showThinking;
        }

        @Override
        public void onStreamStart() {
            streamingActive.set(true);
            // 先清掉 spinner 正在刷新的 \r 行，避免流式文本黏在"思考中..."后面
            spinner.clearLine();
            chunkReceivedThisStream = false;
            reasoningPrinted = false;
            currentLine.setLength(0);
        }

        @Override
        public void onChunk(String chunk) {
            // 正文开始前，如果前面输出过思考内容，先补两个换行分隔，
            // 避免回答直接贴着思考内容显示
            if (reasoningPrinted) {
                reasoningPrinted = false;
                buffer.append("\n\n");
                out.println();
                out.println();
            }
            buffer.append(chunk);
            chunkReceivedThisStream = true;

            // 增量处理：把 chunk 按行拆分，完整的行交给渲染逻辑。
            // 未换行的剩余部分留在 currentLine 中静默累积，
            // 等其他 chunk 补全后再渲染（业界标准的行缓冲做法，
            // 避免"回显原文再 \r 清除重绘"带来的文本重叠问题）。
            currentLine.append(chunk);
            String pending = currentLine.toString();
            int newlinePos = pending.indexOf('\n');
            while (newlinePos >= 0) {
                String line = pending.substring(0, newlinePos);
                pending = pending.substring(newlinePos + 1);
                handleCompleteLine(line);
                newlinePos = pending.indexOf('\n');
            }
            currentLine.setLength(0);
            currentLine.append(pending);
            out.flush();
        }

        private void handleCompleteLine(String line) {
            // 代码块：处理围栏，收集块内容
            if (line.stripLeading().startsWith("```")) {
                if (codeBlockBuffer == null) {
                    // 围栏开始 —— 之后的行收集到 codeBlockBuffer
                    codeBlockBuffer = new StringBuilder();
                    codeBlockLang = line.trim().substring(3).trim();
                    return;
                }
                // 围栏闭合 —— 整体渲染代码块
                String code = codeBlockBuffer.toString();
                codeBlockBuffer = null;
                // 用 MarkdownRenderer 整体渲染（行号+语法高亮）
                out.print(MarkdownRenderer.render("```" + codeBlockLang + "\n" + code + "\n```\n"));
                return;
            }
            if (codeBlockBuffer != null) {
                // 代码块内部 —— 累积，不渲染（等闭合围栏）
                codeBlockBuffer.append(line).append('\n');
                return;
            }

            // 表格：| 开头行——累积表格，遇到非表格行/空行时整体渲染
            if (looksLikeTableLine(line)) {
                if (tableBuffer == null) {
                    tableBuffer = new StringBuilder();
                }
                tableBuffer.append(line).append('\n');
                // 下一行若不是表格行，handleCompleteLine 会在 else 分支刷新表格
                return;
            }
            if (tableBuffer != null) {
                // 表格结束 —— 整体渲染已收集的表格
                flushTable();
            }

            // 普通行（或空行）—— 立即渲染。
            // 注意不要传尾随 \n：render() 内部 splitLines 会把尾随空串当空行，
            // 若再传 "\n" 会每个输出行多出一个空行（行距翻倍）。
            if (line.isEmpty()) {
                out.print("\n");
            } else {
                out.print(MarkdownRenderer.render(line));
            }
        }

        private boolean looksLikeTableLine(String line) {
            // 表格行: 以 | 开头且含至少两个单元格，或 | 结尾
            String trimmed = line.trim();
            if (!trimmed.contains("|")) return false;
            if (!trimmed.startsWith("|") && !trimmed.endsWith("|")) return false;
            // 排除代码、链接等极端情况
            int pipes = (int) trimmed.chars().filter(c -> c == '|').count();
            return pipes >= 2;
        }

        private void flushTable() {
            if (tableBuffer == null) return;
            String table = tableBuffer.toString();
            tableBuffer = null;
            out.print(MarkdownRenderer.render(table));
        }

        @Override
        public void onReasoningChunk(String reasoningChunk) {
            // 也写入 buffer，使 isEmpty() 正确反映"流式已启用"状态，
            // 避免最终回退到 Markdown 渲染导致重复显示
            buffer.append(reasoningChunk);
            chunkReceivedThisStream = true;

            if (!showThinking) {
                // 关闭思考显示时仅缓冲不输出（内容仍会回传给模型）
                return;
            }
            // 推理内容以暗淡+斜体实时显示（Claude Code 风格）
            reasoningPrinted = true;
            out.print(colorize(DIM + ITALIC, reasoningChunk));
            out.flush();
        }

        @Override
        public void onStreamEnd() {
            // 刷新残留：未换行的末行、未闭合的表格
            if (currentLine.length() > 0) {
                String line = currentLine.toString();
                currentLine.setLength(0);
                if (tableBuffer != null && looksLikeTableLine(line)) {
                    tableBuffer.append(line).append('\n');
                    flushTable();
                } else {
                    if (tableBuffer != null) flushTable();
                    handleCompleteLine(line);
                }
            } else if (tableBuffer != null) {
                flushTable();
            }
            // 未闭合的代码块：原样输出已收集内容
            if (codeBlockBuffer != null) {
                out.print(MarkdownRenderer.render("```" + codeBlockLang + "\n" + codeBlockBuffer + "\n```\n"));
                codeBlockBuffer = null;
            }

            streamEndedWithContent.set(chunkReceivedThisStream);
            if (chunkReceivedThisStream) {
                // 有内容输出的流结束时补一个换行，让后续内容另起一行
                out.println();
                out.flush();
            }
            streamingActive.set(false);
        }

        private boolean isEmpty() {
            return buffer.length() == 0;
        }
    }

    /**
     * 工具显示 —— 带 │ 管道缩进的边框块。
     *
     * ┌ read_file ─────────────────────────
     * │ src/main/java/Foo.java
     * │ ✓ 完成
     * │   1 │ package com.example;
     * │   2 │ public class Foo {
     * └─────────────────────────────────────
     */
    private static final class ConsoleToolDisplayCallback implements ToolDisplayCallback {
        private final String border = "─────────────────────────────────────────────";
        private final PrintWriter out;
        private final AtomicBoolean streamEndedWithContent;

        ConsoleToolDisplayCallback(PrintWriter out, AtomicBoolean streamEndedWithContent) {
            this.out = out;
            this.streamEndedWithContent = streamEndedWithContent;
        }

        @Override
        public void onToolStart(String toolName, String summary) {
            String summaryText = truncate(summary, 40);
            // 本轮流刚结束且正文已另起一行时，不再加空行，避免双空行
            if (!streamEndedWithContent.getAndSet(false)) {
                out.println();
            }
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
            // 清除"正在运行工具..."行
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
