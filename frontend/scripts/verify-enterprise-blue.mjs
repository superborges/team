// Read-only UI verification. Only local authentication writes are permitted.
import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { chromium, expect } from '@playwright/test';

process.chdir(fileURLToPath(new URL('..', import.meta.url)));
const output = 'design-references/enterprise-blue';
await mkdir(output, { recursive: true });
const browser = await chromium.launch();
const report = { mode: 'real-local-api-read-only', date: '2026-09-05', layouts: [], contrast: [], screenshots: [], pageErrors: [] };

function contrast(a, b) {
  const luminance = color => {
    const c = color.match(/[\d.]+/g).slice(0, 3).map(Number).map(n => n / 255).map(n => n <= .04045 ? n / 12.92 : ((n + .055) / 1.055) ** 2.4);
    return c[0] * .2126 + c[1] * .7152 + c[2] * .0722;
  };
  const values = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (values[0] + .05) / (values[1] + .05);
}
async function capture(page, name, fullPage = false) {
  await page.evaluate(() => { document.activeElement?.blur(); });
  await page.evaluate(() => document.fonts.ready);
  const path = `${output}/${name}.png`;
  await page.screenshot({ path, fullPage, animations: 'disabled' });
  report.screenshots.push(path);
}
try {
  for (const app of ['h5', 'admin']) {
    const context = await browser.newContext({ viewport: { width: app === 'h5' ? 390 : 1440, height: 1000 }, deviceScaleFactor: 1, locale: 'zh-CN' });
    const page = await context.newPage();
    page.setDefaultTimeout(15000);
    page.on('pageerror', error => report.pageErrors.push(error.message));
    // Guard against accidental business mutations while gathering screenshots.
    await page.route('**/api/v1/**', route => {
      const r = route.request(), path = new URL(r.url()).pathname;
      if (!['GET', 'HEAD'].includes(r.method()) && !path.startsWith('/api/v1/auth/')) throw new Error(`Read-only verification blocked ${r.method()} ${path}`);
      return route.continue();
    });
    const base = `http://127.0.0.1:${app === 'h5' ? 5174 : 5175}/${app}/`;
    await page.goto(`${base}${app === 'admin' ? 'time' : ''}?date=${report.date}`);
    await page.locator(app === 'h5' ? '#test-person' : '#employeeNo')[app === 'h5' ? 'selectOption' : 'fill']('98123');
    await page.getByRole('button', { name: app === 'h5' ? '进入我的工时' : '进入系统', exact: true }).click();
    await expect(page.getByRole('textbox', { name: '记录 1 工作内容', exact: true })).toBeVisible();
    for (const width of [320, 375, 390, 768, 1024, 1440]) {
      await page.setViewportSize({ width, height: 1000 });
      const layout = await page.evaluate(() => ({ viewport: innerWidth, document: document.documentElement.scrollWidth }));
      report.layouts.push({ app, ...layout });
      assert.ok(layout.document <= width, `${app} overflow at ${width}`);
    }
    await page.setViewportSize({ width: app === 'h5' ? 390 : 1440, height: 1000 });
    const colors = await page.locator('.submission-main-actions .primary').evaluate(el => {
      const s = getComputedStyle(el); return { color: s.color, background: s.backgroundColor, borderRadius: s.borderRadius };
    });
    const ratio = contrast(colors.color, colors.background);
    report.contrast.push({ app, target: 'week-submit', ...colors, ratio });
    assert.equal(colors.background, 'rgb(22, 100, 255)');
    assert.ok(ratio >= 4.5, `${app} primary contrast ${ratio}`);
    await capture(page, `${app}-timesheet`);
    const height = await page.evaluate(() => document.documentElement.scrollHeight);
    await page.setViewportSize({ width: app === 'h5' ? 390 : 1440, height });
    await capture(page, `${app}-timesheet-full`, true);
    await context.close();
  }
  assert.deepEqual(report.pageErrors, []);
  await writeFile(`${output}/verification.json`, JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report, null, 2));
} finally { await browser.close(); }
