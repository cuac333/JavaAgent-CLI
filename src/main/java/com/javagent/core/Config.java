package com.javagent.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

public class Config {
    /**
     * 配置文件名
     */
    private static final String CONFIG_FILE = "config.properties";
    /**
     * 会话文件名
     */
    private static final String SESSION_FILE = "last_session.json";

    /**
     * 配置项键名常量
     *
     * 这些常量定义了配置文件中使用的键名，对应以下配置项：
     *
     * agent.mock_mode - 是否使用模拟模式（不实际调用API） - 默认值: true
     * agent.api_key - API 密钥 - 默认值: 空
     * agent.base_url - API 基础 URL - 默认值: https://api.openai.com/v1
     * agent.model - 使用的模型名称 - 默认值: gpt-5.4-mini
     * agent.auto_save - 是否自动保存会话 - 默认值: true
     * agent.max_iterations - 最大工具调用循环次数 - 默认值: 12
     * agent.enable_bash - 是否启用 Bash 工具 - 默认值: false
     * agent.stream_responses - 是否流式输出响应 - 默认值: true
     * agent.system_prompt - 自定义系统提示词 - 默认值: 空
     * agent.approval_cache - 是否启用审批缓存 - 默认值: true
     * agent.allow_external_paths - 是否允许访问工作区外路径 - 默认值: false
     * agent.bypass_permissions - 是否跳过所有工具审批确认 - 默认值: false
     * agent.rate_limit_qps - API 请求速率限制（每秒请求数） - 默认值: 10
     */
    private static final String KEY_MOCK_MODE = "agent.mock_mode";
    private static final String KEY_API_KEY = "agent.api_key";
    private static final String KEY_BASE_URL = "agent.base_url";
    private static final String KEY_MODEL = "agent.model";
    private static final String KEY_AUTO_SAVE = "agent.auto_save";
    private static final String KEY_MAX_ITERATIONS = "agent.max_iterations";
    private static final String KEY_ENABLE_BASH = "agent.enable_bash";
    private static final String KEY_STREAM_RESPONSES = "agent.stream_responses";
    private static final String KEY_SYSTEM_PROMPT = "agent.system_prompt";
    private static final String KEY_APPROVAL_CACHE = "agent.approval_cache";
    private static final String KEY_ALLOW_EXTERNAL_PATHS = "agent.allow_external_paths";
    private static final String KEY_BYPASS_PERMISSIONS = "agent.bypass_permissions";
    private static final String KEY_RATE_LIMIT_QPS = "agent.rate_limit_qps";
    private static final String KEY_EFFORT = "agent.effort";
    private static final String KEY_MAX_TOKENS = "agent.max_tokens";
    private static final String KEY_COMPACT_THRESHOLD = "agent.compact_threshold";

    /**
     * 存储所有配置项的 Properties 对象
     */
    private final Properties properties = new Properties();
    /**
     * 当前工作目录
     */
    private final Path workingDirectory;
    /**
     * 应用主目录（通常为 ~/.javaagent-cli）
     */
    private final Path appHome;
    /**
     * 配置文件路径
     */
    private final Path configPath;

    private Config(Path workingDirectory, Path appHome, Path configPath) {
        this.workingDirectory = workingDirectory;
        this.appHome = appHome;
        this.configPath = configPath;
    }

    public static Config loadDefault() throws IOException {
        Path workingDirectory = Paths.get(System.getProperty("user.dir"));
        Path appHome = Paths.get(System.getProperty("user.home")).resolve(".javaagent-cli");
        return load(workingDirectory, appHome);
    }

    public static Config load(Path workingDirectory) throws IOException {
        return load(workingDirectory, workingDirectory.resolve(".javaagent-cli"));
    }

    private static Config load(Path workingDirectory, Path appHome) throws IOException {
        Path candidateInWorkingTree = workingDirectory.resolve(CONFIG_FILE);
        Path configPath = Files.exists(candidateInWorkingTree)
                ? candidateInWorkingTree
                : appHome.resolve(CONFIG_FILE);

        Config config = new Config(workingDirectory, appHome, configPath);
        config.read();
        config.ensureDefaults();
        config.save();
        return config;
    }

    private void read() throws IOException {
        if (!Files.exists(configPath)) {
            return;
        }
        try (InputStream inputStream = Files.newInputStream(configPath)) {
            properties.load(inputStream);
        }
    }

    private void ensureDefaults() {
        properties.putIfAbsent(KEY_MOCK_MODE, "true");
        properties.putIfAbsent(KEY_API_KEY, "");
        properties.putIfAbsent(KEY_BASE_URL, "https://api.openai.com/v1");
        properties.putIfAbsent(KEY_MODEL, "gpt-5.4-mini");
        properties.putIfAbsent(KEY_AUTO_SAVE, "true");
        properties.putIfAbsent(KEY_MAX_ITERATIONS, "12");
        properties.putIfAbsent(KEY_ENABLE_BASH, "false");
        properties.putIfAbsent(KEY_STREAM_RESPONSES, "true");
        properties.putIfAbsent(KEY_SYSTEM_PROMPT, "");
        properties.putIfAbsent(KEY_APPROVAL_CACHE, "true");
        properties.putIfAbsent(KEY_ALLOW_EXTERNAL_PATHS, "false");
        properties.putIfAbsent(KEY_BYPASS_PERMISSIONS, "false");
        properties.putIfAbsent(KEY_RATE_LIMIT_QPS, "10");
        properties.putIfAbsent(KEY_EFFORT, "medium");
        properties.putIfAbsent(KEY_MAX_TOKENS, "200000");
        properties.putIfAbsent(KEY_COMPACT_THRESHOLD, "0.8");
    }

    public void save() throws IOException {
        Path parent = configPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream outputStream = Files.newOutputStream(configPath)) {
            properties.store(outputStream, "JavaAgent CLI configuration");
        }
    }

    public Path configPath() {
        return configPath;
    }

    public Path stateDirectory() {
        if (Files.isWritable(workingDirectory)) {
            return workingDirectory.resolve(".javaagent-cli");
        }
        return appHome;
    }

    public Path sessionPath() {
        if (Files.isWritable(workingDirectory)) {
            return workingDirectory.resolve(SESSION_FILE);
        }
        return stateDirectory().resolve(SESSION_FILE);
    }

    public Path sessionDirectory() {
        return stateDirectory().resolve("sessions");
    }

    public Path currentSessionMarkerPath() {
        return stateDirectory().resolve("current-session.txt");
    }

    public boolean isMockMode() {
        return Boolean.parseBoolean(properties.getProperty(KEY_MOCK_MODE));
    }

    public void setMockMode(boolean mockMode) throws IOException {
        properties.setProperty(KEY_MOCK_MODE, Boolean.toString(mockMode));
        save();
    }

    public String apiKey() {
        return properties.getProperty(KEY_API_KEY, "");
    }

    public void setApiKey(String apiKey) throws IOException {
        properties.setProperty(KEY_API_KEY, apiKey == null ? "" : apiKey.trim());
        save();
    }

    public String baseUrl() {
        return properties.getProperty(KEY_BASE_URL);
    }

    public String model() {
        return properties.getProperty(KEY_MODEL);
    }

    public boolean autoSave() {
        return Boolean.parseBoolean(properties.getProperty(KEY_AUTO_SAVE));
    }

    public int maxIterations() {
        return Integer.parseInt(properties.getProperty(KEY_MAX_ITERATIONS));
    }

    public boolean bashEnabled() {
        return Boolean.parseBoolean(properties.getProperty(KEY_ENABLE_BASH));
    }

    public void setBashEnabled(boolean enabled) throws IOException {
        properties.setProperty(KEY_ENABLE_BASH, Boolean.toString(enabled));
        save();
    }

    public boolean streamResponses() {
        return Boolean.parseBoolean(properties.getProperty(KEY_STREAM_RESPONSES));
    }

    public void setStreamResponses(boolean enabled) throws IOException {
        properties.setProperty(KEY_STREAM_RESPONSES, Boolean.toString(enabled));
        save();
    }

    public String customSystemPrompt() {
        return properties.getProperty(KEY_SYSTEM_PROMPT, "").trim();
    }

    public void setCustomSystemPrompt(String prompt) throws IOException {
        properties.setProperty(KEY_SYSTEM_PROMPT, prompt == null ? "" : prompt.trim());
        save();
    }

    public void clearCustomSystemPrompt() throws IOException {
        properties.setProperty(KEY_SYSTEM_PROMPT, "");
        save();
    }

    public boolean approvalCacheEnabled() {
        return Boolean.parseBoolean(properties.getProperty(KEY_APPROVAL_CACHE));
    }

    public void setApprovalCacheEnabled(boolean enabled) throws IOException {
        properties.setProperty(KEY_APPROVAL_CACHE, Boolean.toString(enabled));
        save();
    }

    public boolean allowExternalPaths() {
        return Boolean.parseBoolean(properties.getProperty(KEY_ALLOW_EXTERNAL_PATHS));
    }

    public void setAllowExternalPaths(boolean enabled) throws IOException {
        properties.setProperty(KEY_ALLOW_EXTERNAL_PATHS, Boolean.toString(enabled));
        save();
    }

    public Path workingDirectory() {
        return workingDirectory;
    }

    public boolean bypassPermissions() {
        return Boolean.parseBoolean(properties.getProperty(KEY_BYPASS_PERMISSIONS));
    }

    public void setBypassPermissions(boolean enabled) throws IOException {
        properties.setProperty(KEY_BYPASS_PERMISSIONS, Boolean.toString(enabled));
        save();
    }

    public int rateLimitQps() {
        return Integer.parseInt(properties.getProperty(KEY_RATE_LIMIT_QPS, "10"));
    }

    public String effort() {
        return properties.getProperty(KEY_EFFORT, "medium").toLowerCase();
    }

    public int maxTokens() {
        return Integer.parseInt(properties.getProperty(KEY_MAX_TOKENS, "200000"));
    }

    public double compactThreshold() {
        return Double.parseDouble(properties.getProperty(KEY_COMPACT_THRESHOLD, "0.8"));
    }

    public void setEffort(String effort) throws IOException {
        String level = effort.toLowerCase();
        if (!level.equals("low") && !level.equals("medium") && !level.equals("high") && !level.equals("max")) {
            throw new IllegalArgumentException("effort must be low|medium|high|max");
        }
        properties.setProperty(KEY_EFFORT, level);
        save();
    }

    public void reload() throws IOException {
        read();
        ensureDefaults();
    }

    public java.util.List<String> validate() {
        java.util.List<String> warnings = new java.util.ArrayList<>();
        if (maxIterations() < 1 || maxIterations() > 50) {
            warnings.add("agent.max_iterations=" + maxIterations() + " (建议 1-50)");
        }
        return warnings;
    }
}
