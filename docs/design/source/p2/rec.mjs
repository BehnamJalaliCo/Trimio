import pw from 'playwright/index.js'; const { chromium } = pw;
import fs from 'fs';
const [file, outDir, seconds, fps] = process.argv.slice(2);
fs.mkdirSync(outDir, { recursive: true });
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2 });
await page.goto('file://' + file);
await page.evaluate(() => document.fonts.ready);
await page.waitForTimeout(500);
const n = Math.round(+seconds * +fps);
for (let i = 0; i < n; i++) {
  await page.evaluate(t => window.render(t), i * 1000 / +fps);
  await page.screenshot({ path: outDir + '/f' + String(i).padStart(4, '0') + '.png' });
}
await browser.close();
