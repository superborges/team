# A 阶段本地 API 验收记录

- 执行时间：2026-09-05T20:10:37.214423+08:00
- 运行编号：`20260905-201037-469a72`
- 目标：`http://127.0.0.1:8080`，仅 local profile + 固定测试身份 + 本地 MySQL/Redis。
- 结果：**PASS**；13 项通过，0 项失败。
- 执行方式：项目根目录运行 `python3 scripts/verify-local-api.py`；先启动本地 API 和基础设施。
- 数据策略：只选择未来 90 天内尚无记录的日期；写入“本地API验证”标记并保留版本/审计；不删除数据库卷。工号变更在 finally 恢复。
- 本次测试日期：2026-09-10。

| 检查 | 结果 | 实际证据 |
|---|---|---|
| 01 本地环境隔离 | PASS | Local mode + seed identities + health UP confirmed; no production endpoint accepted |
| 02 未登录访问拒绝 | PASS | GET /me → 401 UNAUTHENTICATED |
| 03 CSRF 校验 | PASS | POST local-login without CSRF → 403 |
| 04 本地会话与前导零工号 | PASS | Two independent employee sessions; administrator 00123 retains leading zero and stable ID 4 |
| 05 员工主数据权限与字段隔离 | PASS | Employee master users/rates both 403; identity/catalog contain no money fields |
| 06 真实保存与读回 | PASS | 2026-09-10: 4h draft + independent onsite persisted, version 1 |
| 07 24 小时限制与事务回滚 | PASS | 25h → 422 DAY_LIMIT; day version, revision, onsite, audit and receipt counts unchanged |
| 08 同日双会话并发 | PASS | Two distinct sessions with one expectedVersion → exactly one 200 and one 409 VERSION_CONFLICT |
| 09 幂等重放与内容冲突 | PASS | Same key/body replays original response with no new revision; changed body → 409 IDEMPOTENCY_CONFLICT |
| 10 取消版本与历史保留 | PASS | Entry 277 and onsite 118 retain original history; cancellation adds a version |
| 11 封账时点门禁 | PASS | 2026-07-01: ordinary old-month write rejected without changing records → 409 PERIOD_CLOSED |
| 12 半天假与自然周 | PASS | 2026-09-04: 480−240=240 required minutes; repeat read stable; natural week has seven days; non-Monday rejected |
| 13 工号变更保持内部关联 | PASS | 98123 → VT00000 → 98123, same internal ID 1 / existing session / historical day; old number rejected while changed |

完整原始结果保存在 `.local/api-verification-20260905-201037-469a72.json`，不包含会话 Cookie、CSRF 值或生产密钥。

本记录只验证已实现的本地 A 阶段 API。真实企微 SSO / OA、送审审批、正式成本报表、月报发布与完整 MVP 验收尚未由本脚本验证。基础设施与镜像检查见本地运行说明；这些结果不代表生产上线通过。
