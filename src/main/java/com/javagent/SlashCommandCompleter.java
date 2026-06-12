package com.javagent;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.util.ArrayList;
import java.util.List;

/**
 * 斜杠命令的 JLine3 补全器。
 * 用户输入时显示过滤后的命令列表。
 */
public class SlashCommandCompleter implements Completer {

    public record CommandDef(String name, String description) {}

    private final List<CommandDef> commands = new ArrayList<>();

    public void register(String name, String description) {
        commands.add(new CommandDef(name, description));
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        String buffer = line.line().trim();
        if (!buffer.startsWith("/")) return;

        for (CommandDef def : commands) {
            if (def.name().startsWith(buffer)) {
                String display = String.format("%-20s %s", def.name(), def.description());
                candidates.add(new Candidate(
                        def.name(), display, "commands", def.description(),
                        null, null, true
                ));
            }
        }
    }

    public List<CommandDef> allCommands() {
        return new ArrayList<>(commands);
    }
}
