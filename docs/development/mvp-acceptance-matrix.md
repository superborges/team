# MVP AT01–AT18 实现与验证矩阵

核对日期：2026-09-05。依据 [MVP 产品需求设计 §10](../prd/工时与项目成本管理系统_MVP产品需求设计_v1.md) 与已批准实施计划。本矩阵记录本地核心实现和已执行证据；**不把单个测试方法、界面存在或本地样例成功等同于整项业务验收通过**。生产外部接入、真实名单和业务试点不在本次本地通过范围。

路径以仓库根目录为基准。审批 8 项、治理 5 项真实 MySQL 专项于 20:03:46 通过；最终统一构建和双端结果以 [MVP 本地开发验收](MVP本地开发验收.md) 为准。首批 A / XLSX 文档保留当时验证记录，其“尚未实现”描述不代表当前范围。

## 逐项映射

| 标准 | 已实现位置与行为 | 已执行的真实证据 | 尚需业务验收 / 接入验证 |
|---|---|---|---|
| AT01 工号与稳定身份 | `master/MasterDataService`、`identity/CurrentUser`、`integrations/ImportService`；字符串工号、稳定 bigint ID、换号不改引用，无法解析旧工号时明确拒绝，不按姓名猜配。 | [主数据 HTTP 回归](master-data-verification.md) 验证两类工号、前导零、换号后同会话同 ID；`ImportWorkbookTest` / `ExtendedImportDatabaseTest` 验证文本工号和人员导入。 | 真实 SSO 验签、企微 UserID 对应和旧工号补拉语义须与公司契约联调；本地不自动猜测旧工号。 |
| AT02 工作负荷和待分配 | `work/DayService`、`approval/ApprovalService`、`reporting/ReportService`；工作与待分配分开，只有当前已确认项目工作计人工成本，未决保持不完整提示。 | `ApprovalDatabaseTest.approvedBusinessExamplesMatchWorkloadTravelAndOnsiteRatioWithoutDiscountingCost` 实际保存→送审→审批→报表，4h 项目 + 1h 非项目 + 3h 待分配得到 62.50% 负荷；分包测试证明非项目和待分配成本为 0。 | 试点抽核真实日输入、未决解释是否被管理人员正确使用。 |
| AT03 请假基数与去重 | `work/DayRules.leaveMinutes`、`integrations/ImportService`、`master/MasterDataService`；合并已批准时段，来源幂等，日历变动从原始请假重新计算。 | [日历 HTTP + MySQL](master-data-verification.md) 休息日 0/0/0 恢复工作日后为 480/240/240；[导入回归](import-verification.md) 同来源重放不加扣假，重叠时段合并，未知区间阻断确认完整；`local-api-verification` 半天假读回。 | OA 的审批、撤销、时段定义及覆盖完整性需真实样本；半天假 + 4h 的完整业务签收仍由试点确认。 |
| AT04 发生日标准与快照 | `costing/CostingService`、`approval/ApprovalService`；发生日职级和日费率，逐项 HALF_UP 到分，保留采用标准与职级历史 ID，缺标准阻断该项。 | `CostingDatabaseTest` 实查 8/31、9/1 职级分界，4h 分别 600.00、800.00；`CostingServiceTest` 舍入；审批矩阵缺人工标准只阻断人工，现场可通过，更正后旧金额快照不变。 | 尚需使用公司实际标准做一次“8/31 工作、9/1 调级/调价、9/3 审批”的业务见证；日期查询测试不冒充整条日历场景。 |
| AT05 交通中支援不重复 | `work/DayService`、`approval/ApprovalService`、`reporting/ReportService`；工作项按实际归集对象拆分，工时种类不叠加计费。 | 审批业务示例经真实保存、审批、报表得到 A 360 分钟、B 120 分钟、合计 480 分钟，项目占比 75%。 | 系统无时间轴推断；交通内支援由填报人拆分，审批人核实无重叠。 |
| AT06 独立现场与必要驻留 | `work/DayService`、`approval/ApprovalService`、`reporting/ReportService`；现场一天一个对象、独立送审，工时 0 时占比不适用。 | 审批路由和业务示例验证零工作现场、周日必要驻留独立通过且现场费 200.00；`ExtendedImportDatabaseTest` 出差来源不会自动创建现场日。 | 半天 / 当天往返、必要驻留与私人延长须业务核实，不能仅凭出差单或时数自动认定。 |
| AT07 本人回避与路由异常 | `approval/ApprovalData.route`、`ApprovalService`、`MasterGovernanceService`；PM、负责人和领导按明确回避链处理；无有效人保留异常，公司指定有依据和审计。 | `routingSelfAvoidanceUnresolvedDayAtomicityAndZeroWorkOnsite` 验证三类本人链、无接收人不通过、显式指定后可继续；同日工时先整体验路由。 | 用真实组织名单核实指定负责人、领导和公司指定人；不能由管理员角色隐式代批。 |
| AT08 部分处理后的转交 | `approval/ApprovalService.transfer`、`approval_transfer`；只变更待处理项实际接收人，保留旧结论与逐轮截止；停用提示待办数量。 | 分包测试核对旧处理人、时刻、截止及新权限；两线程同版本审批/转交只有一方成功。 | 本地收件箱已用真实 worker 验证，真实企微接收、离职即时停用及业务交接须联调。 |
| AT09 事实争议与协调 | `approval/DisputeService`、`CorrectionService`、`work/DayService`；审批人不能改员工小时数；按项目主责部门协调，陈述追加、升级留痕，只限制争议项。 | `disputeOnlyBlocksItsRowAndIndependentRulingDoesNotApproveIt` 验证另一项正常通过、协调/升级、历史协调可见、确认事实不自动审批、要求更正不会直接改金额。 | “实际 8h 与合理 4h”的事实判断需真实双方及协调人演练；机制不替代核实。最后一轮浏览器争议流程见双端报告。 |
| AT10 自然周与自动提交 | `approval/ApprovalService.submitSystem`、`operations/JobService` / `JobWorker` / `JobScheduler`、`ConfigService`；同一送审校验，持久任务键、租约及系统审计。 | [真实 worker 7 项](worker-verification.md)：上个自然周、完整日成功、不足日保留、现场独立、重复执行、过期租约接管、失败通知；`ApprovalDeadlineTest` 跨年上海时间截止。 | 未模拟 OS 崩溃或跨主机接管；真实企微投递、实际定时运营及提醒效果须验证。 |
| AT11 查询 / 导出范围一致 | `reporting/ReportingAccess`、`ReportService`、`ExportService`、`approval/ApprovalData`；临时接收包不扩大成本范围，人员与项目范围各按业务口径。 | [报表 MySQL 矩阵](reporting-verification.md) 员工无金额、PM 跨部门本项目、部门员工外项目不泄漏金额；导出申请/生成/下载重查，撤权后下载拒绝；审批普通临时接收人无成本字段。 | 真实角色与组织配置需业务签收；多角色混合账号应按实际名单抽查。 |
| AT12 正式版与修订 | `closing/MonthClosingService`、`PeriodGate`、`reporting/ReportFacts` / `ExportService`；V1 截点冻结、范围授权、V2 发布切换、旧版保存。 | [报表 5 项矩阵](reporting-verification.md) V1 1550.00 / V2 1300.00、旧版标签金额不变、修订发布前仍 V1、范围关闭、导出固定选定版本；审批另证实跨月精确动作。 | 报表测试直接布置审批事实，不能替代真实 9/10 未决→9/12 授权补审→发布并导出旧 V1 的一次业务见证；已有本地浏览器月报流程，试点仍需覆盖真实封账周期。 |
| AT13 现场投入占比 | `reporting/ReportService`；按实际工作分母、汇总累计分钟，不平均逐日百分比；低于 50% 仅复核，0 工时不适用。 | 审批业务示例实际批准 A 2h / 全日 8h 得到 25% 且 `reviewRequired=true`，现场成本仍 200.00；纯驻留比率 null / NOT_APPLICABLE，成本仍 200.00。 | 对跨日汇总与合理 B 支援的实际样本抽核；提示不能被当成自动扣费依据。 |
| AT14 日校验与部分重提 | `work/DayRules` / `DayService`、`approval/ApprovalService`；日总量、上限、内容和超长说明校验，审核保留红标；全日校验但只提交新版本，现场独立。 | `DayRulesTest` / [日 API 回归](local-api-verification.md)；审批 6h 已确认 + 2h 驳回后只送 2h；真实 worker 不足 7h 时保留工时草稿但提交现场。 | 内容可理解性、16h 说明的真实审批质量需试点观察；自动测试不证明实际认真核实。 |
| AT15 封账与共享基数 | `closing/PeriodGate`、`MonthClosingService`、`master/MasterDataService`、`integrations/ImportService`；按人 / 日 / 对象 / 记录 / 动作授权，共享日历检查所有受影响人员 BASE。 | 审批跨月矩阵逐项失败/成功；`PeriodBaseDatabaseTest` 对所有受影响人员检查；导入已封账来源保留失败差异而不改基数；API 截止已到且没有月份行仍拒绝。 | OA 迟到的实际增量与补录责任流程需联调；授权不是业务审批，正式旧版不随实时来源改写。 |
| AT16 来源失败与人工兜底 | `integrations/ImportService`、`SourceCoverageService`、`ProjectSourceLinkService`、报表来源比对；六类 XLSX、原始行、逐行应用、差异下载、明确覆盖状态。 | `ImportDatabaseTest`、`ExtendedImportDatabaseTest`、`ProjectSourceLinkDatabaseTest`：未知人员、失败批不能声明完整、旧失败版本不复活撤销、出差不会自动造现场；显式关联保留旧 ID 和全部权威字段；报表区分未比对 / 无单 / 不完整。 | 真实 OA 接口、分页和增量完整性尚待契约；人工声明覆盖由指定责任人核实，系统不能自行证明 OA 全量已到。 |
| AT17 幂等与并发 | `approval/*`、`integrations/*`、`closing/*`、`operations/*`；版本 CAS、幂等回执、稳定父行锁、逐行事务及持久任务；已送审现场不可直接改删。 | 审批与转交真实两线程竞争；来源重放及撤销；报表双线程封账只产生一版；真实 worker 重复任务不加审批项；日 API 旧版本冲突；更正/取消审批历史保留。 | 已覆盖指定竞争矩阵，未声称穷尽所有交错；浏览器连点与现场跨日期更正以最终双端报告补充，生产持续写压测仍需进行。 |
| AT18 历史与恢复 | `master/*` 生效历史、审批成本快照、月度 JSON 快照、审计；`scripts/verify-local-recovery.py`。 | [恢复演练](本地恢复演练.md) 48 表 / 2749 行一致，32 个正式版本快照哈希一致；报表验证主数据名称变更不改旧版；治理批量调整保留 ID / 工号 / 授权及历史。 | 本机新库恢复已执行，异机恢复、生产权限/加密、保留周期和 RPO / RTO 须在正式环境验收；恢复结果不等于真实业务审批正确。 |

## 已补齐的页面级边界

P07 支持多人正副职有效期、独立范围授权、人员历史只读、人员部门 / 职级批量影响预览和负责人批量任职；预览后资料改变须重看，避免覆盖并发调整。P08 部门负责人仅维护有效范围内 LOCAL 非项目，不因 LEADER 全公司分析权限扩大写权限；提供精简部门和审批人选项，不能借入口读取全公司身份资料。人员停用显示未交接待办数量。

这些治理场景已通过 5 项真实数据库矩阵；最新前端 8 项交互回归已由前端任务完成，最终重启后的真实浏览器批量、非项目范围与争议结果统一在 [MVP 本地开发验收](MVP本地开发验收.md) 更新。服务验证详见 [审批与治理专项](approval-governance-verification.md)。

## 本地完成与真实上线之间的边界

本地核心不再以审批、月报或导入的占位界面代替业务动作；上述每项均能定位实现和现有证据。此结论不是“AT01–AT18 已由业务全部签收”。剩余工作主要为以下真实条件，未伪装为本地能力已经接通：

- 公司现有 SSO 的服务端校验、企微通讯录 / 应用消息、苍穹项目 / 请假 / 出差真实契约及失败重试联调。
- 真实组织、纳入名单、负责人回避链、标准及日历初始化；旧工号解析和来源覆盖责任确认。
- 1–2 个部门至少四周试点，覆盖一次真实封账和历史修订，人工抽核上述金额与事实，检验连续两周及时率 / 审批率及常规填报耗时。
- 生产 HTTPS / 手机网络、异机恢复和容量验证。现有 [100 并发读取烟测](local-load-verification.md) 只有两个预登录身份、样例库和短时 GET；不等于 100 名独立用户并发写入，也不等于千人全年数据容量验收。

已批准的业务规则不因这些外部条件缺失而删除；本地固定模板和明确错误状态是当前可用的核实路径。真实条件准备好后，沿同一接口与业务规则联调，不另造平行业务口径。
