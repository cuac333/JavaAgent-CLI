# JavaAgent CLI

一个基于 ReAct 架构的自主编程智能体（Autonomous Coding Agent），使用 Java 21 实现，具备工具调用、文件编辑、流式推理和交互式终端能力。参照 Claude Code 的交互范式，通过 Tool-Augmented LLM 模式将大语言模型与本地开发工具链深度集成。

## 核心能力

- **ReAct 推理循环** — Reasoning + Acting 交替执行，支持多步工具编排
- **Tools** — 8 个内置工具（文件读写、编辑、搜索、Shell、HTTP），支持 SPI 插件扩展
- **SSE 流式推理** — 逐 token 实时输出，支持 reasoning_content 思维链，增量 Markdown 渲染（粗体/斜体/代码块/表格/链接）
- **Human-in-the-Loop** — 只读操作自动放行，写操作需用户显式批准
- **Token 级上下文可视化** — `/context` 命令用真实 token 数计量上下文占用，彩色柱状图分类显示
- **推理深度控制** — 5 级 effort（low/high/xhigh/max/ultra），交互式滑块选择，ultra 霓虹特效
- **安全沙箱** — 工作区隔离、危险命令检测、敏感信息脱敏、连续失败保护
- **跨平台** — Windows（PowerShell/cmd.exe）/ macOS / Linux，统一处理 `\n` 和 `\r\n` 换行符
- **打断机制** — agent 运行期间按 Ctrl+C 可打断当前操作，返回命令提示符（不退出程序）
- **429 限流指数退避** — 流式/非流式路径均支持 1s/2s/4s/8s/16s 退避重试，最多 5 轮

> 完整更新历史见 [CHANGELOG](docs/CHANGELOG.md)。

## 快速开始

```bash
# 环境要求：Java 21+、Maven 3.8+

# 首次使用：构建项目
cd JavaAgent-CLI
mvn clean package -DskipTests

# Linux / macOS
ln -s "$(pwd)/javaagentcli" ~/.local/bin/javaagentcli
javaagentcli

# Windows
javaagentcli.cmd
# 或直接运行：
java --enable-native-access=ALL-UNNAMED -jar target/javaagent-cli-1.0.0.jar
```

启动脚本自动传入 `--enable-native-access=ALL-UNNAMED` 消除 JVM 警告。直接运行时需手动添加该参数：`java --enable-native-access=ALL-UNNAMED -jar target/javaagent-cli-1.0.0.jar --mock`。

## 使用方式

启动后直接输入自然语言指令，Agent 自主规划执行路径并调用工具完成任务：

```
> 帮我读取 src/Main.java 的前 20 行
> 搜索项目中所有 TODO 注释
```

输入 `/` 可查看所有斜杠命令，上下键浏览，Tab 确认。

## Agent 架构

### ReAct 推理循环

本项目采用 **ReAct（Reasoning + Acting）** 范式，核心流程为：

1. **Observation** — 接收用户输入，连同对话历史注入上下文窗口
2. **Reasoning** — LLM 基于 System Prompt 和工具定义进行推理，决定下一步行动
3. **Acting** — 若模型返回 `tool_calls`，Agent 调用对应工具并获取执行结果
4. **Reflection** — 工具结果回灌至上下文，模型基于新观察继续推理或生成最终回复
5. **Loop** — 重复步骤 2-4，直至模型产出纯文本回复或达到最大迭代次数

```text
                    ┌─────────────────────────────┐
                    │        User Input           │
                    └──────────────┬──────────────┘
                                   ▼
                    ┌──────────────────────────────┐
                    │   Context Window Management  │
                    │   (ConversationManager)      │
                    └──────────────┬───────────────┘
                                   ▼
                    ┌──────────────────────────────┐
                    │   LLM Inference (chat)       │
                    │   ├─ MockModelClient         │
                    │   └─ OpenAI-Compatible Client│
                    └──────────────┬───────────────┘
                                   ▼
                      ┌────────────┴────────────┐
                      │     Response Type?      │
                      └────────────┬────────────┘
               ┌───────────────────┼───────────────────┐
               ▼                   ▼                   ▼
          ┌─────────┐      ┌─────────────┐      ┌─────────┐
          │  TEXT   │      │ TOOL_CALLS  │      │  ERROR  │
          │ Final   │      │ Function    │      │ Handle  │
          │ Reply   │      │ Calling     │      │ & Exit  │
          └─────────┘      └──────┬──────┘      └─────────┘
                                  ▼
                      ┌───────────────────────┐
                      │  Human-in-the-Loop    │
                      │  (ApprovalManager)    │
                      │  ├─ Auto-approve: r/o │
                      │  ├─ Prompt user: r/w  │
                      │  └─ Deny: policy      │
                      └───────────┬───────────┘
                                  ▼
                      ┌───────────────────────┐
                      │  Tool Execution       │
                      │  ├─ Tool.execute()    │
                      │  ├─ ToolStats.record()│
                      │  └─ Sanitizer.clean() │
                      └───────────┬───────────┘
                                  ▼
                        Observation → Reasoning → ...
```

### 关键设计

| 机制 | 说明 |
|---|---|
| **ReAct Loop** | 推理-行动交替循环，最多 ***12 轮***（可配置），支持多步工具编排 |
| **Human-in-the-Loop** | 只读操作自动放行，修改操作需用户显式批准，外部路径默认拒绝 |
| **Streaming Inference** | SSE 逐 token 流式输出，支持 `reasoning_content` 思维链，增量 Markdown 渲染 |
| **Effort Control** | 5 级推理深度（low/high/xhigh/max/ultra），交互式滑块选择，ultra 霓虹动画 |
| **Failure Recovery** | 同一工具连续失败 3 次自动中断，防止无限循环 |
| **Guardrails** | Unix 10 类 + Windows 10 类危险命令正则检测、工作区沙箱隔离、敏感信息脱敏 |

## 斜杠命令

| 命令 | 说明 |
|---|---|
| `/help` | 显示帮助信息 |
| `/exit` `/quit` | 退出程序 |
| `/clear` `/new [title]` | 新建会话 |
| `/save [title]` | 保存当前会话 |
| `/load [id\|title\|latest]` | 加载已保存的会话 |
| `/sessions` | 列出已保存的会话 |
| `/tools` | 列出已注册的工具 |
| `/mode mock\|real` | 切换模型模式 |
| `/stream on\|off` | 开关流式输出 |
| `/thinking on\|off` | 开关思考内容显示 |
| `/bash on\|off` | 开关 Bash 工具 |
| `/bypass on\|off` | 跳过所有工具审批确认 |
| `/effort [low\|high\|xhigh\|max\|ultra]` | 调节模型推理深度（交互式滑块，←→ 选择，ultra 霓虹特效） |
| `/context` | 查看上下文 token 占用（彩色柱状图，按 System Prompt / Tools / Messages 分类） |
| `/compact` | 压缩对话历史（LLM 摘要，保留关键上下文，节省 token） |
| `/prompt show\|set\|reset` | 管理自定义 System Prompt |
| `/approvals clear` | 清空审批缓存 |
| `/reload` | 重新加载配置文件 |
| `/export` | 导出当前对话为 Markdown |
| `/stats` | 查看工具调用统计 |
| `/status` | 显示运行时状态 |

## 工具注册表

| 工具 | 别名 | 说明 | 权限 |
|---|---|---|---|
| `read_file` | `cat` | 读取文本文件（256KB 上限，自动分页） | 只读 |
| `write_file` | `write`, `save_file` | 写入文件（带 60 行预览，100KB 上限） | 读写 |
| `edit` | `replace`, `sed` | 精确字符串替换（带彩色 diff 预览） | 读写 |
| `delete_file` | `rm` | 删除文件（禁止删除工作区根目录） | 读写 |
| `grep` | `search`, `find` | 正则搜索（跳过 target/.git，最多 100 匹配） | 只读 |
| `list_directory` | `ls`, `dir` | 递归列出目录内容 | 只读 |
| `bash` | `shell`, `exec` | 执行 Shell 命令（Linux/macOS 用 bash，Windows 优先 PowerShell → cmd.exe，默认禁用，10s 超时） | 读写 |
| `network` | `http`, `fetch` | HTTP 请求 | 读写 |

工具系统支持 SPI 插件扩展：实现 `ToolProvider` 接口并通过 `ServiceLoader` 自动发现。

## 配置

在项目根目录创建 `config.properties`（以下为默认值）：

```properties
# 模型
agent.mock_mode=true
agent.api_key=
agent.base_url=https://api.openai.com/v1
agent.model=gpt-5.4-mini
agent.effort=high               # 推理深度：low | high | xhigh | max | ultra

# 行为
agent.auto_save=true
agent.max_iterations=12          # ReAct 最大循环次数
agent.enable_bash=false
agent.stream_responses=true
agent.approval_cache=true
agent.allow_external_paths=false
agent.bypass_permissions=false

# 上下文
agent.system_prompt=             # 自定义 System Prompt
agent.max_tokens=200000          # 模型上下文窗口 token 数（影响 /context 显示）
agent.compact_threshold=0.8      # 自动压缩阈值（0.0-1.0），达到时 LLM 自动总结对话
agent.rate_limit_qps=10          # API 请求限流（QPS）
```

配置文件查找顺序：项目根目录 → 工作目录 → `~/.javaagent-cli/config.properties`。运行时可通过 `/reload` 热重载。

## 安全机制

- **工作区隔离** — 所有文件操作限制在项目目录内
- **审批确认** — 写文件、删文件、bash、网络请求需要用户确认
- **危险命令检测** — Unix 10 类 + Windows 10 类正则匹配（rm -rf、fork bomb、reverse shell、del /s、format、shutdown、bcdedit 等）
- **敏感信息脱敏** — API Key/Token 自动过滤，防止泄漏到会话文件
- **连续失败保护** — 同一工具连续失败 3 次自动中断

## 项目结构

```text
src/main/java/com/javagent/
├── JavaAgentCLI.java              # CLI 入口、REPL、Slash Command 路由
├── BannerPrinter.java             # 启动横幅、运行时状态栏
├── SlashCommandCompleter.java     # JLine3 Tab 补全
├── core/
│   ├── Agent.java                 # ReAct 核心循环引擎
│   ├── Config.java                # Properties 配置管理
│   ├── ConversationManager.java   # 多会话持久化、上下文压缩
│   ├── ApprovalManager.java       # 权限策略引擎 + 审批缓存
│   ├── ContextUsage.java          # 上下文 token 使用量数据模型
│   └── ToolStats.java             # 工具调用指标（次数/耗时/错误率）
├── model/
│   ├── ModelClient.java           # 模型客户端抽象接口
│   ├── OpenAiCompatibleModelClient.java  # OpenAI 兼容 API（SSE 流式 + 重试 + 限流）
│   └── MockModelClient.java       # 关键词匹配 Mock（离线调试用）
├── tools/
│   ├── Tool.java                  # 工具抽象接口
│   ├── ToolRegistry.java          # 名称/别名查找 + SPI 插件发现
│   ├── ToolProvider.java          # SPI 扩展接口
│   ├── ReadFileTool / WriteFileTool / EditTool / DeleteFileTool
│   ├── GrepTool / ListDirectoryTool / BashTool / NetworkTool
│   └── FileToolSupport.java       # 文件工具公共逻辑（路径校验、二进制检测）
└── util/
    ├── Terminal.java              # ANSI 终端渲染 + splitLines 跨平台换行符处理 + neon 霓虹特效 + CJK displayWidth
    ├── TokenCounter.java          # jtokkit token 计数（cl100k_base 编码）
    ├── ContextDisplay.java        # /context 彩色柱状图渲染
    ├── MarkdownRenderer.java      # Markdown → ANSI 转换
    ├── Sanitizer.java             # 敏感信息正则脱敏
    └── RateLimiter.java           # 令牌桶限流器
```

## 测试

```bash
mvn test
```

79 个 JUnit 5 测试用例，覆盖 Agent 循环、工具执行、审批策略、配置管理、会话持久化等核心模块。

## 开源协议

本项目采用 [MIT 开源协议](LICENSE)（MIT License）发布。

版权所有 © 2026 四川农业大学信息工程学院 JavaAgent-CLI 开发团队。

在遵守 MIT 协议条款的前提下，任何人都可以自由使用、复制、修改、合并、发布、分发、再许可和/或出售本软件的副本。
