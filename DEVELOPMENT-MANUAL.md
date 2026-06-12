# JavaAgent CLI 开发手册

> 从零到一，完整掌握 JavaAgent CLI 的注册、配置、使用与进阶开发。

---

## 目录

- [第一章 环境准备](#第一章-环境准备)
- [第二章 获取 API Key](#第二章-获取-api-key)
- [第三章 构建项目](#第三章-构建项目)
- [第四章 创建配置文件](#第四章-创建配置文件)
- [第五章 首次启动与验证](#第五章-首次启动与验证)
- [第六章 日常使用指南](#第六章-日常使用指南)
- [第七章 斜杠命令详解](#第七章-斜杠命令详解)
- [第八章 工具系统详解](#第八章-工具系统详解)
- [第九章 安全机制](#第九章-安全机制)
- [第十章 插件开发](#第十章-插件开发)
- [第十一章 高级配置](#第十一章-高级配置)
- [第十二章 常见问题与排错](#第十二章-常见问题与排错)
- [第十三章 架构概览](#第十三章-架构概览)
- [附录 A 完整配置参数速查表](#附录-a-完整配置参数速查表)
- [附录 B 斜杠命令速查表](#附录-b-斜杠命令速查表)
- [附录 C 工具注册表速查](#附录-c-工具注册表速查)

---

## 第一章 环境准备

### 1.1 系统要求

| 项目     | 要求                                                |
| -------- | --------------------------------------------------- |
| Java     | **JDK 21+**（必须是 21 或更高版本）                 |
| Maven    | **3.8+**（用于构建项目）                            |
| 操作系统 | Windows 10/11、macOS 12+、Linux（Ubuntu 20.04+ 等） |
| 网络     | 需要访问 LLM API 端点（OpenAI 兼容接口）            |

### 1.2 安装 Java 21

#### Windows

```powershell
# 方式一：使用 winget（推荐）
winget install Microsoft.OpenJDK.21

# 方式二：手动下载
# 访问 https://learn.microsoft.com/zh-cn/java/openjdk/download
# 下载 OpenJDK 21 LTS 安装包，安装后重启终端

# 验证安装
java -version
# 输出应包含: openjdk version "21.x.x"
```

#### macOS

```bash
# 使用 Homebrew
brew install openjdk@21

# 验证
java -version
```

#### Linux (Ubuntu/Debian)

```bash
# 安装 OpenJDK 21
sudo apt update
sudo apt install openjdk-21-jdk

# 验证
java -version
```

### 1.3 安装 Maven

#### Windows

```powershell
winget install Apache.Maven
# 或从 https://maven.apache.org/download.cgi 下载，解压后配置 PATH
```

#### macOS

```bash
brew install maven
```

#### Linux

```bash
sudo apt install maven
```

### 1.4 验证环境

```bash
java -version
# openjdk version "21.x.x" ...

mvn -version
# Apache Maven 3.9.x ...
```

> ⚠️ **常见问题**：如果 `java` 命令找不到，请检查 `JAVA_HOME` 环境变量是否指向 JDK 21 的安装目录。

---

## 第二章 获取 API Key

JavaAgent CLI 需要一个兼容 OpenAI 接口的 LLM API Key。以下是几种常见的获取方式。

### 2.1 OpenAI 官方 API

1. 访问 [https://platform.openai.com](https://platform.openai.com)
2. 注册账号并完成邮箱/手机验证
3. 登录后进入 **API Keys** 页面：`Settings → API Keys → Create new secret key`
4. 复制生成的 Key（格式：`sk-...`），**仅显示一次，请妥善保存**
5. 记住你的 **API Key** 和 **Base URL**（默认 `https://api.openai.com/v1`）

### 2.2 第三方兼容服务

如果你使用的是国内或第三方 OpenAI 兼容服务（如 Azure OpenAI、DeepSeek、Xiaomi Mimo 等），流程类似：

1. 在对应平台注册账号
2. 进入控制台/API 管理页面
3. 创建 API Key
4. 记录 **Base URL**（各平台不同，如 `https://api.deepseek.com/v1`）

> 💡 **提示**：JavaAgent CLI 使用的是标准 OpenAI Chat Completions 接口，任何兼容该接口的服务都可以直接使用。

### 2.3 Mock 模式（无需 API Key）

如果你想先体验一下 CLI 的交互，可以使用 Mock 模式，不需要任何 API Key：

```bash
java --enable-native-access=ALL-UNNAMED -jar target/javaagent-cli-1.0.0.jar --mock
```

Mock 模式下，CLI 会根据关键词自动回复，用于离线调试和功能演示。

---

## 第三章 构建项目

### 3.1 获取源码

```bash
git clone <仓库地址>
cd JavaAgent-CLI
```

### 3.2 编译打包

```bash
mvn clean package -DskipTests
```

成功后会在 `target/` 目录下生成 fat JAR：

```
target/javaagent-cli-1.0.0.jar
```

> ⚠️ **常见问题**：
>
> - 如果报 `UnsupportedClassVersionError`，说明 Java 版本低于 21，请重新安装。
> - 如果 Maven 下载依赖慢，可以配置国内镜像（见第十二章）。

### 3.3 运行测试

```bash
mvn test
```

应通过 66 个 JUnit 5 测试用例。

---

## 第四章 创建配置文件

配置文件 `config.properties` 是整个项目的运行核心。本章详细讲解如何从零创建。

### 4.1 配置文件位置

程序启动时按以下顺序查找配置文件：

1. **项目根目录**（当前工作目录）— 最高优先级
2. **用户主目录** `~/.javaagent-cli/config.properties` — 备选位置

> 💡 **推荐**：在项目根目录创建 `config.properties`，方便管理。

### 4.2 最小化配置

对于只想快速上手的用户，只需要创建一个包含 API Key 的配置文件：

```properties
# 最小化配置 - 只需填写 api_key 和 base_url
agent.api_key=sk-your-api-key-here
agent.base_url=https://api.openai.com/v1
```

将其保存到项目根目录的 `config.properties` 文件中。

### 4.3 完整配置模板

以下是包含所有可用参数的完整配置模板：

```properties
# ============================================================
# JavaAgent CLI 配置文件
# ============================================================

# ---- 模型配置 ----
agent.mock_mode=false                  # true=Mock模式（离线）  false=真实API模式
agent.api_key=                         # 你的 API Key
agent.base_url=https://api.openai.com/v1  # API 端点地址
agent.model=gpt-5.4-mini               # 模型名称
agent.effort=high                      # 推理深度：low | high | xhigh | max | ultra

# ---- 行为配置 ----
agent.auto_save=true                    # 自动保存会话
agent.max_iterations=12                 # ReAct 最大循环次数（1-50）
agent.enable_bash=false                 # 是否启用 Bash 工具（安全考虑默认关闭）
agent.stream_responses=true             # 是否启用 SSE 流式输出
agent.approval_cache=true              # 是否缓存工具审批（避免重复确认）
agent.allow_external_paths=false        # 是否允许访问工作区外的路径
agent.bypass_permissions=false          # 是否跳过所有工具审批（危险！）

# ---- 上下文配置 ----
agent.system_prompt=                    # 自定义 System Prompt（留空使用默认）
agent.max_tokens=200000                 # 模型上下文窗口 token 数
agent.compact_threshold=0.8             # 自动压缩阈值（0.0-1.0）
agent.rate_limit_qps=10                 # API 请求限流（QPS）
```

### 4.4 按场景配置示例

#### 场景一：OpenAI 官方 API

```properties
agent.mock_mode=false
agent.api_key=sk-proj-xxxxxxxxxxxxxxxx
agent.base_url=https://api.openai.com/v1
agent.model=gpt-5.4-mini
agent.enable_bash=false
```

#### 场景二：DeepSeek API

```properties
agent.mock_mode=false
agent.api_key=sk-xxxxxxxxxxxxxxxx
agent.base_url=https://api.deepseek.com/v1
agent.model=deepseek-chat
agent.enable_bash=false
```

#### 场景三：Azure OpenAI

```properties
agent.mock_mode=false
agent.api_key=your-azure-api-key
agent.base_url=https://your-resource.openai.azure.com/openai/deployments/your-deployment
agent.model=gpt-5.4-mini
agent.enable_bash=false
```

> ⚠️ **注意**：Azure 的 `base_url` 格式与标准 OpenAI 不同，需要包含部署名称。

#### 场景四：离线演示（Mock 模式）

```properties
agent.mock_mode=true
agent.api_key=
agent.base_url=
agent.model=mock
agent.enable_bash=false
```

### 4.5 配置文件编码

配置文件必须使用 **UTF-8 编码**保存。如果使用 Windows 记事本，请在保存时选择编码为 UTF-8。

> ⚠️ **常见问题**：如果配置文件中包含中文注释但编码不是 UTF-8，可能导致乱码。推荐使用 VS Code 或 IntelliJ IDEA 编辑。

---

## 第五章 首次启动与验证

### 5.1 启动命令

#### Linux / macOS

```bash
# 方式一：使用启动脚本（推荐）
./javaagentcli

# 方式二：添加到 PATH 后全局使用
ln -s "$(pwd)/javaagentcli" ~/.local/bin/javaagentcli
javaagentcli

# 方式三：直接运行 JAR
java --enable-native-access=ALL-UNNAMED -jar target/javaagent-cli-1.0.0.jar

# 方式四：Mock 模式（无需 API Key）
java --enable-native-access=ALL-UNNAMED -jar target/javaagent-cli-1.0.0.jar --mock
```

#### Windows

```cmd
:: 方式一：使用启动脚本（推荐）
javaagentcli.cmd

:: 方式二：直接运行 JAR
java --enable-native-access=ALL-UNNAMED -jar target\javaagent-cli-1.0.0.jar

:: 方式三：PowerShell
java --enable-native-access=ALL-UNNAMED -jar .\target\javaagent-cli-1.0.0.jar
```

> 💡 **提示**：启动脚本自动传入 `--enable-native-access=ALL-UNNAMED` 参数，消除 JLine3/JNI 的 JVM 警告信息。直接用 `java -jar` 运行时需要手动添加该参数。

### 5.2 成功启动标志

启动成功后，你会看到一个双栏圆角方框横幅：

```
╭─ JavaAgent CLI v1.0.0 ─────────────────────────────────────────────────────╮
│                                       │  快速开始                          │
│                                       │ ───────────────────────────────────│
│  输入问题，Agent 自动调用工具帮你     │  /help  查看所有命令               │
│  只读工具(read,grep,ls)免审批运行     │  /tools  查看可用工具              │
│  文件编辑(edit,write,delete)需确认    │  /status  查看运行状态             │
│  输入 / 然后按 Tab 键自动补全命令     │  /exit  退出程序                   │
│                                       │ ───────────────────────────────────│
│  D:\develop\vs-code\JavaAgent-CLI-mcq │  real · mimo-v2.5-pro · 8 tools    │
╰────────────────────────────────────────────────────────────────────────────╯
```

如果之前有保存的会话，横幅下方还会显示：

```
  会话: 你好 (64 条消息)
```

然后出现 `> ` 提示符，表示可以开始输入。

### 5.3 配置变更状态栏

当你通过斜杠命令修改配置后（如 `/stream off`、`/bash on`），会显示一行紧凑的状态栏：

```
  ─ real · gpt-5.4-mini · 8 tools │ 流式:开 │ Bash:关 │ 审批:关 │ 思考:high ─
```

显示当前所有关键配置项的状态，方便你随时确认。

### 5.4 验证功能

启动后尝试以下命令确认一切正常：

| 命令 | 预期结果 |
|------|----------|
| `你好` | LLM 正常回复，说明 API 连接正确 |
| `/tools` | 列出 8 个内置工具 |
| `/status` | 显示模式、模型、上下文占用 |
| `/context` | 显示 token 占用柱状图 |

显示上下文 token 占用情况（彩色柱状图，按 System Prompt / Tools / Messages 分类）。

---

## 第六章 日常使用指南

### 6.1 基本对话

直接输入自然语言指令，Agent 会自主规划并执行：

```
> 帮我读取 src/Main.java 的前 20 行
> 搜索项目中所有 TODO 注释
> 把 Foo.java 里的 oldMethod 改名为 newMethod
> 帮我创建一个 Hello World 的 Java 类
```

输入 `/` 然后按 **Tab 键**可以自动补全命令，上下键浏览历史输入。

### 6.2 工具调用流程

Agent 调用工具时，会先显示思考动画，然后展示带边框的工具执行面板：

```
  ⠴ 思考中...
  ┌ read_file ─ test_tools.txt
  │ 执行工具中...
  │ ✓ 完成
  │ Lines: 1-6 of 6
  │    1 │ 这是一个测试文件，用于测试JavaAgent CLI的工具。
  │    2 │ 第一行内容。
  │ ... (还有 4 行)
  └──────────────────────────────────────────────
```

**思考动画**：使用 braille 字符旋转动画（⠴ ⠇ ⠋ ⠸ ⠧ ⠦ ⠏），表示 LLM 正在推理。

**权限规则：**

| 工具类型                                    | 行为                                |
| ------------------------------------------- | ----------------------------------- |
| 只读操作（read_file, grep, list_directory） | 自动执行，无需确认                  |
| 写操作（write_file, edit, delete_file）     | 弹出确认提示                        |
| Bash 命令                                   | 需确认（默认禁用，需先 `/bash on`） |
| HTTP 请求                                   | 需确认                              |

**写操作确认流程**：

对于写操作，Agent 会先显示确认提示，包含工具名、目标文件和操作预览：

```
  ⠴ 思考中...
  ┌ edit ─ test_tools.txt
  │ 执行工具中...

  允许执行? [Y/n]
    edit → test_tools.txt
    preview:
    │ - 第一行内容。
    │ + 第一行已修改。
    y
  ✓ 已允许
  │ ✓ 完成
  │ Edited D:\...\test_tools.txt (-1 lines, +1 lines)
  │
  │ @@ -2,1 +2,1 @@
  │ - 第一行内容。
  │ + 第一行已修改。
  └──────────────────────────────────────────────
```

**操作方式：**

| 操作 | 按键 |
|------|------|
| **确认执行** | 直接按 **Enter**，或输入 `y` 再按 **Enter** |
| **拒绝执行** | 输入 `n` 再按 **Enter** |

**审批缓存**：首次批准某个工具+文件组合后，后续相同操作会自动放行（可通过 `/approvals clear` 清空缓存）。

### 6.3 会话管理

```

/save my-project # 保存当前会话
/sessions # 查看所有已保存的会话
/load latest # 加载最近的会话
/clear # 新建一个空会话

```

### 6.4 导出对话

```

/export # 将当前对话导出为 Markdown 文件

```

---

## 第七章 斜杠命令详解

在提示符后输入 `/` 可查看所有可用命令。以下是详细说明：

### 会话管理

| 命令 | 说明 | 示例 |
|------|------|------|
| `/help` | 显示帮助信息 | `/help` |
| `/exit` 或 `/quit` | 退出程序 | `/exit` |
| `/clear` 或 `/new [标题]` | 新建会话（可选标题） | `/new bug-fix` |
| `/save [标题]` | 保存当前会话 | `/save feature-x` |
| `/load [id\|标题\|latest]` | 加载已保存的会话 | `/load latest` |
| `/sessions` | 列出所有已保存的会话 | `/sessions` |

### 模式切换

| 命令 | 说明 | 示例 |
|------|------|------|
| `/mode mock\|real` | 切换 Mock/Real 模式 | `/mode mock` |
| `/stream on\|off` | 开关流式输出 | `/stream off` |
| `/bash on\|off` | 开关 Bash 工具 | `/bash on` |
| `/bypass on\|off` | 跳过所有工具审批（危险！） | `/bypass on` |
| `/effort [级别]` | 调节推理深度 | `/effort ultra` |

### 上下文管理

| 命令 | 说明 | 示例 |
|------|------|------|
| `/context` | 查看上下文 token 占用 | `/context` |
| `/compact` | 压缩对话历史 | `/compact` |
| `/prompt show\|set\|reset` | 管理自定义 System Prompt | `/prompt set 你是Java专家` |

`/context` 显示真实的 token 计数和彩色柱状图：

```
Context Usage

       ⛁ ⛁ ⛁ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶    29.6k/200.0k tokens (15%)   mimo-v2.5-pro

       Estimated usage by category
       ⛁  System prompt: 227 (0.1%)  ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶
       ⛁  Tool definitions: 250 (0.1%)  ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶
       ⛁  Messages: 29.1k (14.5%)  ⛁ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶
       ⛶  Free space: 170.4k (85.2%)

    会话: 你好
    ID: f4953d8a
```

### 其他

| 命令 | 说明 | 示例 |
|------|------|------|
| `/tools` | 列出已注册的工具 | `/tools` |
| `/approvals clear` | 清空审批缓存 | `/approvals clear` |
| `/reload` | 重新加载配置文件 | `/reload` |
| `/export` | 导出当前对话为 Markdown | `/export` |
| `/stats` | 查看工具调用统计 | `/stats` |
| `/status` | 显示运行时状态 | `/status` |

### Effort 级别说明

Effort 控制模型的推理深度，影响回答质量和速度：

| 级别 | 说明 | 适用场景 |
|------|------|----------|
| `low` | 快速简单回答 | 简单问答、快速查询 |
| `high` | 平衡质量与速度（默认） | 日常使用 |
| `xhigh` | 深度推理 | 复杂逻辑分析 |
| `max` | 最大推理深度 | 架构设计、复杂调试 |
| `ultra` | 极致推理 + 霓虹特效 | 需要最高精度的任务 |

使用 `/effort` 不带参数时，会弹出 **交互式滑块选择器**：

```
  Speed                                         Intelligence
  ═══════════════════════════════════════════════════▲═════
    low        high        xhigh       max         ultra
  ultra — 极限推理，穷举一切可能
  ← → 选择  1-5 快选  Enter 确认  Esc 取消
```

直接指定级别可跳过交互：`/effort ultra`

---

## 第八章 工具系统详解

### 8.1 内置工具一览

| 工具 | 别名 | 功能 | 权限 | 限制 |
|------|------|------|------|------|
| `read_file` | `cat` | 读取文件内容 | 只读 | 256KB 上限，自动分页 |
| `write_file` | `write`, `save_file` | 写入文件 | 读写 | 100KB 上限，60 行预览 |
| `edit` | `replace`, `sed` | 精确字符串替换 | 读写 | 需提供 old_string 和 new_string |
| `delete_file` | `rm` | 删除文件 | 读写 | 禁止删除工作区根目录 |
| `grep` | `search`, `find` | 正则搜索文件内容 | 只读 | 跳过 target/.git，最多 100 匹配 |
| `list_directory` | `ls`, `dir` | 列出目录内容 | 只读 | 支持递归列出 |
| `bash` | `shell`, `exec` | 执行 Shell 命令 | 读写 | 默认禁用，10 秒超时 |
| `network` | `http`, `fetch` | HTTP 请求 | 读写 | — |

### 8.2 工具执行流程

```

用户输入 → Agent 推理 → 返回 tool_calls
→ ApprovalManager 审批 → Tool.execute() → 结果存储到对话历史
→ Agent 继续推理 → ... → 最终文本回复

```

### 8.3 各工具实际显示效果

以下是每个工具执行时的真实显示。

> 💡 **审批操作**：需要确认的工具会显示 `允许执行? [Y/n]`，操作方式：
> - **确认**：直接按 **Enter**，或输入 `y` 再按 **Enter**
> - **拒绝**：输入 `n` 再按 **Enter**

#### list_directory（列目录）

```
┌ list_directory ─ .
│ 执行工具中...
│ ✓ 完成
│ recursive=false
│ [D] -p
│ [D] .codebuddy
│ [D] .git
│ [D] .github
│ [F] .gitignore
│ [D] .idea
│ [D] .javaagent-cli
│ ... (更多条目)
│ entries=25
└──────────────────────────────────────────────

```

> `[D]` 表示目录，`[F]` 表示文件。

#### write_file（写文件）

需要确认，确认后显示写入结果和预览：

```
  ┌ write_file ─ test_tools.txt
  │ 执行工具中...

  允许执行? [Y/n]
    write_file → test_tools.txt
    y
  ✓ 已允许
  │ ✓ 完成
  │ Wrote 65 characters to D:\...\test_tools.txt (append=false).
  │ Preview (6/6 lines):
  │    1│ 这是一个测试文件，用于测试JavaAgent CLI的工具。
  │    2│ 第一行内容。
  │    3│ 第二行内容。
  │    4│ 第三行内容。
  │    5│ 第四行内容。
  │    6│ 第五行内容。
  └──────────────────────────────────────────────
```

#### read_file（读文件）

自动执行，显示文件内容和行号：

```
┌ read_file ─ test_tools.txt
│ 执行工具中...
│ ✓ 完成
│ Lines: 1-6 of 6
│ 1 │ 这是一个测试文件，用于测试JavaAgent CLI的工具。
│ 2 │ 第一行内容。
│ 3 │ 第二行内容。
│ 4 │ 第三行内容。
│ 5 │ 第四行内容。
│ ... (还有 1 行)
└──────────────────────────────────────────────

```

#### grep（搜索）

自动执行，显示匹配结果和统计：

```
┌ grep ─ test_tools.txt
│ 执行工具中...
│ ✓ 完成
│ D:\...\test_tools.txt: 2: 第一行内容。
│ D:\...\test_tools.txt: 3: 第二行内容。
│ D:\...\test_tools.txt: 4: 第三行内容。
│ D:\...\test_tools.txt: 5: 第四行内容。
│ D:\...\test_tools.txt: 6: 第五行内容。
│ files_scanned=1, matches=5
└──────────────────────────────────────────────

```

#### edit（编辑）

需要确认，显示 diff 预览：

```
  ┌ edit ─ test_tools.txt
  │ 执行工具中...

  允许执行? [Y/n]
    edit → test_tools.txt
    preview:
    │ - 第一行内容。
    │ + 第一行已修改。
    y
  ✓ 已允许
  │ ✓ 完成
  │ Edited D:\...\test_tools.txt (-1 lines, +1 lines)
  │
  │ @@ -2,1 +2,1 @@
  │ - 第一行内容。
  │ + 第一行已修改。
  └──────────────────────────────────────────────
```

#### delete_file（删文件）

需要确认：

```
┌ delete_file ─ temp_file_to_delete.txt
│ 执行工具中...

允许执行? [Y/n]
delete_file → temp_file_to_delete.txt
y
✓ 已允许
│ ✓ 完成
│ Deleted file: D:\...\temp_file_to_delete.txt
└──────────────────────────────────────────────

```

#### bash（Shell 命令）

需要确认，默认禁用（需先 `/bash on`）：

```
┌ bash ─ echo "Hello from bash tool"
│ 执行工具中...

允许执行? [Y/n]
bash → echo "Hello from bash tool"
y
✓ 已允许
│ ✓ 完成
│ $ echo "Hello from bash tool"
│ Hello
│ from
│ bash
│ tool
│ [exit=0]
└──────────────────────────────────────────────

```

> ⚠️ **安全提示**：Bash 工具会检测危险命令（如 `rm -rf`、`fork bomb`、`reverse shell` 等），检测到会自动拒绝执行。

#### network（HTTP 请求）

需要确认：

```
┌ network ─ {timeout=10, method=GET, url=http://h...
│ 执行工具中...

允许执行? [Y/n]
network → timeout=10
✓ 已允许
│ ✓ 完成
│ === HTTP 响应 ===
└──────────────────────────────────────────────

```

---

## 第九章 安全机制

### 9.1 安全层级

JavaAgent CLI 采用多层安全防护：

1. **工作区隔离** — 所有文件操作限制在项目目录内，无法读写工作区外的文件
2. **审批确认** — 写操作需要用户显式批准
3. **危险命令检测** — 自动拦截高危 Shell 命令
4. **敏感信息脱敏** — API Key/Token 自动过滤，防止泄漏到会话文件
5. **连续失败保护** — 同一工具连续失败 3 次自动中断

### 9.2 审批策略

| 操作类型 | 策略 |
|----------|------|
| 读文件、搜索、列目录 | 自动放行（只读） |
| 写文件、编辑文件 | 需用户确认 |
| 删除文件 | 需用户确认 |
| Bash 命令 | 需用户确认（默认禁用） |
| HTTP 请求 | 需用户确认 |
| 外部路径访问 | 自动拒绝 |

### 9.3 危险命令检测

BashTool 内置了 20+ 种危险命令正则匹配：

**Unix 类（10 类）：**
- `rm -rf`、`rm -r /`
- `fork bomb`（`:(){ :|:& };:`）
- `reverse shell`（`/dev/tcp`、`nc -e`）
- `chmod 777`
- `dd if=/dev/zero` 等

**Windows 类（10 类）：**
- `del /s /q`
- `format`
- `shutdown`、`bcdedit`
- `rd /s` 等

检测到危险命令会自动拒绝执行并提示用户。

### 9.4 敏感信息脱敏

`Sanitizer` 会自动过滤对话历史中的敏感信息：

- API Key（`sk-...`、`tp-...`）
- Bearer Token
- 其他格式的认证信息

确保会话文件不会泄漏密钥。

---

## 第十章 插件开发

JavaAgent CLI 支持通过 SPI（Service Provider Interface）机制扩展自定义工具。

### 10.1 插件结构

一个插件包含以下文件：

```

my-plugin/
├── pom.xml
├── src/main/
│ ├── java/com/example/plugin/
│ │ ├── MyTool.java # 工具实现
│ │ └── MyToolProvider.java # SPI Provider
│ └── resources/META-INF/services/
│ └── com.javagent.tools.ToolProvider # SPI 注册

````

### 10.2 实现工具接口

```java
package com.example.plugin;

import com.javagent.tools.Tool;
import com.javagent.tools.ToolDefinition;
import java.util.Map;

public class MyTool implements Tool {

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
            "my_tool",                    // 工具名称
            "My custom tool",             // 描述
            List.of("my", "custom"),      // 别名
            Map.of(                       // 参数定义
                "input", Map.of(
                    "type", "string",
                    "description", "输入参数"
                )
            )
        );
    }

    @Override
    public String execute(Map<String, String> parameters) {
        String input = parameters.get("input");
        // 工具逻辑
        return "Result: " + input;
    }

    @Override
    public boolean requiresApproval() {
        return false;  // false=只读，true=需确认
    }
}
````

### 10.3 创建 SPI Provider

```java
package com.example.plugin;

import com.javagent.tools.ToolProvider;
import java.util.List;

public class MyToolProvider implements ToolProvider {
    @Override
    public List<Tool> getTools() {
        return List.of(new MyTool());
    }
}
```

### 10.4 注册 SPI

创建文件 `src/main/resources/META-INF/services/com.javagent.tools.ToolProvider`，内容：

```
com.example.plugin.MyToolProvider
```

### 10.5 构建与使用

```bash
# 构建插件
cd my-plugin
mvn clean package

# 将插件 JAR 添加到 classpath 运行
java --enable-native-access=ALL-UNNAMED \
    -cp "target/javaagent-cli-1.0.0.jar:my-plugin-1.0.0.jar" \
    com.javagent.JavaAgentCLI
```

> 💡 **参考**：项目中的 `plugin-demo/` 目录包含一个完整的计算器插件示例。

---

## 第十一章 高级配置

### 11.1 自定义 System Prompt

你可以通过配置或命令自定义 System Prompt：

```properties
# 在 config.properties 中设置
agent.system_prompt=你是一个Java专家，回答时请使用中文
```

或在运行时使用命令：

```
/prompt set 你是一个Java专家，回答时请使用中文
/prompt show    # 查看当前 Prompt
/prompt reset   # 恢复默认
```

### 11.2 上下文窗口管理

```properties
agent.max_tokens=200000          # 上下文窗口大小
agent.compact_threshold=0.8      # 自动压缩阈值
```

当上下文占用超过阈值时，Agent 会自动压缩对话历史，保留最近 6 条消息，将之前的对话交给 LLM 总结。

手动压缩：

```
/compact
```

查看当前占用：

```
/context
```

### 11.3 会话持久化

会话自动保存到 `~/.javaagent-cli/sessions/` 目录，格式为 JSON。

```
/save my-project          # 手动保存
/sessions                 # 查看所有会话
/load latest              # 加载最近会话
/load <会话ID>            # 加载指定会话
```

### 11.4 运行时参数

启动时可通过命令行参数覆盖配置：

```bash
# 强制使用 Mock 模式
java -jar target/javaagent-cli-1.0.0.jar --mock

# 强制使用 Real 模式
java -jar target/javaagent-cli-1.0.0.jar --real

# 指定 API Key
java -jar target/javaagent-cli-1.0.0.jar --api-key sk-xxx
```

---

## 第十二章 常见问题与排错

### 12.1 环境问题

| 问题 | 原因 | 解决方案 |
|------|------|----------|
| `java: command not found` | 未安装 Java 21 | 安装 JDK 21+，`java -version` 验证 |
| `UnsupportedClassVersionError` | Java 版本 < 21 | 重新安装 JDK 21 |
| `mvn: command not found` | 未安装 Maven | 安装 Maven 3.8+ |
| `--enable-native-access` 报错 | 直接用 `java -jar` | 改用 `javaagentcli.cmd` 启动脚本 |

---

### 12.2 API 连接问题

| 错误 | 原因 | 解决方案 |
|------|------|----------|
| `Connection refused` | 网络不通或 `base_url` 错误 | 检查网络、确认 URL 末尾有 `/v1` |
| `401 Unauthorized` | API Key 无效/过期 | 检查 Key 无空格、在有效期内 |
| `429 Rate limit` | 请求过于频繁 | 降低 `agent.rate_limit_qps=5` |
| `404 Model not found` | model 名称错误 | 确认模型名：OpenAI `gpt-5.4-mini`、DeepSeek `deepseek-chat`、Mimo `mimo-v2.5-pro` |

---

### 12.3 构建问题

| 错误信息 | 原因 | 解决方案 |
|----------|------|----------|
| `UnsupportedClassVersionError` | Java 版本 < 21 | 安装 JDK 21 |
| `Could not resolve dependencies` | 网络问题 | 配置 Maven 国内镜像（见下） |
| `Shade plugin error` | shade 插件冲突 | 删除 `target/` 重新构建 |
| `target/` 目录不存在 | 未构建 | `mvn clean package -DskipTests` |

**配置 Maven 国内镜像**：编辑 `~/.m2/settings.xml`，在 `<mirrors>` 中添加：

```xml
<mirror>
    <id>aliyun</id>
    <mirrorOf>central</mirrorOf>
    <name>Alibaba Cloud Maven Mirror</name>
    <url>https://maven.aliyun.com/repository/central</url>
</mirror>
```

---

### 12.4 运行时问题

| 问题 | 原因 | 解决方案 |
|------|------|----------|
| 启动后无提示符 | 终端不支持 JLine3 | 使用标准终端（Windows Terminal、iTerm2） |
| 工具调用无反应 | 超过最大迭代次数 | 增加 `agent.max_iterations=20`，或查 `/status` |
| `/context` 显示异常 | token 计数器初始化失败 | 重启程序或 `/reload` 重载配置 |
| 会话保存失败 | 目录权限不足 | 检查 `~/.javaagent-cli/` 写入权限 |

---

### 12.5 跨平台与 Mock 模式问题

| 问题 | 解决方案 |
|------|----------|
| Windows 中文乱码 | 使用 Windows Terminal，或 cmd.exe 先执行 `chcp 65001` |
| Linux/macOS Bash 报错 | 确认 `/bin/bash` 存在：`which bash` |
| Windows 路径问题 | 使用正斜杠 `/`，CLI 自动处理路径分隔符 |
| Mock 模式不回复 | 输入包含关键词："读取"、"搜索"、"写入"、"你好" |
| Mock/Real 切换 | `/mode mock` 或 `/mode real` |

---

### 12.7 性能问题

| 问题 | 优化方案 |
|------|----------|
| 回复速度慢 | 降低 Effort（`/effort high`）、`/compact` 压缩上下文、换轻量模型 |
| Token 消耗快 | `/context` 查看占用、`/compact` 压缩、`/prompt reset` 减少系统 Prompt |

---

## 第十三章 架构概览

### 13.1 ReAct 推理循环

本项目采用 **ReAct（Reasoning + Acting）** 范式，核心流程为：

1. **Observation** — 接收用户输入，连同对话历史注入上下文窗口
2. **Reasoning** — LLM 基于 System Prompt 和工具定义进行推理，决定下一步行动
3. **Action** — 若模型返回 `tool_calls`，Agent 调用对应工具并获取执行结果
4. **Reflection** — 工具结果回灌至上下文，模型基于新观察继续推理或生成最终回复
5. **Loop** — 重复步骤 2-4，直至模型产出纯文本回复或达到最大迭代次数

```text
                    ┌─────────────────────────────┐
                    │        User Input            │
                    └──────────────┬──────────────┘
                                   ▼
                    ┌──────────────────────────────┐
                    │   Context Window Management   │
                    │   (ConversationManager)       │
                    └──────────────┬───────────────┘
                                   ▼
                    ┌──────────────────────────────┐
                    │   LLM Inference (chat)        │
                    │   ├─ MockModelClient          │
                    │   └─ OpenAI-Compatible Client  │
                    └──────────────┬───────────────┘
                                   ▼
                      ┌────────────┴────────────┐
                      │     Response Type?       │
                      └────────────┬────────────┘
               ┌───────────────────┼───────────────────┐
               ▼                   ▼                   ▼
          ┌─────────┐      ┌─────────────┐      ┌─────────┐
          │  TEXT    │      │ TOOL_CALLS  │      │  ERROR  │
          │ Final    │      │ Function    │      │ Handle  │
          │ Reply    │      │ Calling     │      │ & Exit  │
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

### 13.2 关键设计机制

| 机制                    | 说明                                                                     |
| ----------------------- | ------------------------------------------------------------------------ |
| **ReAct Loop**          | 推理-行动交替循环，最多 12 轮（可配置），支持多步工具编排                |
| **Human-in-the-Loop**   | 只读操作自动放行，写操作需用户显式批准，外部路径默认拒绝                 |
| **Streaming Inference** | SSE 逐 token 流式输出，支持 `reasoning_content` 思维链                   |
| **Effort Control**      | 5 级推理深度（low/high/xhigh/max/ultra），交互式滑块选择                 |
| **Failure Recovery**    | 同一工具连续失败 3 次自动中断，防止无限循环                              |
| **Guardrails**          | Unix 10 类 + Windows 10 类危险命令正则检测、工作区沙箱隔离、敏感信息脱敏 |

### 13.3 系统架构

```text
┌─────────────────────────────────────────────────────────────┐
│                    JavaAgent CLI                             │
├─────────────────────────────────────────────────────────────┤
│  REPL (JLine3)  │  Slash Commands  │  Tab Completion        │
├─────────────────┴──────────────────┴───────────────────────┤
│                    Agent (ReAct Loop)                        │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  Process Turn → LLM Call → Response Handling         │   │
│  │  ├─ TEXT → Final Reply                               │   │
│  │  ├─ TOOL_CALLS → Approval → Execute → Loop           │   │
│  │  └─ ERROR → Recovery                                 │   │
│  └──────────────────────────────────────────────────────┘   │
├─────────────────────────────────────────────────────────────┤
│  Model Layer          │  Tool Layer                         │
│  ├─ MockModelClient   │  ├─ ToolRegistry (SPI Discovery)   │
│  └─ OpenAI Client     │  ├─ 8 Built-in Tools               │
│      (SSE/Retry/RL)   │  └─ ApprovalManager (Policy Chain) │
├─────────────────────────────────────────────────────────────┤
│  Core Layer                                                  │
│  ├─ Config (Properties)   ├─ ConversationManager (JSON)     │
│  ├─ ContextUsage (Tokens) ├─ ToolStats (Metrics)            │
│  └─ Sanitizer (Security)  └─ RateLimiter (Token Bucket)     │
├─────────────────────────────────────────────────────────────┤
│  Util Layer                                                  │
│  ├─ Terminal (ANSI/splitLines/CJK)  ├─ TokenCounter (jtokkit)│
│  ├─ ContextDisplay (Bar Chart)      ├─ MarkdownRenderer      │
│  └─ Sanitizer (Regex Filter)        └─ RateLimiter           │
└─────────────────────────────────────────────────────────────┘
```

### 13.4 核心流程

1. **用户输入** → JLine3 REPL 读取，支持 Tab 自动补全
2. **Agent.processTurn()** → 将用户消息加入对话历史，构建 System Prompt
3. **LLM 推理** → 发送上下文 + 工具定义到模型，获取响应
4. **响应分流**：
    - **TEXT** → 直接返回最终回复，结束本轮
    - **TOOL_CALLS** → 进入工具审批 → 执行 → 结果回灌 → 继续循环
    - **ERROR** → 尝试清除旧上下文重试，仍失败则中断
5. **结果处理** → Sanitizer 脱敏后存储到对话历史，ConversationManager 自动保存
6. **上下文检查** → 如果 token 占用超过阈值，自动触发压缩

### 13.5 项目结构

```
src/main/java/com/javagent/
├── JavaAgentCLI.java              # CLI 入口、REPL、Slash Command 路由
├── BannerPrinter.java             # 启动横幅、运行时状态栏
├── SlashCommandCompleter.java     # JLine3 Tab 补全
├── core/
│   ├── Agent.java                 # ReAct 核心循环引擎
│   ├── Config.java                # Properties 配置管理
│   ├── ConversationManager.java   # 多会话持久化
│   ├── ApprovalManager.java       # 权限策略引擎 + 审批缓存
│   ├── ContextUsage.java          # 上下文 token 使用量
│   └── ToolStats.java             # 工具调用指标
├── model/
│   ├── ModelClient.java           # 模型客户端抽象接口
│   ├── OpenAiCompatibleModelClient.java  # OpenAI 兼容 API
│   └── MockModelClient.java       # 关键词匹配 Mock
├── tools/
│   ├── Tool.java                  # 工具抽象接口
│   ├── ToolRegistry.java          # 名称/别名查找 + SPI 发现
│   ├── ToolProvider.java          # SPI 扩展接口
│   ├── ReadFileTool / WriteFileTool / EditTool / DeleteFileTool
│   ├── GrepTool / ListDirectoryTool / BashTool / NetworkTool
│   └── FileToolSupport.java       # 文件工具公共逻辑
└── util/
    ├── Terminal.java              # ANSI 终端渲染
    ├── TokenCounter.java          # jtokkit token 计数
    ├── ContextDisplay.java        # /context 彩色柱状图
    ├── MarkdownRenderer.java      # Markdown → ANSI
    ├── Sanitizer.java             # 敏感信息脱敏
    └── RateLimiter.java           # 令牌桶限流
```

---

## 附录 A 完整配置参数速查表

| 参数                         | 类型    | 默认值                      | 说明                               |
| ---------------------------- | ------- | --------------------------- | ---------------------------------- |
| `agent.mock_mode`            | boolean | `true`                      | Mock 模式（离线调试）              |
| `agent.api_key`              | string  | —                           | API Key                            |
| `agent.base_url`             | string  | `https://api.openai.com/v1` | API 端点                           |
| `agent.model`                | string  | `gpt-5.4-mini`              | 模型名称                           |
| `agent.effort`               | string  | `high`                      | 推理深度：low/high/xhigh/max/ultra |
| `agent.auto_save`            | boolean | `true`                      | 自动保存会话                       |
| `agent.max_iterations`       | int     | `12`                        | 最大循环次数（1-50）               |
| `agent.enable_bash`          | boolean | `false`                     | 启用 Bash 工具                     |
| `agent.stream_responses`     | boolean | `true`                      | SSE 流式输出                       |
| `agent.approval_cache`       | boolean | `true`                      | 缓存工具审批                       |
| `agent.allow_external_paths` | boolean | `false`                     | 允许外部路径                       |
| `agent.bypass_permissions`   | boolean | `false`                     | 跳过所有审批                       |
| `agent.system_prompt`        | string  | —                           | 自定义 System Prompt               |
| `agent.max_tokens`           | int     | `200000`                    | 上下文窗口大小                     |
| `agent.compact_threshold`    | float   | `0.8`                       | 自动压缩阈值                       |
| `agent.rate_limit_qps`       | int     | `10`                        | API 限流（QPS）                    |

---

## 附录 B 斜杠命令速查表

| 命令                        | 说明            |
| --------------------------- | --------------- |
| `/help`                     | 帮助信息        |
| `/exit` `/quit`             | 退出            |
| `/clear` `/new [title]`     | 新建会话        |
| `/save [title]`             | 保存会话        |
| `/load [id\|title\|latest]` | 加载会话        |
| `/sessions`                 | 列出会话        |
| `/tools`                    | 列出工具        |
| `/mode mock\|real`          | 切换模式        |
| `/stream on\|off`           | 开关流式输出    |
| `/bash on\|off`             | 开关 Bash       |
| `/bypass on\|off`           | 跳过审批        |
| `/effort [level]`           | 推理深度        |
| `/context`                  | 查看 token 占用 |
| `/compact`                  | 压缩对话        |
| `/prompt show\|set\|reset`  | 管理 Prompt     |
| `/approvals clear`          | 清空审批缓存    |
| `/reload`                   | 重载配置        |
| `/export`                   | 导出 Markdown   |
| `/stats`                    | 工具统计        |
| `/status`                   | 运行状态        |

---

## 附录 C 工具注册表速查

| 工具             | 别名                 | 权限 | 限制               |
| ---------------- | -------------------- | ---- | ------------------ |
| `read_file`      | `cat`                | 只读 | 256KB              |
| `write_file`     | `write`, `save_file` | 读写 | 100KB, 60 行预览   |
| `edit`           | `replace`, `sed`     | 读写 | —                  |
| `delete_file`    | `rm`                 | 读写 | 禁止删根目录       |
| `grep`           | `search`, `find`     | 只读 | 100 匹配上限       |
| `list_directory` | `ls`, `dir`          | 只读 | —                  |
| `bash`           | `shell`, `exec`      | 读写 | 默认禁用, 10s 超时 |
| `network`        | `http`, `fetch`      | 读写 | —                  |
