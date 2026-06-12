package com.javagent.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javagent.core.Config;
import com.javagent.tools.ToolDefinition;
import com.javagent.util.RateLimiter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * OpenAI 兼容的模型客户端 —— 调用真实 AI API，支持 SSE 流式输出。
 *
 * 支持任何 OpenAI API 兼容服务（OpenAI、DeepSeek、Moonshot 等）。
 * 当提供 TextStreamHandler 时，使用真正的 SSE 流式逐 token 输出。
 */
public class OpenAiCompatibleModelClient implements ModelClient {
    private static final Logger LOG = Logger.getLogger(OpenAiCompatibleModelClient.class.getName());
    private static final int MAX_RETRIES = 3;
    private static final long BASE_DELAY_MS = 1000;
    private final Config config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RateLimiter rateLimiter;

    public OpenAiCompatibleModelClient(Config config) {
        this.config = config;
        this.rateLimiter = new RateLimiter(config.rateLimitQps());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public ModelResponse chat(String systemPrompt, List<Message> messages, List<ToolDefinition> tools) {
        if (config.apiKey().isBlank()) {
            return ModelResponse.error("真实模式需要 API Key。请配置 agent.api_key 或切换回模拟模式。");
        }

        try {
            // 先尝试带工具调用
            String body = buildRequestBody(systemPrompt, messages, tools, false);
            HttpRequest request = buildRequest(body);
            HttpResponse<String> response = sendWithRetry(request);
            if (response.statusCode() >= 400) {
                // 如果返回 400 且有工具，去掉工具重试（模型可能不支持函数调用）
                if (response.statusCode() == 400 && !tools.isEmpty()) {
                    String bodyNoTools = buildRequestBody(systemPrompt, messages, List.of(), false);
                    HttpRequest retryRequest = buildRequest(bodyNoTools);
                    HttpResponse<String> retryResponse = sendWithRetry(retryRequest);
                    if (retryResponse.statusCode() < 400) {
                        return parseResponse(retryResponse.body());
                    }
                }
                return ModelResponse.error("HTTP 错误 " + response.statusCode() + ": " + truncate(response.body(), 500));
            }
            return parseResponse(response.body());
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return ModelResponse.error("请求失败: " + e.getMessage());
        }
    }

    @Override
    public ModelResponse chat(String systemPrompt, List<Message> messages,
                               List<ToolDefinition> tools, TextStreamHandler streamHandler) {
        // 未请求流式或无处理器: 回退到非流式
        if (streamHandler == null) {
            return chat(systemPrompt, messages, tools);
        }
        // 模拟模式: 使用模拟流式输出
        if (config.isMockMode()) {
            return chat(systemPrompt, messages, tools);
        }

        if (config.apiKey().isBlank()) {
            return ModelResponse.error("真实模式需要 API Key。");
        }

        try {
            String body = buildRequestBody(systemPrompt, messages, tools, true);
            HttpRequest request = buildRequest(body);

            // 通过 BodyHandlers.ofLines() 使用 SSE 流式
            StringBuilder contentBuilder = new StringBuilder();
            StringBuilder reasoningBuilder = new StringBuilder();
            List<ToolCallDelta> toolCallDeltas = new ArrayList<>();

            HttpResponse<java.util.stream.Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() >= 400) {
                // 关闭流
                response.body().close();
                // 如果带工具返回 400，回退到非流式（会处理重试）
                if (response.statusCode() == 400 && !tools.isEmpty()) {
                    return chat(systemPrompt, messages, tools);
                }
                return ModelResponse.error("HTTP 错误 " + response.statusCode());
            }

            response.body().forEach(line -> {
                if (line.isEmpty() || line.startsWith(":")) return; // 跳过注释/保活消息
                if (!line.startsWith("data: ")) return;

                String data = line.substring(6).trim();
                if ("[DONE]".equals(data)) return;

                try {
                    JsonNode chunk = objectMapper.readTree(data);
                    JsonNode choices = chunk.path("choices");
                    if (choices.isEmpty()) return;

                    JsonNode delta = choices.path(0).path("delta");

                    // 内容增量
                    JsonNode contentNode = delta.path("content");
                    if (!contentNode.isMissingNode() && !contentNode.isNull()) {
                        String text = contentNode.asText("");
                        if (!text.isEmpty()) {
                            contentBuilder.append(text);
                            streamHandler.onChunk(text);
                        }
                    }

                    // 推理内容增量（思维模型）
                    JsonNode reasoningNode = delta.path("reasoning_content");
                    if (!reasoningNode.isMissingNode() && !reasoningNode.isNull()) {
                        String reasoning = reasoningNode.asText("");
                        if (!reasoning.isEmpty()) {
                            reasoningBuilder.append(reasoning);
                        }
                    }

                    // 工具调用增量
                    JsonNode toolCallsNode = delta.path("tool_calls");
                    if (toolCallsNode.isArray()) {
                        for (JsonNode tc : toolCallsNode) {
                            int index = tc.path("index").asInt(0);
                            // 确保列表足够大
                            while (toolCallDeltas.size() <= index) {
                                toolCallDeltas.add(new ToolCallDelta());
                            }
                            ToolCallDelta tcd = toolCallDeltas.get(index);
                            if (tc.has("id") && !tc.path("id").asText("").isEmpty()) {
                                tcd.id = tc.path("id").asText();
                            }
                            JsonNode fn = tc.path("function");
                            if (fn.has("name") && !fn.path("name").asText("").isEmpty()) {
                                tcd.name = fn.path("name").asText();
                            }
                            if (fn.has("arguments")) {
                                tcd.arguments.append(fn.path("arguments").asText(""));
                            }
                        }
                    }

                } catch (JsonProcessingException e) {
                    LOG.log(Level.FINE, "跳过格式错误的 SSE 数据块", e);
                }
            });

            // 从累积数据构建响应
            String reasoning = reasoningBuilder.isEmpty() ? null : reasoningBuilder.toString();
            if (!toolCallDeltas.isEmpty() && toolCallDeltas.stream().anyMatch(t -> t.name != null)) {
                List<ToolCall> toolCalls = toolCallDeltas.stream()
                        .filter(t -> t.id != null && t.name != null)
                        .map(t -> {
                            Map<String, Object> args = parseArguments(t.arguments.toString());
                            return new ToolCall(t.id, t.name, args);
                        })
                        .toList();
                return ModelResponse.toolCalls(contentBuilder.toString(), toolCalls, reasoning);
            }

            String content = contentBuilder.toString();
            if (content.isEmpty() && reasoning != null) {
                // 某些思维模型只返回 reasoning_content 而没有 content
                content = reasoning;
            }
            if (content.isEmpty()) {
                return ModelResponse.error("API 返回空响应。");
            }
            return ModelResponse.text(content, reasoning);

        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            String msg = e.getMessage();
            return ModelResponse.error("流式请求失败: " + (msg != null ? msg : e.getClass().getSimpleName()));
        }
    }

    @Override
    public String name() {
        return "openai-compatible";
    }

    private HttpRequest buildRequest(String body) {
        return HttpRequest.newBuilder()
                .uri(URI.create(config.baseUrl() + "/chat/completions"))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /**
     * 带指数退避的 HTTP 请求重试（处理 429 和 5xx）
     */
    private HttpResponse<String> sendWithRetry(HttpRequest request) throws IOException, InterruptedException {
        rateLimiter.acquire();
        IOException lastException = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status == 429 || status >= 500) {
                    if (attempt < MAX_RETRIES) {
                        long delay = BASE_DELAY_MS * (1L << attempt); // 1s, 2s, 4s
                        LOG.log(Level.WARNING, "HTTP " + status + "，" + delay + "ms 后重试（第 " + (attempt + 1) + "/" + MAX_RETRIES + ")");
                        Thread.sleep(delay);
                        continue;
                    }
                }
                return response;
            } catch (IOException e) {
                lastException = e;
                if (attempt < MAX_RETRIES) {
                    long delay = BASE_DELAY_MS * (1L << attempt);
                    LOG.log(Level.WARNING, "IO 错误，" + delay + "ms 后重试（第 " + (attempt + 1) + "/" + MAX_RETRIES + "): " + e.getMessage());
                    Thread.sleep(delay);
                }
            }
        }
        throw lastException;
    }

    private String buildRequestBody(String systemPrompt, List<Message> messages,
                                      List<ToolDefinition> tools, boolean stream) throws JsonProcessingException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", config.model());
        root.put("temperature", 0.0);
        root.put("tool_choice", "auto");
        if (stream) {
            root.put("stream", true);
            // 启用流选项以获取用量信息
            ObjectNode streamOptions = objectMapper.createObjectNode();
            streamOptions.put("include_usage", false);
            root.set("stream_options", streamOptions);
        }

        ArrayNode messagesNode = objectMapper.createArrayNode();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            ObjectNode systemMessage = objectMapper.createObjectNode();
            systemMessage.put("role", "system");
            systemMessage.put("content", systemPrompt);
            messagesNode.add(systemMessage);
        }

        for (Message message : messages) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("role", message.role().apiValue());

            switch (message.role()) {
                case USER, SYSTEM -> node.put("content", message.content());
                case ASSISTANT -> {
                    node.put("content", message.content());
                    // 包含思维模型的 reasoning_content（必须回传）
                    if (message.reasoningContent() != null && !message.reasoningContent().isEmpty()) {
                        node.put("reasoning_content", message.reasoningContent());
                    }
                    if (message.hasToolCalls()) {
                        ArrayNode toolCallsNode = objectMapper.createArrayNode();
                        for (ToolCall toolCall : message.toolCalls()) {
                            ObjectNode toolCallNode = objectMapper.createObjectNode();
                            toolCallNode.put("id", toolCall.id());
                            toolCallNode.put("type", "function");
                            ObjectNode functionNode = objectMapper.createObjectNode();
                            functionNode.put("name", toolCall.name());
                            functionNode.put("arguments", objectMapper.writeValueAsString(toolCall.input()));
                            toolCallNode.set("function", functionNode);
                            toolCallsNode.add(toolCallNode);
                        }
                        node.set("tool_calls", toolCallsNode);
                    }
                }
                case TOOL -> {
                    if (message.toolResult() == null) {
                        node.put("content", message.content());
                    } else {
                        node.put("tool_call_id", message.toolResult().toolCallId());
                        node.put("content", message.toolResult().content());
                    }
                }
            }
            messagesNode.add(node);
        }
        root.set("messages", messagesNode);

        ArrayNode toolsNode = objectMapper.createArrayNode();
        for (ToolDefinition tool : tools) {
            ObjectNode toolNode = objectMapper.createObjectNode();
            toolNode.put("type", "function");
            ObjectNode functionNode = objectMapper.createObjectNode();
            functionNode.put("name", tool.name());
            functionNode.put("description", tool.description());
            ObjectNode parametersNode = objectMapper.createObjectNode();
            parametersNode.put("type", "object");
            ObjectNode propertiesNode = objectMapper.createObjectNode();
            tool.parameterDescriptions().forEach((name, description) -> {
                ObjectNode propertyNode = objectMapper.createObjectNode();
                propertyNode.put("type", tool.parameterTypes().getOrDefault(name, "string"));
                propertyNode.put("description", description);
                propertiesNode.set(name, propertyNode);
            });
            parametersNode.set("properties", propertiesNode);
            ArrayNode requiredNode = objectMapper.createArrayNode();
            for (String name : tool.requiredParameters()) {
                requiredNode.add(name);
            }
            parametersNode.set("required", requiredNode);
            functionNode.set("parameters", parametersNode);
            toolNode.set("function", functionNode);
            toolsNode.add(toolNode);
        }
        root.set("tools", toolsNode);

        return objectMapper.writeValueAsString(root);
    }

    private ModelResponse parseResponse(String responseBody) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(responseBody);
        if (root.has("error")) {
            return ModelResponse.error(root.path("error").path("message").asText("未知 API 错误"));
        }

        JsonNode messageNode = root.path("choices").path(0).path("message");
        if (messageNode.isMissingNode()) {
            return ModelResponse.error("响应中未包含消息。");
        }

        // 提取思维模型的 reasoning_content
        String reasoningContent = null;
        JsonNode reasoningNode = messageNode.path("reasoning_content");
        if (!reasoningNode.isMissingNode() && !reasoningNode.isNull()) {
            reasoningContent = reasoningNode.isTextual() ? reasoningNode.asText() : reasoningNode.toString();
        }

        JsonNode toolCallsNode = messageNode.path("tool_calls");
        if (toolCallsNode.isArray() && !toolCallsNode.isEmpty()) {
            List<ToolCall> toolCalls = objectMapper.convertValue(toolCallsNode, new TypeReference<List<JsonNode>>() {})
                    .stream()
                    .map(node -> {
                        String id = node.path("id").asText();
                        String name = node.path("function").path("name").asText();
                        String rawArguments = node.path("function").path("arguments").asText("{}");
                        Map<String, Object> input = parseArguments(rawArguments);
                        return new ToolCall(id, name, input);
                    })
                    .toList();
            return ModelResponse.toolCalls(extractContent(messageNode.path("content")), toolCalls, reasoningContent);
        }

        return ModelResponse.text(extractContent(messageNode.path("content")), reasoningContent);
    }

    private Map<String, Object> parseArguments(String rawArguments) {
        try {
            return objectMapper.readValue(rawArguments, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (JsonProcessingException e) {
            return Map.of("raw", rawArguments);
        }
    }

    private String extractContent(JsonNode contentNode) {
        if (contentNode == null || contentNode.isMissingNode() || contentNode.isNull()) {
            return "";
        }
        if (contentNode.isTextual()) {
            return contentNode.asText();
        }
        if (contentNode.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode block : contentNode) {
                if (block.has("text")) {
                    builder.append(block.path("text").asText());
                }
            }
            return builder.toString();
        }
        return contentNode.toString();
    }

    /** 流式工具调用增量的累加器 */
    private static class ToolCallDelta {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
    }
}
