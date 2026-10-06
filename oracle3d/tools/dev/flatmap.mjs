// Renders the captured overworld as one flat 2D image (debugging aid).
// Usage: OUT=map.png node tools/dev/flatmap.mjs [season] [group] [x0 y0 w h (rooms)]
import fs from 'fs'; import { PNG } from 'pngjs';
const w = JSON.parse(fs.readFileSync('dev-data/world/world.json'));
const pool = PNG.sync.read(fs.readFileSync('dev-data/world/pool.png'));
const [season = 0, group = 0, x0 = 0, y0 = 0, rw = 16, rh = 16] = process.argv.slice(2).map(Number);
const world = w.worlds.find((g) => g.group === group);
const out = new PNG({ width: rw * 160, height: rh * 128 });
const frames = w.tilesets.map((t) => new Uint16Array(new Uint8Array(Buffer.from(t.frames[0], 'base64')).buffer));
for (let ry = 0; ry < rh; ry++) for (let rx = 0; rx < rw; rx++) {
  const r = world.seasons[Math.min(season, world.seasons.length - 1)][(y0 + ry) * 16 + x0 + rx];
  if (!r) continue;
  const lay = Buffer.from(r.layout, 'base64');
  for (let my = 0; my < 8; my++) for (let mx = 0; mx < 10; mx++) {
    const p = frames[r.tileset][lay[my * 10 + mx]];
    const ox = (p % 256) * 16, oy = Math.floor(p / 256) * 16;
    for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) {
      const s = ((oy + y) * pool.width + ox + x) * 4;
      const d = ((ry * 128 + my * 16 + y) * out.width + rx * 160 + mx * 16 + x) * 4;
      for (let c = 0; c < 4; c++) out.data[d + c] = pool.data[s + c];
    }
  }
}
fs.writeFileSync(process.env.OUT, PNG.sync.write(out));
