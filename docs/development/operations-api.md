# 参数、任务、通知与扩展导入契约

基准 `/api/v1`，沿用 Session / CSRF。除本人通知外 operations 全部需 ADMIN。时间字段为 UTC 操作时间；配置 HH:mm 和日期按上海时区。ID 为字符串。

## P11

- GET `/operations/config` → `{current:Snapshot,latestVersion:string,history:Snapshot[]}`。
- POST `/operations/config/preview`，Update → `{effectiveFrom,existingFutureDays,notes:string[]}`。
- PUT `/operations/config`，Update → 新 Snapshot。创建新生效版本，不覆盖旧版；起日从明天起且晚于已有所有配置版本。
- GET `/operations/jobs` → `[{id,type,businessKey,status,plannedAt,nextRunAt,attempts,error,completedAt}]`；状态 PENDING/RUNNING/RETRY/FAILED/COMPLETED。
- POST `/operations/jobs/{id}/retry` → 空响应，失败任务重新入队。
- GET `/operations/health` → `{worker:[{workerKey,heartbeatAt,lastScanDate}],pendingJobs,failedJobs,pendingNotifications}`。
- GET `/operations/notifications` → `[{id,recipient,eventType,body,status,error,createdAt}]`；LOCAL 明确表示本地收件箱，不能称企微发送成功。
- POST `/operations/notifications/{id}/retry` → 空响应，仅失败可重试。
- GET `/operations/my-notifications` → `[{id,eventType,body,path,status,createdAt}]`，任何用户仅本人。
- GET `/operations/audit?objectType=&objectId=` → 最近100条 `[{id,actorType,actorName,jobId,action,objectType,objectId,beforeData,afterData,reason,createdAt}]`；空筛选可省略。actorType USER/SYSTEM。

Snapshot：`{id,effectiveFrom,params,rules}`。

Update：`{expectedVersion:latestVersion,effectiveFrom,params,rules,reason}`。

params：`{dayLimitMinutes:1440,redFlagMinutes:960,defaultDayMinutes:480,stepMinutes:30,submitWeekday:1,submitTime:"12:00",approveWeekday:3,approveTime:"18:00",closeDay:10,dailyCompletionTime:"12:00"}`。步长可15/30/60分钟；每日上限480–1440，红标480至上限，默认基数0–480；周几1–7，封账日1–28。明确维护的日历优先；已有月门禁时点、送审截止和历史月报不变。

Rule：`{code,label,enabled,time,dayType,dayOfWeek,dayOfMonth,threshold,recipients,template}`。从 GET 读取内置规则，无新增删除。dayType WORKDAY/DAILY/WEEKLY/MONTHLY/EVENT；recipients SELF/SELF_MANAGER/APPROVER/APPROVER_SUPERVISOR/ADMIN。template仅支持 `{date}`、`{name}`、`{week}`、`{count}`。AUTO_SUBMIT/MONTH_CLOSE是实际动作，时间由 params 的截止控制，开关在规则中。

## P10 扩展（与原有三类相同上传/确认流程）

新增 dataset：

| 值 | 固定列头 |
|---|---|
| USER | employeeNo,wecomUserid,name,departmentCode,level,effectiveFrom |
| DEPARTMENT | code,name,parentCode,approverEmployeeNo,supervisorEmployeeNo |
| TRIP | sourceKey,sourceVersion,employeeNo,date,projectCode,status |

USER仅新增，默认EMPLOYEE权限，不通过表格提升管理员；已有工号报冲突，换号仍在人员维护按内部ID进行。DEPARTMENT仅新增，上级/负责人可空；同文件顺序上级在先。TRIP为已审批出差按自然日展开；projectCode可空，status APPROVED/REVOKED。所有标识文本不静默去空格，未知身份/项目保留失败行，不生成现场日。

- GET `/imports/{id}/errors` → 失败行 XLSX，原列加行号/原因；文本不写成公式。
- GET `/source-coverage` → 最近100条 `[{id,dataset,fromDate,toDate,departmentId,departmentName,state,note,verifiedBy,verifiedAt,importBatchId}]`。
- POST `/source-coverage`，`{dataset:"LEAVE"|"TRIP",fromDate,toDate,departmentId:string|null,state:"COMPLETE"|"PARTIAL"|"FAILED",note,importBatchId:string|null}` → 同一记录。toDate包含末日。部门空表示全公司；需明确核实说明，关联有错误批次不可标完整。

来源覆盖代表管理员对指定期间/范围的核实，不因一次文件上传自动置完整；报告只有明确完整覆盖才能判断“无单”。

## 规则执行与授权补充

- 普通提醒可选工作日/每日/每周/每月；驳回固定实时事件，自动送审固定按周，封账固定按月。
- 审批类接收范围为实际审批人、审批人及上级、管理员；员工类为本人、本人及负责人、管理员；封账为管理员。阈值范围 1–90。页面只展示该规则实际支持的组合。
- 新审批通知按规则时点排队，投递前重新检查当前接收人、未决状态和开关。模板与提醒数量、自动提交失败详情分别保留。系统执行由 `SYSTEM + jobId + targetUserId` 审计，无虚构系统工号。
- 任务失败指数退避，达到五次后待人工重试；只认当前租约 token，过期 RUNNING 可接管。生成导出前后及下载时检查有效权限，worker 按小时清理过期私有文件。
- 旧月请假/出差事实需要对应人日 BASE 授权。共同日历或来源覆盖变更需要所有受影响人员的 BASE 授权，一人的解锁不能扩展到全部门或全公司。
