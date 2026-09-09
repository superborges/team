<script setup lang="ts">
import ModalShell from '../../shared/ModalShell.vue';
import { computed, inject, onBeforeUnmount, onMounted, ref, watch, type Ref } from 'vue';
import { onBeforeRouteLeave } from 'vue-router';
import { ElAlert, ElButton, ElOption, ElSelect, ElTable, ElTableColumn, ElTag } from 'element-plus';
import { api, command, displayTime, downloadFile, errorMessage, request, type ImportBatch, type ImportBatchSummary, type ImportDataset, type ImportRow } from '@worktime/api-client';
const dataset = ref<ImportDataset>('PROJECT'); const file = ref<File>(); const fileInput = ref<HTMLInputElement>(); const batch = ref<ImportBatch>(); const batches = ref<ImportBatchSummary[]>([]); const busy = ref(false); const loading = ref(true); const error = ref(''); const listError = ref('');
const sharedBusy = inject<Ref<boolean>>('operationBusy');
watch(busy, value => { if (sharedBusy) sharedBusy.value = value; });
const labels: Record<ImportDataset, string> = { PROJECT: '项目', RATE: '成本标准', LEAVE: '已核实请假', USER: '人员', DEPARTMENT: '部门', TRIP: '已审批出差' };
interface SourceLink{id:string;sourceCode:string;workItemId:string;workItemCode:string;workItemName:string;type:string;batchId:string|null;rowNumber:number|null;linkedByName:string;reason:string;linkedAt:string}
const linking=ref<ImportRow>(),linkTarget=ref(''),linkReason=ref(''),linkError=ref(''),linkObjects=ref<{id:string;code:string;name:string;type:string}[]>([]),links=ref<SourceLink[]>([]),showLinks=ref(false);
async function loadLinks(){busy.value=true;error.value='';try{links.value=await request('/imports/project-links');showLinks.value=true;}catch(e){error.value=errorMessage(e);}finally{busy.value=false;}}
async function openLink(row:ImportRow){busy.value=true;linkError.value='';linkTarget.value='';linkReason.value='';linking.value=row;try{linkObjects.value=(await api.catalog()).workItems.filter(w=>w.type===row.data.type);}catch(e){linkError.value=errorMessage(e);}finally{busy.value=false;}}
async function saveLink(){if(!batch.value||!linking.value)return;busy.value=true;linkError.value='';try{await command(`/imports/${batch.value.id}/rows/${linking.value.rowNumber}/project-link`,{workItemId:linkTarget.value,reason:linkReason.value});linking.value=undefined;batch.value=await api.importBatch(batch.value.id);await loadLinks();}catch(e){linkError.value=errorMessage(e);}finally{busy.value=false;}}
const stateNames: Record<string, string> = { PREVIEW: '待确认', APPLIED: '已导入', PARTIAL: '部分导入' };
const canApply = computed(() => batch.value && batch.value.state !== 'APPLIED' && batch.value.rows.length > 0);
const columnLabels: Record<string, string> = { employeeNo: '工号', wecomUserid: '企微 UserID', level: '职级', parentCode: '上级部门', supervisorEmployeeNo: '上级负责人工号', projectCode: '项目编码', date: '日期', code: '编码', name: '名称', type: '类型', departmentCode: '主责部门编码', approverEmployeeNo: '审批人工号', dimension: '标准类型', dailyRate: '每日标准（元）', effectiveFrom: '生效日期', effectiveTo: '截止日期', leaveMinutes: '请假分钟', startMinute: '开始分钟', endMinute: '结束分钟', status: '状态', sourceKey: '来源记录', sourceVersion: '来源版本' };
function describe(row: ImportRow) { return Object.entries(row.data ?? {}).map(([key, value]) => `${columnLabels[key] ?? key}：${value === null ? '空' : typeof value === 'object' ? JSON.stringify(value) : String(value)}`).join('；'); }
function rowState(row: ImportRow) { return row.error ? '需处理' : row.state === 'APPLIED' ? '已导入' : row.valid ? '校验通过' : '校验未通过'; }
async function loadList() { loading.value = true; listError.value = ''; try { batches.value = await api.importBatches(); } catch (e) { listError.value = errorMessage(e); } finally { loading.value = false; } }
function chooseFile(event: Event) { file.value = (event.target as HTMLInputElement).files?.[0]; batch.value = undefined; error.value = ''; }
function changeDataset() { file.value = undefined; batch.value = undefined; error.value = ''; if (fileInput.value) fileInput.value.value = ''; }
async function download() {
  busy.value = true; error.value = '';
  try { const blob = await api.importTemplate(dataset.value); const url = URL.createObjectURL(blob); const link = document.createElement('a'); link.href = url; link.download = `${labels[dataset.value]}导入模板.xlsx`; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000); }
  catch (e) { error.value = errorMessage(e); } finally { busy.value = false; }
}
async function preview() {
  if (!file.value || busy.value) return;
  if (!file.value.name.toLowerCase().endsWith('.xlsx') || file.value.size > 2 * 1024 * 1024) { error.value = '请选择不超过 2 MB 的 .xlsx 文件，每批最多 500 行。'; return; }
  busy.value = true; error.value = '';
  try { batch.value = await api.previewImport(dataset.value, file.value); file.value = undefined; if (fileInput.value) fileInput.value.value = ''; await loadList(); }
  catch (e) { error.value = errorMessage(e); } finally { busy.value = false; }
}
async function apply() {
  if (!batch.value || busy.value) return;
  if (!window.confirm(`将导入批次 ${batch.value.id} 中通过校验的记录。未通过的行不会导入，并保留失败原因。确认继续？`)) return;
  busy.value = true; error.value = '';
  try { batch.value = await api.applyImport(batch.value.id); await loadList(); } catch (e) { error.value = errorMessage(e); } finally { busy.value = false; }
}
async function downloadErrors() { if (!batch.value) return; busy.value = true; error.value = ''; try { downloadFile(await request<Blob>(`/imports/${batch.value.id}/errors`, {}, 'blob'), `导入失败行-${batch.value.id}.xlsx`); } catch(e) { error.value = errorMessage(e); } finally { busy.value = false; } }
async function inspect(id: string) { busy.value = true; error.value = ''; try { batch.value = await api.importBatch(id); } catch (e) { error.value = errorMessage(e); } finally { busy.value = false; } }
onBeforeRouteLeave(() => !busy.value && (!linking.value||!linkReason.value||window.confirm('项目关联说明尚未保存，确定离开？')) && (!file.value || window.confirm('有尚未上传的文件。确定离开？')));
onMounted(loadList); onBeforeUnmount(() => { if (sharedBusy) sharedBusy.value = false; });
</script>

<template>
  <div class="page-head"><div><h1>数据导入</h1><p>上传后先检查内容，再确认导入。出错的行会显示原因，方便修改。</p></div></div>
  <ElAlert v-if="error" :title="error" type="error" :closable="false" show-icon class="message" />
  <section class="import-upload"><h2>上传已核对的资料</h2><div class="import-controls"><label>资料类型<ElSelect v-model="dataset" aria-label="资料类型" :disabled="busy" @change="changeDataset"><ElOption v-for="(label, key) in labels" :key="key" :value="key" :label="label" /></ElSelect></label><ElButton :disabled="busy" @click="download">下载模板</ElButton><label class="file-label">Excel 文件<div class="file-picker"><ElButton :disabled="busy" @click="fileInput?.click()">选择文件</ElButton><span :title="file?.name">{{ file?.name ?? '尚未选择文件' }}</span><input ref="fileInput" type="file" accept=".xlsx" aria-label="Excel 文件" hidden :disabled="busy" @change="chooseFile" /></div></label><ElButton type="primary" :disabled="busy || !file" :loading="busy" @click="preview">上传并预览</ElButton></div><p>支持人员、部门、项目、成本标准、请假和出差。人员与部门只能新增；修改工号请到“人员与组织”。每批最多 500 行，文件不超过 2 MB。</p></section>
  <p class="workflow-note">出差数据按审批通过的日期逐日导入，不自动生成现场日记录。导入后请到 <RouterLink to="/coverage">数据完整性核查</RouterLink> 登记已核查的日期和部门范围。</p>
  <section v-if="batch" class="table-panel import-preview"><div class="table-toolbar"><div><strong>{{ labels[batch.dataset] }} · 批次 {{ batch.id }}</strong><ElTag type="info">{{ stateNames[batch.state] ?? batch.state }}</ElTag></div><ElButton v-if="canApply" type="primary" :disabled="busy" :loading="busy" @click="apply">{{ batch.state === 'PARTIAL' || batch.validCount === 0 ? '重新检查并导入' : '确认导入有效行' }}</ElButton></div><div class="import-summary"><ElButton v-if="batch.errorCount" :disabled="busy" @click="downloadErrors">下载失败记录</ElButton> 校验通过 {{ batch.validCount }} 行，已导入 {{ batch.appliedCount }} 行，待处理 {{ batch.errorCount }} 行。请逐行核对结果。</div><ElTable :data="batch.rows" empty-text="此批次暂无记录" max-height="440"><ElTableColumn prop="rowNumber" label="行号" width="80" /><ElTableColumn label="记录内容" min-width="420"><template #default="{ row }"><span class="import-row-data">{{ describe(row as ImportRow) }}</span></template></ElTableColumn><ElTableColumn label="结果" width="115"><template #default="{ row }"><ElTag :type="row.error ? 'warning' : 'success'">{{ rowState(row as ImportRow) }}</ElTag></template></ElTableColumn><ElTableColumn prop="error" label="失败原因" min-width="240" /><ElTableColumn v-if="batch.dataset==='PROJECT'" label="项目关联" width="145"><template #default="{row}"><ElButton v-if="row.error && /编码|同名|关联|重复|已存在|冲突/.test(row.error)" link type="primary" :disabled="busy" @click="openLink(row as ImportRow)">关联已有项目或事项</ElButton></template></ElTableColumn></ElTable></section>
  <section class="workflow-page"><div class="workflow-actions"><button :disabled="busy" @click="loadLinks">查看项目关联记录</button><span class="workflow-note">关联后会保留系统中已有的名称、负责人、主责部门和历史记录。请返回原批次重新导入。</span></div><div v-if="showLinks" class="workflow-panel source-links"><p v-if="!links.length">暂无项目关联记录。</p><div class="workflow-table-wrap"><table v-if="links.length" class="workflow-table"><thead><tr><th>来源编码</th><th>系统中的项目或事项</th><th>核实依据</th><th>登记人 / 时间</th></tr></thead><tbody><tr v-for="link in links" :key="link.id"><td>{{ link.sourceCode }}</td><td>{{ link.workItemCode }} · {{ link.workItemName }}</td><td>{{ link.reason }}</td><td>{{ link.linkedByName }} · {{ displayTime(link.linkedAt) }}</td></tr></tbody></table></div></div><ModalShell v-if="linking" :busy="busy" @close="linking=undefined"><section class="workflow-dialog" role="dialog" aria-modal="true" aria-label="关联已有项目或事项"><h2>核实项目来源</h2><p>批次 {{ batch?.id }} 第 {{ linking.rowNumber }} 行：{{ linking.data.code }} / {{ linking.data.name }}</p><p>请选择系统中已核实的同类型项目或事项，导入内容不会覆盖其现有资料。如果文件格式有误，请先修正后重新上传。</p><form @submit.prevent="saveLink"><label>系统中已有的项目或事项<select aria-label="系统中已有的项目或事项" v-model="linkTarget" required :disabled="busy"><option value="">选择已核实的同类型项目或事项</option><option v-for="w in linkObjects" :key="w.id" :value="w.id">{{ w.code }} · {{ w.name }}</option></select></label><label>核实依据<textarea v-model="linkReason" rows="3" required maxlength="1000" :disabled="busy" /></label><p v-if="linkError" class="workflow-error">{{ linkError }}</p><div class="workflow-actions"><button type="button" :disabled="busy" @click="linking=undefined">取消</button><button type="submit" class="primary" :disabled="busy">确认关联</button></div></form></section></ModalShell></section>
  <section class="table-panel"><div class="table-toolbar"><span>最近批次 <small>最多 50 批</small></span><ElButton :loading="loading" :disabled="busy" @click="loadList">刷新</ElButton></div><ElAlert v-if="listError" :title="listError" type="error" :closable="false" /><ElTable :data="batches" max-height="460" :empty-text="loading ? '正在读取…' : listError ? '未能读取批次，请重试' : '尚无导入批次'"><ElTableColumn prop="id" label="批次" width="100" /><ElTableColumn label="资料类型" min-width="150"><template #default="{ row }">{{ labels[row.dataset as ImportDataset] ?? row.dataset }}</template></ElTableColumn><ElTableColumn label="状态" min-width="130"><template #default="{ row }">{{ stateNames[row.state] ?? row.state }}</template></ElTableColumn><ElTableColumn prop="validCount" label="校验通过" min-width="100" /><ElTableColumn prop="errorCount" label="问题行" min-width="100" /><ElTableColumn label="操作" width="100"><template #default="{ row }"><ElButton link type="primary" :disabled="busy" @click="inspect(row.id)">查看详情</ElButton></template></ElTableColumn></ElTable></section>
</template>

<style scoped>
.source-links { max-height: 460px; overflow: auto; }
.import-upload { margin-bottom: var(--ts-space-24); padding: var(--ts-space-20); border: 1px solid var(--ts-color-border); border-radius: var(--ts-radius-card); background: var(--ts-color-surface); }
.import-upload h2 { margin-bottom: var(--ts-space-20); color: var(--ts-color-text-strong); font-size: var(--ts-type-section); }
.import-controls { display: flex; align-items: flex-end; flex-wrap: wrap; gap: var(--ts-space-16); }
.import-controls > label { display: flex; min-width: 0; max-width: 100%; flex-direction: column; gap: var(--ts-space-8); color: var(--ts-color-text-secondary); font-size: var(--ts-type-label); }
.import-controls .el-select { width: 180px; max-width: 100%; }
.import-controls .file-label { flex: 1 1 230px; }
.file-picker { display: flex; min-width: 0; align-items: center; gap: var(--ts-space-8); }
.file-picker > span { min-width: 0; max-width: 250px; overflow: hidden; color: var(--ts-color-text-muted); text-overflow: ellipsis; white-space: nowrap; font-size: var(--ts-type-caption); }
.import-upload > p { margin: var(--ts-space-16) 0 0; color: var(--ts-color-text-muted); font-size: var(--ts-type-caption); line-height: 1.6; }
.import-preview { margin-bottom: var(--ts-space-24); }
.import-summary { padding: 0 var(--ts-space-20) var(--ts-space-16); color: var(--ts-color-text-secondary); font-size: var(--ts-type-caption); line-height: 1.6; overflow-wrap: anywhere; }
.import-row-data { font-size: var(--ts-type-caption); line-height: 1.7; overflow-wrap: anywhere; }
.import-preview .table-toolbar > div { flex-wrap: wrap; }
@media (max-width: 767px) { .import-upload { padding: var(--ts-space-16); } .import-controls .file-label { flex-basis: 100%; width: 100%; } .import-summary { padding-inline: var(--ts-space-16); } }
</style>
