import { chromium } from 'playwright-core';
const browser = await chromium.launch({ executablePath: '/opt/pw-browsers/chromium', args: ['--use-angle=swiftshader','--enable-unsafe-swiftshader'] });
const page = await browser.newPage({ viewport: { width: 640, height: 360 } });
await page.goto('http://localhost:5173/');
await page.waitForFunction(() => window.__oracle3d);
const log = await page.evaluate(() => {
  const o = window.__oracle3d; const out = [];
  o.run(2700, (f) => (f > 300 && f % 120 < 5 ? 128 : 0) | (f > 300 && f % 120 > 60 && f % 120 < 65 ? 16 : 0));
  o.run(3000, (f) => (f % 20 < 2 ? 16 : 0)); out.push('room ' + o.emu.gb.v('wActiveRoom').toString(16));
  for (let i = 0; i < 160; i++) { o.run(1, () => 8); if (i % 4 == 0) out.push([i, o.emu.gb.v('wActiveRoom').toString(16), o.emu.gb.v('wScrollMode'), o.app.link.x, o.app.link.z].join(' ')); }
  return out.join('\n');
});
console.log(log);
await browser.close();
