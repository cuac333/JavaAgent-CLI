package com.javagent.core;

import com.javagent.model.Message;
import com.javagent.util.Sanitizer;
import com.javagent.util.TokenCounter;
import com.javagent.model.ModelClient;
import com.javagent.model.ModelResponse;
import com.javagent.model.TextStreamHandler;
import com.javagent.model.ToolCall;
import com.javagent.model.ToolDisplayCallback;
import com.javagent.tools.Tool;
import com.javagent.tools.ToolDefinition;
import com.javagent.tools.ToolExecutionResult;
import com.javagent.tools.ToolRegistry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

//todo 这是智能体的核心
public class Agent {
    private static final Logger LOG = Logger.getLogger(Agent.class.getName());
    private final Config config;
    private final ModelClient modelClient;
    private final ToolRegistry toolRegistry;
    private final ConversationManager conversationManager;
    private final ApprovalManager approvalManager;
    private final ToolStats toolStats = new ToolStats();

    // 工具定义的预计算 token 数量（静态，只计算一次）
    private final int cachedToolTokens;

    public Agent(Config config, ModelClient modelClient, ToolRegistry toolRegistry, ConversationManager conversationManager) {
        this.config = config;
        this.modelClient = modelClient;
        this.toolRegistry = toolRegistry;
        this.conversationManager = conversationManager;
        this.approvalManager = new ApprovalManager(config);
        // 预计算工具定义的 token 数量（工具是静态的）
        this.cachedToolTokens = TokenCounter.countTokens(buildToolsSection(toolRegistry.definitions()));
    }

    public ToolStats toolStats() {
        return toolStats;
    }

    public String processTurn(String userInput, ApprovalHandler approvalHandler) {
        return processTurn(userInput, approvalHandler, null, null);
    }

    public String processTurn(String userInput, ApprovalHandler approvalHandler, TextStreamHandler streamHandler) {
        return processTurn(userInput, approvalHandler, streamHandler, null);
    }

    /**
     * 处理一个对话轮次 —— Agent 主循环。
     *
     * @param userInput        用户的文本输入
     * @param approvalHandler  工具审批回调（需要确认时调用）
     * @param streamHandler    流式文本回调（可为 null）
     * @param displayCallback  工具执行显示回调（可为 null）
     * @return Agent 的最终文本回复
     */
    public String processTurn(String userInput, ApprovalHandler approvalHandler,
                               TextStreamHandler streamHandler, ToolDisplayCallback displayCallback) {
        return processTurn(userInput, approvalHandler, streamHandler, displayCallback, null);
    }

    /**
     * 处理一个对话轮次（支持 ESC 打断）。
     *
     * @param userInput        用户的文本输入
     * @param approvalHandler  工具审批回调（需要确认时调用）
     * @param streamHandler    流式文本回调（可为 null）
     * @param displayCallback  工具执行显示回调（可为 null）
     * @param cancelFlag       打断标志（可为 null；置为 true 时 Agent 尽快停止）
     * @return Agent 的最终文本回复
     */
    public String processTurn(String userInput, ApprovalHandler approvalHandler,
                               TextStreamHandler streamHandler, ToolDisplayCallback displayCallback,
                               AtomicBoolean cancelFlag) {
        conversationManager.addUserMessage(userInput);
        String systemPrompt = buildSystemPrompt(toolRegistry.definitions());

        int consecutiveFailures = 0;
        String lastFailedTool = null;
        for (int iteration = 0; iteration < config.maxIterations(); iteration++) {
            if (cancelFlag != null && cancelFlag.get()) {
                return finishInterrupted("已被打断。");
            }
            TextStreamHandler effectiveStreamHandler = config.streamResponses() ? streamHandler : null;
            modelClient.setCancelFlag(cancelFlag);
            ModelResponse response = modelClient.chat(
                    systemPrompt,
                    conversationManager.currentContext(),
                    toolRegistry.definitions(),
                    effectiveStreamHandler
            );
            if (cancelFlag != null && cancelFlag.get()) {
                return finishInterrupted("已被打断。");
            }

            // 当 API 因缺少 reasoning_content 而拒绝时，用新上下文重试
            // （旧对话消息缺少思维模型的推理数据时会发生这种情况）
            if (response.isError() && response.errorMessage().contains("reasoning_content")) {
                List<Message> freshContext = List.of(
                        Message.user(userInput)
                );
                modelClient.setCancelFlag(cancelFlag);
                response = modelClient.chat(
                        systemPrompt,
                        freshContext,
                        toolRegistry.definitions(),
                        effectiveStreamHandler
                );
                if (!response.isError()) {
                    // 清除旧历史，重新开始
                    conversationManager.startNewSession(null);
                    conversationManager.addUserMessage(userInput);
                }
            }

            if (response.isError()) {
                String errorText = "Agent 错误: " + response.errorMessage();
                conversationManager.addAssistantMessage(errorText);
                autoSaveQuietly();
                return errorText;
            }

            if (response.isText()) {
                conversationManager.addAssistantMessage(response.content(), response.reasoningContent());
                autoSaveQuietly();
                return response.content();
            }

            conversationManager.addAssistantToolCallMessage(response.content(), response.toolCalls(), response.reasoningContent());
            for (ToolCall toolCall : response.toolCalls()) {
                // 打断检查：ESC 后不再执行新工具
                if (cancelFlag != null && cancelFlag.get()) {
                    return finishInterrupted("已被打断。");
                }
                // 通知 UI 工具开始执行
                if (displayCallback != null) {
                    displayCallback.onToolStart(toolCall.name(), summarizeToolCall(toolCall));
                }

                ToolExecutionResult result = executeToolCall(toolCall, approvalHandler);
                if (cancelFlag != null && cancelFlag.get()) {
                    return finishInterrupted("已被打断。");
                }
                // 追踪连续失败以打破无限循环
                if (result.error()) {
                    if (toolCall.name().equals(lastFailedTool)) {
                        consecutiveFailures++;
                    } else {
                        lastFailedTool = toolCall.name();
                        consecutiveFailures = 1;
                    }
                    if (consecutiveFailures >= 3) {
                        String stuckText = "Agent 已停止: 工具 '" + toolCall.name() + "' 连续失败 3 次。"
                                + "最后一次错误: " + truncateResult(result.content());
                        conversationManager.addToolResultMessage(toolCall.id(), toolCall.name(),
                                Sanitizer.sanitize(result.content()), true);
                        conversationManager.addAssistantMessage(stuckText);
                        autoSaveQuietly();
                        return stuckText;
                    }
                } else {
                    consecutiveFailures = 0;
                    lastFailedTool = null;
                }
                // 在存储前对工具结果进行脱敏，防止 API Key 泄漏到会话文件中
                String sanitizedContent = Sanitizer.sanitize(result.content());
                conversationManager.addToolResultMessage(toolCall.id(), toolCall.name(), sanitizedContent, result.error());

                // 通知 UI 工具执行完成
                if (displayCallback != null) {
                    displayCallback.onToolEnd(toolCall.name(), !result.error(),
                            truncateResult(result.content()), result.content());
                }
            }
        }

        String limitText = "Agent 已停止: 达到最大工具调用次数上限（" + config.maxIterations() + "）。";
        conversationManager.addAssistantMessage(limitText);
        autoSaveQuietly();
        return limitText;
    }

    /** 用户打断（ESC）时的收尾：记录打断消息并返回。 */
    private String finishInterrupted(String message) {
        conversationManager.addAssistantMessage(message);
        autoSaveQuietly();
        return message;
    }

    private ToolExecutionResult executeToolCall(ToolCall toolCall, ApprovalHandler approvalHandler) {
        Tool tool = toolRegistry.find(toolCall.name()).orElse(null);
        if (tool == null) {
            return ToolExecutionResult.error("工具未找到: " + toolCall.name());
        }

        ApprovalOutcome approvalOutcome = approvalManager.authorize(tool, toolCall, approvalHandler);
        if (!approvalOutcome.approved()) {
            return ToolExecutionResult.error(approvalOutcome.reason());
        }

        long start = System.currentTimeMillis();
        ToolExecutionResult result = tool.execute(toolCall.input());
        long elapsed = System.currentTimeMillis() - start;
        toolStats.record(toolCall.name(), elapsed, result.error());

        return result;
    }

    private String buildSystemPrompt(List<ToolDefinition> tools) {
        return buildBaseSystemPrompt() + "\n\n" + buildToolsSection(tools);
    }

    /** 构建基础系统提示词（身份、规则、推理深度、自定义提示）—— 不含工具定义。 */
    private String buildBaseSystemPrompt() {
        StringBuilder builder = new StringBuilder();
        builder.append("你是 JavaAgent CLI，一个简洁的编程助手。\n");
        builder.append("你是一个交互式智能体，帮助用户完成软件工程任务。\n");
        builder.append("仅在工具能实质性帮助回答问题时才使用工具。\n");
        builder.append("默认在工作区内操作，除非用户明确要求，否则避免执行破坏性操作。\n");
        builder.append("保持简洁。优先编辑已有文件，而非创建新文件。\n");
        builder.append("编写注释或 Javadoc 时，仅使用纯文本。禁止在注释中使用 HTML 标签，如 <table>、<ol>、<li>、<p>、<h3> 等。\n");
        builder.append("使用编辑工具时，确保 old_string 与文件内容完全匹配（包括空白字符）。\n");
        builder.append("如果用户请求不明确，应先询问澄清，而不是自行假设。\n");
        builder.append("严格区分：仅用'是什么/为什么/怎么做'等问题回答时，不写/不改代码；仅当出现明确的动作词如'实现/写/修复/修改'且用户要求执行时才行动；有疑问时，先回答，不要擅自行动。\n");

        // 推理深度级别
        String effort = config.effort();
        switch (effort) {
            case "low" -> builder.append("简洁直接。除非被要求，否则跳过解释。最少推理。\n");
            case "high" -> builder.append("逐步思考。提供透彻的分析和详细的解释。\n");
            case "xhigh" -> builder.append("深度推理，扩展分析。回答前探索多种方案。考虑边界情况和权衡。\n");
            case "max" -> builder.append("最大化推理深度。回答前考虑所有边界情况、替代方案和影响。力求极其详尽。\n");
            case "ultra" -> builder.append("ULTRA 模式: 穷举推理。探索每个角度，质疑假设，压力测试自己的答案。考虑二阶效应、失败模式和隐藏约束。力求最大程度的详尽和精确。\n");
        }

        if (!config.customSystemPrompt().isBlank()) {
            builder.append("\n附加指令:\n");
            builder.append(config.customSystemPrompt()).append("\n");
        }

        return builder.toString().trim();
    }

    /** 构建系统提示词的工具部分。 */
    private String buildToolsSection(List<ToolDefinition> tools) {
        StringBuilder builder = new StringBuilder();
        builder.append("可用工具:\n");
        for (ToolDefinition tool : tools) {
            builder.append("- ").append(tool.name());
            if (!tool.aliases().isEmpty()) {
                builder.append(" (别名: ").append(String.join(", ", tool.aliases())).append(")");
            }
            builder.append(": ").append(tool.description());
            builder.append(" | 必需参数=").append(tool.requiredParameters());
            builder.append("\n");
        }
        return builder.toString().trim();
    }

    private String summarizeToolCall(ToolCall toolCall) {
        var input = toolCall.input();
        if (input.containsKey("path")) {
            return input.get("path").toString();
        }
        if (input.containsKey("command")) {
            String cmd = input.get("command").toString();
            return cmd.length() > 60 ? cmd.substring(0, 57) + "..." : cmd;
        }
        if (input.containsKey("pattern")) {
            return input.get("pattern").toString();
        }
        return input.toString();
    }

    private String truncateResult(String result) {
        if (result == null) return "";
        String oneLine = result.replaceAll("[\\r\\n]+", " ").trim();
        if (oneLine.length() > 100) {
            return oneLine.substring(0, 97) + "...";
        }
        return oneLine;
    }

    private void autoSaveQuietly() {
        if (!config.autoSave()) {
            return;
        }
        try {
            conversationManager.saveCurrentSession();
        } catch (IOException e) {
            LOG.log(Level.FINE, "自动保存失败", e);
        }
    }

    public int approvalCacheSize() {
        return approvalManager.cacheSize();
    }

    public void clearApprovalCache() {
        approvalManager.clearCache();
    }

    private static final int COMPACT_KEEP_RECENT = 6;
    private static final int COMPACT_MIN_MESSAGES = 4;

    private static final String COMPACT_SYSTEM_PROMPT = """
            你是一个对话压缩助手。请将以下对话历史压缩为一段简洁的摘要。
            保留：关键决策、重要事实、用户的核心需求、已完成的操作、待解决的问题。
            忽略：工具调用的具体参数和输出细节、重复内容、格式化信息。
            输出纯文本摘要，不要使用 Markdown 格式，不要加标题。
            用中文输出。""";

    /**
     * 压缩对话历史 —— 用 LLM 总结中间消息，保留首条意图和最近消息。
     *
     * @return 压缩结果描述（含 token 变化）
     */
    public String compact() {
        List<Message> context = conversationManager.currentContext();
        if (context.size() < COMPACT_MIN_MESSAGES) {
            return "消息数量不足（" + context.size() + " 条），无需压缩。";
        }

        // 保留：第 0 条（原始意图）+ 最近 N 条
        int keepEnd = Math.min(COMPACT_KEEP_RECENT, context.size() - 1);
        int summaryEnd = context.size() - keepEnd;

        // 构建需要总结的消息
        List<Message> toSummarize = context.subList(0, summaryEnd);
        List<Message> toKeep = context.subList(summaryEnd, context.size());

        // 发送给 LLM 做摘要（无 tools，无 agent loop）
        StringBuilder convText = new StringBuilder();
        for (Message msg : toSummarize) {
            convText.append(msg.role()).append(": ").append(msg.content()).append("\n\n");
        }

        List<Message> summaryRequest = List.of(Message.user(convText.toString()));
        ModelResponse response = modelClient.chat(COMPACT_SYSTEM_PROMPT, summaryRequest, List.of(), null);

        if (response.isError()) {
            return "压缩失败：" + response.errorMessage();
        }

        String summary = response.content();

        // 构建新消息列表：摘要 + 最近消息
        List<Message> newMessages = new ArrayList<>();
        newMessages.add(Message.system("[对话摘要] " + summary));
        newMessages.addAll(toKeep);

        // 替换
        int oldTokens = contextUsage().totalTokens();
        conversationManager.replaceMessages(newMessages);
        int newTokens = contextUsage().totalTokens();

        return "压缩完成：保留 " + toKeep.size() + " 条最近消息 + 摘要，"
                + "token " + TokenCounter.formatTokens(oldTokens) + " → " + TokenCounter.formatTokens(newTokens)
                + "（节省 " + TokenCounter.formatTokens(oldTokens - newTokens) + "）";
    }

    /**
     * 计算当前上下文的 token 使用分布。
     * 工具定义的 token 是预计算的（静态）；仅基础提示词和消息在每次调用时计算。
     */
    public ContextUsage contextUsage() {
        int sysTokens = TokenCounter.countTokens(buildBaseSystemPrompt());
        int msgTokens = 0;
        for (Message msg : conversationManager.currentContext()) {
            msgTokens += TokenCounter.countTokens(msg.content()) + 4;
            if (msg.toolCalls() != null) {
                for (ToolCall tc : msg.toolCalls()) {
                    msgTokens += TokenCounter.countTokens(tc.toString());
                }
            }
        }
        int total = sysTokens + cachedToolTokens + msgTokens;
        return new ContextUsage(sysTokens, cachedToolTokens, msgTokens,
                total, config.maxTokens());
    }
}
