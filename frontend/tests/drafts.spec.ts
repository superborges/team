import { expect, test, type Page } from '@playwright/test';
import { addDays, displayTime, monday, today, type Day } from '../packages/api-client/src/index';

// Test-only HTTP fixtures verify browser behavior; the application has no mock transport.
const currentDate = today();
const me = { id: '1', employeeNo: '98123', name: '本地员工', departmentId: '1', departmentName: '测试技术部', roles: ['EMPLOYEE'], canManage: false };
const catalog = { departments: [{ id: '1', code: 'TECH', name: '测试技术部' }], workItems: [{ id: '1', code: 'P001', name: '测试项目', type: 'PROJECT', ownerDepartmentId: '1', approverName: '测试负责人' }] };
const emptyDay = (date: string): Day => ({ date, version: 0, baseMinutes: 480, leaveMinutes: 0, requiredMinutes: 480, isWorkday: true, enrolled: true, editable: true, periodStatus: 'OPEN', entries: [], onsite: null, totalMinutes: 0, actualMinutes: 0, idleMinutes: 0, validation: { ready: false, errors: [], warnings: [] } });

async function server(page: Page, options: { authenticated?: boolean; conflict?: boolean; unavailable?: boolean } = {}) {
  let authenticated = options.authenticated ?? true;
  const writes: { body: Record<string, unknown>; csrf: string | undefined }[] = [];
  await page.route('**/api/v1/**', async route => {
    const request = route.request(); const path = new URL(request.url()).pathname.replace('/api/v1', '');
    if (options.unavailable) return route.fulfill({ status: 503, json: { code: 'UNAVAILABLE', message: '服务暂不可用，请重试。' } });
    if (path === '/auth/status') return route.fulfill({ json: { mode: 'local', localLoginEnabled: true, ssoConfigured: false } });
    if (path === '/auth/csrf') return route.fulfill({ json: { headerName: 'X-CSRF-TOKEN', token: 'test-csrf' } });
    if (path === '/auth/local-login') { authenticated = true; writes.push({ body: request.postDataJSON(), csrf: request.headers()['x-csrf-token'] }); return route.fulfill({ json: me }); }
    if (path === '/me') return route.fulfill(authenticated ? { json: me } : { status: 401, json: { code: 'UNAUTHENTICATED', message: '请登录' } });
    if (path === '/catalog') return route.fulfill({ json: catalog });
    if (path.startsWith('/weeks/')) { const start = path.split('/').pop()!; return route.fulfill({ json: { weekStart: start, days: Array.from({ length: 7 }, (_, i) => emptyDay(addDays(start, i))) } }); }
    if (path.startsWith('/days/')) {
      const date = path.split('/').pop()!;
      if (request.method() === 'PUT') {
        const body = request.postDataJSON(); writes.push({ body, csrf: request.headers()['x-csrf-token'] });
        if (options.conflict) return route.fulfill({ status: 409, json: { code: 'VERSION_CONFLICT', message: '该日期已被修改，请重新读取。' } });
        return route.fulfill({ json: { ...emptyDay(date), version: 1, onsite: body.onsite ? { ...body.onsite, id: '1', revisionId: '1', workItemName: '测试项目', state: 'DRAFT', editable: true } : null } });
      }
      return route.fulfill({ json: emptyDay(date) });
    }
    return route.fulfill({ status: 404, json: { code: 'NOT_FOUND', message: '接口不在测试契约中' } });
  });
  return writes;
}

test('Admin 本地登录携带 CSRF，冲突保留输入，取消日期切换保留日期', async ({ page }) => {
  const writes = await server(page, { authenticated: false, conflict: true });
  await page.goto('http://127.0.0.1:5175/admin/');
  await page.getByRole('button', { name: '进入工作台', exact: true }).click();
  await expect(page.getByRole('heading', { name: '我的工时', exact: true })).toBeVisible();
  expect(writes[0]).toEqual({ body: { employeeNo: '98123' }, csrf: 'test-csrf' });
  await expect(page.getByRole('link', { name: '费率标准' })).toHaveCount(0);
  await page.getByRole('button', { name: '添加第一条记录' }).click();
  await page.getByRole('combobox', { name: '记录 1 归集对象', exact: true }).click();
  await page.getByRole('option', { name: '测试项目', exact: true }).click();
  await page.getByLabel('记录 1 小时数', { exact: true }).fill('4');
  await page.getByLabel('记录 1 工作内容', { exact: true }).fill('完成项目现场设备调试与问题复核');
  await page.getByRole('button', { name: '保存草稿', exact: true }).click();
  await expect(page.getByText('该日期已被修改，请重新读取。', { exact: true })).toBeVisible();
  await expect(page.getByLabel('记录 1 工作内容', { exact: true })).toHaveValue('完成项目现场设备调试与问题复核');
  await expect(page.getByRole('button', { name: '保存草稿', exact: true })).toBeDisabled();
  page.once('dialog', dialog => dialog.dismiss());
  await page.getByLabel('选择工作日期').fill(addDays(currentDate, 1));
  await page.getByLabel('选择工作日期').dispatchEvent('change');
  await expect(page.getByLabel('选择工作日期')).toHaveValue(currentDate);
  expect(writes[1].body.expectedVersion).toBe(0);
  expect(writes[1].csrf).toBe('test-csrf');
  await page.screenshot({ path: 'test-results/admin-draft-conflict.png', fullPage: true });
});

test('H5 独立现场保存不自动补造工时，手机没有横向溢出', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const writes = await server(page);
  await page.goto('http://127.0.0.1:5174/h5/');
  await page.getByRole('checkbox', { name: '当天在项目现场' }).click();
  await page.getByLabel('现场归属项目', { exact: true }).selectOption('1');
  await page.getByRole('textbox', { name: '现场事由' }).fill('设备现场实施，必要驻留');
  await page.getByRole('button', { name: '保存草稿', exact: true }).click();
  await expect(page.getByText(/草稿已保存/)).toBeVisible();
  expect(writes[0].body.entries).toEqual([]);
  expect(writes[0].body.onsite).toEqual({ id: null, workItemId: '1', reason: '设备现场实施，必要驻留' });
  await expect(page.getByText('实际工作 0h', { exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: 'test-results/h5-onsite-draft.png', fullPage: true });
});

test('服务不可用时显示重试，不显示虚构业务数据', async ({ page }) => {
  await server(page, { unavailable: true });
  await page.goto('http://127.0.0.1:5175/admin/');
  await expect(page.getByText('服务暂不可用，请重试。', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '重新连接' })).toBeVisible();
  await expect(page.getByRole('button', { name: '保存草稿' })).toHaveCount(0);
});

test('自然周起点包含周末，日期运算不受本机时区影响', () => {
  expect(monday('2026-09-06')).toBe('2026-08-31');
  expect(addDays('2026-08-31', 6)).toBe('2026-09-06');
  expect(displayTime('2026-09-05T11:47:48')).toBe(displayTime('2026-09-05T11:47:48Z'));
  expect(displayTime('2026-09-05T11:47:48')).toContain('19:47:48');
});
