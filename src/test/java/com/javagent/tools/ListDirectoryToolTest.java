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

class ListDirectoryToolTest {
    @TempDir
    Path tempDir;

    private final ListDirectoryTool tool = new ListDirectoryTool();

    @BeforeEach
    void setUp() {
        FileToolSupport.setWorkspaceRoot(tempDir);
    }

    @Test
    void listsDirectoryEntries() throws IOException {
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("README.md"), "demo");

        ToolExecutionResult result = tool.execute(Map.of("path", tempDir.toString()));

        assertFalse(result.error());
        assertTrue(result.content().contains("[D] src"));
        assertTrue(result.content().contains("[F] README.md"));
    }

    @Test
    void rejectsNonDirectoryPaths() throws IOException {
        Path file = tempDir.resolve("a.txt");
        Files.writeString(file, "demo");

        ToolExecutionResult result = tool.execute(Map.of("path", file.toString()));

        assertTrue(result.error());
        assertTrue(result.content().contains("不是目录"));
    }
}
