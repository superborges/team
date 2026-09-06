# 当前分析、正式月报、导出与修订接口

基准 `/api/v1`，ID / 金额 / 百分比为字符串，日期 ISO，操作时间为 UTC（当前无偏移的日期时间字符串也按 UTC 解释，前端转换到上海时间；纯日期不转换时区）。响应按当前授权过滤，前端不能用 CSS 隐藏已经返回的金额。ADMIN 可读全公司报表；其管理权限不产生业务审批权。

## 分析入口

- `GET /reports/capabilities` → `{canReadCosts,canClose,canUnlock,canPublish,projects:[{id,code,name}],users:[{id,employeeNo,name}],departments:[{id,name}]}`。
- `GET /reports/{kind}?from=YYYY-MM-DD&to=YYYY-MM-DD&mode=CURRENT|FORMAL&versionIds=1,2&userId=&workItemId=&departmentId=&level=&grain=WEEK|DAY`。
- `kind` 固定为 `workload`、`compliance`、`project-costs`、`onsite`、`details`。日期范围含首尾，最多一年。
- `CURRENT` 读取当前内容版本；`FORMAL` 按月份读取发布快照。未传 versionIds 时取每月最新版本；明确传入时采用所选版本，同月不可重复。缺失月份返回 missingMonths，不拼入当前数据。

统一 Report：`{kind,from,to,mode,generatedAt,versions:[{id,month,versionNo,cutoffAt,publishedAt}],missingMonths:string[],summary:Record<string,unknown>,rows:Record<string,unknown>[],details:Record<string,unknown>[]}`。

| kind | rows 主要字段 |
|---|---|
| workload | `userId,employeeNo,name,departmentName,levelCode,periodStart,periodEnd,requiredMinutes,confirmedWorkMinutes,confirmedProjectMinutes,confirmedNonProjectMinutes,confirmedIdleMinutes,leaveMinutes,overtimeMinutes,reportedMinutes,missingDays,pendingCount,loadPercent,complete,status`。status 为 CONFIRMED / INCOMPLETE / NO_BASE；不完整时不用低负荷色阶 |
| compliance | `userId,employeeNo,name,expectedPersonDays,readyOnTimeDays,missingDays,unresolvedCount`。summary 包含 `expectedPersonDays,readyOnTimeDays,timelyRatePercent,lateSubmitPeople,notSubmittedPeople,approvalSubmitted,approvalHandled,approvalApproved,approvalRejected,approvalCompletionPercent,overduePackages`；details 为超时/未决项 |
| project-costs | `workItemId,code,name,type,departmentName,pmName,confirmedMinutes,onsiteDays,laborCost,onsiteCost,totalCost,participants,pendingCount`。只返回获准项目；明细用 details 查询加 workItemId |
| onsite | `date,userId,employeeNo,name,departmentName,workItemId,workItemName,reason,state,onsiteDays,onsiteCost?,projectMinutes,totalWorkMinutes,ratioPercent,ratioStatus,reviewRequired,oaStatus,oaComparedAt,oaNote`。ratioStatus 为 CONFIRMED / INCOMPLETE / NOT_APPLICABLE；无成本权则不含 onsiteCost。summary 用合格天数分子分母之和计算占比 |
| details | `kind:TIME|ONSITE,date,userId,employeeNo,name,departmentName,workItemId,workItemName,recordId,revisionId,action,state,minutes?,content?,reason?,approvedAt,approvedByName,costAmount?,dailyRate?,levelCode?,policyVersion?`。成本字段只对具该项目成本权的人返回 |

当前权限决定可见人员 / 项目，快照内姓名、工号、部门、项目标签、计价和流程事实保持当版原值。PM 可看本项目跨部门参与明细，不获得参与人其他项目的时间明细或全日人员负荷。P05 为核实本人负责现场日提供当日实际工时分母，不展示无权项目的名称 / 内容。

## Excel 导出

- `POST /exports`，`{kind,from,to,mode,versionIds?:string[],userId?,workItemId?,departmentId?,level?,grain?}` → `{id,state}`。
- `GET /exports` → 本人最近导出请求列表；`GET /exports/{id}` → `{id,state,kind,createdAt,finishedAt,error,downloadUrl}`。
- `GET /exports/{id}/download` → XLSX。state 为 QUEUED / RUNNING / SUCCEEDED / FAILED。

导出通过 MySQL job 异步执行。登记、生成、下载均重新核对范围；生成采用申请范围与当前权限交集。文件包含的任一数据或金额权限撤销后，拒绝下载整个旧文件，提示重新申请。正式版本只读冻结行。文件位于私有目录，不公开静态 URL。每个文件7天有效；下载时过期返回410，worker按批清理到期文件和遗留临时文件，任务与审计记录保留。申请正式导出时固定当时选中的版本；申请时尚无正式版本的月份，之后封账也不会悄悄补入该文件。

## 月度工作台

- `GET /periods/{month}`，month 为 `YYYY-MM` → `{month,status,scheduledCloseAt,actualClosedAt,latestVersionId,versions,activeAmendment,requests,scopes,canClose,canUnlock,canPublish}`。
- `POST /periods/{month}/close` → Version；仅管理员且达到预定封账时点。固定月份操作键保证重复调用仍为 V1。
- `POST /periods/{month}/requests`，`{reason,scopes:ScopeInput[]}` → Request。员工可为本人提出范围明确的申请；不会自动解锁。
- `POST /periods/{month}/amendments`，`{reason,requestId?:string}` → Amendment。仅有权授权者；同月已有 OPEN 批次时返回既有批次而不新建第二个。
- `POST /periods/{month}/amendments/{id}/scopes`，ScopeInput → Scope。授权者必须具对象主责范围；跨对象更正需原 / 目标对象分别授权。
- `POST /periods/{month}/amendments/{id}/publish`，`{reason}` → Version。仅管理员，重新冻结整月，关闭本批次授权，保留旧版本。固定批次操作键保证幂等。
- `GET /periods/{month}/amendments/{id}/diff` → 与下方相同差异结构，`toVersionId=null` 表示本次修订尚未发布；`fromVersionId` 是上一正式版。预览不改变版本。
- `GET /periods/{month}/versions/{id}/diff` → `{fromVersionId,toVersionId,rows:[{kind,recordId,userId,workItemId,change,before,after}]}`。按稳定记录 / 人日比较新增、取消、内容 / 金额、缺填和未决变化；仍过滤当前权限。

ScopeInput：`{userId,date,workItemId:null|string,objectType:TIME|ONSITE|BASE,recordId:null|string,actions:string[],reason}`。

- actions：`ADD,SAVE,SUBMIT,APPROVE,REJECT,CORRECT,DISPUTE,TRANSFER,BASE`。授权不能代替实际审批或本人回避。
- TIME / ONSITE 指定对象；recordId 非空为既有主体，空值为授权后新建范围。服务端记录 newAfterRecordId，避免把“允许补录”扩成原有记录任意更改。
- BASE 只允许管理员授权人日基数变化，workItemId / recordId 均为空，actions 为 BASE；因为同日可能影响多个项目。
- Scope 返回附加 `id,amendmentId,authorizedBy,authorizedAt,newAfterRecordId,active`。
- Version：`{id,month,versionNo,cutoffAt,publishedAt,reason,previousVersionId}`；Amendment：`{id,month,state,reason,createdBy,createdAt}`；Request：`{id,month,state,reason,requestedBy,createdAt,scopes}`。

V1 / V2 使用同一事务内的一条规范化快照取数语句保存工时、现场日、应有人日、缺填、审批、来源及覆盖事实。普通写入依靠月份门禁与明确 scope，未授权数据始终封闭；修订发布前正式视图仍显示上一已发布版。


## 取数与统计说明

- 成本、人数、现场天数分别从当前指针的有效确认内容聚合，撤销、待审更正及争议项不沿用旧金额；人天费用以审批时快照为准。
- 默认容量使用发生日生效参数，人工日历优先；自动创建的旧日历或 day_record 缓存不覆盖新参数。请假重叠区间取并集，未知多来源或未解决来源失败使负荷保持不完整状态。
- 合规完成时间来自首次完整填写事实。未提交人数按到期的具体缺填/草稿/驳回人日去重，不能因其在本期间提交过另一日就排除。审批完成率仅对截点时已到审批截止的逐轮送审项计算。
- 正式快照的一条 SQL 同时保存事实、人员/项目标签、来源覆盖与实际 UTC 截点。后续历史分析只读快照内容；当前角色和项目权限单独重查。
