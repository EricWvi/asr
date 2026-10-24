# TODO

按阶段推进，每个阶段结束时都应该能跑起来并自测。界面相关的阶段先改原型（design/），确认后再实现；亮色和暗色都要检查。

已定的大方向：

- 技术栈：前端 vite + React 19 + shadcn + React Query + zustand，中文界面；后端 Go + SQLite（modernc.org/sqlite，不用 cgo）。
- OIDC：照搬 ../homepage 的 internal/auth，配置格式参考它的 config.example.yaml。设计 token 不沿用 homepage 的。
- 顺序：Web 语音笔记（PC 加手机网页）→ 安卓 → Mac。
- 文稿首版在线优先：服务端是唯一数据源，编辑后防抖保存，同一份文稿以最后一次写入为准。离线时只能看缓存，不能编辑。
- 存储格式是 Markdown 纯文本，方言固定为 GFM（表格、任务列表、删除线、围栏代码块），另加 `==高亮==`。公式、Mermaid、脚注都不支持。
- ASR 边说边出字：后端通过 WebSocket 代理 DashScope 的 `/api-ws/v1/inference` duplex 接口，开启中间结果。
- 「整理」只处理上次整理之后新转写、且没有被手动改过的文字。整理结果原地替换，可以撤销。
- 原生客户端登录：用系统浏览器走 OIDC，回调后发放长期有效的设备 token。
- 语音模式的转写结果插在光标处。没点过正文时光标在文末，效果等同追加。
- 删除文稿：直接删除，弹出带「撤销」的 toast；服务端软删除，30 天后清理。
- 安卓用 Jetpack Compose，Mac 做成 Swift 原生菜单栏应用。

## 阶段 0：外部 API 验证

在 .tmp 下写一次性脚本，不进仓库。

- [x] 用 16 kHz 单声道 PCM 调用 `qwen-audio-3.1-asr-flash-message`，走 `/inference` 的 `run-task → 二进制音频帧 → finish-task`，流式识别正常。
- [x] 记录实测数据，见下方「ASR 实测」。
- [x] 调用 DeepSeek API 试整理提示词，见下方「整理实测」。

### ASR 实测（2026-10-24，北京）

- 地址是 `wss://<host>/api-ws/v1/inference`，host 已经包含业务空间和地域，所以配置里只需要 host。
- 延迟：连接加 `task-started` 约 0.2–0.26 秒；开始说话约 0.5 秒后出第一批字，之后大约每 0.4 秒更新一次；音频结束后 0.04–0.4 秒拿到定稿和 `task-finished`。
- 中间结果每次都是整句全文，按 `sentence_id` 替换，不能累加。最早几条可能是空字符串。
- 服务端自带 VAD：停顿约 1.5 秒就会断句，同一次任务里会有多个 `sentence_id`。前端一次按住说话可能拿到多句定稿，要按顺序拼接。
- 定稿可能和中间结果不一样，甚至为空：0.3 秒的短音频，中间结果是「嗯，」，定稿是空字符串。一律以 `sentence_end=true` 的文本为准，空的就丢弃。
- 只有静音时，会收到一条空定稿，然后正常 `task-finished`，不报错。
- 74 秒连续推流正常，没有遇到时长上限。更长的时长没有测，实现时设一个上限（例如 5 分钟）。
- 数字会被规整成阿拉伯数字（「三点」→「3点」），标点会自动加。
- `disfluency_removal_enabled: true` 会去掉「嗯」这类口头语，打开。口语到书面语的梳理交给「整理」。`language_hints: ["zh"]` 对普通话结果没有可见差别，保留。
- `task-finished` 的 `usage.duration` 是计费秒数，可以记到日志里。

### 整理实测（2026-10-24）

「整理」的含义：把口语稿梳理成规范的书面 Markdown，保留全部信息，不总结。提示词原型在 .tmp/asr-probe/tidy.py。

- 可用模型：`deepseek-flash`（V4.1-Flash）和 `deepseek-v4-pro`，接口是 OpenAI 兼容的 `https://api.deepseek.com/chat/completions`。
- 一段约 300 字、带改口和待办的口语稿：
  - `deepseek-flash`（默认思考）：首字 2.2 秒，总计 2.9 秒。结构合理，改口取后者，待办识别成 `- [ ]`，信息没有遗漏。
  - `deepseek-v4-pro`：总计 14 秒，效果和 flash 差不多，不划算。
  - `deepseek-flash` 关闭思考（`thinking: {type: disabled}`）：1.3–1.7 秒，但会过度分节，还会调换原文顺序。不采用。
  - `reasoning_effort: "low"` 没有明显提速。
- 一句话的短稿只修语句，不硬加结构，耗时约 1 秒。
- 结论：默认用 `deepseek-flash`，开启思考，流式返回。思考阶段约 2 秒，前端要显示「整理中」。
- 实现时要把待整理段落之前的标题结构作为只读上下文传进去，让新加的标题层级和文稿已有的衔接。

## 阶段 1：工程骨架与登录

- [ ] Go 模块 `asr`，目录结构和 homepage 一致：`cmd/asr`、`internal/{config,auth,store,server}`、`frontend/`，前端产物用 `frontend.go` 嵌入二进制。
- [ ] 照搬 homepage 的 auth：session、登录 Cookie、`/auth/login`、`/auth/callback`、`/auth/logout`、同源检查、`dev_user`。Cookie 名改成 `asr_*`。
- [ ] 照搬 store 的用户和 session 表及迁移机制。
- [ ] config.example.yaml 在 homepage 的基础上新增 `dashscope`（host、api_key、model）和 `deepseek`（api_key、model，默认 `deepseek-flash`）。密钥优先从环境变量读取，即 `ASR_DASHSCOPE_API_KEY` 和 `ASR_DEEPSEEK_API_KEY`，本地开发放在 `.env`。
- [ ] Taskfile：install、build、format、lint、test，和 homepage 保持一致。
- [ ] 前端脚手架：vite、React 19、Tailwind 4、shadcn、React Query、zustand。从 design/pc.html 抽出设计 token（颜色、圆角、阴影、六种文稿颜色及 fill/deep/sel/mark 派生），放进 CSS 变量，亮暗两套。
- [ ] Dockerfile 和 CI 构建镜像（不在本地构建）。到 Authelia 注册 client，redirect 是 `<public_url>/auth/callback`。
- [ ] docs/business-rules.md：参照 homepage，写业务概念与规则，不涉及实现方式。

## 阶段 2：文稿与 PC 布局（键盘模式）

- [ ] 数据：documents 表（id、user_id、title、color、content、created_at、updated_at），所有查询都按 user_id 隔离。
- [ ] API：列表、读取、新建、保存（防抖，以最后写入为准）、重命名、改颜色、删除（软删除）、撤销删除。
- [ ] 新建文稿：标题默认是本地时间字符串，颜色从六种里随机。
- [ ] PC 布局照原型：最大宽 1440px，纸面 880px，正文 760px，纸面统一米黄，暗色下接近中性。
- [ ] 侧栏：按日期分组，分组标题吸顶；隐藏滚动条，还能往下滚时底部渐隐；标题行右侧六个色点用来筛选；左下角是用户行。
- [ ] 右键菜单：重命名、复制 Markdown、导出 .md，分隔线后是删除（红色）。删除后弹出带「撤销」的 toast。
- [ ] 定时清理软删除超过 30 天的文稿及其图片。
- [ ] 吸顶栏：滚动后左侧显示标题，下方出现细线和淡阴影；右侧是「键盘 语音」切换，默认键盘模式。
- [ ] 标题下方的小圆点用来选择文稿颜色。
- [ ] 打开应用后光标直接可以输入，不先停在列表上。
- [ ] 跟随系统亮暗色。

## 阶段 3：CodeMirror 6 编辑器

- [ ] 基础：`@codemirror/lang-markdown`（GFM）、undo/redo、正文区不显示滚动条。
- [ ] 实时预览 decoration：光标所在行显示源码，标记符号用文稿的 deep 色；其他行隐藏标记符号，只显示渲染效果。覆盖标题、列表、引用、粗体、斜体、删除线、行内代码、`==高亮==`（mark 色底）、链接、围栏代码块。
- [ ] 选中文字的背景用文稿的 sel 色。
- [ ] Markdown 快捷输入：`## ` 立即按二级标题显示；`- `、`1. ` 进入列表；回车续写列表，在空项上回车退出列表；Tab 和 Shift+Tab 调整缩进。
- [ ] 待办：`- [ ]` 渲染成可点击的复选框 widget，点击后回写 `[ ]` 和 `[x]`。
- [ ] 图片粘贴：上传到服务端，按用户隔离存储，插入 `![](url)`；非光标行渲染成图片。
- [ ] 准备一份 GFM 样例文稿作为测试，后续安卓端渲染时拿它对照。

## 阶段 4：语音模式（Web）

- [ ] 浏览器录音：getUserMedia 加 AudioWorklet，重采样成 16 kHz 单声道 PCM16，大约每 100 ms 发一包。
- [ ] 后端 `/api/asr` WebSocket：校验 session，转发到 DashScope，把中间结果和定稿结果推回前端。API Key 只放在后端。
- [ ] 超时、提前断连、没有收到定稿结果，都按失败处理并提示，不能因为音频发完就当成成功。
- [ ] 底部悬浮条：整理 ｜ 按住 空格 说话（蓝色，录音中变红）｜ 换行。PC 上按住空格说话。
- [ ] 转写结果插在光标处。说话时在插入位置用淡色显示中间结果，松手定稿；刚转写的文字显示 2px deep 色下划线，约 2.5 秒后淡出。
- [ ] 换行按钮另起一行。
- [ ] 麦克风权限被拒或没有设备时，给出提示。

## 阶段 5：整理（DeepSeek）

- [ ] 记录转写区间：每次定稿后标记一个区间，随编辑映射位置；用户手动改过的区间就移除标记。区间和文稿一起持久化，刷新页面后还在。
- [ ] 后端 `/api/tidy`：代理 DeepSeek，流式返回结果。提示词从 .tmp/asr-probe/tidy.py 移进代码，附带文稿已有的标题结构作为上下文。
- [ ] 点「整理」后，把上次整理之后的待整理区间发出去，结果原地替换，之后清空标记。整理是一次可以撤销的事务。
- [ ] 没有待整理内容时，按钮禁用，悬停时说明原因。

## 阶段 6：手机网页

- [ ] 先做原型 design/mobile.html：同一套风格，以录音按钮为主，包括文稿列表、文稿页、录音中三种状态。
- [ ] 按原型实现响应式布局，按住按钮说话时要有触感反馈。
- [ ] Service worker：缓存应用外壳和最近读过的文稿。不提供 manifest 的安装能力，拦截 `beforeinstallprompt`，不出现安装提示。
- [ ] 真机检查 iOS Safari 和安卓 Chrome 的录音、键盘弹起、安全区。

## 阶段 7：全文搜索

- [ ] SQLite FTS5 索引标题和正文，中文分词方案在实现时确定（例如 trigram）。
- [ ] 侧栏搜索入口，结果高亮命中词。

## 阶段 8：设备 token 与推送通道

- [ ] devices 表：设备名、平台、token 哈希、最近使用时间。
- [ ] 原生客户端登录：客户端打开系统浏览器访问 `/auth/device/login`，走同一套 OIDC，回调后通过自定义 scheme（安卓）或 loopback（Mac）把 token 交回客户端。需要防 CSRF，code 只能用一次。
- [ ] API 同时接受 Cookie session 和 `Authorization: Bearer <设备 token>`。
- [ ] 网页端设备管理：查看设备并吊销。
- [ ] 用户级 WebSocket 推送：Mac 客户端常驻连接，并上报最近一次键盘鼠标活动时间。
- [ ] 「发送到电脑」默认推给最近活跃的那台 Mac，安卓端可以手动切换目标。没有 Mac 在线时直接返回失败，不排队。
- [ ] 发送的是识别原文，不整理，服务端不留存。

## 阶段 9：安卓客户端（Compose）

- [ ] 登录（阶段 8 的流程）。
- [ ] 两种模式：发送到电脑（录音转写后推到 Mac）和语音笔记（和 Web 共用文稿 API）。
- [ ] 发送到电脑：显示当前目标 Mac，可以切换；没有 Mac 在线时提示，并保留文字以便复制。本地保留最近几条发送记录，可以重发或复制。
- [ ] 录音：AudioRecord 采 16 kHz PCM，走同一个 `/api/asr`。
- [ ] 编辑器：BasicTextField 加 VisualTransformation 做实时高亮；查看模式用 multiplatform-markdown-renderer；勾选待办时回写源文本。
- [ ] 拿阶段 3 的 GFM 样例文稿对照渲染结果。

## 阶段 10：Mac 客户端（Swift 菜单栏应用）

- [ ] 只有菜单栏图标，显示连接状态；开机自启。
- [ ] 登录（loopback 回调），token 存进 Keychain。
- [ ] URLSessionWebSocketTask 长连接，断线后自动重连。
- [ ] 插入光标处：引导用户开启辅助功能权限；优先用剪贴板加模拟 Cmd+V，插入后恢复原剪贴板；AX API 直接写入作为备选。
- [ ] 签名和分发方式待定。

## 以后再说

- 热词（`vocabulary`）和上下文（`input.context`）做成用户可配置。
