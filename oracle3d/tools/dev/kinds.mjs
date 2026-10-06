// Debug: which pixels of one room become ground / object / leafy / cliff, and the top
// heights. Usage: OUT=x.png node tools/dev/kinds.mjs season room
import fs from 'fs'; import { PNG } from 'pngjs';
import { TilesetModel, buildChunkHeights } from '../../src/terrain.js';
import { computeElevation } from '../../src/elevation.js';
const w = JSON.parse(fs.readFileSync('dev-data/world/world.json'));
const pool = PNG.sync.read(fs.readFileSync('dev-data/world/pool.png'));
const [season, room] = process.argv.slice(2).map(Number);
const frames = w.tilesets.map((t) => new Uint16Array(new Uint8Array(Buffer.from(t.frames[0], 'base64')).buffer));
const tilePx = (ts, id) => { const p = frames[ts][id]; const ox = (p % 256) * 16, oy = Math.floor(p / 256) * 16; const out = new Uint8Array(1024);
  for (let y = 0; y < 16; y++) out.set(pool.data.subarray(((oy + y) * pool.width + ox) * 4, ((oy + y) * pool.width + ox + 16) * 4), y * 64); return out; };
const models = {};
const model = (ts) => (models[ts] ||= new TilesetModel((id) => tilePx(ts, id), new Uint8Array(Buffer.from(w.tilesets[ts].collisions, 'base64'))));
const world = w.worlds[0];
const map = { mtW: 160, mtH: 128, ids: new Uint8Array(160 * 128), tilesets: new Uint16Array(160 * 128).fill(0xffff) };
map.models = new Proxy({}, { get: (_, k) => (Number(k) === 0xffff ? null : model(Number(k))) });
for (let r = 0; r < 256; r++) {
  const rr = world.seasons[season][r]; if (!rr) continue;
  const lay = Buffer.from(rr.layout, 'base64');
  for (let y = 0; y < 8; y++) for (let x = 0; x < 10; x++) { const mi = ((r >> 4) * 8 + y) * 160 + (r & 15) * 10 + x; map.ids[mi] = lay[y * 10 + x]; map.tilesets[mi] = rr.tileset; }
}
const elev = computeElevation(map, (ts) => w.tilesets[ts].collisionMode);
const c = buildChunkHeights(map, map.models, (room & 15) * 10, (room >> 4) * 8, 10, 8, elev);
const S = 4, out = new PNG({ width: 160 * S * 2, height: 128 * S });
for (let z = 0; z < 128; z++) for (let x = 0; x < 160; x++) {
  const mi = (((room >> 4) * 8) + (z >> 4)) * 160 + (room & 15) * 10 + (x >> 4);
  const px = tilePx(map.tilesets[mi], map.ids[mi]); const p = ((z & 15) * 16 + (x & 15)) * 4;
  const hh = c.h[z * 160 + x];
  const v = Math.max(0, Math.min(255, 60 + hh * 4));
  for (let sy = 0; sy < S; sy++) for (let sx = 0; sx < S; sx++) {
    let d = ((z * S + sy) * out.width + x * S + sx) * 4;
    out.data[d] = px[p]; out.data[d + 1] = px[p + 1]; out.data[d + 2] = px[p + 2]; out.data[d + 3] = 255;
    d += 160 * S * 4;
    out.data[d] = v; out.data[d + 1] = v; out.data[d + 2] = v; out.data[d + 3] = 255;
  }
}
fs.writeFileSync(process.env.OUT, PNG.sync.write(out));
