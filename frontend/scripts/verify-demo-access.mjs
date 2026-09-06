// Run with: node frontend/scripts/verify-demo-access.mjs (prepared demo must be running).
// Uses real HTTPS and browser cookies, never a Basic Auth dialog or business-data writes.
import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import { chromium, webkit, expect } from '@playwright/test';

const privateDir = new URL('../../.local/demo/', import.meta.url);
const config = JSON.parse(await readFile(new URL('config.json', privateDir), 'utf8'));
const base = (await readFile(new URL('url.txt', privateDir), 'utf8')).trim();
assert.equal(new URL(base).protocol, 'https:');
const results = [];
const cases = [
  { name: 'Chrome desktop', engine: chromium, target: 'admin', viewport: { width: 1440, height: 960 } },
  { name: 'Android WeChat UA', engine: chromium, target: 'h5', viewport: { width: 390, height: 844 }, userAgent: 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36 MicroMessenger/8.0.62' },
  { name: 'iOS WeChat UA / WebKit', engine: webkit, target: 'h5', viewport: { width: 390, height: 844 }, userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148 MicroMessenger/8.0.62' },
];

for (const item of cases) {
  const browser = await item.engine.launch({ headless: true });
  try {
    const context = await browser.newContext({ viewport: item.viewport, userAgent: item.userAgent, locale: 'zh-CN' });
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    const url = path => base + path;
    const anonymous = await context.request.get(url('/api/v1/auth/status'));
    assert.equal(anonymous.status(), 401);
    assert.equal((await anonymous.json()).code, 'DEMO_ACCESS_REQUIRED');
    assert.equal(anonymous.headers()['www-authenticate'], undefined);
    assert.equal((await context.request.get(url('/h5/assets/blocked.js'))).status(), 401);
    for (const path of ['/_demo_access', '/demo-access/check', '/actuator/health', '/h5/src/App.vue']) {
      assert.equal((await context.request.get(url(path))).status(), 404, path);
    }
    const missingCsrf = await context.request.post(url('/demo-access/login'), {
      form: { username: config.access_user, password: config.access_password, target: item.target },
    });
    assert.equal(missingCsrf.status(), 403);
    await page.goto(url(`/${item.target}/`));
    await expect(page).toHaveURL(url(`/demo-access/login?target=${item.target}`));
    await expect(page.getByRole('heading', { name: '访问演示' })).toBeVisible();
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    await page.getByLabel('访问账号').fill(config.access_user);
    await page.getByLabel('访问口令').fill('incorrect-demo-password');
    await page.getByRole('button', { name: '进入演示', exact: true }).click();
    await expect(page.getByRole('alert')).toContainText('不正确');
    assert.equal((await context.request.get(url('/api/v1/auth/status'))).status(), 401);
    const previousCookie = (await context.cookies()).find(cookie => cookie.name === 'WORKLOG_DEMO_SESSION');
    await page.getByLabel('访问口令').fill(config.access_password);
    await page.getByRole('button', { name: '进入演示', exact: true }).click();
    const identity = page.locator(item.target === 'h5' ? '#test-person' : '#employeeNo');
    await expect(identity).toBeVisible({ timeout: 20000 });
    const cookie = (await context.cookies()).find(cookie => cookie.name === 'WORKLOG_DEMO_SESSION');
    assert(cookie?.secure && cookie.httpOnly && cookie.sameSite === 'Lax');
    assert.notEqual(cookie.value, previousCookie.value, 'Gate login must rotate the session ID');
    if (item.target === 'h5') await identity.selectOption('98123');
    else await identity.fill('98123');
    await page.getByRole('button', { name: item.target === 'h5' ? '进入我的工时' : '进入工作台', exact: true }).click();
    await expect(page.locator(item.target === 'h5' ? '.mobile-header' : '.account')).toContainText('98123', { timeout: 20000 });
    assert.equal((await context.request.get(url('/api/v1/master/users'))).status(), 403);
    assert.equal((await context.request.post(url('/api/v1/auth/logout'))).status(), 403);
    page.once('dialog', dialog => dialog.accept());
    await page.getByRole('button', { name: '退出', exact: true }).click();
    await expect(identity).toBeVisible({ timeout: 15000 });
    const me = await context.request.get(url('/api/v1/me'));
    assert.equal(me.status(), 401);
    assert.equal((await me.json()).code, 'UNAUTHENTICATED');
    assert.equal((await context.request.get(url('/api/v1/auth/status'))).status(), 200, 'Logout keeps gate access only');
    // Re-selecting a business identity must work after logout with a fresh CSRF token.
    await page.getByRole('button', { name: item.target === 'h5' ? '进入我的工时' : '进入工作台', exact: true }).click();
    await expect(page.locator(item.target === 'h5' ? '.mobile-header' : '.account')).toContainText('98123', { timeout: 15000 });
    await context.clearCookies();
    assert.equal((await context.request.get(url('/api/v1/auth/status'))).status(), 401);
    await page.goto(url(`/${item.target}/`));
    await expect(page.getByRole('heading', { name: '访问演示' })).toBeVisible();
    if (item.engine === webkit) await page.screenshot({ path: new URL('wechat-access-login.png', privateDir).pathname, fullPage: true });
    assert.deepEqual(errors, []);
    results.push({ browser: item.name, target: item.target, passed: true });
    console.log(`${item.name}: access form, CSRF, protected API, login/logout and expiry passed`);
  } finally { await browser.close(); }
}
await writeFile(new URL('wechat-access-checks.json', privateDir), JSON.stringify({ url: base, checkedAt: new Date().toISOString(), results }, null, 2) + '\n', { mode: 0o600 });
