# 本地项目来源显式关联

仅管理员操作，沿用 CSRF 与服务端实时授权。没有真实 OA 接口时，这些接口只登记已上传的原始模板行与本系统稳定归集对象的关系，不改变既有名称、编码、PM、主责部门、生效历史及来源标签。

- `GET /api/v1/imports/project-links`：已绑定列表。
- `POST /api/v1/imports/{batchId}/rows/{rowNumber}/project-link`：`{workItemId:"123",reason:"经项目负责人核实，该来源对应既有手工项目"}`。

返回：`{id,sourceCode,workItemId,workItemCode,workItemName,type,batchId:string|null,rowNumber:number|null,linkedBy,linkedByName,reason,linkedAt}`；ID 均为字符串。`rowNumber` 使用预览里的行号（不是数组索引）。仅 PROJECT 数据集且类型与目标一致的原始行可关联；格式错误行须先修正模板。

确认关联后重新点击原批次的应用/重试。重复关联同一对象为幂等；已绑定另一对象或编码已属于别的对象返回409，不改绑历史数据。不同来源编码允许映射同一既有对象。关联不自动批准业务，也不修改封账快照。原始文件及行仍作为依据保留，完整动作进入审计。

预览发现同编码或疑似同名对象时提示人工核实，不自动重复建项目。无冲突的本地新建项目同样保留来源映射。出差来源使用关联后的项目编码解析规则，保持现场复核与历史业务主体一致。

后端接缝：`ProjectSourceLinkService.find(code)` 只读；`lockForApply(code)` 须在应用事务内、月门禁之后与人员/行锁之前；`bindCreated(code,workItemId)` 与本地创建业务同事务。已绑定时来源应用仅登记接收/处理轨迹，不覆盖目标主数据。
