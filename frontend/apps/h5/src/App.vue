<script setup lang="ts">
import { computed, onMounted, provide, ref } from 'vue';
import { Button as VanButton, Field as VanField, NoticeBar as VanNoticeBar } from 'vant';
import { api, ApiError, errorMessage, type AuthStatus, type Me } from '@worktime/api-client';
const operationBusy = ref(false); provide('operationBusy', operationBusy);
const status = ref<AuthStatus>(); const me = ref<Me>(); const busy = ref(true); const error = ref(''); const employeeNo = ref('98123');
provide('me', me);
const demoMode = import.meta.env.MODE === 'demo';
const localLogin = computed(() => (import.meta.env.DEV || demoMode) && status.value?.localLoginEnabled);
async function init() { busy.value = true; error.value = ''; try { status.value = await api.status(); try { me.value = await api.me(); } catch (e) { if (!(e instanceof ApiError && e.status === 401)) throw e; } } catch (e) { error.value = errorMessage(e); } finally { busy.value = false; } }
async function login() { busy.value = true; error.value = ''; try { me.value = await api.login(employeeNo.value); } catch (e) { error.value = errorMessage(e); } finally { busy.value = false; } }
async function logout() { if (operationBusy.value) return; if (!window.confirm('退出登录？未保存的输入将丢失。')) return; try { await api.logout(); me.value = undefined; } catch (e) { error.value = errorMessage(e); } }
onMounted(init);
</script>

<template>
  <div class="mobile-shell">
    <section v-if="!me" class="mobile-login"><div class="login-symbol">时</div><h1>每一天的投入，<br>清楚记录。</h1><p>工时与项目成本管理系统</p><div v-if="error" class="error-message" role="alert">{{ error }}</div>
      <form v-if="localLogin" @submit.prevent="login"><div class="local-badge">{{ demoMode ? '演示环境' : '本地测试' }} · 测试数据</div><label for="test-person">选择测试身份</label><select id="test-person" v-model="employeeNo"><option value="98123">98123 · 员工</option><option value="XX12345">XX12345 · 项目经理</option><option value="ZD23412">ZD23412 · 部门负责人</option><option value="00123">00123 · 管理员</option></select><VanField v-model="employeeNo" label="工号" maxlength="20" autocomplete="off" /><VanButton block type="primary" native-type="submit" :loading="busy">进入我的工时</VanButton></form>
      <p v-else-if="!busy">{{ status?.ssoConfigured ? '请在企业微信中打开公司的工时应用。' : '企业微信登录暂未开通，请联系管理员。' }}</p><VanButton v-if="error" block :loading="busy" @click="init">重新连接</VanButton><p v-if="busy && !localLogin">正在连接服务…</p>
    </section>
    <template v-else><header class="mobile-header"><div><span>工时与投入</span><small>{{ me.name }} / {{ me.employeeNo }}</small></div><button type="button" :disabled="operationBusy" @click="logout">退出</button></header><VanNoticeBar v-if="status?.mode === 'local'" :scrollable="false" wrapable>{{ demoMode ? '演示环境 · 测试数据' : '本地测试环境 · 测试数据' }}</VanNoticeBar><div v-if="error" class="error-message" role="alert">{{ error }}</div><nav class="mobile-nav" aria-label="主导航"><RouterLink to="/">我的工时</RouterLink><RouterLink to="/approvals">审批中心</RouterLink><RouterLink to="/notifications">通知</RouterLink></nav><RouterView /></template>
  </div>
</template>
