import { createApp } from 'vue';
import { createRouter, createWebHistory } from 'vue-router';
import 'vant/lib/index.css';
import '../../shared/theme.css';
import './style.css';
import '../../shared/workflow.css';
import App from './App.vue';
const router = createRouter({ history: createWebHistory(import.meta.env.BASE_URL), routes: [
  { path: '/', alias: ['/work', '/time'], component: () => import('./TimeView.vue') },
  { path: '/notifications', component: () => import('../../shared/NotificationView.vue'), meta: { title: '我的通知' } },
  { path: '/approvals/:id?', component: () => import('../../shared/ApprovalView.vue'), meta: { title: '审批中心' } },
  { path: '/:pathMatch(.*)*', redirect: '/' },
] });
createApp(App).use(router).mount('#app');
