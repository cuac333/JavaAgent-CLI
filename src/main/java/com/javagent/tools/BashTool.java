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

    // 平台检测 —— 仅计算一次，JVM 生命周期内不变
    private static final boolean IS_WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");
    // PowerShell 可用性 —— 首次调用 bash 时计算一次
    private static volatile Boolean powershellAvailable;

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "bash",
            "执行 shell 命令。此工具默认禁用，每次执行都需要用户确认。",
            Map.of(
                    "command", "要执行的 shell 命令。",
                    "timeoutSeconds", "进程被终止前的最大运行时间（秒）。"
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
            return ToolExecutionResult.error("bash 需要一个非空命令。");
        }
        if (looksDangerous(command)) {
            return ToolExecutionResult.error("命令被内置安全策略拒绝。");
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
                        builder.append("...（输出已截断）");
                        break;
                    }
                }
                output = builder.toString().trim();
            }

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ToolExecutionResult.error("命令在 " + timeoutSeconds + " 秒后超时。");
            }

            String result = "$ " + command + System.lineSeparator() + output + System.lineSeparator() + "[exit=" + process.exitValue() + "]";
            if (process.exitValue() == 0) {
                return ToolExecutionResult.success(result.trim());
            }
            return ToolExecutionResult.error(result.trim());
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolExecutionResult.error("命令执行失败：" + e.getMessage());
        }
    }

    private static ProcessBuilder buildProcess(String command) {
        if (IS_WINDOWS) {
            // 优先使用 PowerShell（功能更强，语法更接近 Unix），回退到 cmd.exe
            if (isPowerShellAvailable()) {
                return new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command);
            }
            return new ProcessBuilder("cmd.exe", "/c", command);
        }
        return new ProcessBuilder("/bin/bash", "-lc", command);
    }

    /** 检查 PowerShell 是否可用，结果在 JVM 生命周期内缓存 */
    private static boolean isPowerShellAvailable() {
        if (powershellAvailable != null) return powershellAvailable;
        powershellAvailable = isCommandAvailable("powershell.exe");
        return powershellAvailable;
    }

    /** 检查命令是否在系统 PATH 中可用（仅执行一次，结果缓存） */
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
        // 规范化：转小写、合并空白、去除引号以防止简单混淆
        String normalized = command.toLowerCase()
                .replaceAll("[\"']", "")
                .replaceAll("\\s+", " ")
                .trim();

        // ── Windows 专用危险命令 ──
        if (IS_WINDOWS) {
            // 递归删除
            if (Pattern.matches(".*\\b(del|erase)\\s+(/s|/q|/s\\s+/q)(\\s|$).*", normalized)) return true;
            if (Pattern.matches(".*\\b(rd|rmdir)\\s+(/s|/q|/s\\s+/q)(\\s|$).*", normalized)) return true;
            if (Pattern.matches(".*Remove-Item\\s+(-Recurse|-Force|-Recurse\\s+-Force)(\\s|$).*", normalized)) return true;
            if (Pattern.matches(".*rm\\s+(-[a-zA-Z]*r[a-zA-Z]*f|-[a-zA-Z]*f[a-zA-Z]*r)(\\s|$).*", normalized)) return true;

            // 格式化磁盘
            if (Pattern.matches(".*\\bformat\\s+[a-z]:(\\s|$).*", normalized)) return true;

            // 系统关机/重启（标志可出现在任意位置）
            if (Pattern.matches(".*\\b(shutdown|restart)\\b.*\\b(/s|/r|/f|/t)\\b.*", normalized)) return true;
            if (Pattern.matches(".*Stop-Computer(\\s|$).*", normalized)) return true;
            if (Pattern.matches(".*Restart-Computer(\\s|$).*", normalized)) return true;

            // 终止关键进程（/f /t 标志可出现在任意位置）
            if (Pattern.matches(".*\\btaskkill\\b.*\\b(/f|/t)\\b.*\\b(system|wininit|csrss|smss|lsass)\\b.*", normalized)) return true;

            // 修改启动配置
            if (Pattern.matches(".*\\bbcdedit(\\s|$).*", normalized)) return true;

            // 修改执行策略（安全绕过）
            if (Pattern.matches(".*Set-ExecutionPolicy\\s+(Bypass|Unrestricted|RemoteSigned)(\\s|$).*", normalized)) return true;

            // 密码擦除（安全删除）
            if (Pattern.matches(".*\\bcipher\\s+/w(\\s|$).*", normalized)) return true;

            // 获取系统目录所有权
            if (Pattern.matches(".*\\btakeown\\b.*\\b(/s|/f)\\b.*", normalized)) return true;
            if (Pattern.matches(".*icacls\\s+[a-z]:\\\\(\\s|$).*", normalized)) return true;
        }

        // 1. 文件系统破坏：rm -rf 针对根目录、home 目录或通配符
        if (Pattern.matches(".*rm\\s+(-[a-zA-Z]*r[a-zA-Z]*f|-[a-zA-Z]*f[a-zA-Z]*r)\\s*(/|~|\\.\\.?|\\*)\\s*.*", normalized)) {
            return true;
        }
        // rm -r /（没有 -f，仍然具有破坏性）
        if (Pattern.matches(".*rm\\s+-[a-zA-Z]*r[a-zA-Z]*\\s+(/|~)\\s*.*", normalized)) {
            return true;
        }
        // shred / wipefile
        if (Pattern.matches(".*shred\\s+.*", normalized) || normalized.contains("wipefs")) {
            return true;
        }

        // 2. 磁盘/设备操作
        if (Pattern.matches(".*mkfs\\s.*", normalized)) return true;
        if (Pattern.matches(".*dd\\s+if=.*of=/dev/.*", normalized)) return true;
        if (Pattern.matches(".*(fdisk|parted|sfdisk)\\s+(/dev/|[a-z])", normalized)) return true;

        // 3. Fork 炸弹
        if (Pattern.matches(".*:\\(\\)\\s*\\{.*", normalized)) return true;  // :(){ ... };:
        if (normalized.contains("|&") && normalized.contains(":")) return true;

        // 4. 管道到 shell：curl/wget 直接管道到 sh/bash
        if (Pattern.matches(".*(curl|wget)\\s.*\\|\\s*(sh|bash|zsh|python|perl).*", normalized)) return true;

        // 5. 系统关机/重启/断电
        if (Pattern.matches(".*(shutdown|halt|reboot|poweroff|init\\s+[06])\\s.*", normalized)) return true;

        // 6. 终止 PID 1 或终止所有进程
        if (Pattern.matches(".*kill\\s+(-9\\s+)?1(\\s|$).*", normalized)) return true;
        if (Pattern.matches(".*kill\\s+-9\\s+-1(\\s|$).*", normalized)) return true;
        if (Pattern.matches(".*pkill\\s+(-9\\s+)?-1(\\s|$).*", normalized)) return true;

        // 7. 递归 chmod 777 / chown 根目录
        if (Pattern.matches(".*chmod\\s+(-R\\s+)?777\\s+/(\\s|$).*", normalized)) return true;
        if (Pattern.matches(".*chown\\s+.*\\s+/(\\s|$).*", normalized)) return true;

        // 8. 修改 /etc/passwd 或 /etc/shadow
        if (Pattern.matches(".*(>|>>)\\s*/etc/(passwd|shadow|sudoers).*", normalized)) return true;
        if (Pattern.matches(".*chmod\\s+.*\\s+/etc/(passwd|shadow).*", normalized)) return true;

        // 9. 通过 curl/wget POST 进行网络数据外泄
        if (Pattern.matches(".*curl\\s+.*-d\\s.*@.*", normalized)) return true;
        if (Pattern.matches(".*wget\\s+.*--post-file.*", normalized)) return true;

        // 10. 反向 shell 模式
        if (Pattern.matches(".*nc\\s+.*-e\\s+/(bin/)?(ba)?sh.*", normalized)) return true;
        if (Pattern.matches(".*bash\\s+-i\\s+>&\\s+/dev/tcp/.*", normalized)) return true;
        if (Pattern.matches(".*python.*socket.*connect.*", normalized)) return true;

        return false;
    }
}
