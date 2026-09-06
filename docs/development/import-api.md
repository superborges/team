# 首批 XLSX 导入接口（E02 最小范围）

> 当前版本已补充 USER / DEPARTMENT / TRIP、失败 XLSX 下载、覆盖核实与项目显式关联；新增契约见 [运营接口](operations-api.md) 和 [项目来源关联](project-source-link-api.md)。本页保留初始三类模板的字段说明。

基准 `/api/v1`。所有接口仅管理员可用，每次写入重新校验权限及业务数据。支持 `PROJECT`、`RATE`、`LEAVE`；人员和组织继续通过已有维护页管理。本实现不调用 OA，也不代表来源期间覆盖完整。

## 接口

| 请求 | 行为 |
|---|---|
| `GET /imports/templates/{dataset}` | 下载 `.xlsx` 固定列头空模板，单元格为文本格式 |
| `POST /imports?dataset=PROJECT` | multipart 字段 `file` 上传预览，返回完整 Batch；带正常 CSRF 请求头，浏览器自行设置 multipart boundary |
| `GET /imports` | 最近 50 个 BatchSummary，按 ID 倒序，省略 rows |
| `GET /imports/{id}` | 完整 Batch，包括 rows |
| `POST /imports/{id}/apply` | 无需请求体，应用可校验行；逐行独立事务。成功行不重复执行，失败行可重试；返回完整 Batch |

BatchSummary：`{id:string,dataset,state,fileName,createdAt,validCount,errorCount,appliedCount}`。

Batch：上述字段 + `rows:[{rowNumber:number,data:Record<string,string>,valid:boolean,error:string|null,state:string}]`。

- Batch `state`：`PREVIEW`、`APPLIED`、`PARTIAL`。全部行成功才为 APPLIED；确认后存在失败行则 PARTIAL。
- Row `state`：`VALID`、`INVALID`、`APPLIED`、`FAILED`。`valid` 为当前校验结果；成功行 valid=true。错误记录在 `error`，包括校验与应用时冲突；前端逐行显示，不伪装整批成功。
- 无单元格内编辑入口。格式不合法的行须修复原文件并重新预览；资料补齐后可对 FAILED / INVALID 行重试，服务端再次校验。
- API 请求本身的鉴权、文件损坏等错误沿用 `{code,message}`；部分行失败仍返回 HTTP 200 的 PARTIAL 结果。

## 固定列头

第一行必须严格使用下列列名和顺序；只允许一个工作表、最多 500 条非空数据行、文件最多 2 MiB。模板列为文本，特别是工号、来源编码、版本、日期和金额；不接受公式、宏文件或额外列。日期使用 `YYYY-MM-DD`，小数使用十进制文本；不把 Excel 数字显示格式当作工号原值。

工号、编码、sourceKey / sourceVersion 保留精确原值；这些标识列含首尾空白时逐行拒绝，不自动去空白合并来源。错误行原值仍保存在预览材料中。

| dataset | 固定列名（顺序） | 说明 |
|---|---|---|
| PROJECT | `code,name,type,departmentCode,approverEmployeeNo,effectiveFrom` | type 为 PROJECT / NON_PROJECT；部门编码必须存在，审批人工号必须为有效人员；source 固定 LOCAL。已存在的 code 报错，不覆盖对象 |
| RATE | `dimension,dailyRate,effectiveFrom,effectiveTo` | dimension 为 JUNIOR / MIDDLE / SENIOR / ONSITE；金额最多四位小数且非负；effectiveTo 可留空。按已有费率版本规则校验重叠和封账 |
| LEAVE | `sourceKey,sourceVersion,employeeNo,date,leaveMinutes,startMinute,endMinute,status` | status 为 APPROVED / REVOKED；来源 key 和版本按不透明字符串精确处理。工号匹配内部稳定 ID；分钟为整数，0–480；start/end 成对留空或同时填写，且差值须等于 leaveMinutes |

LEAVE 的每个 `sourceKey` 固定绑定一人一天；变更人员或日期应显式撤销旧来源，再以新 sourceKey 新增，不静默移动。相同版本、相同内容可重复导入而不重复扣假；相同版本、不同内容报错。不按版本字符串大小猜测时间先后；使用本系统首次接收记录的顺序保护来源头，已经被后续版本取代的旧失败行不可重试覆盖新版本，需核实后提供新来源版本。重放已经成功的旧版本只返回成功，不恢复旧事实。

同日多个有效假单按明确时段合并去重；多来源任一缺少明确时段时拒绝应用并提示核实。封账日期的变化保留为失败来源 / 导入差异，不更新原工时基数，也不会显示成“无请假”。

Excel 实现采用 [Apache POI 官方稳定版 5.5.1](https://poi.apache.org/download.html)。源行规范化值及版本状态保存在 MySQL，预览不会写项目、费率或请假业务表。
