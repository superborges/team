# MVP 审批与协调接口

基准 `/api/v1`，沿用共享 Session / CSRF。所有 ID、小时、金额是字符串，日期是上海自然日，操作时间为 ISO UTC。成功直接返回对象，错误 `{code,message}`。副作用请求发送 `Idempotency-Key`（1–64 位字母、数字、`_`、`-`）；同键不同内容拒绝。

## 周预览与提交

- `POST /weeks/{monday}/preview`（空正文）→ `WeekPreview`。
- `POST /weeks/{monday}/submit`（空正文）→ `BatchResult`。只提交当前用户符合校验的未提交版本；按日工时保持原子性，现场独立。允许其他日期成功，逐项返回失败。
- `POST /onsite-days/{id}/submit`（空正文）→ `BatchResult`，独立现场送审。

`WeekPreview = {weekStart,days:[{date,workReady,onsiteReady,errors:string[],warnings:string[],workItems:[{revisionId,recordId,workItemId,workItemName,kind,hours,approverId,approverName,routeReason}],onsite:null|{revisionId,recordId,workItemId,workItemName,approverId,approverName,routeReason}}]}`。无可送审工作/现场时对应 Ready=false。路由未找到时 approverId/name 可空，errors 说明配置异常。已送审、已通过条目不重复列入；日校验仍计算全部当前有效条目。取消更正以原对象送审并标 `kind:"CANCEL"`、`hours:"0"`。

`BatchResult = {succeeded:[ResultItem],failed:[ResultItem],unchanged:[ResultItem]}`；`ResultItem = {id:string|null,kind:"TIME"|"ONSITE"|"DAY",date,packageId:string|null,code:string|null,message}`。失败不是 HTTP 请求整体成功的证明，前端展示逐日/逐项原因。重复已提交项不重新生成审批项。

## 审批包

所有登录用户可访问以下列表，按实际指派和本人记录返回其可见包；没有待办返回空数组。两端可以统一显示“审批与协调”入口，不按 PM 角色隐藏临时指派待办。

- `GET /approval-packages?view=pending|handled|mine|all&weekStart=可选周一` → `PackageSummary[]`，默认 pending；all 为本人作为员工、当前接收人或历史处理人能访问的并集；管理员的配置转交查看另带管理权限。
- `GET /approval-packages/{id}` → `PackageDetail`。
- `POST /approval-packages/{id}/decide`，`{expectedVersion:number,itemIds:string[],decision:"APPROVE"|"REJECT",reason:string}` → `BatchResult`。理由在驳回时必填。已处理/有争议/缺标准等逐项明确返回，选中可处理项在同一事务确认。
- `POST /approval-packages/{id}/transfer`，`{expectedVersion,newApproverId,reason,basis}` → `PackageDetail`；仅管理员按业务指定执行，所有未处理项一起转交，不改变历史处理人和旧截止。

`PackageSummary = {id,userId,employeeNo,userName,weekStart,workItemId,workItemName,workItemType,approverId,approverName,routeReason,approvalDeadline,version,pendingCount,workMinutes,idleMinutes,onsiteDays,canDecide,canTransfer}`。

`PackageDetail = {package:PackageSummary,items:ApprovalItem[],otherWorkMinutes:number,correctionRequests:CorrectionRequest[],disputes:Dispute[]}`。otherWorkMinutes 仅给同周其他对象工作总量，不返回其名称、内容或费用。

`ApprovalItem = {id,kind:"TIME"|"ONSITE",recordId,revisionId,date,action:"REPORT"|"CANCEL",entryKind:"WORK"|"TRAVEL"|"IDLE"|null,minutes,hours,content,redReason,dayWorkMinutes,projectWorkMinutes,state:"PENDING"|"APPROVED"|"REJECTED",current:boolean,handledBy:string|null,handledName:string|null,handledAt:string|null,reason,disputeOpen,canDecide,canApprove,canReject,canRequestCorrection,canDispute,cost?:{amount,dailyRate,level,policyVersion,rateId,levelHistoryId},costError?:string}`。无金额范围权限时整个 cost/costError 字段省略；状态变化刷新包与本人周记录。现场条目 minutes=0，仍有单独审批项；取消通过金额为0。

## 退回、更正与取消

待审批记录由当前审批人用 REJECT 退回，员工不可自行撤回。已批准记录由员工申请，原实际审批人核实后才生成新版本。请求不会立即改变原确认金额；获准后指向新的 DRAFT，原快照保留，重新批准之前不进入当前确认成本。

- `POST /correction-requests`，`{kind:"TIME"|"ONSITE",recordId,action:"EDIT"|"CANCEL",reason}` → `CorrectionRequest`；限本人当前已通过/锁定记录。
- `GET /correction-requests?view=mine|pending|all` → `CorrectionRequest[]`。
- `POST /correction-requests/{id}/decide`，`{decision:"APPROVE"|"DECLINE",reason}` → `CorrectionRequest`；仅请求当前接收人且非本人；批准必须通过月份范围门禁。
- `POST /correction-requests/{id}/transfer`，`{newApproverId,reason,basis}` → `CorrectionRequest`；管理员登记原审批人离职等代办依据，仅转未处理请求。

`CorrectionRequest = {id,kind,recordId,revisionId,userId,userName,workItemName,date,action,reason,state:"REQUESTED"|"APPROVED"|"DECLINED",approverId,approverName,handledAt,newRevisionId:string|null,canDecide,canTransfer}`。EDIT 获准后员工通过既有日编辑修改 DRAFT 并重新提交；CANCEL 获准后生成携带原对象的取消 DRAFT，并通过周提交重新审批。错日期按取消原记录并在正确日期新增，分别经过月份门禁，不能原地移动主体。正确日期新增记录时在 EntryInput/OnsiteInput 带获准取消请求的 `correctionRequestId`；服务端限制本人、同种记录、不同日期，且一项请求只关联一个新日期主体。

## 公司指定路由

- `GET /approval-designations` → `[{id,userId,userName,workItemId,workItemName,approverId,approverName,reason,basis}]`（管理员）。
- `POST /approval-designations`，`{userId,workItemId,approverId,reason,basis}` → 同一对象。用于逐级回避后无法确定接收人的“人员 × 对象”具体范围，不授予全公司审批权；只能登记有效非本人负责人。

## 争议协调

- `GET /disputes` → 本人参与、实际审批或当前协调人可见的 `Dispute[]`。
- `POST /disputes`，`{approvalItemId,statement}` → `Dispute`；员工或该项实际审批人申请，仅关联该项。
- `POST /disputes/{id}/statements`，`{statement}` → `Dispute`；员工/审批人各自补充其说明，不覆盖最初陈述。
- `POST /disputes/{id}/resolve`，`{action:"RESOLVE"|"ESCALATE",outcome:"CONFIRM_FACTS"|"NEEDS_CORRECTION"|null,reason}` → `Dispute`。协调人来自项目主责部门；升级到该主责部门分管领导。裁定不直接审批/改金额；需要更正回到业务退回或申请更正。

`Dispute = {id,approvalItemId,packageId,userId,userName,workItemName,date,kind,recordId,revisionId,action,entryKind,hours,content,redReason,recordState,openedAt,resolvedAt,state:"OPEN"|"ESCALATED"|"RESOLVED",stage:"COORDINATION"|"RULING",coordinatorId,coordinatorName,employeeStatement,approverStatement,outcome:string|null,resolutionReason:string|null,canComment,canResolve,canEscalate}`。不得由员工本人或涉事实际审批人裁定自身争议；缺有效协调人明确返回异常。未结时仅该版本 `disputeOpen=true`，阻止确认并从当前成本排除，不阻断无争议项。裁定 NEEDS_CORRECTION 后，已确认金额继续排除，直到更正版本通过；待审批项仅允许实际审批人 REJECT 退回，不能直接 APPROVE。历史协调人在升级后仍可只读查看自己处理过的争议。

## 后端数据约定

版本成本字段固定为 `cost_amount,cost_daily_rate,cost_rate_id,cost_policy_version,approved_by,approved_at,submitted_at,owner_department_id,dispute_open,correction_request_id`。工时另有 `cost_level_history_id,cost_level_code`。当前确认查询必须使用主体当前版本、`state IN ('APPROVED','LOCKED') AND action='REPORT' AND dispute_open=FALSE`；历史版本和正式快照不能因此删除或改金额。

`approval_package` 唯一 `(user_id,week_start,work_item_id)`；`approval_item` 每个内容版本唯一，分别关联 `time_revision_id` 或 `onsite_revision_id`（恰好一个），保存独立 `submitted_at,submit_deadline,approval_deadline,status,handled_by,handled_at,reason`。包 `row_version` 用于处理/转交并发。所有涉及业务月份的写入由月份门禁控制，页面按钮不替代服务端校验。

审批包 `pendingWorkMinutes/pendingIdleMinutes/pendingOnsiteDays` 仅计尚待处理的 REPORT 项；既有 `workMinutes/idleMinutes/onsiteDays` 保留当前全部轮次总计。`redFlag` 为相关日期实际工作超过发生日参数阈值；`disputedCount` 为当前待协调或需更正条目数；`transferred` 表示存在转交历史。争议视图直接给出该项原始工时/现场内容与时间，协调人无需获得整个审批包权限。
