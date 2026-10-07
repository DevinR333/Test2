// Debug view: the flat overworld map tinted by solved ground level, with cliff (red)
// and stairs (blue) cells outlined. Usage: OUT=x.png node tools/dev/elevmap.mjs season x0 y0 w h
import fs from 'fs'; import { PNG } from 'pngjs';
import { TilesetModel } from '../../src/terrain.js';
import { computeElevation } from '../../src/elevation.js';
const w = JSON.parse(fs.readFileSync('dev-data/world/world.json'));
const pool = PNG.sync.read(fs.readFileSync('dev-data/world/pool.png'));
const [season = 0, x0 = 0, y0 = 0, rw = 16, rh = 16] = process.argv.slice(2).map(Number);
const frames = w.tilesets.map((t) => new Uint16Array(new Uint8Array(Buffer.from(t.frames[0], 'base64')).buffer));
const tilePx = (ts, id) => { const p = frames[ts][id]; const ox = (p % 256) * 16, oy = Math.floor(p / 256) * 16; const out = new Uint8Array(1024);
  for (let y = 0; y < 16; y++) out.set(pool.data.subarray(((oy + y) * pool.width + ox) * 4, ((oy + y) * pool.width + ox + 16) * 4), y * 64); return out; };
const models = {};
const model = (ts) => (models[ts] ||= new TilesetModel((id) => tilePx(ts, id), new Uint8Array(Buffer.from(w.tilesets[ts].collisions, 'base64'))));
const world = w.worlds[0];
// season -1: each area in its default season, as the game shows it
const seasonOf = (r) => (season >= 0 ? season : (w.seasonTable[world.seasons[0][r]?.pack] ?? 0) & 3);
const map = { mtW: 160, mtH: 128, ids: new Uint8Array(160 * 128), tilesets: new Uint16Array(160 * 128).fill(0xffff) };
map.models = new Proxy({}, { get: (_, k) => (Number(k) === 0xffff ? null : model(Number(k))) });
for (let room = 0; room < 256; room++) {
  const r = world.seasons[seasonOf(room)][room]; if (!r) continue;
  const lay = Buffer.from(r.layout, 'base64'); if (lay.every((v) => v === 0x04 || v === 0xf4)) continue;
  for (let y = 0; y < 8; y++) for (let x = 0; x < 10; x++) { const mi = ((room >> 4) * 8 + y) * 160 + (room & 15) * 10 + x; map.ids[mi] = lay[y * 10 + x]; map.tilesets[mi] = r.tileset; }
}
const elev = computeElevation(map, (ts) => w.tilesets[ts].collisionMode);
const S = Number(process.env.S || 2);
const out = new PNG({ width: rw * 160 * S, height: rh * 128 * S });
const pal = [[0, 0, 255], [0, 160, 255], [0, 200, 120], [120, 220, 0], [255, 220, 0], [255, 120, 0], [255, 0, 0], [200, 0, 200]];
for (let y = 0; y < rh * 128; y++) for (let x = 0; x < rw * 160; x++) {
  const mx = x0 * 160 + x, my = y0 * 128 + y;
  const mi = (my >> 4) * 160 + (mx >> 4);
  let r = 0, g = 0, b = 0;
  if (map.tilesets[mi] !== 0xffff) { const px = tilePx(map.tilesets[mi], map.ids[mi]); const p = ((my & 15) * 16 + (mx & 15)) * 4; r = px[p]; g = px[p + 1]; b = px[p + 2]; }
  const ci = (my >> 3) * elev.cw + (mx >> 3);
  const k = elev.kind[ci], lv = elev.level[ci];
  let t = null;
  if (k === 1) t = [255, 0, 0]; else if (k === 2) t = [0, 0, 255];
  else if (lv !== -32768) t = pal[Math.max(0, Math.min(7, Math.round(lv / 16) + 2))];
  if (t) { r = r * 0.55 + t[0] * 0.45; g = g * 0.55 + t[1] * 0.45; b = b * 0.55 + t[2] * 0.45; }
  for (let sy = 0; sy < S; sy++) for (let sx = 0; sx < S; sx++) { const d = ((y * S + sy) * out.width + x * S + sx) * 4; out.data[d] = r; out.data[d + 1] = g; out.data[d + 2] = b; out.data[d + 3] = 255; }
}
fs.writeFileSync(process.env.OUT, PNG.sync.write(out));
// print level legend
console.log('levels: blue=-32 lightblue=-16 teal=0 lime=16 yellow=32 orange=48 red=64 purple=80');
