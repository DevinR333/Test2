// Debug: dump the live tileset (atlas + collisions) from the running app.
import { chromium } from 'playwright-core';
import fs from 'node:fs';
const browser = await chromium.launch({ executablePath: '/opt/pw-browsers/chromium', args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const page = await browser.newPage({ viewport: { width: 640, height: 360 } });
await page.goto('http://localhost:5173/');
await page.waitForFunction(() => window.__oracle3d, null, { timeout: 600000 });
const res = await page.evaluate(() => {
  const o = window.__oracle3d;
  o.run(2700, (f) => (f > 300 && f % 120 < 5 ? 128 : 0) | (f > 300 && f % 120 > 60 && f % 120 < 65 ? 16 : 0));
  o.warp(0, 200, 85); o.run(60);
  const gb = o.emu.gb;
  const m = o.data.models.get(o.data.liveTs);
  const lay = gb.roomLayout();
  return { ids: Array.from(lay.tiles), cls: Array.from(m.cls), color: Array.from(m.color), ground: [...m.ground], room: gb.v('wActiveRoom'), season: gb.v('wRoomStateModifier') };
});
fs.writeFileSync(process.argv[2], JSON.stringify(res));
await browser.close();
