# 审批、组织治理与来源关联本地验证

2026-09-05，Java 21 / Spring Boot 4.1.1 / 真实 MySQL 8.4。最后一次审批及治理专项运行在 20:03:46 完成：`ApprovalDatabaseTest` 8 项、`MasterGovernanceDatabaseTest` 5 项，共 **13 项通过，0 失败、0 错误、0 跳过**。这些是包含多步骤断言的服务集成矩阵，数量不等于业务验收项数量。

```sh
WORKLOG_INTEGRATION=true python3 scripts/check-backend.py \
  -Dtest=ApprovalDatabaseTest,MasterGovernanceDatabaseTest test
```

另有 `ApprovalDeadlineTest` 1 项单元测试、`ProjectSourceLinkDatabaseTest` 2 项真实数据库测试已通过；来源关联专项完成时间为 19:53:47。Maven 原始报告在 `backend/target/surefire-reports/`，后续统一构建会覆盖该目录。各测试使用标明用途的独立人员和对象，不将测试数据作为生产初始化；治理、来源关联及隔离历史月份场景使用事务回滚。

## 审批矩阵

| 测试方法 | 真实验证的行为 |
|---|---|
| `objectPackagesPartialApprovalTransferAndCostRedactionAreIndependent` | 工时、非项目、待分配、现场按对象分为三个包；重复送审不增加项。项目 4h 确认人工成本 600.00，非项目和待分配成本为 0，现场单独确认 200.00。员工不收到金额，其他人员不能读取无关包。只转交未处理现场项，旧处理人、处理时刻和截止保留；被临时指定的普通员工可审批该项，但不会获得项目金额权限。 |
| `rejectedRevisionResubmitsAloneAndApprovedCorrectionKeepsOldSnapshot` | 6h 已通过、2h 驳回后，按全日 8h 校验，仅新 2h 版本送审。原 6h 的 900.00 快照保留；员工更正由原实际处理人核实，管理员不能直接代批；获准后生成无确认金额的新草稿。取消也生成新版本，需送审通过为 0；取消项可独立提交，不因剩余工时不足而无法取消。 |
| `routingSelfAvoidanceUnresolvedDayAtomicityAndZeroWorkOnsite` | PM 本人项目转当前所属部门指定负责人，负责人本人非项目转分管领导；领导本人无回避接收人时保留异常，管理员显式指定后才能继续。当天工时中任一对象路由失败，该日工时全部不送审；有效现场独立成功。零工时现场可单独送审并计费。 |
| `disputeOnlyBlocksItsRowAndIndependentRulingDoesNotApproveIt` | 同包争议仅限制一条，其余可通过。双方追加陈述，按项目当前主责部门协调并升级，历史协调人保留查看权；管理员无自动裁决权。确认事实不等于业务审批；要求更正后，原确认版本持续排除，员工申请、原处理人核实后才生成新草稿。 |
| `missingRateFailsOnlyLaborWhileOnsiteStillApproves` | 缺发生日人工标准的项目工时仍为待审且无金额，同包现场标准完整的项仍可通过，按 200.00 计费。 |
| `concurrentTransferAndDecisionHaveOneWinnerAndNoMixedHandler` | 两个真实线程用同一包版本同时审批与转交，只有一方成功；包版本、接收人和实际处理人不会混合。 |
| `crossMonthPreviewAndApprovalHonorExactRecordActionsWithoutOpeningWholeMonth` | 同包跨月，其中旧月已封账。预览正确提示权限；精确授予旧记录 SUBMIT 后可送审，仍不能 APPROVE。首次批量审批只通过开放月份的项，授予旧项 APPROVE 后才可确认，旧月份仍 CLOSED。使用回滚的隔离历史月份和非项目零成本，主要验证逐条门禁，不代替正金额月报修订全链路。 |
| `approvedBusinessExamplesMatchWorkloadTravelAndOnsiteRatioWithoutDiscountingCost` | 实际经过日保存、送审、审批与报表：4h 项目 + 1h 非项目 + 3h 待分配的负荷为 62.50%；6h 交通内的 2h B 支援加 2h A 现场归集为 A 6h / B 2h，总计 8h；A 现场投入 2h / 当日实际 8h 为 25%，标复核但现场费仍 200.00；零工时必要驻留的比例为不适用、现场费仍 200.00。 |

`ApprovalDeadlineTest` 补充验证跨年自然周：2026-12-28 的下一周送审、审批截止分别按上海时间 2027-01-04 12:00、01-06 18:00 转为数据库 UTC。`CostingDatabaseTest` 使用发生日 2026-08-31 / 09-01 的职级历史验证 4h 金额 600.00 / 800.00；逐条四舍五入由 `CostingServiceTest` 验证。

## 组织治理和部门非项目权限

| 测试方法 | 真实验证的行为 |
|---|---|
| `multipleManagersOverlapAndSameDayRevocationPreserveSeparateAuthority` | 一个部门可有多个正副职，重复区间被拒绝；任职撤销不误撤独立部门范围授权，全部对应授权撤销后即时失去权限，历史仍保留。 |
| `scopeValidationFutureEffectAndImmediateAdminRevocation` | 角色与 COMPANY / DEPARTMENT 范围组合受校验；未来授权不提前生效；当天管理员授权可当天撤销，既有身份随即不再具有管理权限。 |
| `batchAdjustmentsRequireFreshImpactPreviewAndPreserveIdentityAndScopes` | 人员批量调部门 / 职级先预览影响；预览后资料变化返回 409，刷新后同事务应用。稳定 ID、工号、身份资料和既有范围授权保留，归属历史追加。 |
| `managerBatchPreviewsOverlapAndCreatesIndependentAppointments` | 批量任职先检查有效期冲突，再一次事务写入多人的独立任职记录；相同任职再次预览提示冲突，不覆盖历史。 |
| `scopedManagersMaintainOnlyLocalNonProjectsAndNeverGainAuthorityFromLeaderScope` | 有效部门负责人仅可维护授权部门及子部门的 LOCAL 非项目；不能创建项目、越部门或编辑项目 / 待分配对象。选项只含范围内部门和必要审批人名字，不暴露企微身份及角色资料；全公司人员 / 部门管理接口仍 403。独立 LEADER COMPANY 分析权限不扩大写范围，DM 撤权后立即失去入口权限。 |

人员视图另返回待处理审批及更正申请数量，停用人员不删除或隐式转交其待办；前端显示明确转交提示。该提示字段已编译验证，实际停用、即时失效和恢复稳定身份的 HTTP 回归见 [主数据验证](master-data-verification.md)。最新字段的浏览器验证由最终双端回归记录。

## 已核实项目来源的显式关联

`ProjectSourceLinkDatabaseTest` 使用真实导入服务验证：管理员把来源编码绑定已有稳定对象后，两次应用都返回成功，不新建重名对象，也不改变原对象任何字段或主责部门历史；只留一条关联审计。不能把同一编码重新绑定其他对象，不能劫持已有对象编码，类型不一致和非管理员操作均被拒绝。映射使用稳定来源编码父行锁，原始来源材料仍保留。

接口分别见 [审批](approval-api.md)、[组织授权](governance-api.md)、[批量调整](master-batch-api.md)、[来源关联](project-source-link-api.md)。

## 验证边界

服务测试直接设置测试身份并调用服务，因此验证真实数据库事务、权限及成本逻辑，但不替代浏览器 Cookie / CSRF / 网络路径。部分历史封账测试直接布置隔离状态，未伪称按真实日历等待自动封账。实际 HTTP、worker、浏览器、导出和恢复证据分别记录在 [MVP 本地验收](MVP本地开发验收.md) 及 [AT01–AT18 矩阵](mvp-acceptance-matrix.md)。

没有访问真实企微 SSO / 通讯录 / 应用消息或苍穹 OA，没有模拟企业身份验签成功。争议机制能保存和限制事实修改，但真实工时是否准确、驻留是否必要、业务审批是否认真核实，仍须试点代表判断。
