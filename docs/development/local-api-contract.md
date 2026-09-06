# 本地开发接口约定（首批 A 阶段）

> 后续版本补充了送审、更正、日步长/阈值及争议字段。完整接口见同目录的 approval-api.md、reporting-api.md、operations-api.md、governance-api.md；当前实现与真实回归见 MVP本地开发验收.md。

基准 `/api/v1`。这是首批实际实现契约；SSO、审批、正式报表等后续任务不能用虚假成功替代。所有 bigint ID、金额与输入小时使用字符串。日期 `YYYY-MM-DD`，API 用 camelCase。成功直接返回对象或数组；错误 `{code,message,details?}`。写请求发 `X-CSRF-TOKEN`，Session 为 HttpOnly Cookie，不用 localStorage token。

## 认证

- `GET /auth/status` → `{mode:"local"|"sso",localLoginEnabled:boolean,ssoConfigured:false}`。仅显式 local profile + 独立开关可开发登录，生产不实现伪造的 SSO 校验。
- `GET /auth/csrf` → `{headerName,token}`，匿名亦可取；登录后重新获取。
- `POST /auth/local-login`，`{employeeNo:string}` → Me。仅 local profile；固定测试人员来自数据库，服务端校验状态。
- `POST /auth/logout` → 204。
- `GET /me` → `{id,employeeNo,name,departmentId,departmentName,roles:string[],canManage:boolean}`。
- 测试工号：`98123` 员工，`XX12345` 项目经理，`ZD23412` 部门负责人，`00123` 管理员。额外身份以数据库为准。角色 ADMIN、EMPLOYEE、PM、DEPARTMENT_MANAGER、LEADER。

## 归集与每日草稿

- `GET /catalog` → `{workItems:[{id,code,name,type:"PROJECT"|"NON_PROJECT"|"IDLE",ownerDepartmentId,approverName}],departments:[{id,code,name}]}`。
- `GET /days/{date}` → Day，不要求先创建日记录。
- `PUT /days/{date}`，请求如下。`expectedVersion` 必填（全新日期 0），整个请求是当前可编辑草稿列表。不得省略已有不可编辑记录来删除；首批只有草稿，可编辑记录删除生成取消版本。`onsite:null` 表示取消可编辑现场草稿；无确认记录的物理删除入口。

```json
{
  "expectedVersion": 0,
  "entries": [{"id":null,"workItemId":"1","kind":"WORK","hours":"4","content":"完成项目现场设备调试与问题复核","redReason":""}],
  "onsite": {"id":null,"workItemId":"1","reason":"项目现场实施"}
}
```

Day: `{date,version,baseMinutes,leaveMinutes,requiredMinutes,isWorkday,enrolled,editable,periodStatus,entries,onsite,totalMinutes,actualMinutes,idleMinutes,validation:{ready:boolean,errors:string[],warnings:string[]}}`。

- `entries`: `{id,revisionId,workItemId,workItemName,kind:"WORK"|"TRAVEL"|"IDLE",hours:string,minutes,content,redReason,state:"DRAFT"|"PENDING"|"APPROVED"|"REJECTED"|"CANCELED",editable:boolean}`。
- `onsite`: null 或 `{id,revisionId,workItemId,workItemName,reason,state,editable}`。
- 暂存允许内容未完整和基数不足，仍拒绝无效身份 / 对象、非法小时 / 日期、24h 超限及越过封账。完整性和内容问题由 validation 返回。超过 16h 的实际工作需说明；待分配默认“暂无任务安排”，仍需员工主动选填。
- `GET /weeks/{monday}` → `{weekStart,days:Day[]}`。必须传周一。日期按公司 Asia/Shanghai。
- 首批没有提交/审批 API；UI显示“保存草稿”，不要做假送审。就绪文案为“填写完整”，不能暗示已经送审。

## 主数据（需管理员写权限）

所有列表 `GET` 返回数组；人员、组织、费率、日历维护仅 Admin 展示，接口实际权限由后端控制。新增 `POST` 返回对象，更新 `PUT /{id}` 返回对象。字段未列出不接受任意数据库字段写入。

- `/master/users`: `{id,employeeNo,wecomUserid,name,departmentId,departmentName,level,status,roles:string[],effectiveFrom}`。POST 创建人员，PUT 更新同一内部人及必要历史；入参包含 `employeeNo,wecomUserid,name,departmentId,level,status,effectiveFrom,roles`。
- `/master/departments`: `{id,code,name,parentId,approverUserId,supervisorUserId,status}`；POST / PUT 同字段（无需id）。角色范围沿部门，负责人需有效用户且不能循环部门层级。
- `/master/work-items`: `{id,code,name,type,ownerDepartmentId,approverUserId,approverName,source,status,effectiveFrom}`；POST / PUT 同字段。`type` PROJECT / NON_PROJECT / IDLE；`source` LOCAL / OA，OA 权威字段不可普通修改。
- `/master/rates`: `{id,dimension:"JUNIOR"|"MIDDLE"|"SENIOR"|"ONSITE",dailyRate:string,effectiveFrom,effectiveTo:null|string}`；只提供 POST 新增有效版本，同起日 / 重叠返回422。过去已封账历史改变返回409。无任意金额覆盖 / 删除。
- `/master/calendar?from=...&to=...`: `[{date,isWorkday,baseMinutes,note}]`；`PUT /master/calendar/{date}`，`{isWorkday,baseMinutes,note}`。保存必须经过月份门禁。缺日期按默认周一至周五480分钟、周末0，显式配置法定休假和调休。

## 已知本地范围

首批包含主数据、月份写入限制、日版本、草稿和项目 / 费率 / 已核实请假三类 XLSX 导入，导入接口见[独立契约](import-api.md)。人员 / 组织导入、真实 OA / SSO 及审批完整链路继续按实施计划推进。生产模式未接入 SSO 时明确拒绝业务访问；不存在按浏览器声称的 UserID 建立生产会话的捷径。
