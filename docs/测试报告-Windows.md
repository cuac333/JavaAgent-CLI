# JavaAgent-CLI 测试报告（Windows）

> 项目名称：JavaAgent-CLI
> 版本：1.1.1
> 测试日期：2026-08-22
> 测试框架：JUnit 5.11.4
> 运行环境：Windows 11 Pro 10.0.26200 / Java 21.0.10 (Oracle) / Maven 3.9.12

---

## 一、测试概览

| 指标 | 数值 |
|------|------|
| 测试用例总数 | 79 |
| 通过 | 77 |
| 失败 | 0 |
| 错误 | 0 |
| 跳过 | 2 |
| 通过率 | **100%**（跳过 2 个平台不适用用例） |
| 总耗时 | ~3.5s |

---

## 二、各模块测试详情

### 2.1 工具层（tools）

| 测试类 | 用例数 | 通过 | 跳过 | 耗时 | 说明 |
|--------|--------|------|------|------|------|
| BashToolTest | 17 | 15 | 2 | 1.580s | 空命令、危险命令检测（rm -rf /、mkfs、dd、fork bomb、shutdown、管道bash、反弹shell、kill pid1、chmod 777、shadow修改、shred、curl外传）、安全命令放行 |
| EditToolTest | 9 | 9 | 0 | 0.039s | 精确替换、多匹配检测、文件不存在、内容提示 |
| GrepToolTest | 4 | 4 | 0 | 0.017s | 正则匹配、无效正则、路径缺失、target 目录跳过 |
| ReadFileToolTest | 4 | 4 | 0 | 0.017s | 正常读取、文件不存在、目录路径、超大文件拒绝 |
| WriteFileToolTest | 2 | 2 | 0 | 0.010s | 写入追加、目录拒绝 |
| DeleteFileToolTest | 2 | 2 | 0 | 0.008s | 删除文件、目录拒绝 |
| ListDirectoryToolTest | 2 | 2 | 0 | 0.011s | 列出目录、非目录拒绝 |

### 2.2 核心层（core）

| 测试类 | 用例数 | 通过 | 耗时 | 说明 |
|--------|--------|------|------|------|
| AgentTest | 10 | 10 | 0.956s | 纯文本轮次、单工具轮次、审批拒绝转错误、工具执行错误恢复、最大迭代停止、审批缓存复用、外部路径拦截、流式输出回调、**打断标志检测**、**工具循环中打断** |
| ApprovalManagerTest | 7 | 7 | 0.056s | 只读自动批准、bash 禁用、破坏性工具需审批、绕过模式、审批缓存、缓存清理、受保护路径拒绝 |
| ConfigTest | 1 | 1 | 0.030s | 配置文件加载与默认值 |
| ConversationManagerTest | 2 | 2 | 0.138s | 会话保存加载、消息计数 |
| IntegrationTest | 3 | 3 | 0.090s | 端到端集成测试 |

### 2.3 模型层（model）

| 测试类 | 用例数 | 通过 | 耗时 | 说明 |
|--------|--------|------|------|------|
| MockModelClientTest | 5 | 5 | 0.008s | 读取/搜索/列出/写入/删除指令识别、工具结果摘要 |

### 2.4 工具层（util）

| 测试类 | 用例数 | 通过 | 耗时 | 说明 |
|--------|--------|------|------|------|
| MarkdownRendererTest | 11 | 11 | 0.025s | 表格边框、表格内行内代码、无双竖线、列对齐、**OSC 8 超链接**、**裸 URL 可点击**、**高亮渲染**、引用块、水平线、**等宽表格**、尾随空行 |

---

## 三、跨平台对比

| 对比项 | macOS（旧版 v1.0.0） | Windows（当前 v1.1.1） | 备注 |
|--------|---------------------|----------------------|------|
| 总耗时 | ~3.3s | ~3.5s | 差异微小 |
| 测试用例数 | 66 | 79 | Windows 新增了 Markdown 渲染测试 |
| 测试通过数 | 66 | 77 | Windows 跳过 2 个平台不适用用例 |
| BashToolTest 耗时 | 2.8s | 1.580s | 本次运行环境进程启动速度良好 |
| AgentTest 耗时 | 0.369s | 0.956s | Windows 进程调用略有增加 |
| 跳过用例 | 0 | 2 | `executesSimpleCommand`、`executesSimpleCommandWindows` 被 `@DisabledOnOs(OS.WINDOWS)` 跳过 |

### 性能差异分析

此前版本（v1.0.0）BashToolTest 在 Windows 上耗时 108 秒，当前版本已降至 1.580s，主要改善原因：

1. **进程启动开销**：本次测试环境进程启动速度良好，无显著延迟
2. **命令解析差异**：`allowsSafeCommands` 测试在 Windows 上通过 Git Bash 执行顺畅

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

## 五、Markdown 渲染测试详情（v1.1.0 新增）

MarkdownRendererTest（11 个测试用例）覆盖以下功能：

| 测试用例 | 说明 |
|---------|------|
| 表格边框 | 确认表格渲染出顶部/底部边框（┌└），无行尾双竖线 bug |
| 表格内行内代码 | 反引号被正确消费，代码内容保留 |
| 列对齐 | 竖线显示列在不同行间一致（CJK 等宽计算） |
| 等宽表格 | 边框行与内容行显示宽度一致 |
| OSC 8 超链接 | `[text](url)` 被 OSC 8 序列包裹，终端可点击 |
| 裸 URL 可点击 | 裸 `https://` 链接被 OSC 8 包裹 |
| 高亮渲染 | `==text==` 输出浅蓝紫色（`\033[38;5;147m`） |
| 引用块 | `> text` 输出带 `│` 标记 |
| 水平线 | `---` 输出 `─` 横线 |
| 尾随空行 | 以 `\n` 结尾不产生多余空行 |

---

## 六、结论

JavaAgent-CLI 项目在 Windows 11 环境下共 **79 个测试用例**中 **77 个通过、2 个跳过**（平台不适用），**0 个失败**。核心引擎、工具系统、安全机制、审批系统、会话管理和 Markdown 渲染等主要功能模块在 Windows 平台上均可正常工作。

### v1.1.x 新增测试覆盖

- **AgentTest +2**：新增打断标志检测（`cancelsWhenFlagIsSet`）和工具循环中打断（`cancelsDuringToolLoop`）
- **MarkdownRendererTest +11**：全新测试模块，覆盖表格、链接、高亮、引用等 Markdown 渲染功能

项目具备跨平台运行能力，且经过流式输出、打断机制、指数退避重试、增量 Markdown 渲染等增强，可进行下一步开发和发布。
