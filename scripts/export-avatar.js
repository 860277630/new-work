// Optional asset export with Playwright; the app itself does not need this dependency.
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');

async function main() {
  const root = path.resolve(__dirname, '..');
  const output = path.join(root, 'assets', 'branding');
  fs.mkdirSync(output, { recursive: true });
  const svg = fs.readFileSync(path.join(root, 'web', 'favicon.svg'), 'utf8');
  const edge = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
  const browser = await chromium.launch({ headless: true, executablePath: process.env.BROWSER_EXECUTABLE_PATH || (fs.existsSync(edge) ? edge : undefined) });
  try {
    const page = await browser.newPage();
    for (const size of [144, 512]) {
      const data = await page.evaluate(async ({ svg, size }) => {
        const icon = new Image();
        const loaded = new Promise((resolve, reject) => { icon.onload = resolve; icon.onerror = reject; });
        icon.src = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg);
        await loaded;
        const canvas = document.createElement('canvas'); canvas.width = canvas.height = size;
        canvas.getContext('2d').drawImage(icon, 0, 0, size, size);
        return canvas.toDataURL('image/png').split(',')[1];
      }, { svg, size });
      const png = Buffer.from(data, 'base64');
      if (png.subarray(0, 8).toString('hex') !== '89504e470d0a1a0a'
          || png.readUInt32BE(16) !== size || png.readUInt32BE(20) !== size || png.length >= 2 * 1024 * 1024)
        throw new Error('Avatar dimensions, type or file size do not meet the upload requirements');
      const file = path.join(output, 'wechat-avatar-' + size + '.png');
      fs.writeFileSync(file, png);
      console.log(path.basename(file) + ': ' + size + 'x' + size + ' PNG, ' + png.length + ' bytes');
    }
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
