# 更新日志

## 2026-08-21 v1.1.0 — 增量 Markdown 渲染、流式修复、ESC/Ctrl+C 打断、表格渲染优化

### 新增功能

- **SSE 流式实时输出** — 修复了流式输出不实时的问题，文本现逐行渲染输出（业界标准行缓冲做法）
- **增量 Markdown 渲染** — 流式输出时正文做增量 Markdown 渲染：普通行（标题/粗体/行内代码/列表/引用/链接）行完整后立即渲染；代码块（``` 围栏）和表格（| 开头）收集完整后再整体渲染
- **思考内容显示开关** — 新增 `/thinking on|off` 命令，控制思维模型的 `reasoning_content` 是否显示
- **ESC/Ctrl+C 打断** — agent 运行期间按 Ctrl+C 或 ESC 可打断当前操作，返回命令提示符（通过 JLine3 Signal.INT 信号处理器实现，不退出程序）
- **429 限流指数退避重试** — 流式路径也支持 429/5xx 指数退避重试（1s/2s/4s/8s/16s，最多 5 轮），退避期间可打断
- **Markdown 格式扩展** — 新增 `*斜体*`、`***粗斜体***`、`~~删除线~~`、`<u>下划线</u>`、`==高亮==`（浅蓝紫黑底）、`[text](url)` 链接（OSC 8 超链接，终端可点击）、裸 URL 自动识别
- **表格渲染增强** — 表格内行内代码高亮、列宽对齐、边框与内容行同宽、统一亮度、双竖线修复

### Bug 修复

- **SSE 流式输出不实时** — `ConsoleTextStreamHandler.onChunk` 只缓冲不写入终端，改为实时输出
- **中间思考文本不显示** — 工具调用附带的叙述文字被缓冲到流结束，改为流式实时输出
- **reasoning_content 不可见** — 思维模型推理内容完全没送到 UI，改为暗淡斜体实时显示
- **思考→正文不换行** — 思考内容结束后正文直接贴着输出，改为补两个换行分隔
- **行距翻倍** — `render()` 内部 `splitLines` 对尾随 `\n` 产生空串多输出一个空行，改为统一剥离尾随空串
- **表格内行内代码不高亮** — 单元格内容直接输出纯文本，改为先经过 `renderInline()` 处理行内样式
- **表格行尾双竖线** — 列间分隔符拼接错误导致 `││`，修复为统一 dim 单竖线
- **表格竖线未对齐** — 列宽计算基于原始 cell（含反引号），改为基于 `renderInline` 后的纯文本宽度
- **表格边框宽度不一致** — 上/下/分隔线比内容行宽 2 字符，修复 `joinBorder` 宽度公式
- **链接不可点击** — 蓝色下划线只是 ANSI 样式，改为输出 OSC 8 超链接序列（`ESC]8;;url\ESC\` 包裹）
- **裸 URL 未识别** — 裸 `https://` 链接原样显示，改为自动识别并用 OSC 8 包裹
- **OSC 8 嵌套导致 `]8;;` 暴露** — `BARE_URL_PATTERN` 重复匹配已包裹的 URL，加负向 lookbehind 排除
- **表格宽度被 OSC 8 撑爆** — `stripAnsi` 只去 ANSI 颜色码，改为同时去掉 OSC 8 序列
- **真实模式闪退** — `catch (InterruptedException)` 编译报 unreachable，改为外层只 catch IOException
- **ESC 监听线程与 LineReader 冲突** — 独立线程 `enterRawMode` 干扰 JLine 终端状态，改为 `terminal.handle(Signal.INT, handler)` 信号处理器

### 技术改进

- `ModelClient` 接口新增 `setCancelFlag(AtomicBoolean)` 默认方法，支持打断传播
- 重试循环在各次退避前检查 `cancelFlag`，支持退避中断
- `renderInline` 改用 `replaceAll` 链，从最长到最短避免模式冲突
- `stripAnsi` 增强，同时去掉 ANSI SGR 和 OSC 8 序列