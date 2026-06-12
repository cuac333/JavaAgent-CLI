package com.javagent.core;

import com.javagent.model.Message;
import com.javagent.model.ModelClient;
import com.javagent.model.ModelResponse;
import com.javagent.model.TextStreamHandler;
import com.javagent.model.MockModelClient;
import com.javagent.model.ToolCall;
import com.javagent.tools.BashTool;
import com.javagent.tools.DeleteFileTool;
import com.javagent.tools.GrepTool;
import com.javagent.tools.ReadFileTool;
import com.javagent.tools.ToolDefinition;
import com.javagent.tools.ToolRegistry;
import com.javagent.tools.WriteFileTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTest {
    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        new ToolRegistry().setWorkspaceRoot(tempDir);
    }

    @Test
    void handlesPureTextTurn() throws IOException {
        Config config = Config.load(tempDir);
        ConversationManager manager = new ConversationManager(config);
        ToolRegistry registry = new ToolRegistry();
        Agent agent = new Agent(config, new StaticTextModelClient("plain response"), registry, manager);

        String response = agent.processTurn("hello", toolCall -> ApprovalDecision.APPROVED);

        assertEquals("plain response", response);
        assertEquals(2, manager.messageCount());
    }

    @Test
    void completesSingleReadToolTurn() throws IOException {
        Path file = tempDir.resolve("pom.xml");
        Files.writeString(file, "<project>\n<artifactId>demo</artifactId>\n</project>\n");

        Config config = Config.load(tempDir);
        ConversationManager manager = new ConversationManager(config);
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        registry.register(new GrepTool());
        Agent agent = new Agent(config, new MockModelClient(), registry, manager);

        String response = agent.processTurn("读取 " + file, toolCall -> ApprovalDecision.APPROVED);

        assertTrue(response.contains("已成功读取请求的文件"));
        assertTrue(manager.messageCount() >= 4);
    }

    @Test
    void convertsApprovalDenialIntoToolErrorAndFinalText() throws IOException {
        Config config = Config.load(tempDir);
        config.setBashEnabled(true);
        ConversationManager manager = new ConversationManager(config);
        ToolRegistry registry = new ToolRegistry();
        registry.register(new BashTool());
        Agent agent = new Agent(config, new SequenceModelClient(List.of(
                ModelResponse.toolCalls("Need to run a command.", List.of(ToolCall.of("bash", Map.of("command", "echo hi")))),
                ModelResponse.text("The command was not executed because approval was denied.")
        )), registry, manager);

        String response = agent.processTurn("run echo hi", toolCall -> ApprovalDecision.DENIED);

        assertTrue(response.contains("approval was denied"));
        assertEquals(4, manager.messageCount());
    }

    @Test
    void recoversFromToolExecutionError() throws IOException {
        Config config = Config.load(tempDir);
        ConversationManager manager = new ConversationManager(config);
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        Agent agent = new Agent(config, new SequenceModelClient(List.of(
                ModelResponse.toolCalls("Need to read a file.", List.of(ToolCall.of("read_file", Map.of("path", tempDir.resolve("missing.txt").toString())))),
                ModelResponse.text("The file could not be read because it does not exist.")
        )), registry, manager);

        String response = agent.processTurn("read missing file", toolCall -> ApprovalDecision.APPROVED);

        assertTrue(response.contains("does not exist"));
    }

    @Test
    void stopsAfterMaxIterations() throws IOException {
        Path file = tempDir.resolve("README.md");
        Files.writeString(file, "agent");

        Config config = Config.load(tempDir);
        ConversationManager manager = new ConversationManager(config);
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        Agent agent = new Agent(config, new LoopingModelClient(file), registry, manager);

        String response = agent.processTurn("read forever", toolCall -> ApprovalDecision.APPROVED);

        assertTrue(response.contains("最大工具调用次数上限"));
    }

    @Test
    void reusesApprovalCacheForRepeatedDangerousCalls() throws IOException {
        Path file = tempDir.resolve("notes.txt");
        Config config = Config.load(tempDir);
        ConversationManager manager = new ConversationManager(config);
        ToolRegistry registry = new ToolRegistry();
        registry.register(new WriteFileTool());
        Agent agent = new Agent(config, new SequenceModelClient(List.of(
                ModelResponse.toolCalls("write once", List.of(ToolCall.of("write_file", Map.of("path", file.toString(), "content", "alpha")))),
                ModelResponse.text("first done"),
                ModelResponse.toolCalls("write twice", List.of(ToolCall.of("write_file", Map.of("path", file.toString(), "content", "alpha")))),
                ModelResponse.text("second done")
        )), registry, manager);

        AtomicInteger approvalPrompts = new AtomicInteger();
        ApprovalHandler approvalHandler = toolCall -> {
            approvalPrompts.incrementAndGet();
            return ApprovalDecision.APPROVED;
        };

        assertEquals("first done", agent.processTurn("write the file", approvalHandler));
        assertEquals("second done", agent.processTurn("write the file again", approvalHandler));
        assertEquals(1, approvalPrompts.get());
    }

    @Test
    void blocksExternalPathsWhenWorkspacePolicyForbidsThem() throws IOException {
        Path externalDirectory = tempDir.getParent().resolve("external-space");
        Files.createDirectories(externalDirectory);
        Path externalFile = externalDirectory.resolve("secret.txt");
        Files.writeString(externalFile, "outside");

        Config config = Config.load(tempDir);
        ConversationManager manager = new ConversationManager(config);
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        Agent agent = new Agent(config, new SequenceModelClient(List.of(
                ModelResponse.toolCalls("Need to read outside.", List.of(ToolCall.of("read_file", Map.of("path", externalFile.toString())))),
                ModelResponse.text("The read was blocked by policy.")
        )), registry, manager);

        String response = agent.processTurn("read an external file", toolCall -> ApprovalDecision.APPROVED);

        assertTrue(manager.currentContext().stream().anyMatch(message ->
                message.toolResult() != null && message.toolResult().content().contains("工作区")));
    }

    @Test
    void streamsFinalResponsesIntoCallback() throws IOException {
        Config config = Config.load(tempDir);
        ConversationManager manager = new ConversationManager(config);
        ToolRegistry registry = new ToolRegistry();
        Agent agent = new Agent(config, new StaticTextModelClient("stream this response"), registry, manager);

        StringBuilder streamed = new StringBuilder();
        String response = agent.processTurn("hello", toolCall -> ApprovalDecision.APPROVED, streamed::append);

        assertEquals("stream this response", response);
        assertEquals("stream this response", streamed.toString());
    }

    private static final class StaticTextModelClient implements ModelClient {
        private final String text;

        private StaticTextModelClient(String text) {
            this.text = text;
        }

        @Override
        public ModelResponse chat(String systemPrompt, List<Message> messages, List<ToolDefinition> tools) {
            return ModelResponse.text(text);
        }

        @Override
        public String name() {
            return "static";
        }
    }

    private static final class SequenceModelClient implements ModelClient {
        private final Deque<ModelResponse> responses;

        private SequenceModelClient(List<ModelResponse> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public ModelResponse chat(String systemPrompt, List<Message> messages, List<ToolDefinition> tools) {
            return responses.isEmpty() ? ModelResponse.text("sequence complete") : responses.removeFirst();
        }

        @Override
        public String name() {
            return "sequence";
        }
    }

    private static final class LoopingModelClient implements ModelClient {
        private final Path file;

        private LoopingModelClient(Path file) {
            this.file = file;
        }

        @Override
        public ModelResponse chat(String systemPrompt, List<Message> messages, List<ToolDefinition> tools) {
            return ModelResponse.toolCalls("loop", List.of(ToolCall.of("read_file", Map.of("path", file.toString()))));
        }

        @Override
        public String name() {
            return "loop";
        }
    }
}
