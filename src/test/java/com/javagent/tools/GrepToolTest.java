package com.javagent.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrepToolTest {
    @TempDir
    Path tempDir;

    private final GrepTool tool = new GrepTool();

    @BeforeEach
    void setUp() {
        FileToolSupport.setWorkspaceRoot(tempDir);
    }

    @Test
    void findsMatchesInFiles() throws IOException {
        Path file = tempDir.resolve("a.txt");
        Files.writeString(file, "agent loop\napproval gate\n");

        ToolExecutionResult result = tool.execute(Map.of("pattern", "agent|approval", "path", tempDir.toString()));

        assertFalse(result.error());
        assertTrue(result.content().contains("agent loop"));
        assertTrue(result.content().contains("approval gate"));
    }

    @Test
    void rejectsInvalidRegex() {
        ToolExecutionResult result = tool.execute(Map.of("pattern", "[abc", "path", tempDir.toString()));

        assertTrue(result.error());
        assertTrue(result.content().contains("无效的正则表达式"));
    }

    @Test
    void rejectsMissingPath() {
        ToolExecutionResult result = tool.execute(Map.of("pattern", "agent", "path", tempDir.resolve("missing").toString()));

        assertTrue(result.error());
        assertTrue(result.content().contains("路径未找到"));
    }

    @Test
    void skipsGeneratedTargetArtifacts() throws IOException {
        Path targetDir = tempDir.resolve("target");
        Files.createDirectories(targetDir);
        Files.writeString(targetDir.resolve("generated.txt"), "agent from target");
        Files.writeString(tempDir.resolve("source.txt"), "agent from source");

        ToolExecutionResult result = tool.execute(Map.of("pattern", "agent", "path", tempDir.toString()));

        assertFalse(result.error());
        assertTrue(result.content().contains("agent from source"));
        assertTrue(!result.content().contains("agent from target"));
    }
}
