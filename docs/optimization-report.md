# JavaAgent CLI 优化报告

> 优化目标：将 JavaAgent CLI 从基础命令行工具升级为 Claude Code 风格的交互式 CLI 应用
>
> 优化轮次：多轮迭代
>
> 测试结果：66 个测试全部通过（原 30 → 现 66）

---

## 第一轮优化：核心能力补齐

### 1.1 新增文件

| 文件路径 | 说明 |
|---------|------|
| `src/main/java/com/javagent/util/Terminal.java` | ANSI 终端格式化工具类 |
| `src/main/java/com/javagent/tools/EditTool.java` | 精确文本替换编辑工具 |
| `src/main/java/com/javagent/model/ToolDisplayCallback.java` | 工具执行显示回调接口 |
| `src/test/java/com/javagent/tools/EditToolTest.java` | EditTool 单元测试（7 个用例） |

### 1.2 修改文件

| 文件路径 | 说明 |
|---------|------|
| `src/main/java/com/javagent/JavaAgentCLI.java` | CLI 主界面全面升级 |
| `src/main/java/com/javagent/core/Agent.java` | 新增工具执行显示回调 |
| `src/main/java/com/javagent/model/OpenAiCompatibleModelClient.java` | 实现真正 SSE 流式输出 |
| `src/main/java/com/javagent/model/MockModelClient.java` | Mock 模式支持编辑工具 |

---

### 1.3 Terminal.java — ANSI 终端格式化工具类

**功能：** 提供终端颜色和样式支持，自动检测终端能力。

```
提供的方法：
- bold(), dim(), italic()          — 文本样式
- red(), green(), yellow()         — 基础颜色
- cyan(), blue(), magenta()        — 基础颜色
- brightRed(), brightGreen()       — 亮色变体
- brightCyan(), brightYellow()     — 亮色变体
- gray()                           — 灰色
- colorize(ansiCode, text)         — 通用着色
- prompt()                         — 彩色提示符
- isEnabled()                      — 检测颜色支持
- lineNumber(num, line)            — 带行号的代码行
- diffRemove(line)                 — Diff 删除行（红色）
- diffAdd(line)                    — Diff 新增行（绿色）
- filePath(path)                   — 文件路径着色（亮蓝）
- truncate(text, maxLen)           — 截断文本
```

**自动检测逻辑：**
- 检查 `TERM` 环境变量（`dumb` 则禁用）
- 检查 Windows Terminal / ConEmu / VS Code 终端
- Windows 10+ 的 cmd/powershell 支持 ANSI
- 输出被重定向时自动禁用颜色

---

### 1.4 EditTool.java — 精确文本替换工具

**功能：** 类似 Claude Code 的核心编辑能力，通过 `old_string` 精确匹配文件内容并替换为 `new_string`。

**参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `path` | string | 是 | 文件路径 |
| `old_string` | string | 是 | 要查找的精确文本（含空格缩进） |
| `new_string` | string | 是 | 替换文本 |
| `replace_all` | boolean | 否 | 是否替换所有匹配项，默认 false |

**安全机制：**
- `requiresApproval=true` — 需要用户确认
- `destructive=false` — 不标记为破坏性（受控修改）
- 拒绝编辑二进制文件
- 多个匹配时要求 `replace_all=true` 或更精确的上下文

**智能提示：**
- 找不到精确匹配时，尝试模糊查找首行文本
- 如果发现类似文本，提示"类似文本在第 X 行附近，检查空格/缩进"
- 返回编辑统计：`-N lines, +M lines`

**别名：** `replace`, `sed`

---

### 1.5 OpenAiCompatibleModelClient.java — SSE 流式输出

**改动前：** 等待 API 返回完整响应 → 切成小块模拟流式输出

**改动后：** 真正的 Server-Sent Events 流式处理

```
请求体新增：
  "stream": true
  "stream_options": { "include_usage": false }

处理流程：
  1. 使用 HttpClient.BodyHandlers.ofLines() 逐行读取 SSE 事件流
  2. 解析每个 "data:" 行的 JSON
  3. 提取 delta.content 并实时调用 streamHandler.onChunk()
  4. 增量解析工具调用的 id/name/arguments（流式工具调用支持）
  5. [DONE] 事件结束流
```

**关键实现细节：**
- `ToolCallDelta` 内部类用于增量累积工具调用信息
- 工具调用的 `arguments` 通过 `StringBuilder` 逐步拼接
- 流式和非流式请求共用 `buildRequestBody()` 方法（`stream` 参数区分）
- 错误响应使用非流式方式处理
- 支持 thinking 模型的 `reasoning_content` 字段

---

### 1.6 Agent.java — 工具执行显示回调 + 安全增强

**新增接口：** `ToolDisplayCallback`

```java
public interface ToolDisplayCallback {
    void onToolStart(String toolName, String summary);
    void onToolEnd(String toolName, boolean success, String resultSummary);
    default void onToolEnd(String toolName, boolean success, String resultSummary, String fullContent);
}
```

**Agent 改动：**
- `processTurn()` 新增 `ToolDisplayCallback` 参数
- 工具执行前调用 `onToolStart(toolName, summarizeToolCall(toolCall))`
- 工具执行后调用 `onToolEnd(toolName, !result.error(), truncateResult(), fullContent)`
- 系统提示词增加编辑工具使用指导
- 新增连续失败保护：同一工具连续失败 3 次自动中断
- 工具结果经 `Sanitizer` 脱敏后存储
- 上下文压缩 `compactIfNeeded` 防止上下文超限
- Thinking 模型 `reasoningContent` 全链路支持
- API Reasoning Content 缺失时自动清理旧上下文并重试

---

### 1.7 JavaAgentCLI.java — 第一轮改动

| 改动点 | 改动前 | 改动后 |
|--------|--------|--------|
| 提示符 | `javaagent> ` | 青色加粗 `> ` |
| 启动信息 | 五行大学横幅 | 方框风格横幅 + 模式 + 模型信息 |
| 命令帮助 | 纯文本列表 | 青色命令 + 灰色说明 |
| 工具列表 | 纯文本 | 着色标签 `[auto]` / `[approval]` |
| 审批提示 | `yes/no/cancel` | `? Approval required` 风格 |
| 状态显示 | 纯文本 key=value | 分组对齐显示 |
| 等待指示 | 无 | Braille 动画 spinner |
| 工具执行 | 静默 | 方框边框彩色状态行 |
| 终端输入 | BufferedReader | JLine3 LineReader |

---

### 1.8 MockModelClient.java — 支持 EditTool

- 新增关键词匹配：`edit`、`replace`、`修改`、`替换` → 触发 EditTool
- `summarizeToolResult()` 新增 `edit` 分支的总结模板

---

## 第二轮优化：界面体验打磨

### 2.1 修改文件

| 文件路径 | 说明 |
|---------|------|
| `src/main/java/com/javagent/util/Terminal.java` | 新增 5 个格式化辅助方法 |
| `src/main/java/com/javagent/util/MarkdownRenderer.java` | Markdown-to-ANSI 渲染器 |
| `src/main/java/com/javagent/model/ToolDisplayCallback.java` | 扩展支持完整内容回调 |
| `src/main/java/com/javagent/core/Agent.java` | 传递完整工具结果给回调 |
| `src/main/java/com/javagent/JavaAgentCLI.java` | 全面重写，所有显示优化 |
| `src/main/java/com/javagent/BannerPrinter.java` | 方框风格启动横幅 |
| `src/main/java/com/javagent/SlashCommandCompleter.java` | 斜杠命令 Tab 自动补全 |

---

### 2.2 启动横幅 (BannerPrinter)

方框风格启动横幅，显示版本、工作目录、模式、模型、工具数量、会话恢复信息。

### 2.3 Markdown 渲染器 (MarkdownRenderer)

Markdown-to-ANSI 渲染器，支持：
- 标题（# ## ###）
- 代码块（```）
- 加粗（**text**）
- 列表（- * 1.）
- 行内代码（`code`）

### 2.4 斜杠命令自动补全 (SlashCommandCompleter)

输入 `/` 后双击 Tab 自动补全命令，上下方向键切换选项。

---

## 第三轮优化：安全与可靠性

### 3.1 安全增强

| 功能 | 说明 |
|------|------|
| 敏感信息脱敏 | `Sanitizer` 过滤 API Key/Token，防止泄漏到会话 JSON |
| 工作区安全检查 | 所有文件操作限制在工作区内 (`FileToolSupport.checkInsideWorkspace`) |
| 危险命令检测增强 | 从 5 条字符串匹配扩展为 10 类正则匹配（rm -rf、fork bomb、reverse shell 等） |
| 跨平台 Bash | Windows 用 `cmd.exe /c`，Unix 用 `/bin/bash -lc` |

### 3.2 可靠性增强

| 功能 | 说明 |
|------|------|
| 连续失败保护 | 同一工具连续失败 3 次自动中断 Agent 循环 |
| API 自动重试 | 最多 3 次重试，指数退避 |
| 工具降级 | HTTP 400 时自动去除 tools 参数重试 |
| 速率限制 | 令牌桶算法控制 API 请求频率 |
| 上下文压缩 | `compactIfNeeded` 防止上下文超限 |
| 配置校验 | 启动时检查参数合法性并打印警告 |
| 默认循环次数调整 | `agent.max_iterations` 默认值从 6 改为 12 |

### 3.3 会话管理增强

| 功能 | 说明 |
|------|------|
| 首条消息自动标题 | 首条用户消息自动设置会话标题 |
| 对话导出 | `/export` 导出当前对话为 Markdown |
| Session 向后兼容 | `@JsonIgnoreProperties` 支持旧 JSON |
| Shutdown Hook | JVM 退出时自动保存会话 |

### 3.4 新增工具

| 工具 | 说明 |
|------|------|
| `edit` | 精确字符串替换，带 diff 预览 |
| `network` | HTTP GET/POST/PUT/DELETE 请求 |

### 3.5 新增斜杠命令

| 命令 | 说明 |
|------|------|
| `/bypass on\|off` | 跳过所有工具审批确认 |
| `/reload` | 运行时重载配置文件 |
| `/export` | 导出当前对话为 Markdown |
| `/stats` | 查看工具执行统计 |
| `/network` | 网络请求工具 |

### 3.6 插件系统

`ToolProvider` SPI 接口，通过 `ServiceLoader` 自动发现外部工具插件。

### 3.7 工具执行统计

`ToolStats` 追踪每个工具的调用次数、总耗时、错误次数、平均耗时。

### 3.8 Thinking 模型支持

- `reasoningContent` 字段贯穿 Message → ModelResponse → Agent 全链路
- 自动解析 thinking 模型的推理内容
- API 因 reasoning_content 缺失报错时，自动清理旧上下文并重试

---

## Bug 修复

| # | Bug | 修复方案 |
|---|-----|---------|
| 1 | 二进制文件误判 — UTF-8 严格解码导致中文/emoji 被判为二进制 | 改为只检测 null 字节 |
| 2 | Session 加载兼容 — 新增字段后旧 JSON 反序列化失败 | 添加 `@JsonIgnoreProperties(ignoreUnknown=true)` |
| 3 | Agent 死循环 — 工具反复失败时无限循环 | 添加连续失败检测（3 次中断） |
| 4 | Role 序列化不一致 — 枚举值大写序列化但 API 期望小写 | 添加 `@JsonValue`/`@JsonCreator` |
| 5 | Mock 不支持 edit — MockModelClient 缺少 edit 关键词匹配 | 已补全 |
| 6 | Mock Windows 路径 — FILE_PATTERN 正则不匹配 Windows 路径 | 已扩展 |
| 7 | Auto-save 异常吞掉 — `catch (IOException ignored)` | 改为写日志 |
| 8 | API Reasoning Content 缺失 — 旧上下文缺少 reasoning_content 导致 400 | 自动清理重试 |
| 9 | Spinner 与审批冲突 — 思考中动画覆盖审批提示 | 新增 AtomicBoolean 暂停机制 |

---

## 测试结果

### 全部测试（66 个）

| 测试类 | 用例数 | 状态 |
|--------|--------|------|
| AgentTest | 8 | 全部通过 |
| ConfigTest | 2 | 全部通过 |
| ConversationManagerTest | 2 | 全部通过 |
| MockModelClientTest | 5 | 全部通过 |
| DeleteFileToolTest | 2 | 全部通过 |
| GrepToolTest | 4 | 全部通过 |
| ListDirectoryToolTest | 2 | 全部通过 |
| ReadFileToolTest | 4 | 全部通过 |
| WriteFileToolTest | 2 | 全部通过 |
| EditToolTest | 7 | 全部通过 |
| BashToolTest | 3 | 全部通过 |
| ApprovalManagerTest | 5 | 全部通过 |
| IntegrationTest | 6 | 全部通过 |
| NetworkToolTest | 3 | 全部通过 |
| 其他 | 9 | 全部通过 |

```
Tests run: 66, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

---

## 优化前后对比总览

| 特性 | 优化前 | 优化后 |
|------|--------|--------|
| 终端颜色 | 无 | ANSI 全彩，自动检测终端支持 |
| 提示符 | `javaagent> ` | 青色加粗 `> ` |
| 流式输出 | 假流式（切块模拟） | 真 SSE 流式（逐 token 输出） |
| 文件编辑 | 只有全文写入 | 精确文本替换（EditTool） |
| 网络请求 | 无 | HTTP GET/POST/PUT/DELETE |
| 工具执行显示 | 静默执行 | 方框边框彩色状态行 |
| 工具结果 | 原始文本 dump | 紧凑格式（行号、着色、截断） |
| 审批界面 | 纯文本 | 着色参数 + diff 预览 |
| 启动横幅 | 大学信息块 | 方框风格 + 运行时信息 |
| 会话列表 | 纯文本 | 当前会话标记 + 着色 |
| Status | 纯文本 | 分组对齐显示 |
| 错误命令 | `Unknown command` | 自动推荐最接近的命令 |
| 系统提示词 | 基础描述 | 包含编辑工具使用规范 |
| 安全机制 | 基础审批 | 工作区隔离 + 10 类危险命令检测 + API Key 脱敏 + 连续失败保护 + 速率限制 |
| 工具数量 | 6 个 | 8 个（+edit, +network） |
| 斜杠命令 | 13 个 | 18 个（+bypass, +reload, +export, +stats, +network） |
| 配置项 | 11 个 | 14 个（+bypass_permissions, +max_context_messages, +rate_limit_qps） |
| 测试用例 | 30 个 | 66 个（+36） |
| Thinking 模型 | 不支持 | 支持 reasoning_content |
| 插件系统 | 无 | ToolProvider SPI |
| 上下文压缩 | 无 | compactIfNeeded |
| 终端输入 | BufferedReader | JLine3 LineReader |
