package com.javagent.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.javagent.util.Terminal.splitLines;

/**
 * Edit tool — replace exact string occurrences in a file.
 *
 * The old_string must match exactly (including whitespace/indentation).
 * If exact match fails, the actual file content near the expected location
 * is returned so the model can see the real content and retry correctly.
 */
public class EditTool implements Tool {

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "edit",
            "Replace exact text in a file. old_string must match exactly including whitespace. "
                    + "Use replace_all=true to replace all occurrences.",
            Map.of(
                    "path", "File path to edit.",
                    "old_string", "Exact text to find (must match including whitespace).",
                    "new_string", "Replacement text.",
                    "replace_all", "If true, replace all occurrences. Default false."
            ),
            Map.of(
                    "path", "string",
                    "old_string", "string",
                    "new_string", "string",
                    "replace_all", "boolean"
            ),
            Set.of("path", "old_string", "new_string"),
            true,
            false,
            false,
            List.of("replace", "str_replace", "sed")
    );

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolExecutionResult execute(Map<String, Object> input) {
        String rawPath = FileToolSupport.stringValue(input.get("path"));
        if (rawPath.isBlank()) {
            return ToolExecutionResult.error("edit requires a non-empty path.");
        }

        Path path;
        try {
            path = FileToolSupport.normalizePath(rawPath);
        } catch (InvalidPathException e) {
            return ToolExecutionResult.error("Invalid path: " + rawPath);
        }

        String wsError = FileToolSupport.checkInsideWorkspace(path);
        if (wsError != null) {
            return ToolExecutionResult.error(wsError);
        }

        if (!Files.exists(path)) {
            return ToolExecutionResult.error("File not found: " + path);
        }
        if (!Files.isRegularFile(path)) {
            return ToolExecutionResult.error("Path is not a regular file: " + path);
        }

        String oldString = input.get("old_string") == null ? "" : input.get("old_string").toString();
        String newString = input.get("new_string") == null ? "" : input.get("new_string").toString();

        if (oldString.isEmpty()) {
            return ToolExecutionResult.error("edit requires a non-empty old_string.");
        }
        if (oldString.equals(newString)) {
            return ToolExecutionResult.error("old_string and new_string are identical.");
        }

        boolean replaceAll = FileToolSupport.booleanValue(input.get("replace_all"), false);

        try {
            if (FileToolSupport.isBinary(path)) {
                return ToolExecutionResult.error("Cannot edit binary files.");
            }

            String content = Files.readString(path, StandardCharsets.UTF_8);

            int count = countOccurrences(content, oldString);
            if (count == 0) {
                return buildContentHint(content, oldString);
            }
            if (count > 1 && !replaceAll) {
                return ToolExecutionResult.error(
                        "old_string appears " + count + " times. Use replace_all=true to replace all, "
                                + "or provide more surrounding context to make it unique."
                );
            }

            // Exact match — apply edit
            String newContent;
            int changeStartLine = 0;
            if (replaceAll) {
                newContent = content.replace(oldString, newString);
            } else {
                int idx = content.indexOf(oldString);
                changeStartLine = splitLines(content.substring(0, idx)).length - 1;
                newContent = content.substring(0, idx) + newString + content.substring(idx + oldString.length());
            }
            Files.writeString(path, newContent, StandardCharsets.UTF_8);

            String[] oldLines = splitLines(oldString);
            String[] newLines = splitLines(newString);
            String nl = System.lineSeparator();
            String summary = "Edited " + path.toAbsolutePath()
                    + " (-" + oldLines.length + " lines, +" + newLines.length + " lines)" + nl
                    + "```diff" + nl + generateDiff(oldLines, newLines, changeStartLine) + "```";
            return ToolExecutionResult.success(summary);

        } catch (IOException e) {
            return ToolExecutionResult.error("Failed to edit file: " + e.getMessage());
        }
    }

    // ─────────── Content hint on mismatch ───────────

    /**
     * When exact match fails, show the file content near the best guess location
     * so the model can see the actual text and construct the correct old_string.
     */
    private ToolExecutionResult buildContentHint(String content, String oldString) {
        String[] contentLines = splitLines(content);
        String[] oldLines = splitLines(oldString);

        // Find the most distinctive line from old_string
        String probe = "";
        for (String line : oldLines) {
            String stripped = line.strip();
            if (stripped.length() > probe.length()) {
                probe = stripped;
            }
        }

        // Search for it in the file
        int foundLine = -1;
        if (probe.length() >= 6) {
            for (int i = 0; i < contentLines.length; i++) {
                if (contentLines[i].contains(probe)) {
                    foundLine = i;
                    break;
                }
            }
        }

        StringBuilder msg = new StringBuilder();
        msg.append("old_string not found exactly. ");

        if (foundLine >= 0) {
            int ctxStart = Math.max(0, foundLine - 2);
            int ctxEnd = Math.min(contentLines.length, foundLine + oldLines.length + 3);
            msg.append("File content near the expected location (lines ")
                .append(ctxStart + 1).append("-").append(ctxEnd).append("):\n");
            msg.append("```\n");
            for (int i = ctxStart; i < ctxEnd; i++) {
                String marker = (i == foundLine) ? " >>> " : "     ";
                msg.append(String.format("%4d%s%s%n", i + 1, marker, contentLines[i]));
            }
            msg.append("```\n");
            msg.append("Compare with your old_string and fix whitespace/indentation differences.");
        } else {
            // Couldn't find a match at all — show first 30 lines
            int show = Math.min(contentLines.length, 30);
            msg.append("First ").append(show).append(" lines of the file:\n");
            msg.append("```\n");
            for (int i = 0; i < show; i++) {
                msg.append(String.format("%4d     %s%n", i + 1, contentLines[i]));
            }
            msg.append("```\n");
        }

        return ToolExecutionResult.error(msg.toString());
    }

    // ─────────── Diff preview ───────────

    private String generateDiff(String[] oldLines, String[] newLines, int changeStartLine) {
        String nl = System.lineSeparator();
        StringBuilder diff = new StringBuilder();
        diff.append("@@ -").append(changeStartLine + 1).append(",").append(oldLines.length)
            .append(" +").append(changeStartLine + 1).append(",").append(newLines.length).append(" @@" + nl);

        for (String line : oldLines) {
            diff.append(com.javagent.util.Terminal.diffRemove(line)).append(nl);
        }
        for (String line : newLines) {
            diff.append(com.javagent.util.Terminal.diffAdd(line)).append(nl);
        }
        return diff.toString();
    }

    // ─────────── Helpers ───────────

    private int countOccurrences(String text, String pattern) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(pattern, idx)) != -1) {
            count++;
            idx += pattern.length();
        }
        return count;
    }
}
