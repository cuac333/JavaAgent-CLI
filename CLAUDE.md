# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

JavaAgent CLI — a ReAct-based autonomous coding agent written in Java 21. An LLM decides which local tools to invoke (read/write files, grep, bash, HTTP), executes them, feeds results back, and iterates until it produces a final answer. Modeled after Claude Code's interaction paradigm.

## Build & Run Commands

```bash
# Build (fat JAR via maven-shade-plugin)
mvn clean package -DskipTests

# Run all tests (JUnit 5, 79 tests)
mvn test

# Run a single test class
mvn test -Dtest=AgentTest

# Run a single test method
mvn test -Dtest=AgentTest#shouldProcessToolCalls

# Run the agent (Linux/macOS)
javaagentcli

# Run the agent (Windows)
javaagentcli.cmd

# Run directly
java --enable-native-access=ALL-UNNAMED -jar target/javaagent-cli-1.0.0.jar

# Run in mock mode (no API key needed, keyword-based responses)
java -jar target/javaagent-cli-1.0.0.jar --mock
```

Requirements: Java 21+, Maven 3.8+. No linter or formatter plugins are configured.

## Architecture

**Entry point**: `JavaAgentCLI.java` — loads Config, registers tools, discovers SPI plugins, creates the model client, instantiates Agent, starts a JLine3 REPL with slash commands and tab completion.

**Core loop** (`Agent.java`): ReAct loop with up to `max_iterations` rounds (default 12). Each iteration sends conversation history + tool definitions to the LLM. TEXT response ends the loop; TOOL_CALLS triggers approval pipeline → execution → result stored in conversation → next iteration. Same tool failing 3x consecutively breaks the loop. Supports Ctrl+C interrupt via `cancelFlag` (AtomicBoolean) checked at each loop stage.

**Four packages** under `com.javagent`:

- `core/` — Agent (ReAct loop), Config (properties-based), ConversationManager (multi-session persistence), ApprovalManager (policy chain + approval caching), ContextUsage (token usage record), ToolStats (metrics)
- `model/` — ModelClient interface, OpenAiCompatibleModelClient (SSE streaming, 429 retry with exponential backoff, rate limiting), MockModelClient (keyword-matching for offline dev), Message/ToolCall/ModelResponse records, TextStreamHandler (streaming lifecycle callbacks)
- `tools/` — Tool interface + 8 built-in tools (ReadFile, WriteFile, Edit, DeleteFile, Grep, ListDirectory, Bash, Network). ToolRegistry does name/alias lookup and SPI plugin discovery via `ToolProvider` interface + `ServiceLoader`. FileToolSupport provides shared path validation and binary detection.
- `util/` — Terminal (ANSI rendering + `splitLines()` for cross-platform line ending handling), TokenCounter (jtokkit token counting), ContextDisplay (/context colored bar chart), MarkdownRenderer (incremental streaming-friendly rendering: tables, code blocks, bold/italic, OSC 8 hyperlinks, highlight), Sanitizer (regex-based secret filtering), RateLimiter (token bucket)

**Key design decisions**:
- Read-only tools auto-approve; write tools prompt the user. Bash is disabled by default.
- **Streaming**: SSE streaming with incremental Markdown rendering. Supports `reasoning_content` (thinking model chain-of-thought), toggleable via `/thinking on|off`. Line-buffered: complete lines render immediately, partial lines accumulate.
- **Interrupt**: Ctrl+C handler via `terminal.handle(Signal.INT, ...)` — interrupts current operation and returns to prompt, does not exit program. Checked during retry backoff sleep as well.
- **429 retry**: Exponential backoff (1s/2s/4s/8s/16s, max 5 rounds) for both streaming and non-streaming paths. Interruptible during backoff.
- **Cross-platform**: All text splitting uses `Terminal.splitLines()` (pre-compiled regex `\\r\\n|\\r|\\n`) to handle `\n`/`\r\n`/`\r` line endings. BashTool detects OS via static `IS_WINDOWS` field: Linux/macOS uses `/bin/bash`, Windows prefers PowerShell → cmd.exe. Both Unix and Windows dangerous commands are detected (20+ regex patterns).
- **Token counting**: `/context` command uses jtokkit (tiktoken Java port) with cl100k_base encoding. Agent pre-computes tool definition tokens (static). Context window size configurable via `agent.max_tokens`. Auto-compact at configurable threshold.
- Sessions persist as JSON in `~/.javaagent-cli/sessions/`.
- Plugin system: external JARs implement `ToolProvider`, register via `META-INF/services/com.javagent.tools.ToolProvider`. See `plugin-demo/` for a working example.
- Config lookup: project root → working directory → `~/.javaagent-cli/config.properties`. Hot-reloadable via `/reload`.

## Configuration

`config.properties` at project root (gitignored). Key settings:

| Key | Default | Description |
|-----|---------|-------------|
| `agent.mock_mode` | `true` | Mock mode (no API key needed) |
| `agent.api_key` | — | API key for real model |
| `agent.base_url` | `https://api.openai.com/v1` | API endpoint |
| `agent.model` | `gpt-5.4-mini` | Model name |
| `agent.effort` | `high` | Reasoning depth: low/medium/high/xhigh/max/ultra |
| `agent.max_iterations` | `12` | ReAct max loop count |
| `agent.enable_bash` | `false` | Enable Bash tool |
| `agent.stream_responses` | `true` | SSE streaming output |
| `agent.show_thinking` | `true` | Show reasoning_content |
| `agent.approval_cache` | `true` | Cache tool approvals |
| `agent.bypass_permissions` | `false` | Skip all approval prompts |
| `agent.max_tokens` | `200000` | Context window size |
| `agent.compact_threshold` | `0.8` | Auto-compact trigger ratio |
| `agent.rate_limit_qps` | `10` | API rate limit |

## Testing

79 JUnit 5 tests covering: Agent loop behavior (including interrupt/cancel), each tool's execution, approval policies, config loading, conversation persistence, Markdown rendering (tables, links, code blocks, highlight, blockquotes), and integration tests. Surefire plugin configured with `useSystemClassLoader=false`.

## Code Conventions

- Java 21 features: records, pattern matching, sealed interfaces where applicable
- Comments are in Chinese; method/variable names are in English
- No external frameworks — raw Java with Jackson (JSON), JLine3 (terminal), built-in `java.net.http.HttpClient`
- Source encoding: UTF-8
