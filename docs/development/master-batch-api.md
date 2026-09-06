# 人员批量调整与批量负责人任职

仅管理员；`/api/v1` 前缀，沿用 CSRF。选择最多50人，先预览再提交；同批全部成功或回滚，页面保留输入以便修正。写入逐人的既有历史与汇总审计，不改工号、企微身份、姓名或已有角色范围。

- `POST /master/users/batch/preview`：`{userIds:["1","2"],departmentId:null,level:"SENIOR",effectiveFrom:"2026-09-05",reason:"核实后的职级调整"}`。部门与职级至少选择一个；未选字段保持各人原值。
- `POST /master/users/batch/apply`：同一请求，加上预览返回的 `expectedFingerprint`。

预览返回 `{expectedFingerprint,canApply,errors:[],rows:[{userId,employeeNo,name,fromDepartmentId,fromDepartmentName,toDepartmentId,toDepartmentName,fromLevel,toLevel,affectedDayCount,approvedRevisionCount}],effectiveFrom,reason}`。只读统计影响日起的已保存日期与当前已确认版本数量；已封账历史不能通过人员调整改写。提交返回 `{updated:[UserView],affectedUsers,reason}`。预览后人员变化返回409，须重新核实影响。

- `POST /master/department-managers/batch/preview`：`{userIds:["1","2"],departmentId:"3",title:"HEAD"|"DEPUTY",effectiveFrom,effectiveTo:null,reason}`。
- `POST /master/department-managers/batch/apply`：同一请求，加 `expectedFingerprint`。

负责人预览返回 `{expectedFingerprint,canApply,errors:[],users:[{userId,employeeNo,name}],departmentId,departmentName,title,effectiveFrom,effectiveTo,reason}`。提交返回 `{appointments:[ManagerView],affectedUsers,reason}`。多人任职采用相同有效区间；同人同部门区间交叠或已有同起日历史会在预览明确列出。已存在负责人不会被批量替换，指定回避承接人与分管领导仍单独配置。
