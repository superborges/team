// Explicit local integration check. Uses real HTTP, MySQL and Redis through the running API.
// Writes the test employee's current empty day and uniquely named local master records.
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
process.chdir(fileURLToPath(new URL('..', import.meta.url)));
import { mkdir, writeFile } from 'node:fs/promises';
import { chromium, expect } from '@playwright/test';
const adminUrl = 'http://127.0.0.1:5175/admin/';
const h5Url = 'http://127.0.0.1:5174/h5/';
const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date());
const suffix = Date.now().toString().slice(-9);
const browser = await chromium.launch();
const evidence = { date: today, mode: 'real-local-api', results: [], pageErrors: [] };
await mkdir('local-verification', { recursive: true });
async function login(page, employeeNo, mobile = false) {
  page.setDefaultTimeout(10000);
  page.on('pageerror', error => evidence.pageErrors.push(error.message));
  await page.goto(mobile ? h5Url : adminUrl);
  const status = await page.evaluate(async () => (await fetch('/api/v1/auth/status')).json());
  assert.equal(status.mode, 'local'); assert.equal(status.localLoginEnabled, true);
  if (mobile) await page.locator('#test-person').selectOption(employeeNo);
  else await page.locator('#employeeNo').fill(employeeNo);
  await page.getByRole('button', { name: mobile ? '进入我的工时' : '进入工作台', exact: true }).click();
  await expect(page.getByRole('heading', { name: '我的工时', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '保存草稿', exact: true })).toBeEnabled();
}
try {
  const employeeContext = await browser.newContext({ viewport: { width: 1440, height: 1050 }, locale: 'zh-CN' });
  const page = await employeeContext.newPage();
  await login(page, '98123');
  const initial = await page.evaluate(async date => (await fetch(`/api/v1/days/${date}`)).json(), today);
  assert.ok(initial.entries.every(row => row.content.startsWith('本地界面验证：') || row.kind === 'IDLE'), 'Do not overwrite other current-day input.');
  const catalog = await page.evaluate(async () => (await fetch('/api/v1/catalog')).json());
  const types = ['PROJECT', 'NON_PROJECT', 'IDLE'];
  const amounts = ['4', '1', '3'];
  if (!initial.entries.length) {
  for (let index = 0; index < 3; index++) {
    await page.getByRole('button', { name: index ? '＋ 添加记录' : '添加第一条记录', exact: true }).click();
    await page.getByRole('combobox', { name: `记录 ${index + 1} 归集对象`, exact: true }).click();
    const item = catalog.workItems.find(value => value.type === types[index]);
    const label = item.name + (types[index] === 'IDLE' ? '（待分配）' : '');
    await page.getByRole('listbox', { name: `记录 ${index + 1} 归集对象`, exact: true }).getByRole('option', { name: label, exact: true }).click();
    await page.getByLabel(`记录 ${index + 1} 小时数`, { exact: true }).fill(amounts[index]);
    if (index < 2) await page.getByRole('textbox', { name: `记录 ${index + 1} 工作内容`, exact: true }).fill(index ? '本地界面验证：整理部门协作与项目问题清单' : '本地界面验证：完成现场设备调试与问题复核');
  }
  await page.getByText('当天在项目现场（含交通与必要驻留）', { exact: true }).click();
  await page.getByText('选择现场归属项目', { exact: true }).click();
  await page.getByRole('listbox', { name: '现场归属项目', exact: true }).getByRole('option', { name: catalog.workItems.find(value => value.type === 'PROJECT').name, exact: true }).click();
  await page.getByRole('textbox', { name: '现场事由', exact: true }).fill('本地界面验证：项目现场设备联调');
  await page.getByRole('button', { name: '保存草稿', exact: true }).click();
  await expect(page.getByText(/草稿已保存/)).toBeVisible();
  }
  const saved = await page.evaluate(async date => (await fetch(`/api/v1/days/${date}`)).json(), today);
  assert.equal(saved.totalMinutes, 480); assert.equal(saved.actualMinutes, 300); assert.equal(saved.idleMinutes, 180);
  assert.equal(typeof saved.entries[0].id, 'string'); assert.ok(saved.onsite.id);
  await page.screenshot({ path: 'local-verification/real-admin-draft.png', fullPage: true });
  evidence.results.push('Admin real login and 4h project + 1h non-project + 3h idle + independent onsite persisted');

  const mobileContext = await browser.newContext({ viewport: { width: 390, height: 844 }, locale: 'zh-CN' });
  const mobile = await mobileContext.newPage();
  await login(mobile, '98123', true);
  await expect(mobile.getByText('实际工作 5h', { exact: true })).toBeVisible();
  await mobile.getByRole('checkbox', { name: '当天在项目现场', exact: true }).click();
  await mobile.getByRole('checkbox', { name: '当天在项目现场', exact: true }).click();
  let releaseResponse;
  const responseGate = new Promise(resolve => { releaseResponse = resolve; });
  await mobile.route(`**/days/${today}`, async route => {
    if (route.request().method() !== 'PUT') return route.continue();
    const response = await route.fetch();
    await responseGate; await route.fulfill({ response });
  });
  await mobile.getByRole('button', { name: '保存草稿', exact: true }).click();
  await expect(mobile.getByLabel('选择工作日期')).toBeDisabled();
  await expect(mobile.getByRole('button', { name: '＋ 添加记录', exact: true })).toBeDisabled();
  await expect(mobile.getByRole('button', { name: '移除记录 1', exact: true })).toBeDisabled();
  await expect(mobile.getByRole('button', { name: '复制上一工作日', exact: true })).toBeDisabled();
  await expect(mobile.getByRole('button', { name: '退出', exact: true })).toBeDisabled();
  releaseResponse();
  await expect(mobile.getByText(/草稿已保存/)).toBeVisible();
  const mobileSaved = await mobile.evaluate(async date => (await fetch(`/api/v1/days/${date}`)).json(), today);
  assert.equal(mobileSaved.onsite.id, saved.onsite.id); assert.equal(mobileSaved.totalMinutes, 480);
  assert.equal(await mobile.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
  await mobile.screenshot({ path: 'local-verification/real-h5-draft.png', fullPage: true });
  evidence.results.push('H5 reads Admin result; onsite off/on retains stable id; save in flight disables date, add/remove/copy and logout');

  const adminContext = await browser.newContext({ viewport: { width: 1440, height: 1050 }, locale: 'zh-CN' });
  const admin = await adminContext.newPage();
  await login(admin, '00123');
  await admin.getByRole('link', { name: '人员与组织', exact: true }).click();
  await expect(admin.getByRole('heading', { name: '人员与组织' })).toBeVisible();
  await admin.getByRole('button', { name: '组织部门', exact: true }).click();
  await admin.getByRole('button', { name: '新增部门', exact: true }).click();
  await admin.locator('#master-code').fill(`UI-${suffix}`);
  await admin.locator('#master-name').fill(`本地界面验证部门${suffix}`);
  await admin.getByRole('button', { name: '保存部门', exact: true }).click();
  await expect(admin.getByText('部门已保存。', { exact: true })).toBeVisible();
  const newRow = admin.getByRole('row').filter({ hasText: `UI-${suffix}` });
  await expect(newRow).toBeVisible();
  await newRow.getByRole('button', { name: '编辑', exact: true }).click();
  await admin.locator('#master-name').fill(`本地界面验证部门${suffix}已调整`);
  await admin.getByRole('button', { name: '保存部门', exact: true }).click();
  await expect(admin.getByText(`本地界面验证部门${suffix}已调整`, { exact: true })).toBeVisible();
  await admin.getByRole('link', { name: '归集对象', exact: true }).click();
  await expect(admin.getByRole('heading', { name: '归集对象', exact: true })).toBeVisible();
  await admin.getByRole('button', { name: '新增归集对象', exact: true }).click();
  await admin.locator('#master-code').fill(`UI-P-${suffix}`);
  await admin.locator('#master-name').fill(`本地界面验证项目${suffix}`);
  await admin.getByRole('combobox', { name: '主责部门', exact: true }).click();
  await admin.getByRole('option', { name: '测试交付部', exact: true }).click();
  await admin.getByRole('button', { name: '保存归集对象', exact: true }).click();
  await expect(admin.getByText('归集对象已保存。', { exact: true })).toBeVisible();
  await admin.getByRole('link', { name: '费率标准', exact: true }).click();
  await expect(admin.getByRole('heading', { name: '费率标准', exact: true })).toBeVisible();
  await expect(admin.getByRole('button', { name: '新增生效版本', exact: true })).toBeEnabled();
  await expect(admin.getByText('初级', { exact: true }).first()).toBeVisible();
  await admin.screenshot({ path: 'local-verification/real-admin-rates.png', fullPage: true });
  await admin.getByRole('link', { name: '工作日历', exact: true }).click();
  await expect(admin.getByRole('heading', { name: '工作日历', exact: true })).toBeVisible();
  await expect(admin.getByRole('button', { name: '调整', exact: true }).first()).toBeVisible();
  evidence.results.push('Admin department create/update, local work item create, rate/calendar reads use real APIs');
  assert.deepEqual(evidence.pageErrors, []);
  console.log(JSON.stringify(evidence, null, 2));
} finally {
  await writeFile('local-verification/real-smoke.json', JSON.stringify(evidence, null, 2));
  await browser.close();
}
