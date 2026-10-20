// Optional Edge/Playwright test; creates and stops only its own temporary backend.
'use strict';
const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const net = require('node:net');
const { spawn } = require('node:child_process');
const root = path.resolve(__dirname, '..');
const output = path.join(root, 'build', 'monitor-browser');
fs.mkdirSync(output, { recursive: true });
const dataDirectory = fs.mkdtempSync(path.join(output, 'data-'));
const adminToken = 'browser-test-admin-token-with-more-than-32-characters';
async function freePort() {
  const socket = net.createServer(); await new Promise(resolve => socket.listen(0, '127.0.0.1', resolve));
  const port = socket.address().port; await new Promise(resolve => socket.close(resolve)); return port;
}
async function main() {
  const port = await freePort(), base = 'http://127.0.0.1:' + port;
  const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
  const environment = { ...process.env, HOST: '127.0.0.1', PORT: String(port), AUTH_MODE: 'development', DATA_DIR: dataDirectory,
    MONITOR_TOKEN: adminToken, MONITOR_INTERVAL_SECONDS: '1', MONITOR_CPU_PERCENT: '80', MONITOR_MEMORY_PERCENT: '1',
    MONITOR_HEAP_PERCENT: '85', MONITOR_CONSECUTIVE_SAMPLES: '2' };
  delete environment.MONITOR_DIR;
  const backend = spawn(java, ['-Xmx512m', '-Djava.awt.headless=true', '-cp', ['build/classes', '.tools/*'].join(path.delimiter), 'formatfactory.Server'], {
    cwd: root, windowsHide: true, env: environment
  });
  let logs = '', browser;
  backend.stdout.on('data', value => { logs += value; }); backend.stderr.on('data', value => { logs += value; });
  try {
    let ready = false;
    for (let attempt = 0; attempt < 80; attempt++) {
      if (backend.exitCode !== null) throw new Error('Backend exited: ' + logs);
      try { ready = (await fetch(base + '/api/health')).ok; if (ready) break; } catch (_) {}
      await new Promise(resolve => setTimeout(resolve, 250));
    }
    assert.ok(ready, 'backend did not start: ' + logs);
    const edge = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
    browser = await chromium.launch({ headless: true, executablePath: process.env.BROWSER_EXECUTABLE_PATH || (fs.existsSync(edge) ? edge : undefined) });
    const context = await browser.newContext({ viewport: { width: 1440, height: 1050 }, acceptDownloads: true });
    const page = await context.newPage(), errors = [];
    page.on('pageerror', error => errors.push(error.message));
    page.on('console', message => { if (message.type() === 'error' && !message.text().includes('403')) errors.push(message.text()); });
    await page.goto(base + '/monitor');
    await page.getByText('需要管理员授权', { exact: true }).waitFor();
    assert.equal(await page.locator('#setting-cpu').isDisabled(), true);
    assert.equal((await fetch(base + '/api/admin/metrics')).status, 403);
    assert.equal((await fetch(base + '/api/admin/metrics', { headers: { Authorization: 'Bearer invalid-token' } })).status, 403);
    const session = await (await fetch(base + '/api/session', { method: 'POST', body: JSON.stringify({ deviceId: 'monitor_browser_user_123' }) })).json();
    assert.equal((await fetch(base + '/api/admin/metrics', { headers: { Authorization: 'Bearer ' + session.token } })).status, 403);
    await page.locator('#monitor-token').fill(adminToken);
    await page.getByRole('button', { name: '授权查看', exact: true }).click();
    await page.waitForFunction(() => document.querySelector('#memory-state').textContent === '告警中');
    assert.equal(await page.locator('#status').textContent(), '资源超限');
    assert.ok((await page.locator('#event-list').textContent()).includes('超限告警'));
    assert.equal(await page.locator('#monitor-token').inputValue(), '');
    assert.equal(await page.evaluate(() => location.search), '');
    assert.equal(await page.evaluate(() => localStorage.length + sessionStorage.length), 0, 'Token was stored in browser');
    await page.locator('#setting-cpu').fill('82');
    // Let a real periodic refresh occur: a form edit must survive updated readings.
    const updated = await page.locator('#updated-at').textContent();
    await page.waitForFunction(value => document.querySelector('#updated-at').textContent !== value, updated);
    assert.equal(await page.locator('#setting-cpu').inputValue(), '82');
    assert.equal(await page.locator('#trend path.line').count(), 3);
    assert.equal(await page.locator('#trend line.threshold').count(), 3);
    await page.screenshot({ path: path.join(output, 'alert-desktop.png'), fullPage: true });
    for (const [id, value] of Object.entries({ 'setting-cpu': 80, 'setting-memory': 85, 'setting-heap': 85, 'setting-interval': 5, 'setting-count': 3 }))
      await page.locator('#' + id).fill(String(value));
    await page.getByRole('button', { name: '保存并应用', exact: true }).click();
    await page.getByText('已保存并应用，重启后继续使用（环境变量可覆盖）。', { exact: true }).waitFor();
    const saved = JSON.parse(fs.readFileSync(path.join(dataDirectory, 'monitor', 'settings.json'), 'utf8'));
    assert.equal(saved.cpuPercent, 80); assert.equal(saved.memoryPercent, 85); assert.equal(saved.heapPercent, 85);
    assert.equal(saved.intervalSeconds, 5); assert.equal(saved.consecutiveSamples, 3);
    assert.ok((await page.locator('#event-list').textContent()).includes('设置变更'));
    assert.equal(await page.locator('#status').textContent(), '监控正常');
    const downloadPromise = page.waitForEvent('download'); await page.locator('#export-samples').click();
    const download = await downloadPromise, csv = path.join(output, 'resource-samples.csv'); await download.saveAs(csv);
    const contents = fs.readFileSync(csv, 'utf8'); assert.ok(contents.includes('cpuPercent')); assert.ok(contents.split('\n').length >= 3);
    await page.screenshot({ path: path.join(output, 'desktop.png'), fullPage: true });
    await page.setViewportSize({ width: 390, height: 844 });
    await page.screenshot({ path: path.join(output, 'mobile.png'), fullPage: true });
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), 'Mobile page overflow');
    assert.deepEqual(errors, [], 'Browser script or CSP errors');
    const names = fs.readdirSync(path.join(dataDirectory, 'monitor'));
    const records = kind => names.filter(name => name.startsWith(kind + '-') && name.endsWith('.jsonl')).flatMap(name =>
      fs.readFileSync(path.join(dataDirectory, 'monitor', name), 'utf8').trim().split('\n').map(line => JSON.parse(line)));
    assert.ok(records('metrics').length >= 3);
    assert.ok(records('alerts').some(event => event.type === 'ALERT'));
    assert.ok(records('alerts').some(event => event.type === 'CONFIG_CHANGED'));
    console.log('PASS: real Edge monitoring, admin authorization, live metrics/alerts, periodic refresh, preserved edits, settings persistence, CSV export, journals and mobile layout');
  } finally {
    if (browser) await browser.close();
    backend.kill();
    if (backend.exitCode === null) await Promise.race([new Promise(resolve => backend.once('exit', resolve)), new Promise(resolve => setTimeout(resolve, 3000))]);
  }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
