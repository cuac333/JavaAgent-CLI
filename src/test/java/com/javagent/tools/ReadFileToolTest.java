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

class ReadFileToolTest {
    @TempDir
    Path tempDir;

    private final ReadFileTool tool = new ReadFileTool();

    @BeforeEach
    void setUp() {
        FileToolSupport.setWorkspaceRoot(tempDir);
    }

    @Test
    void readsRegularTextFile() throws IOException {
        Path file = tempDir.resolve("sample.txt");
        Files.writeString(file, "line1\nline2\nline3\n");

        ToolExecutionResult result = tool.execute(Map.of("path", file.toString(), "limit", 2));

        assertFalse(result.error());
        assertTrue(result.content().contains("line1"));
        assertTrue(result.content().contains("line2"));
    }

    @Test
    void failsWhenFileDoesNotExist() {
        ToolExecutionResult result = tool.execute(Map.of("path", tempDir.resolve("missing.txt").toString()));

        assertTrue(result.error());
        assertTrue(result.content().contains("文件未找到"));
    }

    @Test
    void failsOnDirectoryPath() {
        ToolExecutionResult result = tool.execute(Map.of("path", tempDir.toString()));

        assertTrue(result.error());
        assertTrue(result.content().contains("不是普通文件"));
    }

    @Test
    void rejectsOversizedFiles() throws IOException {
        Path file = tempDir.resolve("large.txt");
        Files.writeString(file, "a".repeat(300_000));

        ToolExecutionResult result = tool.execute(Map.of("path", file.toString()));

        assertTrue(result.error());
        assertTrue(result.content().contains("过大"));
    }
}
