// Debug: in-app chunk data along a map column. Usage: node tools/dev/livecell.mjs room pos x y0 y1
import { chromium } from 'playwright-core';
const [room, pos, X, Y0, Y1] = process.argv.slice(2).map(Number);
const browser = await chromium.launch({ executablePath: '/opt/pw-browsers/chromium', args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const page = await browser.newPage({ viewport: { width: 640, height: 360 } });
await page.goto('http://localhost:5173/');
await page.waitForFunction(() => window.__oracle3d, null, { timeout: 600000 });
const res = await page.evaluate(async ([room, pos, X, Y0, Y1]) => {
  const o = window.__oracle3d;
  o.run(2700, (f) => (f > 300 && f % 120 < 5 ? 128 : 0) | (f > 300 && f % 120 > 60 && f % 120 < 65 ? 16 : 0));
  o.warp(0, room, pos); o.run(60);
  await new Promise((r) => setTimeout(r, 3000));
  const m = o.maps.g0, out = [];
  for (let y = Y0; y < Y1; y += 2) {
    const rx = Math.floor(X / 160), ry = Math.floor(y / 128);
    const c = m.chunks.get(ry * 16 + rx);
    if (!c || !c.heights) { out.push(y + ' no chunk'); continue; }
    const i = (y - ry * 128) * 160 + (X - rx * 160), hs = c.heights;
    const ci = (y >> 3) * m.elev.cw + (X >> 3);
    out.push(`${y}: h${hs.h[i]} ox${hs.ox[i]} oy${hs.oy[i]} wx${hs.wx[i]} wy${hs.wy[i]} kind${m.elev.kind[ci]} lvl${m.elev.level[ci]} ts${m.tilesets[(y >> 4) * m.mtW + (X >> 4)]} id${m.ids[(y >> 4) * m.mtW + (X >> 4)].toString(16)}`);
  }
  return out.join('\n');
}, [room, pos, X, Y0, Y1]);
console.log(res);
await browser.close();
