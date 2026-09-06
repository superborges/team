<script setup lang="ts">
import { computed, onMounted, provide, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElAlert, ElButton, ElIcon, ElInput, ElOption, ElSelect } from 'element-plus';
import { Bell, Calendar, Checked, DataAnalysis, Document, Clock, Coin, FolderOpened, Upload, User } from '@element-plus/icons-vue';
import { api, ApiError, errorMessage, type AuthStatus, request, type Me } from '@worktime/api-client';
const route = useRoute(); const router = useRouter();
const operationBusy = ref(false); provide('operationBusy', operationBusy);
const status = ref<AuthStatus>(); const me = ref<Me>(); const busy = ref(true); const error = ref(''); const employeeNo = ref('98123');
const canReadCosts=ref(false);
async function updateCapabilities(){try{canReadCosts.value=(await request<{canReadCosts:boolean}>('/reports/capabilities')).canReadCosts;}catch{canReadCosts.value=false;}}
const demoMode = import.meta.env.MODE === 'demo';
const localLogin = computed(() => (import.meta.env.DEV || demoMode) && status.value?.localLoginEnabled);
provide('me', me);
async function init() {
  busy.value = true; error.value = '';
  try { status.value = await api.status(); try { me.value = await api.me(); void updateCapabilities(); } catch (e) { if (!(e instanceof ApiError && e.status === 401)) throw e; } }
  catch (e) { error.value = errorMessage(e); } finally { busy.value = false; }
}
async function login() { busy.value = true; error.value = ''; try { me.value = await api.login(employeeNo.value); void updateCapabilities(); } catch (e) { error.value = errorMessage(e); } finally { busy.value = false; } }
async function logout() { if (operationBusy.value) return; if (!window.confirm('退出登录？未保存的输入将丢失。')) return; try { await api.logout(); me.value = undefined; await router.push('/time'); } catch (e) { error.value = errorMessage(e); } }
onMounted(init);
</script>

<template>
  <div v-if="!me" class="login-page">
    <div class="login-story"><div class="brand-mark">时</div><h1>每一天的投入，<br>清楚记录。</h1><p>从个人记录到项目投入，让工作事实有所依据。</p><div class="login-line">工时与项目成本管理系统</div></div>
    <section class="login-form">
      <span class="app-label">工时与投入</span><h2>{{ localLogin ? (demoMode ? '进入演示工作台' : '进入本地工作台') : '登录工作台' }}</h2>
      <p v-if="localLogin" class="muted">当前为{{ demoMode ? '演示' : '本地验证' }}环境，以下身份与业务数据仅用于功能验证。</p>
      <ElAlert v-if="error" :title="error" type="error" :closable="false" show-icon />
      <form v-if="localLogin" @submit.prevent="login">
        <label for="employeeNo">测试身份</label>
        <ElSelect v-model="employeeNo" aria-label="测试身份" class="full"><ElOption label="98123 · 员工" value="98123" /><ElOption label="XX12345 · 项目经理" value="XX12345" /><ElOption label="ZD23412 · 部门负责人" value="ZD23412" /><ElOption label="00123 · 管理员" value="00123" /></ElSelect>
        <label for="employeeNo">工号</label><ElInput id="employeeNo" v-model="employeeNo" maxlength="20" autocomplete="off" />
        <ElButton type="primary" native-type="submit" :loading="busy" class="full login-submit">进入工作台</ElButton>
      </form>
      <p v-else-if="!busy" class="muted">{{ status?.ssoConfigured ? '请从公司企业微信自建应用进入。' : '企微登录尚未配置，请联系管理员完成接入。' }}</p>
      <ElButton v-if="error" :loading="busy" @click="init">重新连接</ElButton>
      <p v-if="busy && !localLogin" class="muted">正在连接服务…</p>
    </section>
  </div>
  <div v-else class="workspace">
    <header class="topbar"><div class="header-brand"><span class="brand-mark">时</span><strong>工时与投入</strong><span class="header-divider"></span><span class="header-context">{{ route.meta.title }}</span></div><div class="account"><span class="avatar">{{ me.name.slice(-2) }}</span><span>{{ me.name }}<small>{{ me.departmentName }} / {{ me.employeeNo }}</small></span><ElButton text :disabled="operationBusy" @click="logout">退出</ElButton></div></header>
    <aside class="sidebar">
      <nav aria-label="主导航"><RouterLink to="/time"><ElIcon aria-hidden="true"><Clock /></ElIcon>我的工时</RouterLink><RouterLink to="/approvals"><ElIcon aria-hidden="true"><Checked /></ElIcon>审批与协调</RouterLink><RouterLink to="/notifications"><ElIcon aria-hidden="true"><Bell /></ElIcon>我的通知</RouterLink><RouterLink v-if="!me.canManage && me.roles.includes('DEPARTMENT_MANAGER')" to="/department-items"><ElIcon aria-hidden="true"><FolderOpened /></ElIcon>非项目事项</RouterLink><p class="nav-section">分析与月报</p><RouterLink to="/reports/workload"><ElIcon aria-hidden="true"><DataAnalysis /></ElIcon>人员负荷与履约</RouterLink><RouterLink v-if="canReadCosts" to="/reports/costs"><ElIcon aria-hidden="true"><Coin /></ElIcon>项目投入与成本</RouterLink><RouterLink to="/reports/onsite"><ElIcon aria-hidden="true"><FolderOpened /></ElIcon>现场台账与占比</RouterLink><RouterLink to="/periods"><ElIcon aria-hidden="true"><Document /></ElIcon>月报与修订</RouterLink><RouterLink v-if="me.roles.some(r => ['ADMIN','PM','DEPARTMENT_MANAGER','LEADER'].includes(r))" to="/rates"><ElIcon aria-hidden="true"><Coin /></ElIcon>费率标准</RouterLink><template v-if="me.canManage"><p class="nav-section">基础管理</p><RouterLink to="/people"><ElIcon aria-hidden="true"><User /></ElIcon>人员与组织</RouterLink><RouterLink to="/work-items"><ElIcon aria-hidden="true"><FolderOpened /></ElIcon>归集对象</RouterLink><RouterLink to="/imports"><ElIcon aria-hidden="true"><Upload /></ElIcon>数据导入</RouterLink><RouterLink to="/coverage"><ElIcon aria-hidden="true"><Checked /></ElIcon>来源覆盖核实</RouterLink><RouterLink to="/settings"><ElIcon aria-hidden="true"><Calendar /></ElIcon>规则与运行</RouterLink></template></nav>
      <div class="sidebar-foot">以实际工作为准<br>草稿保存后可继续完善</div>
    </aside>
    <div class="main-shell">
      <div v-if="status?.mode === 'local'" class="environment-note"><span class="status-dot"></span>{{ demoMode ? '演示环境 · 测试数据' : '本地验证环境 · 使用测试身份与测试标准' }} · 业务结果以实际服务响应为准</div>
      <ElAlert v-if="error" :title="error" type="error" :closable="false" />
      <main :class="{ 'time-workspace': route.path === '/time' }"><div v-if="(route.meta.manage && !me.canManage) || (route.meta.departmentManage && !me.canManage && !me.roles.includes('DEPARTMENT_MANAGER'))" class="empty-state"><h2>暂无此页面的访问权限</h2><p>基础管理仅向已授权人员开放。</p><RouterLink to="/time">返回我的工时</RouterLink></div><RouterView v-else :key="route.path" /></main>
    </div>
  </div>
</template>
