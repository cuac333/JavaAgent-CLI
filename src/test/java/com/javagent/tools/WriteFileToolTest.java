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

class WriteFileToolTest {
    @TempDir
    Path tempDir;

    private final WriteFileTool tool = new WriteFileTool();

    @BeforeEach
    void setUp() {
        FileToolSupport.setWorkspaceRoot(tempDir);
    }

    @Test
    void writesAndAppendsTextFiles() throws IOException {
        Path file = tempDir.resolve("notes.txt");

        ToolExecutionResult first = tool.execute(Map.of("path", file.toString(), "content", "hello"));
        ToolExecutionResult second = tool.execute(Map.of("path", file.toString(), "content", "\nworld", "append", true));

        assertFalse(first.error());
        assertFalse(second.error());
        assertTrue(Files.readString(file).contains("hello"));
        assertTrue(Files.readString(file).contains("world"));
    }

    @Test
    void rejectsDirectoryTargets() {
        ToolExecutionResult result = tool.execute(Map.of("path", tempDir.toString(), "content", "bad"));

        assertTrue(result.error());
        assertTrue(result.content().contains("目录"));
    }
}
