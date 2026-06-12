package com.javagent.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.javagent.model.Message;
import com.javagent.model.Role;
import com.javagent.model.ToolCall;

import java.io.IOException;
import java.nio.file.Files;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 对话管理器 —— 管理对话历史和多会话
 *
 * 核心功能：
 * 1. 维护当前对话的消息列表
 * 2. 支持多会话：保存/加载/切换不同对话
 * 3. 每次对话自动保存，下次启动自动恢复
 *
 * 什么是"多会话"？
 * 就像浏览器的多个标签页一样，你可以有多个独立的对话，
 * 每个对话有自己的历史记录。你可以：
 * - /save 保存当前对话
 * - /load 加载之前的对话
 * - /sessions 查看所有保存的对话
 * - /new 开始新对话
 *
 * 数据存储：
 * - 每个会话保存为 ~/.javaagent-cli/sessions/{uuid}.json
 * - 当前会话 ID 记录在 current-session.txt
 */
public class ConversationManager {
    private static final Logger LOG = Logger.getLogger(ConversationManager.class.getName());
    private static final DateTimeFormatter TITLE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final Config config;
    // Jackson JSON 序列化器（用于保存/加载会话）
    private final ObjectMapper objectMapper;

    // 当前会话状态
    private String currentSessionId;          // 当前会话 ID
    private String currentSessionTitle;       // 当前会话标题
    private List<Message> messages = new ArrayList<>();  // 当前对话的消息列表
    private LocalDateTime sessionStart = LocalDateTime.now();  // 会话开始时间
    private String lastResponse = "";         // AI 最后一次回复

    public ConversationManager(Config config) {
        this.config = config;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(SerializationFeature.INDENT_OUTPUT);
        startNewSession(null);
    }

    /** 添加用户消息 */
    public void addUserMessage(String content) {
        messages.add(Message.user(content));
        // 首条用户消息自动设置会话标题
        if (messages.stream().filter(m -> m.role() == Role.USER).count() == 1) {
            autoSetTitle(content);
        }
    }

    private void autoSetTitle(String content) {
        if (content == null || content.isBlank()) return;
        // 只在标题仍为默认格式时自动设置
        if (currentSessionTitle != null && currentSessionTitle.startsWith("Session ")) {
            String title = content.replaceAll("[\\r\\n]+", " ").trim();
            if (title.length() > 40) {
                title = title.substring(0, 37) + "...";
            }
            currentSessionTitle = title;
        }
    }

    /** 添加 AI 纯文本回复 */
    public void addAssistantMessage(String content) {
        messages.add(Message.assistant(content));
        lastResponse = content == null ? "" : content;
    }

    public void addAssistantMessage(String content, String reasoningContent) {
        messages.add(Message.assistant(content, reasoningContent));
        lastResponse = content == null ? "" : content;
    }

    /** 添加 AI 工具调用消息 */
    public void addAssistantToolCallMessage(String content, List<ToolCall> toolCalls) {
        messages.add(Message.assistantWithToolCalls(content, toolCalls));
    }

    public void addAssistantToolCallMessage(String content, List<ToolCall> toolCalls, String reasoningContent) {
        messages.add(Message.assistantWithToolCalls(content, toolCalls, reasoningContent));
    }

    /** 添加工具结果消息 */
    public void addToolResultMessage(String toolCallId, String toolName, String content, boolean error) {
        messages.add(Message.toolResult(toolCallId, toolName, content, error));
    }

    /** 获取当前对话的完整消息列表（不可变副本） */
    public List<Message> currentContext() {
        return List.copyOf(messages);
    }

    /** 替换整个消息列表（用于 compact 压缩） */
    public void replaceMessages(List<Message> newMessages) {
        this.messages = new ArrayList<>(newMessages);
    }

    public int messageCount() {
        return messages.size();
    }

    public String lastResponse() {
        return lastResponse;
    }

    public String currentSessionId() {
        return currentSessionId;
    }

    public String currentSessionTitle() {
        return currentSessionTitle;
    }

    /** 清空当前会话 */
    public void clearCurrentSession() {
        startNewSession(null);
    }

    /** 开始新会话 */
    public void startNewSession(String title) {
        LocalDateTime now = LocalDateTime.now();
        this.currentSessionId = UUID.randomUUID().toString();
        this.currentSessionTitle = title == null || title.isBlank() ? defaultTitle(now) : title.trim();
        this.messages = new ArrayList<>();
        this.sessionStart = now;
        this.lastResponse = "";
    }

    /** 保存当前会话（无标题覆盖） */
    public void saveCurrentSession() throws IOException {
        saveCurrentSession(null);
    }

    /** 保存当前会话（可覆盖标题） */
    public void saveCurrentSession(String titleOverride) throws IOException {
        if (titleOverride != null && !titleOverride.isBlank()) {
            currentSessionTitle = titleOverride.trim();
        }

        SessionSnapshot snapshot = new SessionSnapshot(
                currentSessionId,
                currentSessionTitle,
                sessionStart,
                LocalDateTime.now(),
                List.copyOf(messages),
                lastResponse
        );

        Files.createDirectories(config.sessionDirectory());
        if (config.sessionPath().getParent() != null) {
            Files.createDirectories(config.sessionPath().getParent());
        }
        if (config.currentSessionMarkerPath().getParent() != null) {
            Files.createDirectories(config.currentSessionMarkerPath().getParent());
        }

        objectMapper.writeValue(sessionFile(currentSessionId).toFile(), snapshot);
        objectMapper.writeValue(config.sessionPath().toFile(), snapshot);
        Files.writeString(config.currentSessionMarkerPath(), currentSessionId);
    }

    /** 加载上次会话 */
    public boolean loadLastSession() throws IOException {
        Path markerPath = config.currentSessionMarkerPath();
        if (Files.exists(markerPath)) {
            String sessionId = Files.readString(markerPath).trim();
            if (!sessionId.isBlank() && Files.exists(sessionFile(sessionId))) {
                applySnapshot(objectMapper.readValue(sessionFile(sessionId).toFile(), SessionSnapshot.class));
                return true;
            }
        }

        if (!Files.exists(config.sessionPath())) {
            return false;
        }

        SessionSnapshot snapshot = objectMapper.readValue(config.sessionPath().toFile(), SessionSnapshot.class);
        applySnapshot(snapshot);
        saveCurrentSession();
        return true;
    }

    /** 按 ID 或标题加载会话 */
    public boolean loadSession(String query) throws IOException {
        List<SessionSummary> sessions = listSessions();
        if (sessions.isEmpty()) {
            return false;
        }

        SessionSummary summary;
        if (query == null || query.isBlank() || "latest".equalsIgnoreCase(query.trim())) {
            summary = sessions.getFirst();
        } else {
            String normalized = query.trim();
            summary = sessions.stream()
                    .filter(session -> session.id().equals(normalized)
                            || session.id().startsWith(normalized)
                            || session.title().equalsIgnoreCase(normalized)
                            || session.title().toLowerCase().contains(normalized.toLowerCase()))
                    .findFirst()
                    .orElse(null);
        }

        if (summary == null) {
            return false;
        }

        applySnapshot(objectMapper.readValue(sessionFile(summary.id()).toFile(), SessionSnapshot.class));
        Files.writeString(config.currentSessionMarkerPath(), currentSessionId);
        objectMapper.writeValue(config.sessionPath().toFile(), currentSnapshot());
        return true;
    }

    /** 列出所有保存的会话 */
    public List<SessionSummary> listSessions() throws IOException {
        if (!Files.exists(config.sessionDirectory())) {
            return List.of();
        }

        try (Stream<Path> stream = Files.list(config.sessionDirectory())) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(this::readSummaryQuietly)
                    .filter(summary -> summary != null)
                    .sorted(Comparator.comparing(SessionSummary::lastUpdated).reversed())
                    .toList();
        }
    }

    /**
     * 导出当前对话为 Markdown 格式
     */
    public String exportAsMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(currentSessionTitle).append("\n\n");
        sb.append("**会话 ID:** `").append(shortId(currentSessionId)).append("`  \n");
        sb.append("**开始时间:** ").append(sessionStart.format(TITLE_FORMAT)).append("  \n");
        sb.append("**消息数:** ").append(messages.size()).append("\n\n");
        sb.append("---\n\n");

        for (Message msg : messages) {
            switch (msg.role()) {
                case USER -> {
                    sb.append("## 用户\n\n");
                    sb.append(msg.content() != null ? msg.content() : "").append("\n\n");
                }
                case ASSISTANT -> {
                    sb.append("## 助手\n\n");
                    if (msg.reasoningContent() != null && !msg.reasoningContent().isBlank()) {
                        sb.append("<details><summary>思考过程</summary>\n\n");
                        sb.append(msg.reasoningContent()).append("\n\n");
                        sb.append("</details>\n\n");
                    }
                    if (msg.content() != null && !msg.content().isBlank()) {
                        sb.append(msg.content()).append("\n\n");
                    }
                    if (msg.toolCalls() != null) {
                        for (var tc : msg.toolCalls()) {
                            sb.append("### 工具调用: `").append(tc.name()).append("`\n\n");
                            sb.append("```json\n").append(tc.input()).append("\n```\n\n");
                        }
                    }
                }
                case TOOL -> {
                    String name = (msg.toolResult() != null && msg.toolResult().toolName() != null)
                            ? msg.toolResult().toolName() : "tool";
                    sb.append("## 工具结果: `").append(name).append("`\n\n");
                    sb.append("```\n").append(msg.content() != null ? msg.content() : "").append("\n```\n\n");
                }
                case SYSTEM -> {
                    sb.append("## 系统\n\n");
                    sb.append(msg.content() != null ? msg.content() : "").append("\n\n");
                }
            }
        }
        return sb.toString();
    }

    public String sessionStats() {
        long userCount = messages.stream().filter(message -> message.role() == Role.USER).count();
        long assistantCount = messages.stream().filter(message -> message.role() == Role.ASSISTANT).count();
        long toolCount = messages.stream().filter(message -> message.role() == Role.TOOL).count();
        return "id=" + shortId(currentSessionId)
                + ", title=" + currentSessionTitle
                + ", messages=" + messages.size()
                + ", user=" + userCount
                + ", assistant=" + assistantCount
                + ", tool=" + toolCount;
    }

    private SessionSnapshot currentSnapshot() {
        return new SessionSnapshot(
                currentSessionId,
                currentSessionTitle,
                sessionStart,
                LocalDateTime.now(),
                List.copyOf(messages),
                lastResponse
        );
    }

    private SessionSummary readSummaryQuietly(Path file) {
        try {
            SessionSnapshot snapshot = objectMapper.readValue(file.toFile(), SessionSnapshot.class);
            String id = snapshot.sessionId() == null || snapshot.sessionId().isBlank()
                    ? file.getFileName().toString().replace(".json", "")
                    : snapshot.sessionId();
            String title = snapshot.title() == null || snapshot.title().isBlank()
                    ? defaultTitle(snapshot.sessionStart() == null ? LocalDateTime.now() : snapshot.sessionStart())
                    : snapshot.title();
            LocalDateTime updated = snapshot.lastUpdated() == null ? LocalDateTime.MIN : snapshot.lastUpdated();
            int messageCount = snapshot.messages() == null ? 0 : snapshot.messages().size();
            return new SessionSummary(id, title, updated, messageCount);
        } catch (IOException e) {
            LOG.log(Level.FINE, "读取会话摘要失败: " + file, e);
            return null;
        }
    }

    /** 从快照恢复会话状态 */
    private void applySnapshot(SessionSnapshot snapshot) {
        this.currentSessionId = snapshot.sessionId() == null || snapshot.sessionId().isBlank()
                ? UUID.randomUUID().toString()
                : snapshot.sessionId();
        this.currentSessionTitle = snapshot.title() == null || snapshot.title().isBlank()
                ? defaultTitle(snapshot.sessionStart() == null ? LocalDateTime.now() : snapshot.sessionStart())
                : snapshot.title();
        this.sessionStart = snapshot.sessionStart() == null ? LocalDateTime.now() : snapshot.sessionStart();
        this.messages = snapshot.messages() == null ? new ArrayList<>() : new ArrayList<>(snapshot.messages());
        this.lastResponse = snapshot.lastResponse() == null ? "" : snapshot.lastResponse();
    }

    private Path sessionFile(String sessionId) {
        return config.sessionDirectory().resolve(sessionId + ".json");
    }

    private String defaultTitle(LocalDateTime time) {
        return "会话 " + TITLE_FORMAT.format(time);
    }

    private String shortId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "无";
        }
        return sessionId.length() <= 8 ? sessionId : sessionId.substring(0, 8);
    }

    /** 会话摘要（用于列表展示） */
    public record SessionSummary(
            String id,
            String title,
            LocalDateTime lastUpdated,
            int messageCount
    ) {
    }

    /** 会话快照（完整会话数据，用于保存/加载） */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SessionSnapshot(
            String sessionId,
            String title,
            LocalDateTime sessionStart,
            LocalDateTime lastUpdated,
            List<Message> messages,
            String lastResponse
    ) {
    }
}
