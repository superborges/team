# 双前端本地开发

Admin 使用 Vue 3 / Element Plus，H5 使用 Vue 3 / Vant 4。两端共享 `packages/api-client` 的 HTTP 契约和序列化规则，不使用浏览器 mock 服务或 localStorage 登录令牌。先按项目开发说明启动 MySQL、Redis 和 8080 API。

```sh
npm ci
npm run dev:admin
npm run dev:h5
```

两个开发命令分别在独立终端运行：Admin `http://127.0.0.1:5175/admin/`，H5 `http://127.0.0.1:5174/h5/`。5173 已被本机另一应用占用，本项目不停止该应用。两端 `/api` 均代理到 `http://127.0.0.1:8080`，保持同源 cookie 与 CSRF。生产静态部署采用架构规定的同域 `/admin`、`/h5`、`/api` 路由。

开发环境必须同时满足 Vite DEV 和 API `localLoginEnabled=true` 才显示测试身份选择。默认员工工号 `98123`，管理员 `00123`；其他测试身份见 API 契约。生产构建不显示本地测试登录入口。真实 SSO 尚未接入时明确显示不可用，不从浏览器传入 UserID 伪造会话。

## 验证

```sh
npm run typecheck
npm run build
npx playwright install chromium
npm test
node scripts/verify-local.mjs
node scripts/verify-imports.mjs
node scripts/verify-workflow.mjs
node scripts/verify-extended-imports.mjs
node scripts/verify-governance.mjs
node scripts/verify-project-link.mjs
node scripts/verify-dispute.mjs
node scripts/verify-master-batch.mjs
node scripts/verify-department-items.mjs
```

- `npm test` 是八个浏览器 / 日期回归检查，使用测试文件内明确声明的 HTTP fixtures，覆盖 CSRF 登录、409 输入保留、取消日期切换、独立现场零工时、不可用状态、自然周日期、UTC 操作时间、取消版本、争议只能退回及不确定请求重试标识、批量预览指纹冲突，以及部门精简取数 / 撤权输入保留。
- `verify-local.mjs` 仅对运行中的真实 local API 执行：保存测试员工当天 4h 项目 + 1h 非项目 + 3h 待分配和现场，H5 回读 / 现场取消重开，真实保存响应等待期间的忙态，管理员部门新增 / 编辑、项目新增、费率 / 日历读取。它会写测试业务数据，只接受空日期或该脚本自身留下的内容，不覆盖其他内容。
- `verify-imports.mjs` 从真实接口下载项目模板，用 Python 标准库写入一行有效项目与一行错误部门，经页面上传、预览、应用和重试。验证 multipart / CSRF、部分成功逐行结果，以及成功项目不会重复新增；会留下清楚标注的本地导入样例。
- `verify-workflow.mjs` 创建独立 WF 工号与项目，执行 H5 填报 / 提交、PM 部分审批、申请更正 / 核实 / 重提、当前成本、异步导出、现场分析、规则预览与 2026-07 空月份 V1 / 修订发布。后端 worker 必须运行；需要接续报表阶段时可使用 `--resume-reports`，读取已留存测试身份。
- `verify-extended-imports.mjs` 经真实模板导入独立组织、人员及出差；验证仅新增员工权限、逐行失败文件与显式部分覆盖。`verify-governance.mjs` 使用该脚本的独立员工验证部门副职和领导范围的新增 / 即时撤销 / 历史，并检查出差没有自动变为现场申报。
- `verify-project-link.mjs` 在已建验证项目上导入疑似同名来源，人工显式关联后重试，检查全部原主数据与内部 ID 保留。`verify-dispute.mjs` 为验证工时发起协调，由项目主责部门核实原始事实，以确认事实结案，保留原批准状态。脚本可从项目根目录或前端目录执行，输出固定在前端目录。
- `verify-master-batch.mjs` 创建两名独立员工，预览并原子应用当天职级历史及未来副职任职，保留工号和角色，并撤销测试未来任职。`verify-department-items.mjs` 验证部门负责人仅读取精简选项并维护所辖本地非项目事项；查看真实待交接数量的停用提示后取消，不修改已有验证身份。
- 真实检查结果、样例文件及截图位于被 Git 忽略的 `local-verification/`；浏览器 fixtures 输出在 `test-results/`，不会清理真实验证证据。测试 fixtures 成功不代表企微、OA 或生产上线已验证。

当前页面覆盖日周填报与送审、逐项审批 / 更正 / 转交 / 争议协调、五类分析与异步导出、正式月报 / 修订、人员组织 / 批量调整 / 范围授权 / 历史、部门非项目事项、停用交接提示、费率、日历、六类 XLSX 导入 / 来源覆盖，以及规则 / 任务 / 通知 / 审计。审批入口向所有登录用户开放，具体动作由服务端 can* 字段控制。统一费率对 PM / 部门负责人 / 公司领导只读，管理员维护。

失败请求保留输入；保存 / 复制 / 送审期间禁止日期切换、增删、退出和路由离开。取消更正显示为独立取消版本，不作为普通零小时工时编辑。错日期更正可为新记录关联本人已获准的 CANCEL 请求，旧主体不移动日期。共用原生 dialog 提供模态焦点与键盘行为，忙态不可取消。原子保存、精确月份范围与即时权限仍由服务端强制执行。

## 依赖基线

Node `22.22.2`（`.nvmrc`），npm workspaces 与 package-lock。Vue `3.5.42`、Router `4.6.4`、Vite `8.2.2`、TypeScript `5.9.3`、vue-tsc `3.3.11`、Vant `4.10.2`、Element Plus `2.14.5`、官方图标 `2.3.2`、Playwright `1.63.0`。依赖均固定精确版本。

安装方式核对官方资料：[Vite 起步](https://vite.dev/guide/)、[Vant 官方文档](https://vant-ui.github.io/vant/)、[Element Plus 安装](https://element-plus.org/en-US/guide/installation.html)。视觉规则和用户指定技能的适用性见 [DESIGN.md](./DESIGN.md)。
