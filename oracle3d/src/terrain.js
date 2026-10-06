// Turns the game's flat, oblique-view tile art into 3D terrain.
//
// Zelda's overworld art is drawn in an oblique projection: a thing of height H standing
// at depth z is drawn H pixels further up the screen than its base. So every solid
// region in the 2D map (a tree, a house, a cliff) is really a roof seen from above
// with a front wall below it. For each column of solid pixels we pick a height from how
// tall the region is on screen, stand its bottom rows up as a vertical front wall,
// and lay the rest down as the roof. Water and holes sink below the ground.
//
// Everything here is plain JS so the Node tools can run it too.

export const CLS = { GROUND: 0, SOLID: 1, WATER: 2, HOLE: 3, LAVA: 4 };

const DEPTH = { [CLS.WATER]: -3, [CLS.HOLE]: -14, [CLS.LAVA]: -2 };

// Collision bits 0-3 mark which 8x8 quarter of a metatile is solid
// (bit 3 top-left, bit 2 top-right, bit 1 bottom-left, bit 0 bottom-right).
const QUARTER_BIT = [8, 4, 2, 1];

// How tall (in pixels) a solid run that is `len` pixels long on screen stands.
export function runHeight(len) {
  if (len <= 4) return 2;
  return Math.max(3, Math.min(22, Math.round(len * 0.42)));
}

// Per-tileset lookups: the class of every pixel of every metatile.
// tile(id) returns that metatile's 16x16 RGBA pixels.
export class TilesetModel {
  constructor(tile, collisions) {
    this.cls = new Uint8Array(256 * 256); // [id*256 + py*16 + px]
    this.collisions = collisions;
    for (let id = 0; id < 256; id++) {
      const c = collisions[id];
      const hazard = c === 0x10 ? classifyHazard(tile(id)) : CLS.GROUND;
      for (let py = 0; py < 16; py++) for (let px = 0; px < 16; px++) {
        let k = CLS.GROUND;
        if (c >= 1 && c <= 15) {
          if (c & QUARTER_BIT[(py >> 3) * 2 + (px >> 3)]) k = CLS.SOLID;
        } else if (c === 0x10) {
          k = hazard;
        }
        this.cls[id * 256 + py * 16 + px] = k;
      }
    }
  }
}

function classifyHazard(px) {
  let r = 0, g = 0, b = 0;
  for (let i = 0; i < 256; i++) { r += px[i * 4]; g += px[i * 4 + 1]; b += px[i * 4 + 2]; }
  r /= 256; g /= 256; b /= 256;
  if (b > r + 20 && b > g - 10) return CLS.WATER;
  if (r > 150 && r > b + 60 && g < r) return CLS.LAVA;
  if (r + g + b < 150) return CLS.HOLE;
  return CLS.WATER;
}

// The height model of one chunk (a room) of a tile map.
//
// map: { mtW, mtH, ids, tilesets } with ids/tilesets as arrays of mtW*mtH metatiles.
// models: TilesetModel per tileset index.
// elev: result of computeElevation (ground level per 8x8 cell, cliff/stair cells), or null.
// The chunk covers metatiles [cx, cx+cw) x [cy, cy+ch). A margin of rows above and below
// is examined so runs crossing the chunk edge get the same height on both sides.
const MARGIN = 96;
const NO_LEVEL = -32768;

export function buildChunkHeights(map, models, cx, cy, cw, ch, elev) {
  const W = cw * 16, H = ch * 16;
  const y0px = cy * 16 - MARGIN;
  const rows = H + MARGIN * 2;
  const mapPxH = map.mtH * 16, mapPxW = map.mtW * 16;

  const classAt = (px, py) => {
    if (px < 0 || py < 0 || px >= mapPxW || py >= mapPxH) return CLS.GROUND;
    const mi = (py >> 4) * map.mtW + (px >> 4);
    const m = models[map.tilesets[mi]];
    if (!m) return CLS.GROUND;
    return m.cls[map.ids[mi] * 256 + (py & 15) * 16 + (px & 15)];
  };
  // Cell kind (0 region, 1 cliff, 2 stairs) and ground level at a map pixel.
  const kindAt = (px, py) => {
    if (!elev || px < 0 || py < 0 || px >= mapPxW || py >= mapPxH) return 0;
    return elev.kind[(py >> 3) * elev.cw + (px >> 3)];
  };
  const levelAt = (px, py) => {
    if (!elev || px < 0 || py < 0 || px >= mapPxW || py >= mapPxH) return NO_LEVEL;
    return elev.level[(py >> 3) * elev.cw + (px >> 3)];
  };

  // Outputs, one per pixel cell of the chunk:
  //   h: top height; texture row of the top = runY0 + (z - runY0) * runK
  const h = new Int16Array(W * H);
  const runK = new Float32Array(W * H);
  const runY0 = new Int32Array(W * H);

  // Pixel kinds within a column: ground/hazard, object (solid, not cliff), cliff, stairs.
  const P_GROUND = 0, P_OBJECT = 1, P_CLIFF = 2, P_STAIRS = 3;
  const col = new Uint8Array(rows);
  const cls = new Uint8Array(rows);
  const lvl = new Int16Array(rows);

  let curX = 0;
  const put = (r, height, y0, k) => {
    const z = y0px + r - cy * 16;
    if (z < 0 || z >= H) return;
    const i = z * W + curX;
    h[i] = height; runY0[i] = y0; runK[i] = k;
  };

  for (let x = 0; x < W; x++) {
    curX = x;
    const ax = cx * 16 + x;
    for (let r = 0; r < rows; r++) {
      const py = y0px + r;
      const c = classAt(ax, py), kd = kindAt(ax, py);
      cls[r] = c;
      lvl[r] = levelAt(ax, py);
      col[r] = kd === 2 ? P_STAIRS : kd === 1 ? P_CLIFF : c === CLS.SOLID ? P_OBJECT : P_GROUND;
    }
    // Level of the nearest region pixel above/below a run (skipping nothing else).
    const levelAbove = (r) => (r > 0 && lvl[r - 1] !== NO_LEVEL ? lvl[r - 1] : NO_LEVEL);
    const levelBelow = (e) => (e < rows && lvl[e] !== NO_LEVEL ? lvl[e] : NO_LEVEL);

    let r = 0;
    while (r < rows) {
      const kind = col[r];
      if (kind === P_GROUND) {
        const base = lvl[r] === NO_LEVEL ? 0 : lvl[r];
        put(r, base + (DEPTH[cls[r]] || 0), y0px + r, 1);
        r++;
        continue;
      }
      let e = r;
      while (e < rows && col[e] === kind) e++;
      const len = e - r;
      const top = y0px + r;
      if (kind === P_OBJECT) {
        // A tree, rock, house...: stands on the ground level of its own cells.
        const base = lvl[r] === NO_LEVEL ? Math.max(0, levelBelow(e) === NO_LEVEL ? 0 : levelBelow(e)) : lvl[r];
        const ht = runHeight((r === 0 || e === rows) ? Math.max(len, 64) : len);
        // The roof shows rows [top, end - ht) stretched over the footprint; the bottom
        // `ht` rows become the front wall (drawn by the side faces).
        const k = Math.max(0, len - ht) / len;
        for (let rr = r; rr < e; rr++) put(rr, base + ht, top, k);
      } else if (kind === P_CLIFF) {
        let a = levelAbove(r), b = levelBelow(e);
        if (a === NO_LEVEL && b === NO_LEVEL) { a = b = 0; }
        if (a === NO_LEVEL) a = b;
        if (b === NO_LEVEL) b = a;
        if (a > b) {
          // South-facing cliff: rim on top at the plateau level, face below it.
          const face = a - b;
          const k = Math.max(0, len - face) / len;
          for (let rr = r; rr < e; rr++) put(rr, a, top, k);
        } else {
          // Top edge of a drop (or a side wall): flat at the higher level.
          for (let rr = r; rr < e; rr++) put(rr, b, y0px + rr, 1);
        }
      } else {
        // Stairs: a ramp from the level above to the level below.
        let a = levelAbove(r), b = levelBelow(e);
        if (a === NO_LEVEL) a = b === NO_LEVEL ? 0 : b;
        if (b === NO_LEVEL) b = a;
        for (let rr = r; rr < e; rr++) put(rr, Math.round(a + (b - a) * (rr - r + 0.5) / len), y0px + rr, 1);
      }
      r = e;
    }
  }
  return { W, H, h, runK, runY0, originX: cx * 16, originZ: cy * 16 };
}

// Height of the terrain under a world pixel (for placing sprites), using a chunk.
export function heightAt(chunk, wx, wz) {
  const x = Math.floor(wx) - chunk.originX, z = Math.floor(wz) - chunk.originZ;
  if (x < 0 || z < 0 || x >= chunk.W || z >= chunk.H) return 0;
  return chunk.h[z * chunk.W + x];
}

// Build the triangle mesh of a chunk. Returns typed arrays:
//   position (x, y, z), normal, src (texture source pixel in map space)
// Top faces are merged into rectangles where height and texture mapping match.
export function meshChunk(c, neighborHeight) {
  const { W, H, h, runK, runY0 } = c;
  const pos = [], nor = [], src = [];
  const ox = c.originX, oz = c.originZ;

  const quad = (p, n, s) => {
    // p: 4 corners [x,y,z]; s: 4 [sx,sy]; two triangles 0-1-2, 0-2-3
    for (const k of [0, 1, 2, 0, 2, 3]) {
      pos.push(p[k][0], p[k][1], p[k][2]);
      nor.push(n[0], n[1], n[2]);
      src.push(s[k][0], s[k][1]);
    }
  };

  // ---- top faces (greedy) ----
  const done = new Uint8Array(W * H);
  const same = (a, b) => h[a] === h[b] && runK[a] === runK[b] && runY0[a] === runY0[b];
  for (let z = 0; z < H; z++) {
    for (let x = 0; x < W; x++) {
      const i = z * W + x;
      if (done[i]) continue;
      let w = 1;
      while (x + w < W && !done[i + w] && same(i, i + w)) w++;
      let d = 1;
      outer: while (z + d < H) {
        for (let k = 0; k < w; k++) {
          const j = (z + d) * W + x + k;
          if (done[j] || !same(i, j)) break outer;
        }
        d++;
      }
      for (let dz = 0; dz < d; dz++) for (let k = 0; k < w; k++) done[(z + dz) * W + x + k] = 1;
      const y = h[i];
      const x0 = ox + x, x1 = ox + x + w, z0 = oz + z, z1 = oz + z + d;
      const sy = (zz) => runY0[i] + (zz - runY0[i]) * runK[i];
      quad(
        [[x0, y, z0], [x0, y, z1], [x1, y, z1], [x1, y, z0]],
        [0, 1, 0],
        [[x0, sy(z0)], [x0, sy(z1)], [x1, sy(z1)], [x1, sy(z0)]],
      );
    }
  }

  const hAt = (x, z) => {
    if (x >= 0 && z >= 0 && x < W && z < H) return h[z * W + x];
    return neighborHeight ? neighborHeight(ox + x, oz + z) : 0;
  };

  // ---- vertical faces between columns (facing +x / -x) ----
  // Each chunk owns the faces on its left edge and interior; the face on boundary x
  // separates cell x-1 and cell x.
  for (let x = 0; x <= W; x++) {
    if (x === W) break; // right edge belongs to the neighbour's left edge
    let z = 0;
    while (z < H) {
      const a = hAt(x - 1, z), b = h[z * W + x];
      if (a === b) { z++; continue; }
      let e = z + 1;
      while (e < H && hAt(x - 1, e) === a && h[e * W + x] === b) e++;
      const wx = ox + x, z0 = oz + z, z1 = oz + e;
      const lo = Math.min(a, b), hi = Math.max(a, b);
      // Faces +x if the left column is higher (wall of the left column facing right).
      const facesPlusX = a > b;
      const sx = facesPlusX ? wx - 0.5 : wx + 0.5;
      const s = (zz, yy) => [sx, zz - (yy - lo) - 0.01];
      if (facesPlusX) {
        quad([[wx, hi, z1], [wx, lo, z1], [wx, lo, z0], [wx, hi, z0]], [1, 0, 0],
          [s(z1, hi), s(z1, lo), s(z0, lo), s(z0, hi)]);
      } else {
        quad([[wx, hi, z0], [wx, lo, z0], [wx, lo, z1], [wx, hi, z1]], [-1, 0, 0],
          [s(z0, hi), s(z0, lo), s(z1, lo), s(z1, hi)]);
      }
      z = e;
    }
  }

  // ---- vertical faces between rows (facing +z = south, toward the camera / -z) ----
  for (let z = 0; z < H; z++) {
    let x = 0;
    while (x < W) {
      const a = hAt(x, z - 1), b = h[z * W + x];
      if (a === b) { x++; continue; }
      let e = x + 1;
      while (e < W && hAt(e, z - 1) === a && h[z * W + e] === b) e++;
      const wz = oz + z, x0 = ox + x, x1 = ox + e;
      const lo = Math.min(a, b), hi = Math.max(a, b);
      if (a > b) {
        // South-facing front wall: texture is the oblique art, srcY = z - y.
        const s = (xx, yy) => [xx, wz - (yy - lo) - 0.5];
        quad([[x0, hi, wz], [x0, lo, wz], [x1, lo, wz], [x1, hi, wz]], [0, 0, 1],
          [s(x0, hi), s(x0, lo), s(x1, lo), s(x1, hi)]);
      } else {
        // North-facing wall (back of things, or the far bank of water/holes).
        const s = (xx) => [xx, wz + 0.5];
        quad([[x1, hi, wz], [x1, lo, wz], [x0, lo, wz], [x0, hi, wz]], [0, 0, -1],
          [s(x1, hi), s(x1, lo), s(x0, lo), s(x0, hi)]);
      }
      x = e;
    }
  }

  return {
    position: new Float32Array(pos),
    normal: new Float32Array(nor),
    src: new Float32Array(src),
  };
}
