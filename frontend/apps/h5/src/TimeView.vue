<script setup lang="ts">
import SubmissionPanel from '../../shared/SubmissionPanel.vue';
import { computed, inject, onBeforeUnmount, onMounted, ref, watch, type Ref } from 'vue';
import { onBeforeRouteLeave, useRoute } from 'vue-router';
import { Button as VanButton, Checkbox as VanCheckbox, Field as VanField } from 'vant';
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
// Presentation categories do not change the existing save or validation totals.
function entryType(entry: DraftEntry) { return entry.kind === 'IDLE' ? 'IDLE' : catalog.value.workItems.find(item => item.id === entry.workItemId)?.type; }
const projectHours = computed(() => entries.value.filter(entry => entry.kind !== 'IDLE' && entryType(entry) === 'PROJECT').reduce((sum, entry) => sum + (Number(entry.hours) || 0), 0));
const nonProjectHours = computed(() => entries.value.filter(entry => entry.kind !== 'IDLE' && entryType(entry) === 'NON_PROJECT').reduce((sum, entry) => sum + (Number(entry.hours) || 0), 0));
const unclassifiedHours = computed(() => actual.value - projectHours.value - nonProjectHours.value);
const explanationProgress = computed(() => day.value?.requiredMinutes ? Math.min(100, Math.max(0, total.value * 60 / day.value.requiredMinutes * 100)) : 0);
function categoryLabel(entry: DraftEntry) { const type = entryType(entry); return type === 'PROJECT' ? '项目' : type === 'NON_PROJECT' ? '非项目事项' : type === 'IDLE' ? '待安排工作' : entry.workItemId ? '分类待确认' : '请选择项目或事项'; }
function weekHint(value: Day) { if (value.onsite && !value.totalMinutes) return '现场日'; if (!value.enrolled || !value.requiredMinutes && !value.totalMinutes) return '无需填报'; if (!value.totalMinutes) return '未填'; return value.validation.ready ? '填报完整' : '待完善'; }
const projects = computed(() => catalog.value.workItems.filter(i => i.type === 'PROJECT'));
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
  <main class="mobile-time">
    <div class="mobile-title">
      <div><h1>我的工时</h1><p>按日填写，按周一至周日提交审批。</p></div>
      <button type="button" :disabled="busy" @click="changeDate(today())">今天</button>
    </div>
    <section class="mobile-week" aria-label="自然周概览">
      <div class="mobile-week-title">
        <button type="button" aria-label="上一周" :disabled="busy" @click="changeDate(addDays(date, -7))">‹</button>
        <span>{{ monday(date).slice(5).replace('-', '/') }} 至 {{ addDays(monday(date), 6).slice(5).replace('-', '/') }}</span>
        <button type="button" aria-label="下一周" :disabled="busy" @click="changeDate(addDays(date, 7))">›</button>
        <input type="date" :disabled="busy" aria-label="选择工作日期" :value="date" @change="selectDate" />
      </div>
      <div class="mobile-week-strip">
        <button v-for="(item, index) in week" :key="item.date" type="button" :class="{ selected: date === item.date, weekend: !item.isWorkday, today: item.date === today() }" :aria-label="`${item.date} ${item.date === today() ? '今天 ' : ''}${stateLabel(item)} ${reviewProgress(item)}`" :aria-pressed="date === item.date" :disabled="busy" @click="changeDate(item.date)">
          <span>{{ weekdays[index] }}</span><strong>{{ Number(item.date.slice(8)) }}</strong>
          <small>{{ hours(item.totalMinutes) }}h</small><small class="week-hint">{{ weekHint(item) }}</small>
        </button>
      </div>
    </section>
    <div v-if="error" class="error-message" role="alert">{{ error }}</div>
    <div v-if="conflict" class="conflict-note">记录或月份状态已变化，当前输入已保留。请核对后重新加载，系统不会覆盖其他人的修改。<VanButton size="small" @click="refresh">重新加载当天记录</VanButton></div>
    <div v-if="loading && !day" class="mobile-empty" role="status">正在加载当天记录…</div>
    <VanButton v-if="!day && error" block @click="load()">重试</VanButton>
    <template v-if="day">
      <p v-if="day.entries.some(e => e.action === 'CANCEL') || day.onsite?.action === 'CANCEL'" class="workflow-warning">当天有待提交或待审批的取消记录，已从填报工时合计中排除。请查看本周提交预览，确认取消记录是否已提交审批。</p>
      <section class="mobile-day" :aria-busy="loading" aria-label="当日概览">
        <div class="day-heading"><h2>{{ shortDate }} <small>{{ day.editable ? '可填写' : '只读' }}</small></h2><span>{{ day.isWorkday ? '工作日' : '非工作日' }}</span></div>
        <div class="mobile-totals">
          <div data-summary="required"><span>最低应填</span><strong>{{ hours(day.requiredMinutes) }}<small>h</small></strong></div>
          <div data-summary="explained"><span>已填</span><strong>{{ total }}<small>h</small></strong></div>
          <div data-summary="remaining" :class="{ shortage: total * 60 < day.requiredMinutes }"><span>还需填写</span><strong>{{ Math.max(0, day.requiredMinutes / 60 - total) }}<small>h</small></strong></div>
        </div>
        <div class="day-completeness">
          <div v-if="day.requiredMinutes > 0" class="explanation-track" role="progressbar" aria-label="工时填写进度" :aria-valuenow="explanationProgress" :aria-valuetext="`最低应填 ${hours(day.requiredMinutes)} 小时，已填 ${total} 小时`" :aria-valuemin="0" :aria-valuemax="100"><span :style="{ width: `${explanationProgress}%` }"></span></div>
          <p>{{ day.requiredMinutes === 0 ? '当天没有最低工时要求' : total * 60 >= day.requiredMinutes ? '时长已填齐；提交前还会检查填写内容。' : '请补填实际工作或当天没有任务安排的时间。' }}</p>
        </div>
        <div class="mobile-breakdown" aria-label="时间构成">
          <div data-summary="project"><span>项目工作</span><strong>{{ hours(projectHours * 60) }}<small>h</small></strong></div>
          <div data-summary="non-project"><span>非项目工作</span><strong>{{ hours(nonProjectHours * 60) }}<small>h</small></strong></div>
          <div data-summary="idle"><span>待安排工作</span><strong>{{ hours(idle * 60) }}<small>h</small></strong></div>
          <div data-summary="leave"><span>请假</span><strong>{{ hours(day.leaveMinutes) }}<small>h</small></strong></div>
        </div>
        <div class="summary-caption"><p><span>实际工作 {{ actual }}h</span><span v-if="unclassifiedHours > 0">（含待确认分类 {{ hours(unclassifiedHours * 60) }}h）</span><span v-else>（项目工作＋非项目工作）</span></p><p>以上数据含草稿和未保存的修改；已填工时不含请假。</p></div>
        <p class="day-approval"><span>审批进度</span><strong>{{ reviewProgress(day) || '暂无已提交记录' }}</strong><small v-if="day.periodStatus !== 'OPEN'">{{ day.periodStatus === 'CLOSED' ? '月份已封账' : '已到封账时间' }}</small></p>
        <p v-if="!day.editable" class="read-only">{{ !day.enrolled ? '当天不在你的填报日期范围内。' : '当天记录不可修改，可能已到封账时间。' }}</p>
      </section>
      <section class="mobile-onsite" aria-labelledby="onsite-title">
        <div class="onsite-heading"><h2 id="onsite-title">现场日</h2><span v-if="day.onsite" class="entry-tag" :data-state="day.onsite.state">{{ statusLabel(day.onsite.state) }}</span></div>
        <VanCheckbox aria-label="当天因项目出差" :model-value="!!onsite" :disabled="!day.editable || day.onsite?.editable === false || day.onsite?.action === 'CANCEL' || busy" @update:model-value="toggleOnsite">当天因项目出差</VanCheckbox>
        <p class="onsite-help">含出差交通和因项目需要停留的日期，每人每天最多记录 1 个现场日。工时另填，现场日可单独审批。</p>
        <template v-if="onsite">
          <label for="onsite-project">现场项目</label>
          <select id="onsite-project" v-model="onsite.workItemId" :disabled="!day.editable || day.onsite?.editable === false || day.onsite?.action === 'CANCEL' || busy"><option disabled value="">选择项目</option><option v-for="item in projects" :key="item.id" :value="item.id">{{ item.name }}</option></select>
          <VanField v-model="onsite.reason" type="textarea" rows="2" label="现场说明" placeholder="说明当天的现场工作、出差交通或停留原因" maxlength="200" :disabled="!day.editable || day.onsite?.editable === false || day.onsite?.action === 'CANCEL' || busy" />
          <label v-if="!onsite.id && correctionLinks.length">关联现场日更正申请<select v-model="onsite.correctionRequestId" :disabled="busy"><option :value="null">不关联</option><option v-for="c in correctionLinks.filter(c => c.kind === 'ONSITE')" :key="c.id" :value="c.id">{{ c.date }} · {{ c.workItemName }} #{{ c.id }}</option></select></label>
        </template>
        <div id="h5-onsite-actions" class="onsite-actions"></div>
      </section>
      <div class="records-heading"><h2>当天记录 <small>{{ entries.length }} 条</small></h2><button type="button" :disabled="!day.editable || busy" @click="copyPrevious">{{ copying ? '正在加载…' : '复制上一工作日' }}</button></div>
      <div v-if="!entries.length" class="mobile-empty"><h3>当天还没有记录</h3><p>{{ day.requiredMinutes === 0 ? '当天没有最低工时要求；有工作或出差交通时，请如实填写。' : '添加项目工作、部门事务，或当天没有任务安排的时间。' }}</p><VanButton size="small" type="primary" plain :disabled="!day.editable || busy" @click="addEntry">添加第一条记录</VanButton></div>
      <article v-for="(entry, index) in entries" :key="entry.id ?? `new-${index}`" class="mobile-entry">
        <div class="entry-heading"><strong>记录 {{ index + 1 }}</strong><span class="entry-tag" :class="{ 'entry-category-project': entryType(entry) === 'PROJECT' }">{{ categoryLabel(entry) }}</span><span class="entry-tag approval-state" :data-state="entry.state">{{ statusLabel(entry.state) }}</span><button type="button" :aria-label="`移除记录 ${index + 1}`" :disabled="!day.editable || !entry.editable || busy" @click="entries.splice(index, 1)">移除</button></div>
        <label :for="`entry-${index}-item`">项目或事项</label>
        <select :id="`entry-${index}-item`" v-model="entry.workItemId" :disabled="!day.editable || !entry.editable || busy" @change="changeObject(entry)"><option disabled value="">选择项目或事项</option><option v-for="item in catalog.workItems" :key="item.id" :value="item.id">{{ item.name }}{{ item.type === 'IDLE' ? '（待安排工作）' : '' }}</option></select>
        <div class="mobile-entry-pair">
          <label :for="`entry-${index}-kind`">类型<select :id="`entry-${index}-kind`" v-model="entry.kind" :disabled="!day.editable || !entry.editable || entry.kind === 'IDLE' || busy"><option v-if="entry.kind === 'IDLE'" value="IDLE">待安排工作</option><option value="WORK">实际工作</option><option value="TRAVEL">项目交通</option></select></label>
          <label :for="`entry-${index}-hours`">小时数<input :id="`entry-${index}-hours`" v-model="entry.hours" type="number" :min="(day.stepMinutes??30)/60" :max="(day.dayLimitMinutes??1440)/60" :step="(day.stepMinutes??30)/60" inputmode="decimal" :disabled="!day.editable || !entry.editable || busy" /></label>
        </div>
        <label v-if="!entry.id && correctionLinks.length" class="correction-label">关联更正申请<select v-model="entry.correctionRequestId" :disabled="busy"><option :value="null">不关联</option><option v-for="c in correctionLinks.filter(c => c.kind === 'TIME')" :key="c.id" :value="c.id">{{ c.date }} · {{ c.workItemName }} · {{ statusLabel(c.action) }} #{{ c.id }}</option></select></label>
        <VanField v-model="entry.content" :label="entry.kind === 'IDLE' ? `记录 ${index + 1} 暂无任务的原因` : `记录 ${index + 1} 工作内容`" type="textarea" rows="3" autosize maxlength="200" show-word-limit :aria-label="`记录 ${index + 1} 工作内容`" :placeholder="entry.kind === 'IDLE' ? '暂无任务安排' : '10–200 字，记录实际完成的工作'" :disabled="!day.editable || !entry.editable || busy" />
        <p v-if="Number(entry.hours) > 8" class="field-hint">单条超过 8 小时，请核对。</p>
        <VanField v-if="actual * 60 > (day.redFlagMinutes??960) && entry.kind !== 'IDLE'" v-model="entry.redReason" label="工时过长说明" placeholder="说明当天实际工作时间较长的原因" maxlength="200" :disabled="!day.editable || !entry.editable || busy" />
      </article>
      <VanButton v-if="entries.length" class="add-entry-button" block plain :disabled="!day.editable || busy" @click="addEntry">＋ 添加记录</VanButton>
      <div v-if="!dirty && (day.validation.errors.length || day.validation.warnings.length)" class="validation-note"><strong>保存后发现的问题</strong><p v-for="message in [...day.validation.errors, ...day.validation.warnings]" :key="message">{{ message }}</p></div>
      <p class="mobile-footnote">填报进度和审批进度分别显示。已通过的记录需先申请更正。</p>
      <div v-if="entries.some(e => !e.id) || (onsite && !onsite.id)" class="submission-note"><button type="button" :disabled="busy" @click="loadCorrections">关联日期填错的取消申请</button><p v-if="correctionLinks.length">日期填错时，原记录的取消和正确日期的新记录都需提交审批。请为新记录选择已同意的取消申请。</p></div>
      <SubmissionPanel class="mobile-submission" :day="day" :dirty="dirty" :disabled="busy" onsite-target="#h5-onsite-actions" @busy="processing = $event" @refresh="load()">
        <template #save-status><span :class="{ saved: !!saved }" aria-live="polite">{{ dirty ? '有未保存的修改' : saved || '已加载最新记录' }}</span></template>
        <template #save-action><VanButton :loading="saving" :disabled="!day.editable || conflict || busy" @click="save">保存草稿</VanButton></template>
      </SubmissionPanel>
    </template>
  </main>
</template>
