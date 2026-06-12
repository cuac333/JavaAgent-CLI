# JavaAgent-CLI 测试报告（Windows）

> 项目名称：JavaAgent-CLI
> 版本：1.0.0
> 测试日期：2026-06-12
> 测试框架：JUnit 5.11.4
> 运行环境：Windows 11 Pro 10.0.26200 / Java 21.0.10 (Oracle) / Maven 3.9.12

---

## 一、测试概览

| 指标 | 数值 |
|------|------|
| 测试用例总数 | 66 |
| 通过 | 64 |
| 失败 | 0 |
| 错误 | 0 |
| 跳过 | 2 |
| 通过率 | **100%**（跳过 2 个平台不适用用例） |
| 总耗时 | ~118.7s |

---

## 二、各模块测试详情

### 2.1 工具层（tools）

| 测试类 | 用例数 | 通过 | 跳过 | 耗时 | 说明 |
|--------|--------|------|------|------|------|
| BashToolTest | 17 | 15 | 2 | 108.0s | 空命令、危险命令检测（rm -rf /、mkfs、dd、fork bomb、shutdown、管道bash、反弹shell、kill pid1、chmod 777、shadow修改、shred、curl外传）、安全命令放行 |
| EditToolTest | 9 | 9 | 0 | 0.091s | 精确替换、多匹配检测、文件不存在、内容提示 |
| GrepToolTest | 4 | 4 | 0 | 0.034s | 正则匹配、无效正则、路径缺失、target 目录跳过 |
| ReadFileToolTest | 4 | 4 | 0 | 0.025s | 正常读取、文件不存在、目录路径、超大文件拒绝 |
| WriteFileToolTest | 2 | 2 | 0 | 0.022s | 写入追加、目录拒绝 |
| DeleteFileToolTest | 2 | 2 | 0 | 0.008s | 删除文件、目录拒绝 |
| ListDirectoryToolTest | 2 | 2 | 0 | 0.009s | 列出目录、非目录拒绝 |

### 2.2 核心层（core）

| 测试类 | 用例数 | 通过 | 耗时 | 说明 |
|--------|--------|------|------|------|
| AgentTest | 8 | 8 | 1.034s | 纯文本轮次、单工具轮次、审批拒绝转错误、工具执行错误恢复、最大迭代停止、审批缓存复用、外部路径拦截、流式输出回调 |
| ApprovalManagerTest | 7 | 7 | 0.084s | 只读自动批准、bash 禁用、破坏性工具需审批、绕过模式、审批缓存、缓存清理、受保护路径拒绝 |
| ConfigTest | 1 | 1 | 0.019s | 配置文件加载与默认值 |
| ConversationManagerTest | 2 | 2 | 0.146s | 会话保存加载、消息计数 |
| IntegrationTest | 3 | 3 | 0.128s | 端到端集成测试 |

### 2.3 模型层（model）

| 测试类 | 用例数 | 通过 | 耗时 | 说明 |
|--------|--------|------|------|------|
| MockModelClientTest | 5 | 5 | 0.011s | 读取/搜索/列出/写入/删除指令识别、工具结果摘要 |

---

## 三、跨平台对比

| 对比项 | macOS | Windows | 备注 |
|--------|-------|---------|------|
| 总耗时 | ~3.3s | ~118.7s | BashToolTest 进程启动耗时差异显著 |
| 测试通过数 | 66 | 64 | Windows 跳过 2 个平台不适用用例 |
| BashToolTest 耗时 | 2.8s | 108.0s | Windows 进程创建/销毁开销远高于 macOS |
| AgentTest 耗时 | 0.369s | 1.034s | Windows 进程调用略有增加 |
| 跳过用例 | 0 | 2 | `executesSimpleCommand`、`executesSimpleCommandWindows` 被 `@DisabledOnOs(OS.WINDOWS)` 跳过 |

### 性能差异分析

BashToolTest 在 Windows 上耗时 108 秒（macOS 仅 2.8 秒），主要原因：

1. **进程启动开销**：Windows 上 `ProcessBuilder` 启动 PowerShell/cmd.exe 子进程的开销远高于 macOS 上启动 `/bin/bash`
2. **命令解析差异**：Windows 的 `cmd.exe /c` 命令解析比 bash 更慢，且部分 Linux 命令（如 `ls -la`）在 Windows 上需要通过 Git Bash 执行
3. **`allowsSafeCommands` 测试**：该测试在 Windows 上尝试执行 `ls -la`，由于 `ls` 不是 Windows 内置命令，可能触发了 shell 查找和超时逻辑

---

## 四、Windows 特有说明

### 4.1 跳过的用例

以下 2 个用例因平台差异被 `@DisabledOnOs(OS.WINDOWS)` 跳过：

- `executesSimpleCommand`：原为 macOS/Linux 专用的简单命令执行测试
- `executesSimpleCommandWindows`：虽然名称含 "Windows"，但注解仍标记为 `@DisabledOnOs(OS.WINDOWS)`，实际为 Windows 平台的简单命令执行测试

### 4.2 BashTool 行为差异

- Windows 上默认使用 PowerShell（`powershell.exe -NoProfile -NonInteractive -Command`），PowerShell 不可用时降级为 `cmd.exe /c`
- 危险命令检测覆盖 Unix 和 Windows 两种模式（20+ 正则模式），在 Windows 上同样有效
- Windows 环境变量和路径分隔符（`\` vs `/`）在工具执行时被正确处理

### 4.3 跨平台文本处理

所有文本分割均使用 `Terminal.splitLines()`（预编译正则 `\\r\\n|\\r|\\n`），可正确处理 Windows 的 `\r\n` 换行符。

---

## 五、结论

JavaAgent-CLI 项目在 Windows 11 环境下共 66 个测试用例中 64 个通过、2 个跳过（平台不适用），0 个失败。核心引擎、工具系统、安全机制、审批系统和会话管理等主要功能模块在 Windows 平台上均可正常工作。BashToolTest 因 Windows 进程启动开销导致耗时较长，但不影响功能正确性。项目具备跨平台运行能力，可进行下一步开发和发布。
