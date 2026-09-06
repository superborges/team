<script setup lang="ts">
import { computed, inject, onBeforeUnmount, onMounted, ref, watch, type Ref } from 'vue';
import { onBeforeRouteLeave } from 'vue-router';
import { ElAlert, ElButton, ElDialog, ElInput, ElSwitch, ElTable, ElTableColumn, ElTag } from 'element-plus';
import { api, addDays, errorMessage, hours, monday, today, type CalendarDay } from '@worktime/api-client';
const from = ref(monday(today())); const to = computed(() => addDays(from.value, 6)); const rows = ref<CalendarDay[]>([]); const loading = ref(true); const saving = ref(false); const error = ref(''); const formError = ref(''); const dialog = ref(false); const edited = ref<CalendarDay>({ date: today(), isWorkday: true, baseMinutes: 480, note: '' });
const busy=computed(()=>loading.value||saving.value), original=ref('');const sharedBusy=inject<Ref<boolean>>('operationBusy');watch(busy,value=>{if(sharedBusy)sharedBusy.value=value;},{immediate:true});
function mayClose(){return !saving.value&&(!dialog.value||original.value===JSON.stringify(edited.value)||window.confirm('日历修改尚未保存，确定放弃？'));}
function close(done:()=>void){if(mayClose())done();}
onBeforeRouteLeave(()=>!busy.value&&mayClose());onBeforeUnmount(()=>{if(sharedBusy)sharedBusy.value=false;});
async function load(target = from.value) { loading.value = true; error.value = ''; try { rows.value = await api.calendar(target, addDays(target, 6)); from.value = target; } catch (e) { error.value = errorMessage(e); } finally { loading.value = false; } }
function edit(date: string) { const row = rows.value.find(item => item.date === date); if (!row) return; edited.value = { ...row }; original.value=JSON.stringify(edited.value); formError.value = ''; dialog.value = true; }
async function save() { if(saving.value)return; saving.value = true; formError.value = ''; try { await api.saveCalendar(edited.value); dialog.value = false; await load(); } catch (e) { formError.value = errorMessage(e); } finally { saving.value = false; } }
onMounted(() => void load());
</script>

<template>
  <div class="page-head"><div><h1>工作日历</h1><p>维护法定休假与调休安排。日基数变化受月份控制，并留下记录。</p></div><ElButton :disabled="busy" @click="load(monday(today()))">本周</ElButton></div>
  <ElAlert v-if="error" :title="error" type="error" :closable="false" show-icon class="message" />
  <section class="table-panel"><div class="table-toolbar"><div class="calendar-navigation"><ElButton aria-label="上一周" :disabled="busy" @click="load(addDays(from, -7))">‹</ElButton><strong>{{ from }} 至 {{ to }}</strong><ElButton aria-label="下一周" :disabled="busy" @click="load(addDays(from, 7))">›</ElButton></div><ElButton :loading="loading" :disabled="busy" @click="load()">刷新</ElButton></div>
    <ElTable :data="rows" :empty-text="loading ? '正在读取…' : error ? '未能读取日历，请重试' : '没有日历记录'">
      <ElTableColumn prop="date" label="日期" min-width="160" /><ElTableColumn label="日历安排" min-width="160"><template #default="{ row }"><ElTag :type="row.isWorkday ? 'success' : 'info'">{{ row.isWorkday ? '工作日' : '非工作日' }}</ElTag></template></ElTableColumn><ElTableColumn label="基数" min-width="120"><template #default="{ row }">{{ hours(row.baseMinutes) }} h</template></ElTableColumn><ElTableColumn prop="note" label="备注" min-width="230" /><ElTableColumn label="操作" width="90"><template #default="{ row }"><ElButton link type="primary" :disabled="busy" @click="edit(row.date)">调整</ElButton></template></ElTableColumn>
    </ElTable>
  </section><p class="page-footnote">默认周一至周五 8 小时，周末 0 小时。已核实请假在个人应填基数中另行扣减。</p>
  <ElDialog v-model="dialog" title="调整工作日历" width="min(480px, 92vw)" :close-on-click-modal="false" :before-close="close"><ElAlert v-if="formError" :title="formError" type="error" :closable="false" class="message" /><form id="calendar-form" class="master-form" @submit.prevent="save"><strong>{{ edited.date }}</strong><label>日历安排<ElSwitch v-model="edited.isWorkday" :disabled="saving" active-text="工作日" inactive-text="非工作日" @change="edited.baseMinutes = edited.isWorkday ? 480 : 0" /></label><label for="baseMinutes">基数（分钟）<input id="baseMinutes" v-model.number="edited.baseMinutes" :disabled="saving" type="number" min="0" max="1440" step="30" required /></label><label>调整说明<ElInput v-model="edited.note" :disabled="saving" type="textarea" aria-label="调整说明" maxlength="200" :rows="3" /></label></form><template #footer><ElButton :disabled="saving" @click="close(()=>{dialog=false;})">取消</ElButton><ElButton type="primary" form="calendar-form" native-type="submit" :loading="saving">保存日历</ElButton></template></ElDialog>
</template>
