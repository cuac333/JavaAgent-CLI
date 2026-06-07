package com.javagent.tools;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Bash 工具 —— 执行 shell 命令
 *
 * 功能：在系统 shell 中执行命令，返回输出结果
 *
 * 安全机制：
 * - 默认禁用：需要用户手动 /bash on 开启
 * - requiresApproval=true：每次执行都需要用户确认
 * - destructive=true：标记为破坏性操作
 * - 危险命令检测：自动拒绝 rm -rf / 等命令
 * - 超时控制：默认 10 秒，超时自动终止
 * - 输出截断：超过 50,000 字符自动截断
 */
public class BashTool implements Tool {
    private static final int DEFAULT_TIMEOUT_SECONDS = 10;
    private static final int MAX_OUTPUT_CHARS = 50_000;

    // Platform detection — computed once, invariant for JVM lifetime
    private static final boolean IS_WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");
    // PowerShell availability — computed once on first bash invocation
    private static volatile Boolean powershellAvailable;

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "bash",
            "Run a shell command. This tool is disabled by default and always requires approval.",
            Map.of(
                    "command", "Shell command to execute.",
                    "timeoutSeconds", "Maximum runtime before the process is terminated."
            ),
            Map.of(
                    "command", "string",
                    "timeoutSeconds", "integer"
            ),
            Set.of("command"),
            true,
            false,
            true,
            List.of("shell", "exec", "run")
    );

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolExecutionResult execute(Map<String, Object> input) {
        String command = FileToolSupport.stringValue(input.get("command"));
        if (command.isBlank()) {
            return ToolExecutionResult.error("bash requires a non-empty command.");
        }
        if (looksDangerous(command)) {
            return ToolExecutionResult.error("Command rejected by the built-in safety policy.");
        }

        int timeoutSeconds = FileToolSupport.intValue(input.get("timeoutSeconds"), DEFAULT_TIMEOUT_SECONDS);
        ProcessBuilder processBuilder = buildProcess(command);
        processBuilder.redirectErrorStream(true);

        try {
            Process process = processBuilder.start();
            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder builder = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    builder.append(line).append(System.lineSeparator());
                    if (builder.length() > MAX_OUTPUT_CHARS) {
                        builder.append("... (output truncated)");
                        break;
                    }
                }
                output = builder.toString().trim();
            }

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ToolExecutionResult.error("Command timed out after " + timeoutSeconds + " seconds.");
            }

            String result = "$ " + command + System.lineSeparator() + output + System.lineSeparator() + "[exit=" + process.exitValue() + "]";
            if (process.exitValue() == 0) {
                return ToolExecutionResult.success(result.trim());
            }
            return ToolExecutionResult.error(result.trim());
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolExecutionResult.error("Command execution failed: " + e.getMessage());
        }
    }

    private static ProcessBuilder buildProcess(String command) {
        if (IS_WINDOWS) {
            // Prefer PowerShell (more capable, syntax closer to Unix), fallback to cmd.exe
            if (isPowerShellAvailable()) {
                return new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command);
            }
            return new ProcessBuilder("cmd.exe", "/c", command);
        }
        return new ProcessBuilder("/bin/bash", "-lc", command);
    }

    /** Check if PowerShell is available, caching the result for JVM lifetime */
    private static boolean isPowerShellAvailable() {
        if (powershellAvailable != null) return powershellAvailable;
        powershellAvailable = isCommandAvailable("powershell.exe");
        return powershellAvailable;
    }

    /** Check if a command is available on the system PATH (runs once, result cached) */
    private static boolean isCommandAvailable(String cmd) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(IS_WINDOWS ? "where" : "which", cmd);
            pb.redirectErrorStream(true);
            p = pb.start();
            boolean finished = p.waitFor(3, TimeUnit.SECONDS);
            return finished && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (p != null) p.destroy();
        }
    }

    private boolean looksDangerous(String command) {
        // Normalize: lowercase, collapse whitespace, strip quotes to defeat trivial obfuscation
        String normalized = command.toLowerCase()
                .replaceAll("[\"']", "")
                .replaceAll("\\s+", " ")
                .trim();

        // ── Windows-specific dangerous commands ──
        if (IS_WINDOWS) {
            // Recursive delete
            if (Pattern.matches(".*\\b(del|erase)\\s+(/s|/q|/s\\s+/q)(\\s|$).*", normalized)) return true;
            if (Pattern.matches(".*\\b(rd|rmdir)\\s+(/s|/q|/s\\s+/q)(\\s|$).*", normalized)) return true;
            if (Pattern.matches(".*Remove-Item\\s+(-Recurse|-Force|-Recurse\\s+-Force)(\\s|$).*", normalized)) return true;
            if (Pattern.matches(".*rm\\s+(-[a-zA-Z]*r[a-zA-Z]*f|-[a-zA-Z]*f[a-zA-Z]*r)(\\s|$).*", normalized)) return true;

            // Format disk
            if (Pattern.matches(".*\\bformat\\s+[a-z]:(\\s|$).*", normalized)) return true;

            // System halt / reboot (flags can appear anywhere)
            if (Pattern.matches(".*\\b(shutdown|restart)\\b.*\\b(/s|/r|/f|/t)\\b.*", normalized)) return true;
            if (Pattern.matches(".*Stop-Computer(\\s|$).*", normalized)) return true;
            if (Pattern.matches(".*Restart-Computer(\\s|$).*", normalized)) return true;

            // Kill critical processes (/f /t flags can appear anywhere)
            if (Pattern.matches(".*\\btaskkill\\b.*\\b(/f|/t)\\b.*\\b(system|wininit|csrss|smss|lsass)\\b.*", normalized)) return true;

            // Modify boot config
            if (Pattern.matches(".*\\bbcdedit(\\s|$).*", normalized)) return true;

            // Modify execution policy (security bypass)
            if (Pattern.matches(".*Set-ExecutionPolicy\\s+(Bypass|Unrestricted|RemoteSigned)(\\s|$).*", normalized)) return true;

            // Cipher wipe (secure delete)
            if (Pattern.matches(".*\\bcipher\\s+/w(\\s|$).*", normalized)) return true;

            // Take ownership of system dirs
            if (Pattern.matches(".*\\btakeown\\b.*\\b(/s|/f)\\b.*", normalized)) return true;
            if (Pattern.matches(".*icacls\\s+[a-z]:\\\\(\\s|$).*", normalized)) return true;
        }

        // 1. Filesystem destruction: rm -rf targeting root, home, or wildcard
        if (Pattern.matches(".*rm\\s+(-[a-zA-Z]*r[a-zA-Z]*f|-[a-zA-Z]*f[a-zA-Z]*r)\\s*(/|~|\\.\\.?|\\*)\\s*.*", normalized)) {
            return true;
        }
        // rm -r / (without -f, still destructive)
        if (Pattern.matches(".*rm\\s+-[a-zA-Z]*r[a-zA-Z]*\\s+(/|~)\\s*.*", normalized)) {
            return true;
        }
        // shred / wipefile
        if (Pattern.matches(".*shred\\s+.*", normalized) || normalized.contains("wipefs")) {
            return true;
        }

        // 2. Disk/device operations
        if (Pattern.matches(".*mkfs\\s.*", normalized)) return true;
        if (Pattern.matches(".*dd\\s+if=.*of=/dev/.*", normalized)) return true;
        if (Pattern.matches(".*(fdisk|parted|sfdisk)\\s+(/dev/|[a-z])", normalized)) return true;

        // 3. Fork bombs
        if (Pattern.matches(".*:\\(\\)\\s*\\{.*", normalized)) return true;  // :(){ ... };:
        if (normalized.contains("|&") && normalized.contains(":")) return true;

        // 4. Pipe-to-shell: curl/wget piping directly to sh/bash
        if (Pattern.matches(".*(curl|wget)\\s.*\\|\\s*(sh|bash|zsh|python|perl).*", normalized)) return true;

        // 5. System halt/reboot/poweroff
        if (Pattern.matches(".*(shutdown|halt|reboot|poweroff|init\\s+[06])\\s.*", normalized)) return true;

        // 6. Kill PID 1 or kill all processes
        if (Pattern.matches(".*kill\\s+(-9\\s+)?1(\\s|$).*", normalized)) return true;
        if (Pattern.matches(".*kill\\s+-9\\s+-1(\\s|$).*", normalized)) return true;
        if (Pattern.matches(".*pkill\\s+(-9\\s+)?-1(\\s|$).*", normalized)) return true;

        // 7. Recursive chmod 777 / chown on root
        if (Pattern.matches(".*chmod\\s+(-R\\s+)?777\\s+/(\\s|$).*", normalized)) return true;
        if (Pattern.matches(".*chown\\s+.*\\s+/(\\s|$).*", normalized)) return true;

        // 8. /etc/passwd or /etc/shadow modification
        if (Pattern.matches(".*(>|>>)\\s*/etc/(passwd|shadow|sudoers).*", normalized)) return true;
        if (Pattern.matches(".*chmod\\s+.*\\s+/etc/(passwd|shadow).*", normalized)) return true;

        // 9. Network exfiltration via curl/wget POST
        if (Pattern.matches(".*curl\\s+.*-d\\s.*@.*", normalized)) return true;
        if (Pattern.matches(".*wget\\s+.*--post-file.*", normalized)) return true;

        // 10. Reverse shell patterns
        if (Pattern.matches(".*nc\\s+.*-e\\s+/(bin/)?(ba)?sh.*", normalized)) return true;
        if (Pattern.matches(".*bash\\s+-i\\s+>&\\s+/dev/tcp/.*", normalized)) return true;
        if (Pattern.matches(".*python.*socket.*connect.*", normalized)) return true;

        return false;
    }
}
