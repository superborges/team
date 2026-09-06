<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { displayTime, errorMessage, request } from '@worktime/api-client';
const busy=ref(false),error=ref(''),rows=ref<{id:string;eventType:string;body:string;path:string;status:string;createdAt:string}[]>([]);
async function load(){busy.value=true;error.value='';try{rows.value=await request('/operations/my-notifications');}catch(e){error.value=errorMessage(e);}finally{busy.value=false;}}
function internalPath(path:string){if(!path.startsWith('/')||path.startsWith('//'))return null;const local=path.replace(/^\/(admin|h5)/,'');if(/^\/(time|work)(\?|$)/.test(local))return local.replace(/^\/(time|work)/,import.meta.env.BASE_URL.includes('/h5')?'/':'/time');if(import.meta.env.BASE_URL.includes('/h5')&&/^\/(reports|periods|settings)/.test(local))return null;return local;}
onMounted(load);
</script>
<template><div class="workflow-page"><div class="workflow-head"><div><h1>我的通知</h1><p>查看本人填报、审批与更正相关提醒。</p></div><button :disabled="busy" @click="load">刷新</button></div><p v-if="error" class="workflow-error" role="alert">{{ error }}</p><div v-if="!rows.length" class="workflow-empty">{{ busy?'正在读取…':'暂无通知。' }}</div><article v-for="item in rows" :key="item.id" class="workflow-panel"><div class="workflow-between"><strong>{{ item.status==='LOCAL'?'本地收件箱':'业务提醒' }}</strong><small>{{ displayTime(item.createdAt) }}</small></div><p class="preserve-lines">{{ item.body }}</p><RouterLink v-if="internalPath(item.path)" :to="internalPath(item.path)!">查看关联记录</RouterLink></article></div></template>
