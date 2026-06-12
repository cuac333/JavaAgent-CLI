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

class DeleteFileToolTest {
    @TempDir
    Path tempDir;

    private final DeleteFileTool tool = new DeleteFileTool();

    @BeforeEach
    void setUp() {
        FileToolSupport.setWorkspaceRoot(tempDir);
    }

    @Test
    void deletesRegularFiles() throws IOException {
        Path file = tempDir.resolve("obsolete.txt");
        Files.writeString(file, "old");

        ToolExecutionResult result = tool.execute(Map.of("path", file.toString()));

        assertFalse(result.error());
        assertTrue(Files.notExists(file));
    }

    @Test
    void rejectsDirectories() {
        ToolExecutionResult result = tool.execute(Map.of("path", tempDir.toString()));

        assertTrue(result.error());
        assertTrue(result.content().contains("普通文件"));
    }
}
