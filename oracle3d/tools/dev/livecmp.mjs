// Debug: compare the live room's layout with the captured one.
import { chromium } from 'playwright-core';
const [group, room, pos] = process.argv.slice(2).map(Number);
const browser = await chromium.launch({ executablePath: '/opt/pw-browsers/chromium', args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const page = await browser.newPage({ viewport: { width: 640, height: 360 } });
await page.goto('http://localhost:5173/');
await page.waitForFunction(() => window.__oracle3d, null, { timeout: 600000 });
const res = await page.evaluate(([group, room, pos]) => {
  const o = window.__oracle3d;
  o.run(2700, (f) => (f > 300 && f % 120 < 5 ? 128 : 0) | (f > 300 && f % 120 > 60 && f % 120 < 65 ? 16 : 0));
  o.warp(group, room, pos); o.run(60);
  const gb = o.emu.gb, lay = gb.roomLayout();
  const rec = o.data.json.worlds[0].seasons[gb.v('wRoomStateModifier')][room];
  const cap = Uint8Array.from(atob(rec.layout), (c) => c.charCodeAt(0));
  const rows = [];
  for (let y = 0; y < 8; y++) { let s = ''; for (let x = 0; x < 10; x++) { const a = lay.tiles[y * 10 + x], b = cap[y * 10 + x]; s += (a === b ? '  ' : '* ') + a.toString(16).padStart(2, '0') + '/' + b.toString(16).padStart(2, '0') + ' '; } rows.push(s); }
  return rows.join('\n') + '\nseason ' + gb.v('wRoomStateModifier');
}, [group, room, pos]);
console.log(res);
await browser.close();
