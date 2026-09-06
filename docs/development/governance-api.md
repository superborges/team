# 组织与权限治理 API（P07）

所有接口位于 `/api/v1`，仅管理员可访问。ID 为字符串，日期为 `YYYY-MM-DD`，有效期为左闭右开 `[effectiveFrom,effectiveTo)`；结束日可空。写接口沿用会话 CSRF。原因必填，写入完整审计。权限撤销立即生效，保留原始有效期及撤销时间。

## 部门多负责人

- `GET /master/departments/{departmentId}/managers`：全部历史任职。
- `POST /master/departments/{departmentId}/managers`：`{userId,title:"HEAD"|"DEPUTY",effectiveFrom,effectiveTo:null,reason}`。
- `POST /master/department-managers/{id}/revoke`：`{reason}`，包括撤销当天或未来开始的任职。

返回对象/列表元素：`{id,departmentId,departmentName,userId,userName,title,effectiveFrom,effectiveTo,revokedAt,active,canRevoke}`。同部门可有多位正副职；同一人在同一部门的未撤销任职区间不能重叠。任职赋予对应部门及子部门的管理范围，与人员当前所在部门可不同。`department.approverUserId` 是本人回避时的指定负责人，仍通过原部门编辑界面单独维护，不因新增任职自动改选。

## 明确范围的角色授权

- `GET /master/users/{userId}/grants`：全部历史授权。
- `POST /master/users/{userId}/grants`：`{role,scopeType,departmentId:null,effectiveFrom,effectiveTo:null,reason}`。
- `POST /master/role-grants/{id}/revoke`：`{reason}`，即时撤销，重复请求返回当前状态。

返回对象/列表元素：`{id,userId,role,scopeType,departmentId,departmentName,effectiveFrom,effectiveTo,revokedAt,active,canRevoke}`。允许组合如下：

| role | scopeType | departmentId |
|---|---|---|
| EMPLOYEE / ADMIN / PM | COMPANY | null |
| DEPARTMENT_MANAGER | DEPARTMENT | 必填 |
| LEADER | COMPANY / DEPARTMENT | 部门范围必填，公司范围为空 |

同人、角色、范围与部门的未撤销区间不能重叠。跨部门授权独立保存，禁止依据人员当前部门自动替换范围。当前有效任职也形成部门管理权限；撤销一项授权不会撤销另一项仍有效的任职或授权。

## 人员历史

`GET /master/users/{userId}/history` 返回：

```json
{"userId":"1","departments":[{"id":"1","departmentId":"1","departmentName":"研发部","effectiveFrom":"2026-01-01","effectiveTo":null}],"levels":[{"id":"1","level":"JUNIOR","effectiveFrom":"2026-01-01","effectiveTo":null}],"enrollments":[{"id":"1","effectiveFrom":"2026-01-01","effectiveTo":null,"voided":false}]}
```

组织及职级历史只读，新增生效区间继续使用现有人员维护接口。旧 `saveUser.roles[]` 保持兼容：仍勾选的角色保留已有各部门范围；取消勾选会撤销该角色已有授权。前端日常编辑人员应保留原角色数组，角色范围变更集中使用本页明确授权接口。治理接口允许预先设置未来授权/任职；权限仅到生效日起生效。

人员返回新增 `pendingApprovalCount`：当前指派待审批项与未决更正请求的数量。停用保留这些历史并立即禁止登录，界面提示管理员通过已有待办转交入口登记接收人；不因停用自动改审批人。

## 部门负责人维护非项目事项

原 `GET/POST/PUT /master/work-items` 保持管理员能力，对有当前有效任职或明确 DEPARTMENT_MANAGER 授权的人员，开放其部门及子部门的本地 NON_PROJECT 对象。项目、IDLE、OA权威对象、其他部门对象不可修改；LEADER 范围不扩大该写权限。

`GET /master/non-project-options` 返回 `{departments:[{id,name}],approvers:[{id,name}],canManageProjects}`。非管理员只取得可管理部门、范围内人员及已指定负责人/分管领导的必要名称，不取得全公司人员身份字段或角色。非项目维护表单固定 `type=NON_PROJECT,source=LOCAL`，其他请求字段沿用原 WorkItemInput。
