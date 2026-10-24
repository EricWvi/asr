# 听写

用语音记笔记的 ASR 服务：在浏览器里按住说话，转写结果插入文稿光标处，可以再用大模型（默认 DeepSeek，任何 OpenAI 兼容接口都行）把口语稿整理成规范的书面 Markdown。安卓客户端还能把转写结果直接发送到 Mac 的光标处（规划中）。

单个 Go 进程同时提供页面和 API，前端资源通过 `go:embed` 打进可执行文件，运行时不需要 Node.js。登录走 OIDC（Authelia），多用户数据隔离。

阶段计划见 [docs/todos.md](docs/todos.md)，业务规则见 [docs/business-rules.md](docs/business-rules.md)，界面原型在 [design/](design/)。

## 开发

常用命令都在 [Taskfile.yml](Taskfile.yml) 里，需要先安装 [Task](https://taskfile.dev)。运行 `task --list` 查看全部任务。

```sh
task install:frontend   # 安装前端依赖（npm ci）
cp config.example.yaml config.yaml   # 首次：取消注释 dev_user，填好 dashscope.host
task run:server         # 后端 :36750
task run:web            # Vite 开发服务器，/api、/auth 代理到 :36750
```

开发环境不走 OIDC：`config.yaml`（已被 git 忽略）里设置 `dev_user`，所有请求都以该用户登录，`public_url` 和 `oidc` 可以不填。

本地密钥写在仓库根目录的 `.env`（已被 git 忽略），`task` 会自动加载：

```sh
ASR_DASHSCOPE_API_KEY=...
ASR_TIDY_API_KEY=...
```

`task run:server -- -config other.yaml` 可以把参数透传给 `asr`。Service Worker 只在生产构建中注册。

## 测试

```sh
task format          # gofmt 格式化 Go 代码
task lint            # 前端类型检查、gofmt 检查、go vet
task test            # lint + Go 单元与接口测试
task test:contract   # Authelia 契约测试
```

契约测试用 testcontainers 启动真实的 Authelia，完整走一遍登录、多用户和退出流程，不包含在 `task test` 中。需要 Docker 或 Podman socket，并且本地已有以下镜像（测试不会拉取镜像）：

```sh
docker pull docker.io/authelia/authelia:4.39.20
docker pull docker.io/testcontainers/ryuk:0.14.0   # 版本须与 testcontainers-go 的 ReaperDefaultImage 一致
```

## 构建

```sh
task build           # 产物：release/asr
```

`task build` 调用 `scripts/build.sh`：`npm ci` → TypeScript 类型检查 → Vite 构建 `frontend/dist` → 嵌入检查 → Go 测试 → `CGO_ENABLED=0` 编译并嵌入前端。嵌入检查确认 `index.html`、`sw.js`、`favicon.svg` 以及 `index.html` 引用的全部打包资源都会被嵌入。`asr -version` 可查看版本号和 commit。

## 配置

完整示例见 [config.example.yaml](config.example.yaml)。通过 `-config path/to/config.yaml` 指定配置文件，默认读取当前目录下的 `config.yaml`。除非设置 `dev_user`，否则 `public_url` 和 `oidc` 必填。

密钥建议用环境变量提供，优先级高于配置文件：

| 环境变量 | 对应配置 |
| --- | --- |
| `ASR_OIDC_CLIENT_SECRET` | `oidc.client_secret` |
| `ASR_DASHSCOPE_API_KEY` | `dashscope.api_key` |
| `ASR_TIDY_API_KEY` | `tidy.api_key` |

在 Authelia 中注册一个 confidential client：回调地址 `<public_url>/auth/callback`，scope 为 `openid profile email`，授权方式 `authorization_code`，要求 PKCE（S256），认证方式 `client_secret_basic`。

## 发布

推送 `v*` 标签（如 `v1.2.3`）后，GitHub Actions 构建 `linux/amd64`、`linux/arm64` 镜像并推送到 GHCR，标签为版本号和 `latest`；带 `-` 的预发布版本不更新 `latest`。也可以在 Actions 页面手动运行 release 工作流并填写版本号。

容器内数据目录为 `/app/data`，配置文件需挂载到 `/app/config.yaml`。
