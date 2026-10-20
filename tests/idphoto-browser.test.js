// Optional real Edge/Playwright test. Only starts/stops its own temporary backend.
'use strict';
const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const net = require('node:net');
const { spawn } = require('node:child_process');
const root = path.resolve(__dirname, '..');
const output = path.join(root, 'build', 'idphoto-browser');
fs.mkdirSync(output, { recursive: true });

async function main() {
  const socket = net.createServer();
  await new Promise(resolve => socket.listen(0, '127.0.0.1', resolve));
  const port = socket.address().port;
  await new Promise(resolve => socket.close(resolve));
  const base = 'http://127.0.0.1:' + port;
  const dataDirectory = fs.mkdtempSync(path.join(output, 'data-'));
  const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
  const env = { ...process.env, HOST: '127.0.0.1', PORT: String(port), AUTH_MODE: 'development', DATA_DIR: dataDirectory };
  delete env.MONITOR_DIR;
  delete env.MONITOR_TOKEN;
  const backend = spawn(java, ['-Xmx512m', '-Djava.awt.headless=true', '-cp', ['build/classes', '.tools/*'].join(path.delimiter), 'formatfactory.Server'],
    { cwd: root, windowsHide: true, env });
  let logs = '', browser;
  backend.stdout.on('data', value => { logs += value; });
  backend.stderr.on('data', value => { logs += value; });
  try {
    let health;
    for (let attempt = 0; attempt < 80; attempt++) {
      if (backend.exitCode !== null) throw new Error('Backend exited: ' + logs);
      try { health = await (await fetch(base + '/api/health')).json(); break; } catch (_) {}
      await new Promise(resolve => setTimeout(resolve, 250));
    }
    assert.ok(health && health.idPhotoEnabled, 'Local portrait models required');
    const edge = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
    browser = await chromium.launch({ headless: true, executablePath: process.env.BROWSER_EXECUTABLE_PATH || (fs.existsSync(edge) ? edge : undefined) });
    const context = await browser.newContext({ viewport: { width: 1440, height: 1050 }, acceptDownloads: true });
    const page = await context.newPage(), errors = [];
    page.on('pageerror', error => errors.push(error.message));
    page.on('console', message => { if (message.type() === 'error' && message.text().includes('Content Security Policy')) errors.push(message.text()); });
    await page.goto(base);
    await page.getByRole('link', { name: /证件照制作/ }).click();
    await page.waitForFunction(() => document.querySelector('#status').textContent === '本地证件照服务已就绪');
    await page.locator('#photo-file').setInputFiles(path.join(root, 'build/idphoto-test/portrait-fixture.png'));
    assert.equal(await page.locator('#generate').isDisabled(), true, 'Upload consent must be required');
    await page.locator('#consent').check();
    async function generate(width, height, name) {
      await page.locator('#generate').click();
      await page.waitForFunction(() => !document.querySelector('#generate').disabled && document.querySelector('#result-title').textContent === '制作完成', null, { timeout: 90000 });
      assert.equal(await page.locator('#error').textContent(), '');
      await page.waitForFunction(() => document.querySelector('#result-preview').naturalWidth > 0);
      assert.deepEqual(await page.locator('#result-preview').evaluate(img => [img.naturalWidth, img.naturalHeight]), [width, height]);
      const downloadPromise = page.waitForEvent('download');
      await page.locator('#download').click();
      const download = await downloadPromise;
      assert.equal(download.suggestedFilename(), name);
      await download.saveAs(path.join(output, name));
    }
    await generate(295, 413, 'id-photo-25x35.jpg');
    await page.locator('#background').selectOption('blue');
    await page.locator('#format').selectOption('png');
    assert.equal(await page.locator('#result-title').textContent(), '上一次生成的结果');
    await generate(295, 413, 'id-photo-25x35.png');
    await page.screenshot({ path: path.join(output, 'desktop.png'), fullPage: true });
    await page.setViewportSize({ width: 390, height: 844 });
    await page.screenshot({ path: path.join(output, 'mobile.png'), fullPage: true });
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'Mobile overflow');
    await page.locator('#background').selectOption('red');
    await page.locator('#preset').selectOption('35,49');
    assert.match(await page.locator('#dimensions').textContent(), /413 × 579/);

    // A transient result download failure must be recoverable without another inference.
    let injected = false;
    await page.route('**/api/files/*', async route => {
      if (!injected && route.request().method() === 'GET') { injected = true; await route.fulfill({ status: 503, body: 'temporary failure' }); }
      else await route.continue();
    });
    await page.locator('#generate').click();
    await page.locator('#retry-download:not([hidden])').waitFor({ timeout: 90000 });
    await page.waitForFunction(() => !document.querySelector('#retry-download').disabled);
    assert.match(await page.locator('#error').textContent(), /预览下载失败/);
    await page.locator('#retry-download').click();
    await page.waitForFunction(() => !document.querySelector('#download').hidden && !document.querySelector('#generate').disabled);
    await page.waitForFunction(() => document.querySelector('#result-preview').naturalHeight === 579);
    assert.equal(await page.locator('#error').textContent(), '');
    await page.unroute('**/api/files/*');
    await page.locator('#preset').selectOption('custom');
    await page.locator('#width').fill('200');
    let submitted = false;
    const onRequest = request => { if (request.url() === base + '/api/jobs' && request.method() === 'POST') submitted = true; };
    page.on('request', onRequest);
    await page.locator('#generate').click();
    assert.equal(await page.locator('#width').evaluate(input => input.validity.rangeOverflow), true);
    assert.equal(submitted, false, 'Invalid dimensions submitted');
    page.off('request', onRequest);
    await page.locator('#preset').selectOption('25,35');

    // Changing the photo must retain cleanup access to earlier files, even if the new job fails.
    await page.locator('#photo-file').setInputFiles(path.join(root, 'build/idphoto-test/no-face.png'));
    assert.equal(await page.locator('#delete').isVisible(), true);
    await page.locator('#generate').click();
    await page.waitForFunction(() => !document.querySelector('#generate').disabled && document.querySelector('#error').textContent.includes('人脸'), null, { timeout: 90000 });
    const ids = fs.readdirSync(dataDirectory).filter(name => /\.(jpg|png)$/i.test(name));
    assert.ok(ids.length >= 5, 'Upload/result tracking was not exercised');
    await page.locator('#delete').click();
    await page.waitForFunction(() => document.querySelector('#delete').hidden);
    const remainingImages = fs.readdirSync(dataDirectory).filter(name => /\.(jpg|png)$/i.test(name));
    assert.deepEqual(remainingImages, [], 'Uploaded photos or generated files remain after deletion: ' + ids.join(','));
    assert.deepEqual(errors, [], 'Browser JS/CSP errors');
    fs.writeFileSync(path.join(output, 'report.json'), JSON.stringify({ passed: true, filesDeleted: ids.length,
      checks: ['consent', 'real white/JPEG and blue/PNG', 'red two-inch', 'exact pixels', 'download filenames', 'download retry',
        'invalid dimensions', 'no-face error', 'cleanup after changing photos', 'desktop/mobile layout', 'JS/CSP'] }, null, 2));
    console.log('PASS: real Edge ID photo generation, downloads, retry, consent, dimensions, no-face error, complete cleanup and mobile layout');
  } finally {
    if (browser) await browser.close();
    backend.kill();
    if (backend.exitCode === null) await Promise.race([new Promise(resolve => backend.once('exit', resolve)), new Promise(resolve => setTimeout(resolve, 3000))]);
  }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
