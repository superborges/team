<script setup lang="ts">
import ModalShell from './ModalShell.vue';
import './submission.css';
import { onBeforeRouteLeave } from 'vue-router';
import { computed, ref } from 'vue';
import { api, errorMessage, monday, statusLabel, type BatchResult, type Day, type WeekPreview } from '@worktime/api-client';
const props=defineProps<{day:Day;dirty:boolean;disabled:boolean;onsiteTarget?:string}>();const emit=defineEmits<{busy:[value:boolean];refresh:[]}>();
const open=ref(false), preview=ref<WeekPreview>(), result=ref<BatchResult>(), error=ref(''), processing=ref(false), correction=ref(false), record=ref(''), action=ref<'EDIT'|'CANCEL'>('EDIT'), reason=ref(''), message=ref('');
const canSubmit=computed(()=>preview.value?.days.some(d=>d.workReady||d.onsiteReady));
const correctable=computed(()=>[...props.day.entries.filter(e=>e.canRequestCorrection).map(e=>({id:`TIME:${e.id}`,name:`${e.workItemName} · ${e.hours}h`})),...(props.day.onsite?.canRequestCorrection?[{id:`ONSITE:${props.day.onsite.id}`,name:`${props.day.onsite.workItemName} · 现场日`}]:[])]);
function setBusy(v:boolean){processing.value=v;emit('busy',v);}
async function inspect(){if(props.disabled)return;if(props.dirty){error.value='请先保存当天的修改，再预览本周记录内容。';return;}open.value=true;error.value='';result.value=undefined;setBusy(true);try{preview.value=await api.previewWeek(monday(props.day.date));}catch(e){error.value=errorMessage(e);}finally{setBusy(false);}}
async function submit(onsite=false){if(processing.value)return;if(props.dirty){error.value='请先保存当前修改。';return;}if(!window.confirm(onsite?'仅提交当天的现场日审批，工时仍需按周提交。继续？':'将提交本周符合条件的草稿。部分日期或记录不符合要求时，其余符合条件的记录仍会提交审批。继续？'))return;setBusy(true);error.value='';open.value=true;try{result.value=onsite?await api.submitOnsite(props.day.onsite!.id):await api.submitWeek(monday(props.day.date));preview.value=undefined;emit('refresh');}catch(e){error.value=errorMessage(e);}finally{setBusy(false);}}
async function requestCorrection(){setBusy(true);error.value='';try{const[kind,id]=record.value.split(':');await api.requestCorrection(kind as 'TIME'|'ONSITE',id!,action.value,reason.value);message.value='申请已提交，可在审批中心查看核实进度。获准后，还需提交更正或取消记录的审批。';correction.value=false;emit('refresh');}catch(e){error.value=errorMessage(e);}finally{setBusy(false);}}
onBeforeRouteLeave(()=>!processing.value&&(!correction.value||!reason.value||window.confirm('更正申请尚未提交，确定离开？')));
</script>
<template>
<section class="submission-panel workflow-page">
  <p v-if="error&&!open&&!correction" class="workflow-error" role="alert">{{ error }}</p>
  <p v-if="message" class="workflow-success" role="status">{{ message }}</p>
  <Teleport :to="onsiteTarget || 'body'" :disabled="!onsiteTarget" defer>
    <div v-if="day.onsite?.editable" class="onsite-submit workflow-page">
      <button :disabled="disabled||dirty" @click="submit(true)">仅提交当天现场日</button>
      <p class="submission-note">{{ dirty ? '请先保存修改，再单独提交现场日。' : '只提交现场日审批，不受最低应填工时限制。' }}</p>
    </div>
  </Teleport>
  <div class="submission-secondary workflow-actions">
    <button v-if="correctable.length" :disabled="disabled||dirty" @click="correction=true;record=correctable[0]!.id;reason='';error=''">申请更正 / 取消</button>
    <RouterLink to="/approvals">查看审批进度</RouterLink>
  </div>
  <p class="submission-note">工时按自然周（周一至周日）提交，现场日可单独提交审批。已通过和待审批的记录不会重复提交。</p>
  <footer class="submission-actionbar">
    <div class="submission-save-status" aria-live="polite"><slot name="save-status" /></div>
    <div class="submission-main-actions">
      <slot name="save-action" />
      <button class="primary" :disabled="disabled" @click="inspect">预览本周记录</button>
    </div>
  </footer>
<ModalShell v-if="open" :busy="processing" @close="open=false"><div class="workflow-overlay"><section class="workflow-dialog" role="dialog" aria-modal="true" aria-label="本周提交预览"><div class="workflow-between"><h2>{{ result?'本次提交结果':'本周提交预览' }}</h2><button :disabled="processing" @click="open=false">关闭</button></div><p v-if="error" class="workflow-error" role="alert">{{ error }}</p><p v-if="processing" role="status">正在加载或提交，请稍候…</p><template v-if="preview"><p>{{ preview.weekStart }} 开始的自然周（周一至周日）。每条记录的审批人如下。</p><article v-for="item in preview.days" :key="item.date" class="workflow-panel"><div class="workflow-between"><strong>{{ item.date }}</strong><small>{{ item.workReady?'工时可提交':'暂无可提交工时' }} · {{ item.onsiteReady?'现场日可提交':'暂无可提交现场日' }}</small></div><p v-for="(line,i) in item.errors" :key="`e${i}`" class="workflow-error">{{ line }}</p><p v-for="(line,i) in item.warnings" :key="`w${i}`" class="workflow-warning">{{ line }}</p><p v-for="entry in item.workItems" :key="entry.revisionId">{{ entry.workItemName }} · {{ entry.kind==='CANCEL'?'取消申报':`${entry.hours}h` }} → {{ entry.approverName||'尚未设置审批人' }}<small>（{{ entry.routeReason }}）</small></p><p v-if="item.onsite">{{ item.onsite.workItemName }} · 现场日 → {{ item.onsite.approverName||'尚未设置审批人' }}</p></article><button class="primary" :disabled="processing||!canSubmit" @click="submit()">提交符合条件的记录</button></template><template v-if="result"><p class="workflow-note">成功 {{ result.succeeded.length }} 项 · 失败 {{ result.failed.length }} 项 · 无需重复提交 {{ result.unchanged.length }} 项</p><p v-for="(item,i) in result.succeeded" :key="`s${i}`" class="workflow-success">{{ item.date }} · {{ item.message }} <RouterLink v-if="item.packageId" :to="`/approvals/${item.packageId}`">查看审批单</RouterLink></p><p v-for="(item,i) in result.failed" :key="`f${i}`" class="workflow-error">{{ item.date }} · {{ item.message }}</p><p v-for="(item,i) in result.unchanged" :key="`u${i}`">{{ item.date }} · {{ item.message }}</p></template></section></div></ModalShell>
<ModalShell v-if="correction" :busy="processing" @close="correction=false"><div class="workflow-overlay"><section class="workflow-dialog" role="dialog" aria-modal="true" aria-label="申请更正或取消"><h2>申请更正或取消</h2><p>先由原审批人核实申请；同意后生成更正或取消草稿，还需重新提交审批，获批后才生效。日期填错时，需取消原记录，并在正确日期新增。</p><form @submit.prevent="requestCorrection"><label>已通过的记录<select aria-label="已通过的记录" v-model="record" required :disabled="processing"><option v-for="item in correctable" :value="item.id" :key="item.id">{{ item.name }}</option></select></label><label>申请类型<select aria-label="申请类型" v-model="action" :disabled="processing"><option value="EDIT">更正内容或小时数</option><option value="CANCEL">取消申报</option></select></label><label>申请理由<textarea v-model="reason" required rows="4" maxlength="2000" :disabled="processing" /></label><p v-if="error" class="workflow-error">{{ error }}</p><div class="workflow-actions"><button type="button" :disabled="processing" @click="correction=false">取消</button><button class="primary" type="submit" :disabled="processing">提交{{ statusLabel(action) }}申请</button></div></form></section></div></ModalShell></section></template>
