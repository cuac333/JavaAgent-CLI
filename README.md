# JavaAgent CLI

一个基于 ReAct 架构的自主编程智能体，用 Java 21 实现。通过 Tool-Augmented LLM 模式将大语言模型与本地开发工具链集成，支持自然语言指令驱动、文件编辑、代码搜索、Shell 执行和网络请求。

## 快速开始

```bash
# 环境要求：Java 21+、Maven 3.8+
cd JavaAgent-CLI
mvn clean package -DskipTests

# 启动（mock 模式，不依赖 API）
java --enable-native-access=ALL-UNNAMED -jar target/javaagent-cli-1.0.0.jar --mock

# 或使用启动脚本
javaagentcli.cmd        # Windows
./javaagentcli          # Linux/macOS
```

## 核心能力

| 能力 | 说明 |
|------|------|
| **ReAct 循环** | 推理-行动交替，最多 12 轮（可配置），支持多步工具编排 |
| **8 个内置工具** | 文件读写、编辑、正则搜索、目录浏览、Shell、HTTP |
| **SSE 流式推理** | 逐 token 实时输出，支持 `reasoning_content` 思维链，增量 Markdown 渲染 |
| **Human-in-the-Loop** | 只读自动放行，写操作需确认 |
| **上下文管理** | `/context` 查看 token 占用，超过阈值自动 LLM 压缩 |
| **推理深度控制** | 5 级 effort（low→ultra），交互式滑块选择 |
| **打断机制** | Ctrl+C 打断当前操作，返回命令提示符 |
| **429 限流重试** | 指数退避（1s/2s/4s/8s/16s），最多 5 轮 |
| **插件扩展** | SPI 机制，外部 JAR 实现 `ToolProvider` 自动发现 |
| **跨平台** | Windows / macOS / Linux，统一换行符处理 |

## 使用方式

启动后直接输入自然语言指令，Agent 自主规划并调用工具完成任务：

```
> 读取 pom.xml
> 搜索所有 TODO
> 把 enable_bash 改成 true
> 执行 mvn test
```

输入 `/` 查看所有命令，Tab 补全。

### 斜杠命令

| 命令 | 说明 |
|------|------|
| `/help` | 显示帮助 |
| `/exit` `/quit` | 退出 |
| `/clear` `/new [title]` | 新建会话 |
| `/save [title]` | 保存会话 |
| `/load [id\|title]` | 加载会话 |
| `/sessions` | 列出会话 |
| `/tools` | 列出工具 |
| `/mode mock\|real` | 切换模型模式 |
| `/stream on\|off` | 开关流式输出 |
| `/thinking on\|off` | 开关思考内容显示 |
| `/bash on\|off` | 开关 Bash 工具 |
| `/bypass on\|off` | 跳过审批 |
| `/effort [level]` | 调节推理深度 |
| `/context` | 查看 token 占用 |
| `/compact` | 压缩对话历史 |
| `/prompt show\|set\|reset` | 管理 System Prompt |
| `/approvals clear` | 清空审批缓存 |
| `/reload` | 热重载配置 |
| `/export` | 导出对话为 Markdown |
| `/stats` | 工具调用统计 |
| `/status` | 运行状态 |

## 配置

在项目根目录创建 `config.properties`：

```properties
# 模型
agent.mock_mode=true
agent.api_key=
agent.base_url=https://api.openai.com/v1
agent.model=gpt-5.4-mini
agent.effort=high

# 行为
agent.auto_save=true
agent.max_iterations=12
agent.enable_bash=false
agent.stream_responses=true
agent.show_thinking=true

# 上下文
agent.max_tokens=200000
agent.compact_threshold=0.8
agent.rate_limit_qps=10
```

配置文件查找顺序：项目根目录 → 工作目录 → `~/.javaagent-cli/config.properties`。运行时 `/reload` 热重载。

## 工具注册表

| 工具 | 别名 | 说明 | 权限 |
|------|------|------|------|
| `read_file` | `cat` | 读取文本文件 | 只读 |
| `write_file` | `write`, `save_file` | 写入/追加文件 | 读写 |
| `edit` | `replace`, `sed` | 精确字符串替换 | 读写 |
| `delete_file` | `rm` | 删除文件 | 读写 |
| `grep` | `search`, `find` | 正则搜索 | 只读 |
| `list_directory` | `ls`, `dir` | 列出目录 | 只读 |
| `bash` | `shell`, `exec` | 执行 Shell 命令 | 读写 |
| `network` | `http`, `fetch` | HTTP 请求 | 读写 |

支持 SPI 插件扩展，实现 `ToolProvider` 接口并通过 `ServiceLoader` 自动发现。

## 项目结构

```
src/main/java/com/javagent/
├── JavaAgentCLI.java          # CLI 入口、REPL、命令路由
├── BannerPrinter.java         # 启动横幅、状态栏
├── SlashCommandCompleter.java # Tab 补全
├── core/
│   ├── Agent.java             # ReAct 核心循环
│   ├── Config.java            # 配置管理
│   ├── ConversationManager.java  # 会话持久化
│   ├── ApprovalManager.java   # 审批策略
│   └── ToolStats.java         # 工具统计
├── model/
│   ├── ModelClient.java       # 模型接口
│   ├── OpenAiCompatibleModelClient.java  # OpenAI 兼容 API
│   └── MockModelClient.java   # 离线 Mock
├── tools/                     # 8 个内置工具 + SPI 插件接口
└── util/
    ├── Terminal.java          # ANSI 渲染
    ├── MarkdownRenderer.java  # Markdown → ANSI
    ├── Sanitizer.java         # 敏感信息脱敏
    └── RateLimiter.java       # 令牌桶限流
```

## 测试

```bash
mvn test
```

79 个 JUnit 5 测试用例，覆盖 Agent 循环、工具执行、审批策略、配置管理、会话持久化等核心模块。

## 安全机制

- **工作区隔离** — 文件操作限制在项目目录内
- **审批确认** — 写文件、删文件、bash、网络请求需用户确认
- **危险命令检测** — Unix 10 类 + Windows 10 类正则匹配
- **敏感信息脱敏** — API Key/Token 自动过滤
- **连续失败保护** — 同一工具连续失败 3 次自动中断

## 开源协议

[MIT 开源协议](LICENSE)。版权所有 © 2026 四川农业大学信息工程学院 JavaAgent-CLI 开发团队。

更新历史见 [CHANGELOG](docs/CHANGELOG.md)。