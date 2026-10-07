// Debug: heights and colours along one map row. Usage: node tools/dev/row.mjs season y x0 x1
import fs from 'fs'; import { PNG } from 'pngjs';
import { TilesetModel, buildChunkHeights, markGrass } from '../../src/terrain.js';
import { computeElevation } from '../../src/elevation.js';
const w = JSON.parse(fs.readFileSync('dev-data/world/world.json'));
const pool = PNG.sync.read(fs.readFileSync('dev-data/world/pool.png'));
const [season, Y, X0, X1] = process.argv.slice(2).map(Number);
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
const elev = computeElevation(map, (ts) => w.tilesets[ts].collisionMode);
const chunks = {};
for (let x = X0; x < X1; x++) {
  const rx = Math.floor(x / 160), ry = Math.floor(Y / 128);
  const c = (chunks[rx] ||= buildChunkHeights(map, map.models, rx * 10, ry * 8, 10, 8, elev));
  const i = (Y - ry * 128) * 160 + (x - rx * 160);
  const mi = (Y >> 4) * 160 + (x >> 4);
  const col = map.models[map.tilesets[mi]].color[map.ids[mi] * 256 + (Y & 15) * 16 + (x & 15)];
  const ci = (Y >> 3) * elev.cw + (x >> 3);
  console.log(x, 'id', map.ids[mi].toString(16), 'kind', elev.kind[ci], 'lvl', elev.level[ci], 'h', c.h[i], 'ox', c.ox[i], 'fx', c.fx[i], 'xF', c.xF[i], col.toString(16).padStart(6, '0'));
}
