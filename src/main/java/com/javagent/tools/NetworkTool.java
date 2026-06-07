package com.javagent.tools;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 网络请求工具 - 发送HTTP请求并获取响应
 * 
 * 这个工具演示了如何扩展JavaAgent CLI的功能
 */
public class NetworkTool implements Tool {
    
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "network",
            "发送HTTP网络请求并返回响应内容",
            Map.of(
                    "url", "要请求的URL地址",
                    "method", "HTTP方法（GET、POST、PUT、DELETE等）",
                    "timeout", "请求超时时间（秒）"
            ),
            Map.of(
                    "url", "string",
                    "method", "string",
                    "timeout", "integer"
            ),
            Set.of("url"),
            true,      // 需要审批
            false,     // 不是只读
            false,     // 不是破坏性操作
            List.of("http", "fetch", "request")
    );

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolExecutionResult execute(Map<String, Object> input) {
        String url = (String) input.get("url");
        if (url == null || url.isBlank()) {
            return ToolExecutionResult.error("URL不能为空");
        }
        
        String method = (String) input.getOrDefault("method", "GET");
        int timeout = (int) input.getOrDefault("timeout", 30);
        
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(timeout))
                    .build();
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .method(method.toUpperCase(), HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(timeout))
                    .build();
            
            HttpResponse<String> response = client.send(request, 
                    HttpResponse.BodyHandlers.ofString());
            
            StringBuilder result = new StringBuilder();
            String nl = System.lineSeparator();
            result.append("=== HTTP 响应 ===").append(nl);
            result.append("状态码: ").append(response.statusCode()).append(nl);
            result.append("URL: ").append(response.uri()).append(nl);
            result.append("方法: ").append(method.toUpperCase()).append(nl);
            result.append(nl).append("=== 响应头 ===").append(nl);
            response.headers().map().forEach((key, values) ->
                    result.append(key).append(": ").append(String.join(", ", values)).append(nl));
            result.append(nl).append("=== 响应体 ===").append(nl);
            result.append(response.body());
            
            return ToolExecutionResult.success(result.toString());
        } catch (IllegalArgumentException e) {
            return ToolExecutionResult.error("无效的URL: " + url);
        } catch (Exception e) {
            return ToolExecutionResult.error("请求失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}