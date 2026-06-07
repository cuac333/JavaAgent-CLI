package com.javagent;

import com.javagent.core.Config;
import com.javagent.core.ConversationManager;
import com.javagent.tools.ToolRegistry;

import java.io.IOException;
import java.io.PrintWriter;

import static com.javagent.util.Terminal.*;

/**
 * 启动横幅渲染器 —— Claude Code 风格的方框横幅
 */
final class BannerPrinter {
    private final Config config;
    private final ToolRegistry toolRegistry;
    private final ConversationManager conversationManager;
    private final String version;

    BannerPrinter(Config config, ToolRegistry toolRegistry,
                  ConversationManager conversationManager, String version) {
        this.config = config;
        this.toolRegistry = toolRegistry;
        this.conversationManager = conversationManager;
        this.version = version;
    }

    void print(PrintWriter out) throws IOException {
        String tl = "╭", tr = "╮", bl = "╰", br = "╯";
        String h = "─", v = "│";

        int boxWidth = 76;
        int rightCol = 36;
        int leftCol = boxWidth - rightCol - 1;

        String hLine = h.repeat(boxWidth);
        String sep = h.repeat(rightCol - 1);
        String innerPad = " ".repeat(leftCol);

        String modelInfo = (config.isMockMode() ? yellow("mock") : green("real"))
                + dim(" · ") + cyan(config.model())
                + dim(" · ") + toolRegistry.definitions().size() + " tools";

        out.println();
        String titlePart = "JavaAgent CLI v" + version;
        int titleVisibleLen = displayWidth(titlePart);
        out.println(dim(tl + h + " ") + bold(brightCyan("JavaAgent CLI")) + dim(" v" + version)
                + dim(" " + h.repeat(Math.max(0, boxWidth - titleVisibleLen - 3)) + tr));

        int headerRightWidth = 8;
        out.println(dim(v) + innerPad + dim(v) + "  " + bold("快速开始  ")
                + dim(" ".repeat(Math.max(0, rightCol - 2 - 2 - headerRightWidth))) + dim(v));
        out.println(dim(v) + innerPad + dim(v) + dim(" " + sep) + dim(v));

        printRow(out, v, "输入问题，Agent 自动调用工具帮你", leftCol, rightCol, "/help", "查看所有命令");
        printRow(out, v, "只读工具(read,grep,ls)免审批运行", leftCol, rightCol, "/tools", "查看可用工具");
        printRow(out, v, "文件编辑(edit,write,delete)需确认", leftCol, rightCol, "/status", "查看运行状态");
        printRow(out, v, "输入 / 然后按 Tab 键自动补全命令", leftCol, rightCol, "/exit", "退出程序");

        out.println(dim(v) + " ".repeat(leftCol) + dim(v) + dim(" " + sep) + dim(v));

        String cwd = config.workingDirectory().toString();
        int cwdWidth = displayWidth(cwd);
        String rightContent = modelInfo;
        int rightContentWidth = displayWidth(stripAnsi(rightContent));
        out.println(dim(v) + "  " + filePath(cwd)
                + dim(" ".repeat(Math.max(0, leftCol - 2 - cwdWidth))) + dim(v) + "  " + rightContent
                + dim(" ".repeat(Math.max(0, rightCol - 2 - rightContentWidth))) + dim(v));

        out.println(dim(bl + hLine + br));

        if (conversationManager.loadLastSession()) {
            out.println(dim("  会话: ") + cyan(conversationManager.currentSessionTitle())
                    + dim(" (" + conversationManager.messageCount() + " 条消息)"));
        }
        out.println();
        out.flush();
    }

    /**
     * Print a compact one-line status bar (can be called after config changes).
     */
    static void printStatusLine(Config config, ToolRegistry toolRegistry, PrintWriter out) {
        String mode = config.isMockMode() ? yellow("mock") : green("real");
        String model = cyan(config.model());
        int toolCount = toolRegistry.definitions().size();
        String stream = config.streamResponses() ? green("开") : dim("关");
        String bash = config.bashEnabled() ? green("开") : dim("关");
        String bypass = config.bypassPermissions() ? green("开") : dim("关");
        String effort = switch (config.effort()) {
            case "low" -> dim("low");
            case "high" -> yellow("high");
            case "xhigh" -> magenta("xhigh");
            case "max" -> red("max");
            case "ultra" -> neon("ultra", 0);
            default -> dim(config.effort());
        };

        out.println(dim("  ─ ") + mode + dim(" · ") + model + dim(" · ")
                + toolCount + " tools" + dim(" │ ")
                + dim("流式:") + stream + dim(" │ ")
                + dim("Bash:") + bash + dim(" │ ")
                + dim("审批:") + bypass + dim(" │ ")
                + dim("思考:") + effort
                + dim(" ─"));
        out.flush();
    }

    private void printRow(PrintWriter out, String v, String leftText, int leftCol, int rightCol,
                           String rightCmd, String rightDesc) {
        int visLen = displayWidth(stripAnsi(leftText));
        String padding = " ".repeat(Math.max(0, leftCol - 2 - visLen));
        String rightPart = cyan(rightCmd) + dim("  " + rightDesc);
        int rightWidth = displayWidth(stripAnsi(rightCmd)) + 2 + displayWidth(rightDesc);
        String rightPadding = " ".repeat(Math.max(0, rightCol - 2 - rightWidth));
        out.println(dim(v) + "  " + dim(leftText) + padding + dim(v) + "  " + rightPart + rightPadding + dim(v));
    }

    static int displayWidth(String s) {
        int width = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < s.length()) {
                int cp = Character.toCodePoint(c, s.charAt(i + 1));
                width += isWide(cp) ? 2 : 1;
                i++;
            } else {
                width += isWide(c) ? 2 : 1;
            }
        }
        return width;
    }

    private static boolean isWide(int cp) {
        if (cp >= 0x4E00 && cp <= 0x9FFF) return true;
        if (cp >= 0x3400 && cp <= 0x4DBF) return true;
        if (cp >= 0x20000 && cp <= 0x2A6DF) return true;
        if (cp >= 0xF900 && cp <= 0xFAFF) return true;
        if (cp >= 0x3000 && cp <= 0x33FF) return true;
        if (cp >= 0xFF01 && cp <= 0xFF60) return true;
        if (cp >= 0xFFE0 && cp <= 0xFFE6) return true;
        return false;
    }
}
