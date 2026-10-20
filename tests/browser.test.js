// Optional real-browser smoke test: install Playwright or set NODE_PATH to its location.
const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const net = require('node:net');
const { spawn } = require('node:child_process');
const root = path.resolve(__dirname, '..');
const output = path.join(root, 'build', 'browser-test');
fs.mkdirSync(output, { recursive: true });

async function freePort() {
  const socket = net.createServer(); await new Promise(resolve => socket.listen(0, '127.0.0.1', resolve));
  const port = socket.address().port; await new Promise(resolve => socket.close(resolve)); return port;
}
async function main() {
  const port = await freePort();
  const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
  const classpath = ['build/classes', '.tools/*'].join(path.delimiter);
  const backend = spawn(java, ['-Xmx512m', '-Djava.awt.headless=true', '-cp', classpath, 'formatfactory.Server'], {
    cwd: root, windowsHide: true, env: { ...process.env, HOST: '127.0.0.1', PORT: String(port), AUTH_MODE: 'development', DATA_DIR: path.join(output, 'data') }
  });
  let logs = ''; backend.stdout.on('data', value => { logs += value; }); backend.stderr.on('data', value => { logs += value; });
  let browser;
  try {
    const base = 'http://127.0.0.1:' + port;
    let ready = false;
    for (let attempt = 0; attempt < 80; attempt++) {
      if (backend.exitCode !== null) throw new Error('Backend exited: ' + logs);
      try { ready = (await fetch(base + '/api/health')).ok; if (ready) break; } catch (_) {}
      await new Promise(resolve => setTimeout(resolve, 250));
    }
    assert.ok(ready, 'backend did not start: ' + logs);
    const edge = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
    const executablePath = process.env.BROWSER_EXECUTABLE_PATH || (fs.existsSync(edge) ? edge : undefined);
    browser = await chromium.launch({ headless: true, executablePath });
    const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, acceptDownloads: true });
    const page = await context.newPage(); const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
    await page.goto(base); await page.getByText('服务已就绪', { exact: true }).waitFor();
    await page.screenshot({ path: path.join(output, 'desktop.png'), fullPage: true });
    assert.equal(await page.locator('#start-button').isDisabled(), true);
    const source = fs.readFileSync(path.join(root, 'build', 'test-data', '原图.png'));
    await page.locator('#file-input').setInputFiles({ name: '测试图片.png', mimeType: 'image/png', buffer: source });
    assert.equal(await page.locator('#start-button').isDisabled(), true, 'consent required');
    await page.locator('#consent').check();
    async function convertAndDownload(file) {
      await page.locator('#start-button').click();
      try { await page.locator('#result-panel').waitFor({ state: 'visible', timeout: 30000 }); }
      catch (error) { throw new Error('Conversion did not finish: ' + await page.locator('#error-message').textContent() + '\n' + logs + '\n' + error.message); }
      assert.equal(await page.locator('#error-message').isVisible(), false);
      const promise = page.waitForEvent('download'); await page.locator('#download-button').click();
      const download = await promise; const target = path.join(output, file); await download.saveAs(target); return target;
    }
    const jpg = await convertAndDownload('compressed.jpg');
    assert.equal(fs.readFileSync(jpg).subarray(0, 2).toString('hex'), 'ffd8');
    await page.screenshot({ path: path.join(output, 'result.png'), fullPage: true });

    await page.locator('[data-operation="convertImage"]').click();
    await page.locator('#output-format').selectOption('png');
    await page.locator('#file-input').setInputFiles(jpg);
    const png = await convertAndDownload('converted.png');
    assert.equal(fs.readFileSync(png).subarray(0, 8).toString('hex'), '89504e470d0a1a0a');

    await page.locator('[data-operation="imagesToPdf"]').click();
    await page.locator('#file-input').setInputFiles([
      { name: '第一张.png', mimeType: 'image/png', buffer: source },
      { name: '第二张.png', mimeType: 'image/png', buffer: source }
    ]);
    await page.getByRole('button', { name: '上移 第二张.png' }).click();
    assert.ok((await page.locator('.file-info strong').first().textContent()).includes('第二张'));
    const pdf = await convertAndDownload('images.pdf'); assert.equal(fs.readFileSync(pdf).subarray(0, 5).toString(), '%PDF-');

    await page.locator('[data-operation="pdfToImages"]').click();
    await page.locator('#file-input').setInputFiles(pdf);
    const zip = await convertAndDownload('pdf-pages.zip'); assert.equal(fs.readFileSync(zip).subarray(0, 2).toString(), 'PK');
    await page.locator('#delete-button').click();
    await page.locator('#result-panel').waitFor({ state: 'hidden' });
    await page.locator('[data-view="history"]').click();
    assert.equal(await page.locator('.history-row').count(), 3);
    await page.locator('#refresh-history').click();
    await page.locator('[data-view="workspace"]').click();

    const fixture = path.join(root, 'build', 'format-test', 'source-png.png');
    const extended = [
      ['bmp', '1'], ['bmp', '4'], ['bmp', '8'], ['bmp', '24'],
      ['dib', '1'], ['dib', '4'], ['dib', '8'], ['dib', '24'], ['gif'], ['tiff'], ['heic']
    ];
    for (const [format, bits] of extended) {
      await page.locator('[data-operation="convertImage"]').click();
      await page.locator('#output-format').selectOption(format);
      if (bits) { assert.equal(await page.locator('#bmp-setting').isVisible(), true); await page.locator('#bmp-bits').selectOption(bits); }
      assert.ok((await page.locator('#frame-note').textContent()).includes('首帧'));
      await page.locator('#file-input').setInputFiles(fixture);
      const converted = await convertAndDownload('ui-' + format + (bits || '') + '.' + format);
      const bytes = fs.readFileSync(converted);
      if (bits) assert.equal(bytes.readUInt16LE(format === 'bmp' ? 28 : 14), Number(bits));
      if (format === 'heic') assert.equal(bytes.subarray(4, 8).toString(), 'ftyp');
      await page.locator('#delete-button').click();
      await page.locator('#result-panel').waitFor({ state: 'hidden' });
      // Use each downloaded file as real input; TIFF/HEIC/DIB should show a format badge.
      await page.locator('[data-operation="convertImage"]').click();
      await page.locator('#output-format').selectOption('png');
      await page.locator('#file-input').setInputFiles(converted);
      const restored = await convertAndDownload('ui-from-' + format + (bits || '') + '.png');
      assert.equal(fs.readFileSync(restored).subarray(0, 8).toString('hex'), '89504e470d0a1a0a');
      await page.locator('#delete-button').click();
      await page.locator('#result-panel').waitFor({ state: 'hidden' });
    }
    await page.locator('[data-operation="convertImage"]').click();
    await page.locator('#output-format').selectOption('dib');
    await page.locator('#bmp-bits').selectOption('4');
    await page.screenshot({ path: path.join(output, 'formats-desktop.png'), fullPage: true });

    await page.locator('[data-operation="compress"]').click();
    await page.locator('#file-input').setInputFiles({ name: '损坏.png', mimeType: 'image/png', buffer: Buffer.from('invalid image') });
    await page.locator('#start-button').click();
    await page.locator('#error-message').waitFor({ state: 'visible', timeout: 30000 });
    assert.ok((await page.locator('#error-message').textContent()).includes('图片内容无效'));
    await page.locator('#clear-files').click();
    await page.setViewportSize({ width: 390, height: 844 });
    await page.screenshot({ path: path.join(output, 'mobile.png'), fullPage: true });
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), 'mobile horizontal overflow');
    assert.deepEqual(errors, [], 'browser runtime errors');
    console.log('PASS: real Edge browser, four tools, all image formats including HEIC, BMP/DIB 1/4/8/24-bit, input round trips, downloads, consent, PDF order, history, deletion, corrupt image, mobile layout');
  } finally {
    if (browser) await browser.close();
    backend.kill();
    await Promise.race([new Promise(resolve => backend.once('exit', resolve)), new Promise(resolve => setTimeout(resolve, 3000))]);
  }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
