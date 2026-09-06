# Coolify 稳定演示部署

本配置从公开仓库 [superborges/team](https://github.com/superborges/team) 的源码构建完整 MVP，运行在阿里云服务器上，目标域名为 `team.ninestories.cn`，访问不再依赖开发电脑或临时隧道。管理入口为 `http://47.253.187.165:8000/`。本配置是独立演示环境：保留网页访问口令与测试身份选择，首次启动用仓库里的 Flyway 本地样例初始化独立数据库；现有电脑上的演示记录不会自动搬入。

## 资源与构建

| 服务 | 用途 | 存储与访问 |
| --- | --- | --- |
| `web` | Node 22 执行锁定依赖安装、类型检查、`build:demo`，Nginx 提供 H5 与管理端 | 唯一配置外部域名的服务，容器端口 `80` |
| `api` | Maven / JDK 21 从源码构建，JRE 21 运行 Spring Boot | 内部 `8081`；启动时完成数据库迁移 |
| `worker` | 与 API 使用相同源码与运行镜像定义，执行后台任务 | 无 HTTP 服务，等待 API 健康后启动，不执行 Flyway |
| `mysql` | MySQL 8.4，独立 `worklog_demo` 库及同名业务账号 | `mysql-data` 命名卷，无宿主机端口 |
| `redis` | Redis 7.4，会话及应用依赖 | `redis-data` 命名卷，无宿主机端口 |

API 与 worker 均以 `10001:10001` 运行，共享 `exports` 命名卷；镜像预设目录属主，首次挂载即可写入。所有状态保存在命名卷，应用重建与重启不会删除数据。MySQL 业务账号仅获得本应用数据库权限，root 口令不传入 API 或 worker。

构建文件为 `deploy/Dockerfile.coolify.backend`、`deploy/Dockerfile.coolify.web` 及对应的 `.dockerignore`。构建只读取源码、锁文件和 Nginx 配置，不读取 `.local`、`.runtime`、本机 `target` 或 `dist`。Nginx 复用 `deploy/nginx.demo.conf`，使用现有网页口令与会话校验；公开的登录样式来自 `frontend/apps/shared/tokens.css`。

## 首次部署

1. 在 Coolify 的项目中创建资源，选择 **Public Repository**，填入 `https://github.com/superborges/team`，选择实际推送的分支。公开仓库无需额外 GitHub 授权或 Deploy Key，接入步骤见 [Coolify 的 Compose Build Pack 文档](https://coolify.io/docs/applications/build-packs/docker-compose)。
2. Build Pack 选择 **Docker Compose**；Base Directory 填 `/`；Docker Compose Location 填 `/deploy/compose.coolify.yml`。保留普通 Compose 部署模式，由 Coolify 管理代理与网络。
3. 在 Environment Variables 填写下表 4 项。使用密码管理器分别生成随机口令，访问口令至少 12 位，建议四项均使用 32 位以上字母数字随机值。保存为运行时变量，关闭这些变量的 Build Variable 选项；Advanced 中关闭自动向 Dockerfile 注入 Build Args，本项目构建不需要任何秘密。
4. 仅给 `web` 配置 `https://team.ninestories.cn`，端口为 `80`。为 `team.ninestories.cn` 添加指向 `47.253.187.165` 的 A 记录，服务器与阿里云安全组允许 `80/443`，由 Coolify 配置 HTTPS。其他服务的 Domains 留空，无需添加 `ports` 或自定义网络。服务域名与内部端口规则见 [Coolify Compose 网络与环境变量说明](https://coolify.io/docs/knowledge-base/docker/compose)。
5. 点击 Deploy，等待 MySQL、Redis、API 和 web 健康，worker 运行。初次构建会下载 Maven、npm 依赖与基础镜像；构建日志中不应出现任何本机绝对路径。

| 变量 | 用途 |
| --- | --- |
| `DB_ROOT_PASSWORD` | 首次初始化 MySQL 的 root 口令 |
| `DB_APP_PASSWORD` | `worklog_demo` 业务账号口令，API / worker 共用 |
| `REDIS_PASSWORD` | Redis 认证，API / worker 共用 |
| `DEMO_ACCESS_PASSWORD` | 网页入口口令，用户名固定为 `preview` |

四个变量没有内置值；缺失时 Compose 会停止部署。MySQL 初始化变量只对空数据卷生效，已有数据库修改密码需先执行数据库账号变更，再同步 Coolify 变量；仅修改变量不会重设数据库密码。

Coolify v4.1.2 在 `build` 时也会解析完整 Compose，但构建环境不包含上述运行时口令。因此在 General 的 **Custom Build Command** 填入以下命令，使用仅对构建进程有效的普通占位值；**Custom Start Command** 保留默认值。Coolify 会自动补充 Compose 文件、项目名和仓库根目录参数，启动时再读取真实运行时口令。不要把占位值保存为运行时变量，也不要为真实口令打开 Available at Buildtime。

```sh
DB_ROOT_PASSWORD=build-only DB_APP_PASSWORD=build-only REDIS_PASSWORD=build-only DEMO_ACCESS_PASSWORD=build-only docker compose build
```

构建上下文以仓库根目录为准；本地运行同一文件时必须加 `--project-directory .`，与 Coolify 的路径解析保持一致。

## 使用与验收

电脑端访问 `https://team.ninestories.cn/admin/`，手机端访问 `https://team.ninestories.cn/h5/`。先输入 `preview` 和配置的访问口令，再选择系统内的测试身份。页面使用 Secure 会话 Cookie，需要 HTTPS；不要改为普通 HTTP 来绕过访问问题。

上线后用 Chrome 与微信各验证一次网页口令登录、测试员工进入工时页、保存草稿与审批列表。未通过网页口令时 `/api/v1/auth/status` 应为 `401`，页面应转入口令表单；通过入口后仍须完成测试身份登录，业务写操作继续校验 CSRF。自然周提交和现场日独立送审沿用现有业务实现。健康端点仅用于容器内部，不从 Nginx 对外公开。

本配置使用 `local,api` / `local,worker`，启用现有演示守卫；通知保持本地记录，不发送真实企微消息。业务日期保持 `Asia/Shanghai`，数据库连接保持 UTC 时间处理。正式接入企微 SSO 时应另用正式部署方案，不能把本配置的测试身份入口作为正式登录。

## 更新与本地验证

向配置的 Git 分支推送后，在 Coolify Redeploy；配置 GitHub App 自动部署时可随推送触发。正常更新保留三个命名卷，不执行删除卷操作；备份、恢复时同时保存 MySQL 数据、私有导出文件和 Coolify 中的环境变量。

本地构建验证可在仓库根目录注入四项临时测试口令后执行以下命令；不要复用电脑上已有的 Compose 项目名或公开临时测试口令：

```sh
docker compose --project-directory . -p team-coolify-check -f deploy/compose.coolify.yml config --quiet
docker compose --project-directory . -p team-coolify-check -f deploy/compose.coolify.yml build
docker compose --project-directory . -p team-coolify-check -f deploy/compose.coolify.yml up -d
docker compose --project-directory . -p team-coolify-check -f deploy/compose.coolify.yml ps
docker compose --project-directory . -p team-coolify-check -f deploy/compose.coolify.yml exec -T api curl --fail http://127.0.0.1:8081/actuator/health
docker compose --project-directory . -p team-coolify-check -f deploy/compose.coolify.yml exec -T web wget -q -O /dev/null 'http://127.0.0.1/demo-access/login?target=h5'
docker compose --project-directory . -p team-coolify-check -f deploy/compose.coolify.yml down
```

这组命令不映射宿主机端口，适合验证干净源码构建、启动顺序与内部健康；可在 API 和 worker 容器中执行 `test -w /app/exports` 检查导出目录权限。完整浏览器验收在 Coolify 的 HTTPS 地址进行。`down` 保留验证卷；确认本地验证数据无保留价值后，仅清理 `team-coolify-check` 项目的三个验证卷。

2026-09-06 已在本地 Linux/arm64 完成两个源码 Docker build：前端通过依赖安装、类型检查与 demo 打包，后端通过 Maven 编译打包（跳过单元测试执行）。镜像检查确认 Nginx 配置有效、仅包含构建静态产物、JAR 包含业务与演示迁移、`10001:10001` 可写新建导出卷。

随后用随机临时口令、刚构建的镜像和独立 `team-coolify-check` 项目运行了完整五服务：MySQL、Redis、API、web 全部健康；worker 产生新鲜心跳且无错误日志；网页口令表单返回 `200`、匿名业务 API 返回 `401`；API 和 worker 的非 root 用户均可写同一导出卷。验证没有映射宿主机端口，结束后已清理专用容器、网络和数据卷，已有演示容器保持运行。构建、运行日志与 `checks.json` 保存在未提交的 `.local/coolify-build/`；仍需完成服务器部署后的 HTTPS 浏览器验收。
