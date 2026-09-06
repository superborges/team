# 本地运行与部署脚手架

更新：2026-09-05。当前用于完整本地业务的开发和验证；真实企微 SSO、OA、生产网络及业务试点尚未完成。用户已选择先本地验证。本地示例身份只在 `local` profile 开启，不能用于正式环境。

## 1. 已验证的运行环境

| 组件 | 本次实际版本 / 状态 |
|---|---|
| 平台 | macOS / Apple Silicon |
| Java | Temurin 21.0.12.1+1；官方 SHA256 校验通过 |
| Maven | 3.9.11；发行包 SHA512、Wrapper ZIP SHA512 校验通过；Wrapper 固定 ZIP SHA256 |
| Node / npm | 22.22.2 / 10.9.7，复用已安装版本 |
| Docker Engine | 29.7.2，使用已安装的 Docker Desktop |
| MySQL | 8.4.11 / InnoDB / utf8mb4；实际连接查询通过 |
| Redis | 7.4.11；认证连接返回 PONG |

Java 与 Maven 放在项目 `.runtime/`，没有安装全局软件包；Maven Wrapper 仍按官方默认在用户 `.m2/` 缓存依赖和发行包。本地 MySQL / Redis 的镜像 digest 固定在 `deploy/compose.local.yml`，容器项目为 `allen-worklog`。生产 Redis 版本仍需对齐公司支持范围。

## 2. 启动本地环境

以下命令均在项目根目录执行。首次启动 Docker Desktop 后，等待 Docker 图标显示引擎已运行。

```bash
open -a Docker
scripts/bootstrap-runtime.sh
scripts/local-infra.sh up
scripts/verify-infra.sh
```

`bootstrap-runtime.sh` 为当前 macOS Apple Silicon 提供带校验和的固定发行包；其他平台准备 Java 21 后直接使用 Maven Wrapper。脚本不会覆盖已有的 `.runtime/java` 或 `.runtime/maven` 非预期路径。

基础服务仅监听 `127.0.0.1`，以下均为公开的本地开发口令，正式环境禁止复用：

| 服务 | 地址 | 数据库 / 用户 | 本地口令 |
|---|---|---|---|
| MySQL 业务连接 | `127.0.0.1:13306` | `worklog` / `worklog` | `worklog-local` |
| MySQL 本地迁移检查 | `127.0.0.1:13306` | 独立检查库 / `root` | `worklog-root-local` |
| Redis | `127.0.0.1:16379` | 默认认证用户 | `worklog-redis-local` |

在一个终端启动后端：

```bash
scripts/start-local-api.sh

# API 完成构建后，另开终端运行后台任务
scripts/start-local-worker.sh
```

该脚本显式启用 `local,api`，绑定 `127.0.0.1:8080`，使用上述 local profile 连接配置。API 与 worker 均从项目根目录运行，共享 `.local/exports`。API 在构建锁内复制不可变制品，并将绝对路径记入 `.local/run/api-artifact.path`；worker 从该指针复用同一制品，不读取可能已被重新构建的 `target/`。更换版本时成对重启 API 与 worker。本地 profile 执行数据库迁移与本地样例初始化。健康入口为 `http://127.0.0.1:8080/actuator/health`，它不是完整业务验收。

另开终端运行前端：

```bash
cd frontend
npm ci
npm run dev:admin
```

管理端地址：`http://127.0.0.1:5175/admin/`。再开一个终端在 `frontend/` 执行 `npm run dev:h5`，手机布局入口：`http://127.0.0.1:5174/h5/`。两个开发服务器通过代理访问同一个后端；当前仅在本机浏览器验证，未声称企微手机网络已经打通。管理端使用 5175，避免占用本机已有服务的 5173 端口。

## 3. 检查与停止

```bash
scripts/local-infra.sh status
scripts/verify-infra.sh
scripts/local-infra.sh logs
```

后台与前端开发进程在各自终端按 `Ctrl+C` 停止。基础设施执行以下命令，保留数据库和 Redis 卷：

```bash
scripts/local-infra.sh stop
```

恢复时再次执行 `scripts/local-infra.sh up`。不要使用 `down -v`、删除 `allen-worklog_mysql-data` 或 Docker 全局清理命令来处理普通启动问题。容器首次初始化后，修改 Compose 密码不会自动修改已有数据库账号密码。

构建入口如下；测试结果以本次实际运行记录为准：

```bash
WORKLOG_INTEGRATION=true python3 scripts/check-backend.py verify
npm --prefix frontend run typecheck
npm --prefix frontend run build
```

基础设施和 API 已启动时，运行实际 HTTP / MySQL 验收：

```bash
python3 scripts/verify-local-api.py
```

脚本只接受固定本地地址、显式 local 模式和本地种子身份，选择尚无记录的测试日期，保留测试版本与审计。最后一次执行结果写入 `docs/development/local-api-verification.md`；原始结果在 `.local/`。这份检查不调用外部企微或 OA。XLSX 导入的 POI / MySQL 验证另见 [导入验证记录](import-verification.md)，其接口范围见 [导入接口约定](import-api.md)。

## 4. 正式环境部署参考

`deploy/compose.reference.yml` 是后续部署参考，当前未用于生产。它与本地 Compose 分开使用，不合并配置。参考方案连接已有的私有 MySQL / Redis，由公司 HTTPS 入口代理本机 `127.0.0.1:8088`。

- `api`：同一后端制品，`prod,api` profile，内部 8080。
- `worker`：同一后端制品，`prod,worker` profile，强制关闭 HTTP；执行持久化队列中的自动送审、封账、导出和固定提醒。
- `web`：构建后的 H5 / Admin 静态文件，经 Nginx 提供 `/h5/`、`/admin/`、`/api/`；私有文件卷与 Actuator 不暴露为网页目录。
- API 与 worker 均关闭启动时 Flyway；正式模式不启用本地登录或样例迁移。
- API 与 worker 的 `EXPORT_DIR` 都显式固定为 `/app/files`，映射同一个 `private-files` 卷；JRE镜像以UID10001运行，该目录归同一用户所有。只读根文件系统的临时写入使用 `/tmp`。

镜像构建前先完成前述构建检查；以下命令只构建本地镜像，不推送或发布：

```bash
docker build -f deploy/Dockerfile.backend -t worklog-backend:0.1.0 .
docker build -f deploy/Dockerfile.web -t worklog-web:0.1.0 .
```

运行环境必须由运维注入 `BACKEND_IMAGE`、`WEB_IMAGE`、`DB_URL`、`DB_APP_USER`、`DB_APP_PASSWORD`、`REDIS_HOST`、`REDIS_PASSWORD`，镜像使用发布时固定的版本或 digest。配置未提供必需值时 Compose 会直接拒绝解析；不存在生产默认口令。运行数据库账号与迁移账号分离，审计表仅允许运行账号追加和读取。

本次已实际构建 `worklog-web:0.1.0`，以只读根文件系统启动临时容器，验证 `/healthz`、`/admin/`、`/h5/`、两端子路由刷新及 JS / CSS 静态资源均返回 200，资源类型正确。检查容器已停止，未发布到外部环境；原始结果在 `.runtime/web-image-verification.json`。

`deploy/nginx.conf` 假定该监听口仅由已验证的公司 HTTPS 入口访问，因此转发协议声明为 HTTPS。DNS、证书、可信代理和企微内访问路径必须在正式发布前联调，不能直接把该 HTTP 端口开放为员工入口。

## 5. 单次数据库迁移

运维通过现有受控机制在进程环境注入 `DB_URL`、`DB_MIGRATION_USER`、`DB_MIGRATION_PASSWORD`，完成备份并确认目标库后执行：

```bash
scripts/migrate.sh
```

该脚本通过独立 `deploy/migration-pom.xml` 使用与后端同一 Spring Boot BOM 的 Flyway / MySQL 驱动，只读取 `backend/src/main/resources/db/migration`，不包含 `db/local`。迁移时校验已应用迁移，禁止 Flyway clean。两份 POM 的 Spring Boot parent 必须同步升级。

本次已在专用 `worklog_migration_check` 库依次执行 V1–V10；2026-09-05 19:54 从V4实际升级至V10并重跑，Flyway 显示无待执行迁移，十个迁移记录均 success=1，且 `app_user`、`work_item`、`day_record` 行数均为0，确认没有加载本地样例身份或业务数据。原始输出在 `.runtime/migration-check.log` 和 `.runtime/migration-repeat-check.log`。该检查库独立于本地应用 `worklog`。不要对已经含本地样例迁移的开发库使用正式迁移检查来冒充正式空库验证。

正式发布顺序为：构建检查 → 独立备份 → 单次迁移 → 发布 API / worker / web → 业务与任务健康检查。代码回退不会逆向撤销数据库迁移；兼容性、备份恢复和完整月报验收仍按实施计划执行。

数据库隔离恢复、48张表行数与正式月报内容哈希核对结果见 [本地恢复演练](本地恢复演练.md)；五项分析、导出与月度修订回归见 [报表验证](reporting-verification.md)。

## 6. 部署接缝复核

2026-09-05完成所有shell脚本 `bash -n`、Python脚本语法解析、README/本运行说明中的本地Markdown链接检查，均通过。正式compose以占位参数执行 `config --format json` 并检查：api/worker只含prod业务profile、关闭Flyway、共享 `/app/files` 私有卷与EXPORT_DIR，worker无HTTP，web端口仅绑定回环地址。只读临时web容器执行 `nginx -t` 通过，容器已自动移除；该检查未启动生产compose或发布服务。

API启动复制发生在Maven构建锁内；worker读取 `.local/run/api-artifact.path`，避免API启动后另一轮package导致两进程版本不同。该路径指向不可变快照，更新应用版本时需成对重启。

## 当前本地制品

最终 API / worker 使用同一份不可变 `worklog-mvp-20260905-2010.jar`，启动指针为 `.local/run/api-artifact.path`。当前 local 包含 V1000–V1002 测试数据迁移；V1002 为旧开发部门补齐缺失的待分配对象。正式迁移仍只有 V1–V10，不包含测试身份、费率或开发补种子。实际测试摘要见 [MVP 本地验收](MVP本地开发验收.md)。
