import { createApp } from 'vue';
import { createRouter, createWebHistory } from 'vue-router';
import 'element-plus/dist/index.css';
import '../../shared/theme.css';
import './style.css';
import '../../shared/workflow.css';
import App from './App.vue';

const router = createRouter({ history: createWebHistory(import.meta.env.BASE_URL), routes: [
  { path: '/', redirect: '/time' },
  { path: '/time', component: () => import('./TimeView.vue'), meta: { title: '我的工时' } },
  { path: '/people', component: () => import('./MasterView.vue'), meta: { title: '人员与组织', manage: true }, props: { section: 'people' } },
  { path: '/work-items', component: () => import('./MasterView.vue'), meta: { title: '项目与事项', manage: true }, props: { section: 'work-items' } },
  { path: '/department-items', component: () => import('./MasterView.vue'), meta: { title: '非项目事项', departmentManage: true }, props: { section: 'work-items', scoped: true } },
  { path: '/rates', component: () => import('./MasterView.vue'), meta: { title: '成本标准' }, props: { section: 'rates' } },
  { path: '/settings', component: () => import('./OperationsView.vue'), meta: { title: '系统设置', manage: true } },
  { path: '/imports', component: () => import('./ImportView.vue'), meta: { title: '数据导入', manage: true } },
  { path: '/governance', component: () => import('./GovernanceView.vue'), meta: { title: '负责人和权限', manage: true } },
  { path: '/coverage', component: () => import('./CoverageView.vue'), meta: { title: '数据完整性核查', manage: true } },
  { path: '/reports/workload', component: () => import('./ReportView.vue'), props: { section: 'workload' }, meta: { title: '人员工时分析' } },
  { path: '/reports/costs', component: () => import('./ReportView.vue'), props: { section: 'costs' }, meta: { title: '项目投入与成本' } },
  { path: '/reports/onsite', component: () => import('./ReportView.vue'), props: { section: 'onsite' }, meta: { title: '现场统计' } },
  { path: '/periods', component: () => import('./ClosingView.vue'), meta: { title: '月度结算' } },
  { path: '/notifications', component: () => import('../../shared/NotificationView.vue'), meta: { title: '我的通知' } },
  { path: '/approvals/:id?', component: () => import('../../shared/ApprovalView.vue'), meta: { title: '审批中心' } },
  { path: '/:pathMatch(.*)*', redirect: '/time' },
] });
createApp(App).use(router).mount('#app');
