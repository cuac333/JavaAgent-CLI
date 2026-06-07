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
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Agent — the core engine.
 *
 * Implements the ReAct-style agent loop:
 *   User input -> AI thinks -> calls tool -> tool result -> AI thinks again -> ... -> final reply
 */
public class Agent {
    private static final Logger LOG = Logger.getLogger(Agent.class.getName());
    private final Config config;
    private final ModelClient modelClient;
    private final ToolRegistry toolRegistry;
    private final ConversationManager conversationManager;
    private final ApprovalManager approvalManager;
    private final ToolStats toolStats = new ToolStats();

    // Pre-computed token count for tool definitions (static, computed once)
    private final int cachedToolTokens;

    public Agent(Config config, ModelClient modelClient, ToolRegistry toolRegistry, ConversationManager conversationManager) {
        this.config = config;
        this.modelClient = modelClient;
        this.toolRegistry = toolRegistry;
        this.conversationManager = conversationManager;
        this.approvalManager = new ApprovalManager(config);
        // Pre-compute tool definition tokens (tools are static)
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
     * Process one conversation turn — the agent loop.
     *
     * @param userInput        user's text input
     * @param approvalHandler  approval callback for tools that need confirmation
     * @param streamHandler    streaming text callback (can be null)
     * @param displayCallback  tool execution display callback (can be null)
     * @return the agent's final text reply
     */
    public String processTurn(String userInput, ApprovalHandler approvalHandler,
                               TextStreamHandler streamHandler, ToolDisplayCallback displayCallback) {
        conversationManager.addUserMessage(userInput);
        String systemPrompt = buildSystemPrompt(toolRegistry.definitions());

        int consecutiveFailures = 0;
        String lastFailedTool = null;
        for (int iteration = 0; iteration < config.maxIterations(); iteration++) {
            TextStreamHandler effectiveStreamHandler = config.streamResponses() ? streamHandler : null;
            ModelResponse response = modelClient.chat(
                    systemPrompt,
                    conversationManager.currentContext(),
                    toolRegistry.definitions(),
                    effectiveStreamHandler
            );

            // Retry with fresh context if the API rejects due to missing reasoning_content
            // (happens when old conversation messages lack thinking model's reasoning data)
            if (response.isError() && response.errorMessage().contains("reasoning_content")) {
                List<Message> freshContext = List.of(
                        Message.user(userInput)
                );
                response = modelClient.chat(
                        systemPrompt,
                        freshContext,
                        toolRegistry.definitions(),
                        effectiveStreamHandler
                );
                if (!response.isError()) {
                    // Clear old history and start fresh
                    conversationManager.startNewSession(null);
                    conversationManager.addUserMessage(userInput);
                }
            }

            if (response.isError()) {
                String errorText = "Agent error: " + response.errorMessage();
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
                // Notify UI about tool execution
                if (displayCallback != null) {
                    displayCallback.onToolStart(toolCall.name(), summarizeToolCall(toolCall));
                }

                ToolExecutionResult result = executeToolCall(toolCall, approvalHandler);
                // Track consecutive failures to break infinite loops
                if (result.error()) {
                    if (toolCall.name().equals(lastFailedTool)) {
                        consecutiveFailures++;
                    } else {
                        lastFailedTool = toolCall.name();
                        consecutiveFailures = 1;
                    }
                    if (consecutiveFailures >= 3) {
                        String stuckText = "Agent stopped: tool '" + toolCall.name() + "' failed 3 times in a row. "
                                + "Last error: " + truncateResult(result.content());
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
                // Sanitize tool result before storing to prevent API key leakage in session files
                String sanitizedContent = Sanitizer.sanitize(result.content());
                conversationManager.addToolResultMessage(toolCall.id(), toolCall.name(), sanitizedContent, result.error());

                // Notify UI about tool completion
                if (displayCallback != null) {
                    displayCallback.onToolEnd(toolCall.name(), !result.error(),
                            truncateResult(result.content()), result.content());
                }
            }
        }

        String limitText = "Agent stopped after reaching the max tool-call iterations (" + config.maxIterations() + ").";
        conversationManager.addAssistantMessage(limitText);
        autoSaveQuietly();
        return limitText;
    }

    private ToolExecutionResult executeToolCall(ToolCall toolCall, ApprovalHandler approvalHandler) {
        Tool tool = toolRegistry.find(toolCall.name()).orElse(null);
        if (tool == null) {
            return ToolExecutionResult.error("Tool not found: " + toolCall.name());
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

    /** Build the base system prompt (identity, rules, effort, custom prompt) — no tool definitions. */
    private String buildBaseSystemPrompt() {
        StringBuilder builder = new StringBuilder();
        builder.append("You are JavaAgent CLI, a concise coding assistant.\n");
        builder.append("You are an interactive agent that helps users with software engineering tasks.\n");
        builder.append("Use tools only when they materially help answer the request.\n");
        builder.append("Operate inside the workspace by default and avoid destructive actions unless explicitly requested.\n");
        builder.append("Be concise. Prefer editing existing files over creating new ones.\n");
        builder.append("When writing comments or Javadoc, use plain text only. Do NOT use HTML tags like <table>, <ol>, <li>, <p>, <h3> etc. in comments.\n");
        builder.append("When using the edit tool, ensure old_string matches the file content exactly (including whitespace).\n");
        builder.append("If the user request is vague, ask for clarification instead of making assumptions.\n");
        builder.append("Strictly distinguish: only answer questions with what/why/how etc. without writing/modifying code;"
        +" act only when explicit action words like implement/write/fix/modify appear and user requests execution; when in doubt, always answer first, never act unasked.\n");

        // Effort level — controls reasoning depth
        String effort = config.effort();
        switch (effort) {
            case "low" -> builder.append("Be brief and direct. Skip explanations unless asked. Minimal reasoning.\n");
            case "medium" -> builder.append("Provide balanced responses with moderate detail.\n");
            case "high" -> builder.append("Think step-by-step. Provide thorough analysis and detailed explanations.\n");
            case "max" -> builder.append("Maximize reasoning depth. Consider all edge cases, alternatives, and implications before responding. Be extremely thorough.\n");
        }

        if (!config.customSystemPrompt().isBlank()) {
            builder.append("\nAdditional instructions:\n");
            builder.append(config.customSystemPrompt()).append("\n");
        }

        return builder.toString().trim();
    }

    /** Build the tools section of the system prompt. */
    private String buildToolsSection(List<ToolDefinition> tools) {
        StringBuilder builder = new StringBuilder();
        builder.append("Available tools:\n");
        for (ToolDefinition tool : tools) {
            builder.append("- ").append(tool.name());
            if (!tool.aliases().isEmpty()) {
                builder.append(" (aliases: ").append(String.join(", ", tool.aliases())).append(")");
            }
            builder.append(": ").append(tool.description());
            builder.append(" | required=").append(tool.requiredParameters());
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
            LOG.log(Level.FINE, "Auto-save failed", e);
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
     * Calculate current context usage breakdown by token count.
     * Tool definition tokens are pre-computed (static); only the base prompt
     * and messages are tokenized on each call.
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
