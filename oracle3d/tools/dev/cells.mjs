// Debug: elevation cells (kind/level) around a map pixel. Usage: node tools/dev/cells.mjs season x y [r]
import fs from 'fs'; import { PNG } from 'pngjs';
import { TilesetModel, markGrass } from '../../src/terrain.js';
import { computeElevation } from '../../src/elevation.js';
const w = JSON.parse(fs.readFileSync('dev-data/world/world.json'));
const pool = PNG.sync.read(fs.readFileSync('dev-data/world/pool.png'));
const [season, X, Y, R = 6] = process.argv.slice(2).map(Number);
const frames = w.tilesets.map((t) => new Uint16Array(new Uint8Array(Buffer.from(t.frames[0], 'base64')).buffer));
const tilePx = (ts, id) => { const p = frames[ts][id]; const ox = (p % 256) * 16, oy = Math.floor(p / 256) * 16; const out = new Uint8Array(1024);
  for (let y = 0; y < 16; y++) out.set(pool.data.subarray(((oy + y) * pool.width + ox) * 4, ((oy + y) * pool.width + ox + 16) * 4), y * 64); return out; };
const models = {};
const model = (ts) => { if (!models[ts]) { models[ts] = new TilesetModel((id) => tilePx(ts, id), new Uint8Array(Buffer.from(w.tilesets[ts].collisions, 'base64'))); markGrass(models[ts], w.tilesets[ts].collisionMode); } return models[ts]; };
const world = w.worlds[0];
const map = { mtW: 160, mtH: 128, ids: new Uint8Array(160 * 128), tilesets: new Uint16Array(160 * 128).fill(0xffff) };
map.models = new Proxy({}, { get: (_, k) => (Number(k) === 0xffff ? null : model(Number(k))) });
for (let r = 0; r < 256; r++) {
  const rr = world.seasons[season][r]; if (!rr) continue;
  const lay = Buffer.from(rr.layout, 'base64');
  for (let y = 0; y < 8; y++) for (let x = 0; x < 10; x++) { const mi = ((r >> 4) * 8 + y) * 160 + (r & 15) * 10 + x; map.ids[mi] = lay[y * 10 + x]; map.tilesets[mi] = rr.tileset; }
}
const e = computeElevation(map, (ts) => w.tilesets[ts].collisionMode);
const cx = X >> 3, cy = Y >> 3;
for (let y = cy - R; y <= cy + R; y++) {
  let s = String(y * 8).padStart(5) + ' ';
  for (let x = cx - R; x <= cx + R; x++) {
    const i = y * e.cw + x; const k = e.kind[i]; const l = e.level[i];
    s += (k === 1 ? 'C' : k === 2 ? 'S' : ' ') + (l === -32768 ? '  .' : String(l).padStart(3)) + ' ';
  }
  console.log(s);
}
