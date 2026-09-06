# 工时与项目成本管理系统

按已批准的 MVP 产品设计实现的本地验证版。填报、审批、更正、管理分析、月报修订和后台任务使用真实 MySQL / Redis；本地账号与测试费率用于验证业务。真实企微 SSO、消息投递和 OA 自动接口的适配与联调尚未完成。

## 本地体验

- [管理后台](http://127.0.0.1:5175/admin/)
- [手机 H5 布局](http://127.0.0.1:5174/h5/)
- [API 健康](http://127.0.0.1:8080/actuator/health)

登录页提供员工 `98123`、项目经理 `XX12345`、部门负责人 `ZD23412`、管理员 `00123`。工号是字符串，前导零保留，换号不改变内部人员 ID。所有样例均为测试数据；本机 H5 链接尚不能代表公司网络上的手机入口。

可以从员工填报开始：保存日记录 → 预览自然周并送审 → 换项目经理逐项确认 → 回到员工发起更正 → 原处理人核实 → 重新送审。管理员可查看项目成本、人员负荷及履约、现场台账与占比，申请 Excel 导出，并在月报页查看正式版本和授权修订。

## 本地启动

需要 Java 21、Node.js 22.22.2 / npm 10、Python 3（用于本地脚本）和 Docker。仓库不包含 `.runtime/`、依赖目录、编译产物、运行密钥或本地数据库；macOS Apple Silicon 可先运行 `scripts/bootstrap-runtime.sh` 准备 Java / Maven，其他环境见[运行说明](docs/development/local-runtime.md)。

```bash
# 项目根目录；先启动数据库，再启动 API
./scripts/local-infra.sh up
./scripts/start-local-api.sh

# 新终端；API 构建完成后启动同一制品的 worker
./scripts/start-local-worker.sh

# 两个新终端，分别启动管理端和手机端
npm --prefix frontend ci
npm --prefix frontend run dev:admin
npm --prefix frontend run dev:h5
```

API、worker 从同一项目根目录运行，共享 `.local/exports` 私有导出目录；API 在构建锁内保存不可变 JAR 快照，worker 通过 `.local/run/api-artifact.path` 复用同一制品。后续编译不会覆盖运行中的制品，更换版本时应成对重启两进程。记录保存在数据库中，页面刷新、API 重启不会丢失。所有服务仅监听本机；不要删除数据卷来处理普通错误。

## 功能范围

- 双端日填报、自然周送审、独立现场日、复制结构、校验提示、冲突时保留输入。
- 按归集对象分包、逐项确认和驳回、本人回避、公司指定、临时转交、争议协调；已确认记录通过更正或取消版本处理。
- 发生日职级 / 费率、逐条金额舍入、历史成本快照；员工接口隐藏金额。项目、部门与公司权限分别限制报告和导出范围。
- 三个分析视图承载五项报告能力；实时与正式版本、私有异步 Excel、生成和下载时重新检查权限。
- 月度封账 V1、按人 / 日 / 对象 / 动作授权、修订差异预览、V2 发布与旧版本原样保留。
- 人员组织与任职、职级和授权历史、批量调整预览、归集对象、历史费率、工作日历与部门待分配对象。
- 六类 XLSX：人员、组织、项目、费率、请假、出差。预览 / 确认 / 错误下载 / 重试、来源覆盖核实、项目冲突显式关联；出差单不自动生成现场事实。
- 生效版本化的规则参数、自动送审、封账、提醒、任务租约和重试、审计与本地收件箱。`LOCAL` 通知表示本地验证记录，尚未发送到企微。

界面沿用用户确认的设计，并参考 `design-taste-frontend` 技能；手机端使用 Vue 3 + Vant，Admin 使用 Vue 3 + Element Plus。后端实际依赖版本见 `backend/pom.xml` 和锁文件。

## 验证

```bash
# 串行化 Maven 对共享 target/ 的写入；启用真实 MySQL 集成矩阵
WORKLOG_INTEGRATION=true python3 scripts/check-backend.py verify
npm --prefix frontend run typecheck
npm --prefix frontend run build
npm --prefix frontend test

# API / worker 运行后，本地隔离环境的 HTTP 与实际任务验证
python3 scripts/verify-local-api.py
python3 scripts/verify-master-data.py
python3 scripts/verify-calendar-leave.py
python3 scripts/verify-worker.py
python3 scripts/verify-local-load.py

# 备份恢复到新建检查库，保留原库和数据卷
python3 scripts/verify-local-recovery.py
```

数据库集成、HTTP、浏览器及恢复演练分别记录，测试脚本存在不代表对应检查已经通过。自动验证会保留带标记的本地样例与审计。当前结果见[本地 MVP 验收](docs/development/MVP本地开发验收.md)和[验收矩阵](docs/development/mvp-acceptance-matrix.md)。

## 真实接入与发布

源码托管在 GitHub 不会自动启动应用。当前临时外网演示由 Docker 运行，使用独立测试数据库及网页访问口令；启动、恢复与停止见[外网演示环境](docs/development/外网演示环境.md)。该入口仍依赖演示主机，长期固定访问需单独部署后端、数据库及 HTTPS 入口。

按用户要求先完成本地验证。生产 profile 不提供本地登录；本地样例迁移仅在 `db/local`。真实 SSO 必须在服务端验证既有企微身份关联，OA 接入复用已核实来源和导入处理流程。

正式上线仍需要公司 SSO / OA 契约与联调、企微目标人员实际收件、真实主数据及费率、公司 HTTPS 与手机网络、异地备份和业务试点。已完成的本地恢复演练不替代这些发布条件。部署参考不会自动发布服务。

- [MVP 产品设计](docs/prd/工时与项目成本管理系统_MVP产品需求设计_v1.md)
- [技术架构](docs/architecture/工时与项目成本管理系统_MVP技术架构_v1.md)
- [实施计划](docs/plans/工时与项目成本管理系统_MVP实施计划_v1.md)
- [审批接口](docs/development/approval-api.md)、[治理接口](docs/development/governance-api.md)
- [报表接口](docs/development/reporting-api.md)、[运营接口](docs/development/operations-api.md)
- [项目来源关联](docs/development/project-source-link-api.md)
- [恢复演练](docs/development/本地恢复演练.md)、[运行说明](docs/development/local-runtime.md)
