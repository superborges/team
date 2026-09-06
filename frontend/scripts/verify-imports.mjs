// Real local HTTP integration: download the actual template, upload two text rows, apply/retry.
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
process.chdir(fileURLToPath(new URL('..', import.meta.url)));
import { mkdir, writeFile } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import { chromium, expect } from '@playwright/test';
await mkdir('local-verification', { recursive: true });
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1500, height: 1100 }, locale: 'zh-CN' });
page.setDefaultTimeout(10000);
const evidence = { mode: 'real-local-api', pageErrors: [] };
page.on('pageerror', error => evidence.pageErrors.push(error.message));
try {
  await page.goto('http://127.0.0.1:5175/admin/');
  const status = await page.evaluate(async () => (await fetch('/api/v1/auth/status')).json());
  assert.equal(status.mode, 'local'); assert.equal(status.localLoginEnabled, true);
  await page.locator('#employeeNo').fill('00123');
  await page.getByRole('button', { name: '进入工作台', exact: true }).click();
  await expect(page.getByRole('button', { name: '保存草稿', exact: true })).toBeEnabled();
  await page.getByRole('link', { name: '数据导入', exact: true }).click();
  const downloadEvent = page.waitForEvent('download');
  await page.getByRole('button', { name: '下载模板', exact: true }).click();
  const download = await downloadEvent;
  await download.saveAs('local-verification/project-template.xlsx');
  const code = `UI-IMPORT-${Date.now()}`;
  const date = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date());
  execFileSync('python3', ['-c', String.raw`
import sys, zipfile, xml.etree.ElementTree as ET
template, target, code, date = sys.argv[1:]
ns = 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'
ET.register_namespace('', ns)
with zipfile.ZipFile(template) as source:
    entries = {name:source.read(name) for name in source.namelist()}
sheet_name = next(name for name in entries if name.startswith('xl/worksheets/sheet') and name.endswith('.xml'))
sheet = ET.fromstring(entries[sheet_name])
data = sheet.find('{'+ns+'}sheetData')
rows = [[code,'本地界面导入验证项目','PROJECT','TEST-DELIVERY','00123',date], [code+'-BAD','本地界面导入错误部门样例','PROJECT','UNKNOWN-DEP','00123',date]]
for number, values in enumerate(rows, 2):
    row = ET.SubElement(data, '{'+ns+'}row', {'r':str(number)})
    for index, value in enumerate(values):
        cell = ET.SubElement(row, '{'+ns+'}c', {'r':chr(65+index)+str(number),'t':'inlineStr'})
        ET.SubElement(ET.SubElement(cell, '{'+ns+'}is'), '{'+ns+'}t').text = value
dimension = sheet.find('{'+ns+'}dimension')
if dimension is not None: dimension.set('ref','A1:F3')
entries[sheet_name] = ET.tostring(sheet, encoding='utf-8', xml_declaration=True)
with zipfile.ZipFile(target,'w',zipfile.ZIP_DEFLATED) as out:
    for name, data in entries.items(): out.writestr(name,data)
`, 'local-verification/project-template.xlsx', 'local-verification/project-browser-check.xlsx', code, date]);
  await page.getByLabel('Excel 文件', { exact: true }).setInputFiles('local-verification/project-browser-check.xlsx');
  const previewResponse = page.waitForResponse(response => response.url().includes('/imports?dataset=PROJECT') && response.request().method() === 'POST');
  await page.getByRole('button', { name: '上传并预览', exact: true }).click();
  const previewHttp = await previewResponse; assert.equal(previewHttp.status(), 200);
  const preview = await previewHttp.json();
  const requestHeaders = previewHttp.request().headers();
  assert.ok(requestHeaders['content-type'].startsWith('multipart/form-data; boundary='));
  assert.ok(requestHeaders['x-csrf-token']);
  assert.equal(preview.validCount, 1); assert.equal(preview.errorCount, 1);
  await expect(page.locator('.import-preview')).toContainText('问题 1 行');
  const applyResponse = page.waitForResponse(response => response.url().endsWith(`/imports/${preview.id}/apply`));
  page.once('dialog', dialog => dialog.accept());
  await page.getByRole('button', { name: '确认应用有效行', exact: true }).click();
  const appliedHttp = await applyResponse; assert.equal(appliedHttp.status(), 200);
  const applied = await appliedHttp.json(); assert.equal(applied.state, 'PARTIAL'); assert.equal(applied.appliedCount, 1); assert.equal(applied.errorCount, 1);
  await expect(page.locator('.import-preview')).toContainText('已应用 1 行');
  const retryResponse = page.waitForResponse(response => response.url().endsWith(`/imports/${preview.id}/apply`));
  page.once('dialog', dialog => dialog.accept());
  await page.getByRole('button', { name: '重新校验并应用', exact: true }).click();
  const retried = await (await retryResponse).json(); assert.equal(retried.appliedCount, 1); assert.equal(retried.errorCount, 1);
  const projectCount = await page.evaluate(async code => (await (await fetch('/api/v1/master/work-items')).json()).filter(row => row.code === code).length, code);
  assert.equal(projectCount, 1);
  await expect(page.getByRole('button', { name: '重新校验并应用', exact: true })).toBeEnabled();
  await page.screenshot({ path: 'local-verification/real-admin-import-partial.png', fullPage: true });
  assert.deepEqual(evidence.pageErrors, []);
  Object.assign(evidence, { batchId: preview.id, state: retried.state, appliedCount: retried.appliedCount, errorCount: retried.errorCount, projectCount, multipartBoundary: true, csrfHeader: true, result: 'real template download, preview, partial apply, retry idempotency passed' });
  console.log(JSON.stringify(evidence, null, 2));
} catch (error) {
  evidence.failure = String(error);
  await page.screenshot({ path: 'local-verification/real-import-failure.png', fullPage: true });
  throw error;
} finally {
  await writeFile('local-verification/real-import-smoke.json', JSON.stringify(evidence, null, 2));
  await browser.close();
}
