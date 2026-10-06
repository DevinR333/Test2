// Smoke test of the packaged desktop app: pick the ROM, let it build the world, screenshot.
import { _electron as electron } from 'playwright-core';
const [exe, rom, out] = process.argv.slice(2);
const app = await electron.launch({ executablePath: exe, args: ['--no-sandbox', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const win = await app.firstWindow();
win.on('console', (m) => { if (m.type() === 'error') console.log('console:', m.text()); });
win.on('pageerror', (e) => console.log('pageerror:', e.message));
await win.setInputFiles('#romfile', rom);
await win.waitForFunction(() => window.__oracle3d, null, { timeout: 600000, polling: 1000 });
await win.evaluate(() => window.__oracle3d.run(2700, (f) => (f > 300 && f % 120 < 5 ? 128 : 0) | (f > 300 && f % 120 > 60 && f % 120 < 65 ? 16 : 0)));
await win.waitForTimeout(5000);
await win.screenshot({ path: out });
console.log('ok');
await app.close();
