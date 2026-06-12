package com.javagent.core;

import com.javagent.model.ToolCall;
import com.javagent.tools.Tool;
import com.javagent.tools.ToolDefinition;
import com.javagent.tools.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ApprovalManagerTest {

    @TempDir
    Path tempDir;

    private Config config;
    private ApprovalManager manager;

    @BeforeEach
    void setUp() throws IOException {
        // Create a minimal config.properties in tempDir
        Files.writeString(tempDir.resolve("config.properties"),
                "agent.mock_mode=true\nagent.max_iterations=6\n");
        config = Config.load(tempDir);
        manager = new ApprovalManager(config);
    }

    @Test
    void readOnlyToolAutoApproved() {
        Tool tool = makeTool("read_file", false, true, false);
        ToolCall call = new ToolCall("id1", "read_file", Map.of("path", "test.txt"));

        ApprovalOutcome outcome = manager.authorize(tool, call, null);

        assertTrue(outcome.approved());
        assertTrue(outcome.reason().contains("自动批准"));
    }

    @Test
    void bashToolDisabledByDefault() {
        Tool tool = makeTool("bash", true, false, true);
        ToolCall call = new ToolCall("id1", "bash", Map.of("command", "echo hi"));

        ApprovalOutcome outcome = manager.authorize(tool, call, null);

        assertFalse(outcome.approved());
        assertTrue(outcome.reason().contains("禁用"));
    }

    @Test
    void destructiveToolRequiresApproval() {
        Tool tool = makeTool("write_file", true, false, true);
        ToolCall call = new ToolCall("id1", "write_file", Map.of("path", "test.txt", "content", "hello"));

        // With a handler that denies
        ApprovalOutcome outcome = manager.authorize(tool, call, tc -> ApprovalDecision.DENIED);

        assertFalse(outcome.approved());
        assertTrue(outcome.reason().contains("拒绝"));
    }

    @Test
    void bypassModeAutoApproves() throws IOException {
        config.setBypassPermissions(true);
        manager = new ApprovalManager(config);

        Tool tool = makeTool("write_file", true, false, true);
        ToolCall call = new ToolCall("id1", "write_file", Map.of("path", "test.txt", "content", "hello"));

        ApprovalOutcome outcome = manager.authorize(tool, call, null);

        assertTrue(outcome.approved());
        assertTrue(outcome.reason().contains("绕过"));
    }

    @Test
    void approvalCacheWorks() {
        Tool tool = makeTool("write_file", true, false, true);
        ToolCall call = new ToolCall("id1", "write_file", Map.of("path", "test.txt", "content", "hello"));

        // First call: approve
        manager.authorize(tool, call, tc -> ApprovalDecision.APPROVED);
        assertEquals(1, manager.cacheSize());

        // Second call: should use cache
        ApprovalOutcome outcome = manager.authorize(tool, call, null);
        assertTrue(outcome.approved());
        assertTrue(outcome.reason().contains("缓存"));
    }

    @Test
    void clearCacheWorks() {
        Tool tool = makeTool("write_file", true, false, true);
        ToolCall call = new ToolCall("id1", "write_file", Map.of("path", "test.txt", "content", "hello"));

        manager.authorize(tool, call, tc -> ApprovalDecision.APPROVED);
        assertEquals(1, manager.cacheSize());

        manager.clearCache();
        assertEquals(0, manager.cacheSize());
    }

    @Test
    void protectedPathDenied() throws IOException {
        // Try to write to .git directory
        Path gitDir = tempDir.resolve(".git");
        Files.createDirectories(gitDir);
        Path gitFile = gitDir.resolve("config");
        Files.writeString(gitFile, "test");

        Tool tool = makeTool("write_file", true, false, true);
        ToolCall call = new ToolCall("id1", "write_file", Map.of("path", gitFile.toString(), "content", "hack"));

        ApprovalOutcome outcome = manager.authorize(tool, call, null);

        assertFalse(outcome.approved());
        assertTrue(outcome.reason().contains("受保护"));
    }

    private Tool makeTool(String name, boolean requiresApproval, boolean readOnly, boolean destructive) {
        ToolDefinition def = new ToolDefinition(
                name, "Test tool",
                Map.of(), Map.of(), Set.of(),
                requiresApproval, readOnly, destructive, List.of()
        );
        return new Tool() {
            @Override
            public ToolDefinition definition() { return def; }
            @Override
            public ToolExecutionResult execute(Map<String, Object> input) { return ToolExecutionResult.success("ok"); }
        };
    }
}
