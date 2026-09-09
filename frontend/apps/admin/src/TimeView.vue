<script setup lang="ts">
import SubmissionPanel from '../../shared/SubmissionPanel.vue';
import { computed, inject, onBeforeUnmount, onMounted, ref, watch, type Ref } from 'vue';
import { onBeforeRouteLeave, useRoute } from 'vue-router';
import { ElAlert, ElButton, ElCheckbox, ElInput, ElOption, ElSelect, ElTag } from 'element-plus';
import { api, ApiError, addDays, entryInput, errorMessage, freshEntry, hours, monday, stateLabel, statusLabel, reviewProgress, today, type CorrectionRequest, type Catalog, type Day, type EntryInput, type OnsiteInput } from '@worktime/api-client';
type DraftEntry = EntryInput & { editable: boolean; state: string };
const route = useRoute();
const requestedDate=String(route.query.date??'');
const date = ref(/^\d{4}-\d{2}-\d{2}$/.test(requestedDate)&&!Number.isNaN(Date.parse(requestedDate))?requestedDate:today()); const day = ref<Day>(); const week = ref<Day[]>([]); const catalog = ref<Catalog>({ workItems: [], departments: [] });
const entries = ref<DraftEntry[]>([]); const onsite = ref<OnsiteInput | null>(null); const original = ref('');
const loading = ref(true); const saving = ref(false); const copying = ref(false); const error = ref(''); const saved = ref(''); const conflict = ref(false);
const processing = ref(false); const correctionLoading=ref(false);const correctionLinks=ref<CorrectionRequest[]>([]);
const busy = computed(() => loading.value || saving.value || copying.value || processing.value || correctionLoading.value);
const sharedBusy = inject<Ref<boolean>>('operationBusy');
watch(busy, value => { if (sharedBusy) sharedBusy.value = value; }, { immediate: true });
let retainedOnsite: OnsiteInput | null = null;
const snapshot = () => JSON.stringify({ entries: entries.value.map(entryInput), onsite: onsite.value });
const dirty = computed(() => !!day.value && snapshot() !== original.value);
const total = computed(() => entries.value.reduce((sum, e) => sum + (Number(e.hours) || 0), 0));
const actual = computed(() => entries.value.filter(e => e.kind !== 'IDLE').reduce((sum, e) => sum + (Number(e.hours) || 0), 0));
const idle = computed(() => total.value - actual.value);
const projects = computed(() => catalog.value.workItems.filter(i => i.type === 'PROJECT'));
const timeComposition = computed(() => {
  const result = { project: 0, nonProject: 0, unclassified: 0 };
  for (const entry of entries.value) {
    if (entry.kind === 'IDLE') continue;
    const type = catalog.value.workItems.find(item => item.id === entry.workItemId)?.type;
    result[type === 'PROJECT' ? 'project' : type === 'NON_PROJECT' ? 'nonProject' : 'unclassified'] += Number(entry.hours) || 0;
  }
  return result;
});
const completeness = computed(() => !day.value?.enrolled ? '无需填报' : !day.value.requiredMinutes ? '当天没有最低工时要求' : total.value * 60 < day.value.requiredMinutes ? '还有工时未填' : dirty.value ? '时长已填齐，待保存' : day.value.validation.ready ? '填报完整' : '待完善');
const entryCategory = (entry: DraftEntry) => { const type = catalog.value.workItems.find(item => item.id === entry.workItemId)?.type; return entry.kind === 'IDLE' || type === 'IDLE' ? '待安排工作' : type === 'PROJECT' ? '项目' : type === 'NON_PROJECT' ? '非项目事项' : '请选择项目或事项'; };
const approvalTone = (state: string) => ['APPROVED', 'LOCKED'].includes(state) ? 'success' : state === 'REJECTED' ? 'danger' : state === 'PENDING' ? 'pending' : 'neutral';
const weekdays = ['一', '二', '三', '四', '五', '六', '日'];
const shortDate = computed(() => `${Number(date.value.slice(5, 7))} 月 ${Number(date.value.slice(8))} 日`);
function applyDay(value: Day) { day.value = value; entries.value = value.entries.filter(e => e.action !== 'CANCEL').map(e => ({ ...e })); onsite.value = value.onsite && value.onsite.action !== 'CANCEL' ? { id: value.onsite.id, workItemId: value.onsite.workItemId, reason: value.onsite.reason, ...(value.onsite.correctionRequestId ? {correctionRequestId:value.onsite.correctionRequestId}: {}) } : null; retainedOnsite = onsite.value ? { ...onsite.value } : null; original.value = snapshot(); }
async function load(target = date.value) {
  loading.value = true; error.value = ''; conflict.value = false; saved.value = '';
  try { const [data, span, options] = await Promise.all([api.day(target), api.week(monday(target)), api.catalog()]); date.value = target; applyDay(data); week.value = span.days; catalog.value = options; }
  catch (e) { error.value = errorMessage(e); } finally { loading.value = false; }
}
function mayLeave() { if (busy.value) return false; return !dirty.value || window.confirm('当前日期有未保存的修改。确定放弃修改？'); }
async function changeDate(target: string) { if (!busy.value && target && target !== date.value && mayLeave()) await load(target); }
async function selectDate(event: Event) { const input = event.target as HTMLInputElement; await changeDate(input.value); input.value = date.value; }
async function refresh() { if (mayLeave()) await load(); }
async function loadCorrections(){correctionLoading.value=true;error.value='';try{correctionLinks.value=(await api.corrections('mine')).filter(r=>r.state==='APPROVED'&&r.action==='CANCEL'&&r.date!==date.value);if(!correctionLinks.value.length)error.value='暂无已同意的取消申请。日期填错时，请先申请取消原记录；获准后，在正确日期新增记录并关联该申请。';}catch(e){error.value=errorMessage(e);}finally{correctionLoading.value=false;}}
function addEntry() { if (busy.value) return; entries.value.push({ ...freshEntry(), editable: true, state: 'DRAFT' }); }
function changeObject(entry: DraftEntry) { const item = catalog.value.workItems.find(i => i.id === entry.workItemId); if (item?.type === 'IDLE') { entry.kind = 'IDLE'; entry.content = '暂无任务安排'; } else if (entry.kind === 'IDLE') { entry.kind = 'WORK'; entry.content = ''; } }
function toggleOnsite(value: unknown) { if (busy.value) return; if (!value && onsite.value) retainedOnsite = { ...onsite.value }; onsite.value = value ? retainedOnsite ? { ...retainedOnsite } : { id: null, workItemId: '', reason: '' } : null; }
async function save() {
  if (!day.value || busy.value) return; saving.value = true; error.value = ''; saved.value = ''; conflict.value = false;
  try {
    const result = await api.saveDay(date.value, { expectedVersion: day.value.version, entries: entries.value.map(entryInput), onsite: onsite.value });
    applyDay(result); week.value = week.value.map(d => d.date === result.date ? result : d); saved.value = `草稿已保存 ${new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })}`;
  } catch (e) { error.value = errorMessage(e); conflict.value = e instanceof ApiError && e.status === 409; } finally { saving.value = false; }
}
async function copyPrevious() {
  if (busy.value) return;
  if (!window.confirm('将上一工作日的项目或事项、小时数复制到当前日期，并替换可编辑草稿；已提交记录保留。工作内容需重新填写，没有任务的时间需重新确认。继续复制？')) return;
  copying.value = true; error.value = '';
  try {
    for (let offset = 1; offset <= 14; offset++) {
      const previous = await api.day(addDays(date.value, -offset));
      if (!previous.isWorkday) continue;
      if (!previous.entries.length) { error.value = '上一工作日没有可复制的记录，请添加记录。'; return; }
      const hasIdle = previous.entries.some(e => e.kind === 'IDLE');
      if (hasIdle && !window.confirm('上一工作日有待安排工作的时间。确认当天也有相同小时数没有任务安排？')) return;
      entries.value = [...entries.value.filter(e => !e.editable), ...previous.entries.filter(e => e.action !== 'CANCEL').map(e => ({ ...entryInput(e), correctionRequestId: null, id: null, content: e.kind === 'IDLE' ? '暂无任务安排' : '', redReason: '', editable: true, state: 'DRAFT' }))];
      saved.value = ''; return;
    }
    error.value = '前 14 天没有可复制的工作日，请手动添加记录。';
  } catch (e) { error.value = errorMessage(e); } finally { copying.value = false; }
}
function beforeUnload(event: BeforeUnloadEvent) { if (dirty.value || saving.value || copying.value || processing.value) { event.preventDefault(); event.returnValue = ''; } }
onBeforeRouteLeave(mayLeave);
onMounted(() => { window.addEventListener('beforeunload', beforeUnload); void load(); });
onBeforeUnmount(() => { window.removeEventListener('beforeunload', beforeUnload); if (sharedBusy) sharedBusy.value = false; });
</script>

<template>
  <div class="page-head"><div><h1>我的工时</h1><p>按日填写，按自然周（周一至周日）提交审批。现场日可单独审批。</p></div><div class="date-actions"><ElButton @click="changeDate(today())">回到今天</ElButton><input type="date" :disabled="busy" aria-label="选择工作日期" :value="date" @change="selectDate" /></div></div>
  <section class="week-panel" aria-label="自然周概览">
    <div class="week-title"><ElButton text aria-label="上一周" :disabled="busy" @click="changeDate(addDays(date, -7))">‹</ElButton><strong>{{ monday(date) }} 至 {{ addDays(monday(date), 6) }}</strong><ElButton text aria-label="下一周" :disabled="busy" @click="changeDate(addDays(date, 7))">›</ElButton><span>自然周 · 周一至周日</span></div>
    <div class="week-strip"><button v-for="(item, index) in week" :key="item.date" type="button" :class="{ selected: date === item.date, weekend: !item.isWorkday }" :aria-pressed="date === item.date" :disabled="busy" @click="changeDate(item.date)"><span>周{{ weekdays[index] }}</span><strong>{{ Number(item.date.slice(8)) }}<i v-if="item.date === today()">今</i></strong><small>{{ hours(item.totalMinutes) }}<span class="week-unit"> h</span></small><em>{{ !item.requiredMinutes && item.enrolled ? (item.onsite && !item.totalMinutes ? '现场日' : '无最低工时要求') : stateLabel(item) }}</em><span v-if="item.requiredMinutes > 0" class="day-progress"><i :style="{ width: `${Math.min(100, item.totalMinutes / item.requiredMinutes * 100)}%` }"></i></span></button></div>
  </section>
  <ElAlert v-if="error" :title="error" type="error" :closable="false" show-icon class="message" /><div v-if="conflict" class="conflict-note">记录或月份状态已变化，当前输入已保留。请核对后重新加载，系统不会覆盖其他人的修改。<ElButton @click="refresh">重新加载当天记录</ElButton></div>
  <div v-if="loading && !day" class="empty-state">正在加载当天记录…</div>
  <ElButton v-if="!day && error" @click="load()">重试</ElButton>
  <template v-if="day">
    <p v-if="day.entries.some(e => e.action === 'CANCEL') || day.onsite?.action === 'CANCEL'" class="workflow-warning">当天有待提交或待审批的取消记录，已从填报工时合计中排除。请查看本周提交预览，确认取消记录是否已提交审批。</p>
    <section class="day-panel" :aria-busy="loading">
      <div class="section-heading"><div><h2>{{ shortDate }} <ElTag size="small" type="info">{{ day.isWorkday ? '工作日' : '非工作日' }}</ElTag><ElTag v-if="!day.editable" size="small" type="info">只读</ElTag></h2><p>最低应填工时按工作日历计算，已扣除确认的请假时间。</p></div><div class="summary-states"><span class="summary-completeness">{{ completeness }}</span><span class="approval-state">审批：{{ reviewProgress(day) || '暂无已提交记录' }}</span><span v-if="day.periodStatus !== 'OPEN'" class="period-constraint">{{ day.periodStatus === 'CLOSED' ? '月份已封账' : '已到封账时间' }}</span></div></div>
      <div class="daily-totals"><div data-summary="required"><span>最低应填</span><strong>{{ hours(day.requiredMinutes) }}<small>h</small></strong></div><div data-summary="explained"><span>已填</span><strong>{{ hours(total * 60) }}<small>h</small></strong></div><div data-summary="remaining" :class="{ shortage: total * 60 < day.requiredMinutes }"><span>还需填写</span><strong>{{ hours(Math.max(0, day.requiredMinutes - total * 60)) }}<small>h</small></strong></div></div>
      <div class="time-composition"><div data-summary="project"><span>项目工作</span><strong>{{ hours(timeComposition.project * 60) }}<small>h</small></strong></div><div data-summary="non-project"><span>非项目工作</span><strong>{{ hours(timeComposition.nonProject * 60) }}<small>h</small></strong></div><div data-summary="idle"><span>待安排工作</span><strong>{{ hours(idle * 60) }}<small>h</small></strong></div><div data-summary="leave"><span>请假</span><strong>{{ hours(day.leaveMinutes) }}<small>h</small></strong></div></div>
      <p class="summary-note">实际工作 {{ hours(actual * 60) }} h = 项目工作 + 非项目工作。以上数据包含草稿和未保存的修改；已填工时不含请假。<span v-if="timeComposition.unclassified">另有 {{ hours(timeComposition.unclassified * 60) }} h 待确认项目或事项分类。</span></p>
      <ElAlert v-if="!day.editable" :title="!day.enrolled ? '当天不在你的填报日期范围内。' : '当天记录不可修改，可能已到封账时间。'" type="info" :closable="false" />
    </section>
    <section class="onsite-section" aria-labelledby="admin-onsite-title">
      <div class="onsite-heading"><div><h2 id="admin-onsite-title">现场日</h2><p>出差交通日、因项目需要停留的日期也可记录；工作时间另填工时。</p></div><span v-if="day.onsite" class="entry-status" :class="approvalTone(day.onsite.state)">{{ statusLabel(day.onsite.state) }}{{ day.onsite.action === 'CANCEL' ? ' · 取消申报' : '' }}</span><span v-else-if="onsite" class="entry-status neutral">未保存</span></div>
      <ElCheckbox :model-value="!!onsite" :disabled="!day.editable || day.onsite?.editable === false || day.onsite?.action === 'CANCEL' || busy" @change="toggleOnsite">当天因项目出差（含交通和必要停留）</ElCheckbox>
      <div v-if="onsite" class="onsite-fields"><label>现场项目<ElSelect v-model="onsite.workItemId" placeholder="选择现场项目" aria-label="现场项目" :disabled="!day.editable || day.onsite?.editable === false || day.onsite?.action === 'CANCEL' || busy"><ElOption v-for="item in projects" :key="item.id" :label="item.name" :value="item.id" /></ElSelect></label><label>现场说明<ElInput v-model="onsite.reason" aria-label="现场说明" maxlength="200" placeholder="说明当天的现场工作、出差交通或停留原因" :disabled="!day.editable || day.onsite?.editable === false || day.onsite?.action === 'CANCEL' || busy" /></label></div>
      <label v-if="onsite && !onsite.id && correctionLinks.length" class="content-field">关联现场日更正申请<select aria-label="关联现场日更正申请" v-model="onsite.correctionRequestId" :disabled="busy"><option :value="null">不关联</option><option v-for="c in correctionLinks.filter(c => c.kind === 'ONSITE')" :key="c.id" :value="c.id">{{ c.date }} · {{ c.workItemName }} #{{ c.id }}</option></select></label>
      <p v-if="onsite" class="onsite-note">每人每天最多记录 1 个现场日，不自动增加工时。工时未填完整时，符合条件的现场日仍可单独提交审批。</p>
      <div id="admin-onsite-actions"></div>
    </section>
    <section class="records-panel" :aria-busy="loading" aria-labelledby="admin-records-title">
      <div class="section-heading"><div><h2 id="admin-records-title">当天记录 <small>{{ entries.length }} 条</small></h2><p>填写当天做了什么、用了多久。没有任务安排的时间，选择“待安排工作”。</p></div><ElButton text :disabled="!day.editable || busy" :loading="copying" @click="copyPrevious">复制上一工作日</ElButton></div>
      <div v-if="!entries.length" class="records-empty"><span class="empty-clock" aria-hidden="true">＋</span><h3>这一天，还没有记录</h3><p>添加项目工作、部门事务，或当天没有任务安排的时间。</p><ElButton plain :disabled="!day.editable || busy" @click="addEntry">添加第一条记录</ElButton></div>
      <div v-for="(entry, index) in entries" :key="entry.id ?? `new-${index}`" class="entry-row" :class="{ 'entry-risk': actual * 60 > (day.redFlagMinutes??960) && entry.kind !== 'IDLE' }">
        <div class="entry-top"><strong>记录 {{ index + 1 }}</strong><span class="entry-category" :class="{ project: entryCategory(entry) === '项目' }">{{ entryCategory(entry) }}</span><span class="entry-status" :class="approvalTone(entry.state)">{{ statusLabel(entry.state) }}</span><ElButton text type="danger" :disabled="!day.editable || !entry.editable || busy" :aria-label="`移除记录 ${index + 1}`" @click="entries.splice(index, 1)">移除</ElButton></div>
        <div class="entry-fields">
          <label>项目或事项<ElSelect v-model="entry.workItemId" filterable placeholder="选择项目或事项" :aria-label="`记录 ${index + 1} 项目或事项`" :disabled="!day.editable || !entry.editable || busy" @change="changeObject(entry)"><ElOption v-for="item in catalog.workItems" :key="item.id" :label="`${item.name}${item.type === 'IDLE' ? '（待安排工作）' : ''}`" :value="item.id" /></ElSelect></label>
          <label>类型<ElSelect v-model="entry.kind" :aria-label="`记录 ${index + 1} 类型`" :disabled="!day.editable || !entry.editable || entry.kind === 'IDLE' || busy"><ElOption v-if="entry.kind === 'IDLE'" value="IDLE" label="待安排工作" /><ElOption value="WORK" label="实际工作" /><ElOption value="TRAVEL" label="项目交通" /></ElSelect></label>
          <label>小时数<input v-model="entry.hours" :aria-label="`记录 ${index + 1} 小时数`" type="number" :min="(day.stepMinutes??30)/60" :max="(day.dayLimitMinutes??1440)/60" :step="(day.stepMinutes??30)/60" :disabled="!day.editable || !entry.editable || busy" /></label>
        </div>
        <label v-if="!entry.id && correctionLinks.length" class="content-field">关联更正申请<select v-model="entry.correctionRequestId" :disabled="busy"><option :value="null">不关联</option><option v-for="c in correctionLinks.filter(c => c.kind === 'TIME')" :key="c.id" :value="c.id">{{ c.date }} · {{ c.workItemName }} · {{ statusLabel(c.action) }} #{{ c.id }}</option></select></label>
        <label class="content-field">{{ entry.kind === 'IDLE' ? '暂无任务的原因' : '工作内容' }}<ElInput v-model="entry.content" type="textarea" :rows="4" maxlength="200" show-word-limit :aria-label="`记录 ${index + 1} 工作内容`" :placeholder="entry.kind === 'IDLE' ? '暂无任务安排' : '用 10–200 字说明实际完成的工作，不与前一日完全重复'" :disabled="!day.editable || !entry.editable || busy" /></label>
        <p v-if="entry.kind === 'IDLE'" class="idle-label">没有任务安排的时间由部门负责人确认，不计入实际工作、加班或项目成本。</p>
        <div v-if="Number(entry.hours) > 8" class="field-hint">单条记录超过 8 小时，请核对时间。</div><label v-if="actual * 60 > (day.redFlagMinutes??960) && entry.kind !== 'IDLE'" class="content-field">工时过长说明<ElInput v-model="entry.redReason" maxlength="200" :aria-label="`记录 ${index + 1} 工时过长说明`" placeholder="说明当天实际工作时间较长的原因" :disabled="!day.editable || !entry.editable || busy" /></label>
      </div>
      <div v-if="entries.length" class="add-row"><ElButton plain :disabled="!day.editable || busy" @click="addEntry">＋ 添加记录</ElButton></div>
      <div v-if="!dirty && (day.validation.errors.length || day.validation.warnings.length)" class="validation-note"><strong>保存后发现的问题</strong><p v-for="message in [...day.validation.errors, ...day.validation.warnings]" :key="message">{{ message }}</p></div>
    </section>
    <div v-if="entries.some(e => !e.id) || (onsite && !onsite.id)" class="submission-note"><button type="button" :disabled="busy" @click="loadCorrections">关联日期填错的取消申请</button><p v-if="correctionLinks.length">日期填错时，原记录的取消和正确日期的新记录都需提交审批。请为新记录选择已同意的取消申请。</p></div>
    <SubmissionPanel class="desktop-submission" onsite-target="#admin-onsite-actions" :day="day" :dirty="dirty" :disabled="busy" @busy="processing = $event" @refresh="load()">
      <template #save-status><span :class="saved ? 'save-success' : 'muted'" aria-live="polite">{{ dirty ? '有未保存的修改' : saved || '已加载最新记录' }}</span></template>
      <template #save-action><ElButton :loading="saving" :disabled="!day.editable || conflict || busy" @click="save">保存草稿</ElButton></template>
    </SubmissionPanel>
    <p class="page-footnote">填报进度和审批进度分别显示。已驳回的记录可修改；已通过的记录需先申请更正。</p>
  </template>
</template>
