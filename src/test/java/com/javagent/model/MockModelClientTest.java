package com.javagent.model;

import com.javagent.tools.GrepTool;
import com.javagent.tools.ListDirectoryTool;
import com.javagent.tools.ReadFileTool;
import com.javagent.tools.WriteFileTool;
import com.javagent.tools.DeleteFileTool;
import com.javagent.tools.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockModelClientTest {
    private final MockModelClient client = new MockModelClient();
    private final List<ToolDefinition> tools = List.of(
            new ReadFileTool().definition(),
            new GrepTool().definition(),
            new ListDirectoryTool().definition(),
            new WriteFileTool().definition(),
            new DeleteFileTool().definition()
    );

    @Test
    void requestsReadFileToolForReadPrompts() {
        ModelResponse response = client.chat("", List.of(Message.user("请读取 pom.xml")), tools);

        assertTrue(response.isToolCalls());
        assertEquals("read_file", response.toolCalls().getFirst().name());
        assertEquals("pom.xml", response.toolCalls().getFirst().input().get("path"));
    }

    @Test
    void requestsGrepToolForSearchPrompts() {
        ModelResponse response = client.chat("", List.of(Message.user("搜索 agent/approval")), tools);

        assertTrue(response.isToolCalls());
        assertEquals("grep", response.toolCalls().getFirst().name());
        assertEquals("agent|approval", response.toolCalls().getFirst().input().get("pattern"));
    }

    @Test
    void summarizesToolResults() {
        Message toolMessage = Message.toolResult("1", "read_file", "File: pom.xml\n1: <project>", false);

        ModelResponse response = client.chat("", List.of(Message.user("读取 pom.xml"), toolMessage), tools);

        assertTrue(response.isText());
        assertTrue(response.content().contains("已成功读取请求的文件"));
    }

    @Test
    void requestsWriteFileToolForWritePrompts() {
        ModelResponse response = client.chat("", List.of(Message.user("把 \"hello world\" 写入 notes.txt")), tools);

        assertTrue(response.isToolCalls());
        assertEquals("write_file", response.toolCalls().getFirst().name());
        assertEquals("notes.txt", response.toolCalls().getFirst().input().get("path"));
    }

    @Test
    void requestsListDirectoryToolForListPrompts() {
        ModelResponse response = client.chat("", List.of(Message.user("列出 src/main/java")), tools);

        assertTrue(response.isToolCalls());
        assertEquals("list_directory", response.toolCalls().getFirst().name());
        assertEquals("src/main/java", response.toolCalls().getFirst().input().get("path"));
    }
}
