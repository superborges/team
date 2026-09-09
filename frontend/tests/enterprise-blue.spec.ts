import { expect, test, type Page } from '@playwright/test';
import { addDays, monday, type Day, type Entry } from '../packages/api-client/src/index';

// HTTP fixtures are confined to these tests; screenshots delivered to users use the local API.
const date = '2026-09-04';
const items = [
  { id: '1', name: '项目现场实施与跨区域设备联调及交付验收'.repeat(3), type: 'PROJECT' },
  { id: '2', name: '部门事务与内部知识整理', type: 'NON_PROJECT' },
  { id: '3', name: '待分配时间', type: 'IDLE' },
];
function sampleDay(patch: Partial<Day> = {}): Day {
  const entries: Entry[] = items.map((item, i) => ({
    id: String(i + 1), revisionId: String(i + 10), workItemId: item.id,
    workItemName: item.name, kind: i === 2 ? 'IDLE' : 'WORK', hours: ['4', '1', '3'][i]!,
    minutes: [240, 60, 180][i]!, content: i === 2 ? '暂无任务安排' : '完成设备现场调试与交付资料复核',
    redReason: '', state: 'DRAFT', editable: true,
  }));
  return { date, version: 1, baseMinutes: 480, leaveMinutes: 120, requiredMinutes: 360,
    isWorkday: true, enrolled: true, editable: true, periodStatus: 'OPEN', entries,
    onsite: { id: '1', revisionId: '21', workItemId: '1', workItemName: items[0]!.name,
      reason: '项目现场设备联调与必要驻留', state: 'DRAFT', editable: true },
    totalMinutes: 480, actualMinutes: 300, idleMinutes: 180,
    validation: { ready: true, errors: [], warnings: [] }, ...patch };
}
async function serve(page: Page, readDay: () => Day) {
  const writes: string[] = [];
  await page.route('**/api/v1/**', async route => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace('/api/v1', '');
    if (request.method() === 'POST') writes.push(path);
    if (path === '/auth/status') return route.fulfill({ json: { mode: 'local', localLoginEnabled: true, ssoConfigured: false } });
    if (path === '/me') return route.fulfill({ json: { id: '1', employeeNo: '98123', name: '验证员工', departmentName: '技术部', departmentId: '1', roles: ['EMPLOYEE'], canManage: false } });
    if (path === '/auth/csrf') return route.fulfill({ json: { headerName: 'X-CSRF-TOKEN', token: 'fixture' } });
    if (path === '/reports/capabilities') return route.fulfill({ json: { canReadCosts: false } });
    if (path === '/catalog') return route.fulfill({ json: { workItems: items, departments: [] } });
    if (path.startsWith('/days/')) return route.fulfill({ json: { ...readDay(), date: path.split('/').pop() } });
    if (path.endsWith('/preview')) return route.fulfill({ json: { weekStart: monday(date), days: [{ date, workReady: false, onsiteReady: true, errors: ['当日工时尚未完整'], warnings: [], workItems: [], onsite: { workItemName: items[0]!.name, approverName: '项目负责人' } }] } });
    if (path.endsWith('/submit')) return route.fulfill({ json: { succeeded: [{ id: '1', kind: 'ONSITE', date, message: '现场日已提交审批' }], failed: [], unchanged: [] } });
    if (path.startsWith('/weeks/')) return route.fulfill({ json: { weekStart: monday(date), days: Array.from({ length: 7 }, (_, i) => ({ ...readDay(), date: addDays(monday(date), i) })) } });
    return route.fulfill({ json: [] });
  });
  return writes;
}
const apps = [
  { name: 'H5', url: 'http://127.0.0.1:5174/h5/', onsite: '#h5-onsite-actions' },
  { name: 'Admin', url: 'http://127.0.0.1:5175/admin/time', onsite: '#admin-onsite-actions' },
];

for (const app of apps) {
  test(`${app.name} 企业蓝：4+1+3 与请假独立，六种宽度无全页溢出`, async ({ page }) => {
    await serve(page, () => sampleDay());
    await page.goto(`${app.url}?date=${date}`);
    for (const [name, value] of Object.entries({ required: '6', explained: '8', remaining: '0', project: '4', 'non-project': '1', idle: '3', leave: '2' })) {
      await expect(page.locator(`[data-summary="${name}"]`)).toContainText(new RegExp(`${value}\\s*h`));
    }
    await expect(page.locator(app.name === 'H5' ? '.summary-caption' : '.summary-note')).toContainText(/实际工作\s*5\s*h/);
    await expect(page.getByRole('button', { name: '预览本周记录', exact: true })).toHaveCSS('background-color', 'rgb(22, 100, 255)');
    await expect(page.getByRole('link', { name: '成本标准' })).toHaveCount(0);
    for (const width of [320, 375, 390, 768, 1024, 1440]) {
      await page.setViewportSize({ width, height: 950 });
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), `${app.name} width ${width}`).toBe(true);
      await expect(page.getByRole('button', { name: '保存草稿', exact: true })).toBeVisible();
      await expect(page.getByRole('button', { name: '预览本周记录', exact: true })).toBeVisible();
      expect(await page.locator('[aria-label="自然周概览"] button[aria-pressed]').count()).toBe(7);
    }
  });

  test(`${app.name} 企业蓝：零工时现场独立送审，预览仍取自然周`, async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    const writes = await serve(page, () => sampleDay({ entries: [], totalMinutes: 0, actualMinutes: 0, idleMinutes: 0, requiredMinutes: 480, leaveMinutes: 0, validation: { ready: false, errors: ['工时不足'], warnings: [] } }));
    await page.goto(`${app.url}?date=${date}`);
    const onsiteButton = page.locator(app.onsite).getByRole('button', { name: '仅提交当天现场日', exact: true });
    await expect(onsiteButton).toBeEnabled();
    await page.getByRole('button', { name: '预览本周记录', exact: true }).click();
    const preview = page.getByRole('dialog', { name: '本周提交预览' });
    await expect(preview).toContainText('2026-08-31');
    await preview.getByRole('button', { name: '关闭', exact: true }).click();
    page.once('dialog', dialog => dialog.accept());
    await onsiteButton.click();
    await expect(page.getByRole('dialog', { name: '本周提交预览' })).toContainText('现场日已提交审批');
    expect(writes.filter(path => path.endsWith('/submit'))).toEqual(['/onsite-days/1/submit']);
    expect(writes).toContain('/weeks/2026-08-31/preview');
  });
}

test('H5 企业蓝：零基数与封账待审批分别呈现，已处理条目保持只读', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  const day = sampleDay({ requiredMinutes: 0, baseMinutes: 0, leaveMinutes: 0, isWorkday: false, editable: false, periodStatus: 'CLOSED' });
  day.entries = day.entries.map((entry, i) => ({ ...entry, state: ['PENDING', 'APPROVED', 'REJECTED'][i]!, editable: false }));
  await serve(page, () => day);
  await page.goto(`${apps[0]!.url}?date=${date}`);
  await expect(page.getByText('当天没有最低工时要求', { exact: false })).toBeVisible();
  await expect(page.getByText('月份已封账', { exact: false })).toBeVisible();
  for (const label of ['待审批', '已通过', '已驳回']) await expect(page.locator('.mobile-entry').getByText(label, { exact: true })).toBeVisible();
  await expect(page.getByRole('textbox', { name: '记录 1 工作内容', exact: true })).toBeDisabled();
  await expect(page.getByRole('button', { name: '保存草稿', exact: true })).toBeDisabled();
  await expect(page.locator('body')).not.toContainText(/NaN|Infinity/);
});

test('H5 操作栏：加载不显示零值，输入失焦后可保存，失败保留输入并可重试', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await serve(page, () => sampleDay());
  let release!: () => void;
  const ready = new Promise<void>(resolve => { release = resolve; });
  let saves = 0;
  await page.route(`**/api/v1/days/${date}`, async route => {
    if (route.request().method() === 'GET') { await ready; return route.fulfill({ json: sampleDay() }); }
    saves++;
    if (saves === 1) return route.fulfill({ status: 503, json: { code: 'UNAVAILABLE', message: '保存暂时不可用，请重试' } });
    const body = route.request().postDataJSON();
    return route.fulfill({ json: sampleDay({ version: 2, entries: sampleDay().entries.map((entry, i) => ({ ...entry, ...body.entries[i] })) }) });
  });
  await page.goto(`${apps[0]!.url}?date=${date}`);
  await expect(page.getByRole('status').filter({ hasText: '正在加载当天记录' })).toBeVisible();
  await expect(page.locator('[data-summary="explained"]')).toHaveCount(0);
  release();
  const content = page.getByRole('textbox', { name: '记录 1 工作内容', exact: true });
  await content.fill('核对跨区域设备联调记录并补充验收说明');
  await page.getByRole('button', { name: '保存草稿', exact: true }).click();
  await expect(page.getByRole('alert').filter({ hasText: '保存暂时不可用，请重试' })).toBeVisible();
  await expect(content).toHaveValue('核对跨区域设备联调记录并补充验收说明');
  await page.getByRole('button', { name: '保存草稿', exact: true }).click();
  await expect(page.getByText(/草稿已保存/)).toBeVisible();
  expect(saves).toBe(2);
});
