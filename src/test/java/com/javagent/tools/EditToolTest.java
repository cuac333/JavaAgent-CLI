package com.javagent.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EditToolTest {
    @TempDir
    Path tempDir;

    private final EditTool tool = new EditTool();

    @BeforeEach
    void setUp() {
        FileToolSupport.setWorkspaceRoot(tempDir);
    }

    @Test
    void replacesExactString() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "hello world\nfoo bar\n");

        ToolExecutionResult result = tool.execute(Map.of(
                "path", file.toString(),
                "old_string", "hello world",
                "new_string", "goodbye world"
        ));

        assertFalse(result.error());
        assertTrue(Files.readString(file).contains("goodbye world"));
        assertFalse(Files.readString(file).contains("hello world"));
    }

    @Test
    void errorsWhenOldStringNotFound() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "hello world\n");

        ToolExecutionResult result = tool.execute(Map.of(
                "path", file.toString(),
                "old_string", "nonexistent text",
                "new_string", "replacement"
        ));

        assertTrue(result.error());
        assertTrue(result.content().contains("未精确找到"));
    }

    @Test
    void errorsWhenMultipleMatchesWithoutReplaceAll() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "aaa bbb aaa\n");

        ToolExecutionResult result = tool.execute(Map.of(
                "path", file.toString(),
                "old_string", "aaa",
                "new_string", "ccc"
        ));

        assertTrue(result.error());
        assertTrue(result.content().contains("2 次"));
    }

    @Test
    void replacesAllWhenReplaceAllTrue() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "aaa bbb aaa\n");

        ToolExecutionResult result = tool.execute(Map.of(
                "path", file.toString(),
                "old_string", "aaa",
                "new_string", "ccc",
                "replace_all", "true"
        ));

        assertFalse(result.error());
        assertEquals("ccc bbb ccc\n", Files.readString(file));
    }

    @Test
    void errorsWhenFileNotFound() {
        ToolExecutionResult result = tool.execute(Map.of(
                "path", tempDir.resolve("nonexistent.txt").toString(),
                "old_string", "foo",
                "new_string", "bar"
        ));

        assertTrue(result.error());
        assertTrue(result.content().contains("未找到"));
    }

    @Test
    void errorsOnEmptyOldString() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "hello\n");

        ToolExecutionResult result = tool.execute(Map.of(
                "path", file.toString(),
                "old_string", "",
                "new_string", "bar"
        ));

        assertTrue(result.error());
    }

    @Test
    void errorsWhenOldAndNewAreSame() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "hello\n");

        ToolExecutionResult result = tool.execute(Map.of(
                "path", file.toString(),
                "old_string", "hello",
                "new_string", "hello"
        ));

        assertTrue(result.error());
        assertTrue(result.content().contains("相同"));
    }

    @Test
    void showsContentHintOnMismatch() throws IOException {
        Path file = tempDir.resolve("Hint.java");
        String content = "public class Hint {\n"
                + "    public void method() {\n"
                + "        int x = 1;\n"
                + "    }\n"
                + "}\n";
        Files.writeString(file, content);

        // old_string has wrong indentation — should fail and show content
        String oldString = "        public void method() {\n"
                + "                int x = 1;\n"
                + "        }";

        ToolExecutionResult result = tool.execute(Map.of(
                "path", file.toString(),
                "old_string", oldString,
                "new_string", "replacement"
        ));

        assertTrue(result.error());
        assertTrue(result.content().contains("未精确找到"));
        assertTrue(result.content().contains(">>>"));
        assertTrue(result.content().contains("public void method()"));
    }

    @Test
    void showsFirstLinesWhenProbeNotFound() throws IOException {
        Path file = tempDir.resolve("NoProbe.java");
        Files.writeString(file, "class A {\n    int x;\n}\n");

        ToolExecutionResult result = tool.execute(Map.of(
                "path", file.toString(),
                "old_string", "zzz_completely_unknown_xyz",
                "new_string", "replacement"
        ));

        assertTrue(result.error());
        assertTrue(result.content().contains("未精确找到"));
        assertTrue(result.content().contains("class A"));
    }
}
