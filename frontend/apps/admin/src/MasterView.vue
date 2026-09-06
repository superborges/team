<script setup lang="ts">
import BatchChangePanel from './BatchChangePanel.vue';
import { computed, inject, onBeforeUnmount, onMounted, ref, watch, type Ref } from 'vue';
import { onBeforeRouteLeave } from 'vue-router';
import { ElAlert, ElButton, ElDialog, ElInput, ElOption, ElSelect, ElTable, ElTableColumn, ElTag } from 'element-plus';
import { api, errorMessage, request, today, type Me } from '@worktime/api-client';
const me=inject<Ref<Me|undefined>>('me');
const props = defineProps<{ section: 'people' | 'work-items' | 'rates'; scoped?: boolean }>();
type Row = Record<string, string | string[] | number | null>;
type Resource = 'users' | 'departments' | 'work-items' | 'rates';
interface Field { key: string; label: string; type?: 'date' | 'select' | 'multi' | 'money'; options?: { value: string; label: string }[]; required?: boolean; hint?: string }
const resource = ref<Resource>(props.section === 'people' ? 'users' : props.section);
const rows = ref<Row[]>([]); const users = ref<Row[]>([]); const departments = ref<Row[]>([]); const loading = ref(true); const saving = ref(false); const error = ref(''); const formError = ref(''); const success = ref(''); const search = ref('');
const sharedBusy=inject<Ref<boolean>>('operationBusy');
const batchBusy=ref(false), batchKind=ref<'users'|'managers'|null>(null),selectedUsers=ref<Row[]>([]);
const busy=computed(()=>loading.value||saving.value||batchBusy.value);watch(busy,value=>{if(sharedBusy)sharedBusy.value=value;},{immediate:true});
const dialog = ref(false); const editingId = ref<string | null>(null); const form = ref<Record<string, string | string[]>>({}); const original = ref('');
const stateOptions = [{ value: 'ACTIVE', label: '启用' }, { value: 'INACTIVE', label: '停用' }];
const levels = [{ value: 'JUNIOR', label: '初级' }, { value: 'MIDDLE', label: '中级' }, { value: 'SENIOR', label: '高级' }];
const names: Record<Resource, string> = { users: '人员', departments: '部门', 'work-items': '归集对象', rates: '费率版本' };
const pendingApprovalCount=computed(()=>rows.value.find(row=>String(row.id)===editingId.value)?.pendingApprovalCount);
const canEdit=computed(()=>me?.value?.canManage || (props.scoped && me?.value?.roles.includes('DEPARTMENT_MANAGER')));
const descriptions: Record<Resource, string> = { users: '人员以内部 ID 稳定关联，工号调整不改变历史记录。', departments: '维护业务组织与负责人，生效历史由系统留存。', 'work-items': '项目、非项目事务和待分配时间都有明确归属。', rates: '按工作发生日取标准，金额以人民币元表示。新增版本不会覆盖旧金额。' };
const options = (source: Row[]) => source.map(row => ({ value: String(row.id), label: String(row.name) }));
const fields = computed<Field[]>(() => {
  if (resource.value === 'users') return [{ key: 'employeeNo', label: '工号', required: true, hint: '五位数字，或两位字母加五位数字；保留前导零。' }, { key: 'wecomUserid', label: '企业微信 UserID', required: true }, { key: 'name', label: '姓名', required: true }, { key: 'departmentId', label: '所属部门', type: 'select', options: options(departments.value), required: true }, { key: 'level', label: '职级', type: 'select', options: levels, required: true }, { key: 'roles', label: '角色', type: 'multi', options: [{ value: 'EMPLOYEE', label: '员工' }, { value: 'PM', label: '项目经理' }, { value: 'DEPARTMENT_MANAGER', label: '部门负责人' }, { value: 'LEADER', label: '公司领导' }, { value: 'ADMIN', label: '管理员' }] }, { key: 'effectiveFrom', label: '生效日期', type: 'date', required: true, hint: '用于新人员及部门、职级历史；停用和权限撤销即时生效。' }, { key: 'status', label: '状态', type: 'select', options: stateOptions, required: true }];
  if (resource.value === 'departments') return [{ key: 'code', label: '部门编码', required: true }, { key: 'name', label: '部门名称', required: true }, { key: 'parentId', label: '上级部门', type: 'select', options: options(departments.value.filter(row => row.id !== editingId.value)) }, { key: 'approverUserId', label: '默认负责人', type: 'select', options: options(users.value) }, { key: 'supervisorUserId', label: '分管领导', type: 'select', options: options(users.value) }, { key: 'status', label: '状态', type: 'select', options: stateOptions, required: true }];
  if (resource.value === 'work-items') return [{ key: 'code', label: '对象编码', required: true }, { key: 'name', label: '对象名称', required: true }, { key: 'type', label: '类型', type: 'select', options: [{ value: 'PROJECT', label: '项目工作' }, { value: 'NON_PROJECT', label: '非项目事务' }, { value: 'IDLE', label: '待分配' }], required: true }, { key: 'ownerDepartmentId', label: '主责部门', type: 'select', options: options(departments.value), required: true }, { key: 'approverUserId', label: '默认审批人', type: 'select', options: options(users.value) }, { key: 'effectiveFrom', label: '生效日期', type: 'date', required: true }, { key: 'status', label: '状态', type: 'select', options: stateOptions, required: true }];
  return [{ key: 'dimension', label: '标准维度', type: 'select', options: [...levels, { value: 'ONSITE', label: '现场自然日' }], required: true }, { key: 'dailyRate', label: '日标准（元）', type: 'money', required: true }, { key: 'effectiveFrom', label: '生效日期（含）', type: 'date', required: true }, { key: 'effectiveTo', label: '截止日期（不含，可留空）', type: 'date' }];
});
const filtered = computed(() => rows.value.filter(row => Object.values(row).some(value => String(value ?? '').toLowerCase().includes(search.value.toLowerCase()))));
const dirty = computed(() => dialog.value && JSON.stringify(form.value) !== original.value);
const fieldValue = (row: Row, key: string) => {
  const value = row[key];
  if (key === 'roles') return Array.isArray(value) ? value.map(role => ({ ADMIN: '管理员', EMPLOYEE: '员工', PM: '项目经理', DEPARTMENT_MANAGER: '部门负责人', LEADER: '公司领导' })[role as 'ADMIN'] ?? role).join('、') : '—';
  if (key === 'ownerDepartmentId' || key === 'departmentId' || key === 'parentId') return departments.value.find(d => d.id === value)?.name ?? '—';
  if (key === 'approverUserId' || key === 'supervisorUserId') return users.value.find(u => u.id === value)?.name ?? '未指定';
  if (key === 'type') return ({ PROJECT: '项目', NON_PROJECT: '非项目事务', IDLE: '待分配' })[String(value) as 'PROJECT'] ?? value;
  if (key === 'dimension') return [...levels, { value: 'ONSITE', label: '现场自然日' }].find(item => item.value === value)?.label ?? value;
  return value || '—';
};
const columns = computed(() => resource.value === 'users' ? [['employeeNo', '工号'], ['name', '姓名'], ['departmentId', '部门'], ['roles', '角色']] : resource.value === 'departments' ? [['code', '编码'], ['name', '部门'], ['parentId', '上级部门'], ['approverUserId', '负责人'], ['supervisorUserId', '分管领导']] : resource.value === 'work-items' ? [['code', '编码'], ['name', '归集对象'], ['type', '类型'], ['ownerDepartmentId', '主责部门'], ['approverUserId', '审批人']] : [['dimension', '标准维度'], ['dailyRate', '日标准 / 元'], ['effectiveFrom', '生效日期（含）'], ['effectiveTo', '截止日期（不含）']]);
async function load() { loading.value = true; error.value = ''; try { if(resource.value==='rates'){rows.value=await api.list<Row>('rates');return;} if(props.scoped){const [data, choices]=await Promise.all([api.list<Row>('work-items'),request<{departments:Row[];approvers:Row[];canManageProjects:boolean}>('/master/non-project-options')]);rows.value=data.filter(row=>row.type==='NON_PROJECT'&&row.source==='LOCAL');users.value=choices.approvers;departments.value=choices.departments;return;} const [data, people, depts] = await Promise.all([api.list<Row>(resource.value), api.list<Row>('users'), api.list<Row>('departments')]); rows.value = data; users.value = people; departments.value = depts; } catch (e) { error.value = errorMessage(e); } finally { loading.value = false; } }
async function changeResource(target: Resource) { if (busy.value || resource.value === target) return; resource.value = target; rows.value = []; search.value = ''; success.value = ''; await load(); }
function edit(row?: Row) {
  if (busy.value) return;
  editingId.value = row ? String(row.id) : null; formError.value = '';
  const initial: Record<string, string | string[]> = { status: 'ACTIVE', level: 'MIDDLE', type: props.scoped?'NON_PROJECT':'PROJECT', dimension: 'MIDDLE', effectiveFrom: today(), roles: ['EMPLOYEE'] };
  form.value = Object.fromEntries(fields.value.map(field => {const value=row?.[field.key] ?? initial[field.key] ?? '';return [field.key,Array.isArray(value)?value:String(value)];}));
  original.value = JSON.stringify(form.value); dialog.value = true;
}
function close(done: () => void) { if (saving.value) return; if (!dirty.value || window.confirm('有未保存的修改。确定放弃？')) done(); }
async function save() {
  if(saving.value)return;
  saving.value = true; formError.value = '';
  try {
    const body: Record<string, unknown> = Object.fromEntries(fields.value.map(field => [field.key, form.value[field.key]]));
    for (const key of ['parentId', 'approverUserId', 'supervisorUserId', 'effectiveTo']) if (key in body && body[key] === '') body[key] = null;
    if (resource.value === 'users' && editingId.value) delete body.roles;
    if (resource.value === 'work-items') {body.source = 'LOCAL';if(props.scoped)body.type='NON_PROJECT';}
    await api.saveMaster(resource.value, editingId.value, body); dialog.value = false; success.value = `${names[resource.value]}已保存。`; await load();
  } catch (e) { formError.value = errorMessage(e); } finally { saving.value = false; }
}
onBeforeRouteLeave(() => !busy.value && (!dirty.value || window.confirm('有未保存的修改。确定离开？')));
onBeforeUnmount(()=>{if(sharedBusy)sharedBusy.value=false;});
onMounted(load);
</script>

<template>
  <div class="page-head"><div><h1>{{ props.section === 'people' ? '人员与组织' : props.section === 'rates' ? '费率标准' : props.scoped ? '非项目事项' : '归集对象' }}</h1><p>{{ props.scoped ? '维护所辖部门的本地非项目事项。项目、OA 来源与其他部门对象按权限隔离。' : descriptions[resource] }}</p></div><ElButton v-if="canEdit" type="primary" :disabled="busy || !!error" @click="edit()">{{ resource === 'rates' ? '新增生效版本' : `新增${names[resource]}` }}</ElButton></div>
  <div v-if="props.section === 'people'" class="sub-tabs"><button :class="{ active: resource === 'users' }" :disabled="busy" @click="changeResource('users')">人员</button><button :class="{ active: resource === 'departments' }" :disabled="busy" @click="changeResource('departments')">组织部门</button><RouterLink to="/governance">负责人与权限范围 / 人员历史</RouterLink></div>
  <ElAlert v-if="error" :title="error" type="error" :closable="false" show-icon class="message" /><ElAlert v-if="success" :title="success" type="success" :closable="false" class="message" />
  <section class="table-panel"><div class="table-toolbar"><span>{{ names[resource] }}列表 <small>{{ rows.length }} 项</small></span><div><ElInput v-model="search" :placeholder="`查找${names[resource]}`" :aria-label="`查找${names[resource]}`" clearable /><ElButton :loading="loading" :disabled="busy" @click="load">刷新</ElButton></div></div>
    <div v-if="resource==='users'" class="workflow-page batch-toolbar"><span>已选 {{ selectedUsers.length }} 人（每批最多 50 人）</span><button :disabled="busy||!selectedUsers.length||selectedUsers.length>50" @click="batchKind='users'">批量调整部门 / 职级</button><button :disabled="busy||!selectedUsers.length||selectedUsers.length>50" @click="batchKind='managers'">批量登记负责人</button></div>
    <ElTable :data="filtered" @selection-change="(selection:Row[])=>selectedUsers=selection" :empty-text="loading ? '正在读取…' : error ? '未能读取数据，请重试' : '暂无记录，点击上方按钮新增'" stripe>
      <ElTableColumn v-if="resource==='users'" type="selection" width="50" />
      <ElTableColumn v-for="[key, label] in columns" :key="key" :label="label" :min-width="key === 'name' ? 170 : 125"><template #default="{ row }">{{ fieldValue(row, key) }}</template></ElTableColumn>
      <ElTableColumn v-if="resource === 'users'" label="待交接审批" width="120"><template #default="{ row }"><ElTag :type="row.pendingApprovalCount > 0 ? 'warning' : 'info'">{{ row.pendingApprovalCount == null ? '未读取' : `${row.pendingApprovalCount} 项` }}</ElTag></template></ElTableColumn>
      <ElTableColumn v-if="resource === 'work-items'" label="来源" width="95"><template #default="{ row }">{{ row.source === 'OA' ? 'OA 同步' : '本地维护' }}</template></ElTableColumn>
      <ElTableColumn v-if="resource !== 'rates'" label="状态" width="85"><template #default="{ row }"><ElTag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{ row.status === 'ACTIVE' ? '启用' : '停用' }}</ElTag></template></ElTableColumn>
      <ElTableColumn v-if="resource !== 'rates'" label="操作" width="90" fixed="right"><template #default="{ row }"><ElButton link type="primary" :disabled="busy || row.source === 'OA'" @click="edit(row)">{{ row.source === 'OA' ? '来源只读' : '编辑' }}</ElButton></template></ElTableColumn>
    </ElTable>
  </section><p v-if="resource === 'rates'" class="page-footnote">有效区间不能重叠。历史标准只能通过新增版本调整；已封账历史受月份控制。</p>
  <BatchChangePanel v-if="batchKind" :kind="batchKind" :user-ids="selectedUsers.map(u=>String(u.id))" :departments="departments.map(d=>({id:String(d.id),name:String(d.name)}))" @busy="batchBusy=$event" @close="batchKind=null" @done="batchKind=null;success='批量方案已应用。';load()" />
  <ElDialog v-model="dialog" :title="`${editingId ? '编辑' : '新增'}${names[resource]}`" width="min(580px, 92vw)" :before-close="close" :close-on-click-modal="false" destroy-on-close>
    <ElAlert v-if="formError" :title="formError" type="error" :closable="false" class="message" />
    <ElAlert v-if="editingId && resource==='users'" :title="form.status==='INACTIVE' ? `停用即时生效；当前待交接审批 ${pendingApprovalCount ?? '未读取'} 项，请先核实转交。` : `当前待交接审批 ${pendingApprovalCount ?? '未读取'} 项（含待审批记录及待核实更正）。`" :type="form.status==='INACTIVE'?'warning':'info'" :closable="false" class="message" description="在“审批与协调”中完成交接；停用人员不会自动将历史待办转交给其他人。" />
    <form id="master-form" class="master-form" @submit.prevent="save"><div v-if="editingId && resource === 'users'" class="identity-note">内部 ID {{ editingId }} · 修改工号将继续关联同一人。</div><label v-for="field in fields" :key="field.key" :for="`master-${field.key}`">{{ field.label }} <span v-if="field.required" class="required">*</span>
      <ElSelect v-if="field.type === 'select' || field.type === 'multi'" :id="`master-${field.key}`" v-model="form[field.key]" :disabled="saving || (field.key === 'roles' && !!editingId) || (props.scoped && field.key === 'type')" :multiple="field.type === 'multi'" filterable :clearable="!field.required" :aria-label="field.label" :placeholder="`选择${field.label}`"><ElOption v-for="option in field.options" :key="option.value" :value="option.value" :label="option.label" /></ElSelect>
      <input v-else-if="field.type === 'date'" :id="`master-${field.key}`" v-model="form[field.key]" :disabled="saving" type="date" :required="field.required" />
      <input v-else :id="`master-${field.key}`" v-model="form[field.key]" :disabled="saving" :inputmode="field.type === 'money' ? 'decimal' : 'text'" :required="field.required" :maxlength="field.key === 'employeeNo' ? 20 : 200" autocomplete="off" />
      <small v-if="field.key === 'roles' && editingId">已有人员在“负责人与权限范围”中维护角色与范围；资料保存保留全部授权。</small><small v-if="field.hint">{{ field.hint }}</small>
    </label></form><template #footer><ElButton :disabled="saving" @click="close(() => { dialog = false; })">取消</ElButton><ElButton type="primary" native-type="submit" form="master-form" :loading="saving">保存{{ names[resource] }}</ElButton></template>
  </ElDialog>
</template>
