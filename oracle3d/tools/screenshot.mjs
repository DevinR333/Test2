// Headless screenshots of the running game, for checking the renderer.
// Usage: node tools/screenshot.mjs <url> <outDir> [script.json]
import { chromium } from 'playwright-core';
import fs from 'node:fs';

const [url = 'http://localhost:5173/', out = 'shots', plan] = process.argv.slice(2);
fs.mkdirSync(out, { recursive: true });
const browser = await chromium.launch({
  executablePath: process.env.CHROMIUM || '/opt/pw-browsers/chromium',
  args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'],
});
const page = await browser.newPage({ viewport: { width: 1280, height: 720 } });
page.on('console', (m) => { if (m.type() === 'error' || m.type() === 'warning') console.log('console:', m.text()); });
page.on('pageerror', (e) => console.log('pageerror:', e.message));
await page.goto(url);
await page.waitForFunction(() => window.__oracle3d, null, { timeout: 600000, polling: 1000 });
// Boot through the title screen and intro.
await page.evaluate(() => window.__oracle3d.run(2700, (f) => {
  let b = 0;
  if (f > 300 && f % 120 < 5) b |= 1 << 7;
  if (f > 300 && f % 120 > 60 && f % 120 < 65) b |= 1 << 4;
  return b;
}));
const shots = plan ? JSON.parse(fs.readFileSync(plan, 'utf8')) : [{ name: 'start' }];
for (const s of shots) {
  await page.evaluate((s) => {
    const o = window.__oracle3d;
    if (s.warp) { o.warp(...s.warp); o.run(60); }
    if (s.walk) o.run(s.walk[1], () => s.walk[0]);
    if (s.cam) Object.assign(o.cam, s.cam);
    o.cam.ready = false;
  }, s);
  await page.waitForTimeout(s.wait || 4000);
  await page.screenshot({ path: `${out}/${s.name}.png` });
  console.log('shot', s.name);
}
await browser.close();
