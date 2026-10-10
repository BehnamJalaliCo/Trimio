import pw from 'playwright/index.js'; const { chromium } = pw;
const [file, out, w] = process.argv.slice(2);
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: parseInt(w||'2400'), height: 990 }, deviceScaleFactor: 2 });
await page.goto('file://' + file);
await page.evaluate(() => document.fonts.ready);
await page.waitForTimeout(700);
await page.screenshot({ path: out, fullPage: true });
await browser.close();
