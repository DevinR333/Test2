// Turns the game's flat, oblique-view tile art into 3D terrain.
//
// Zelda's overworld art is drawn in an oblique projection: a thing of height H standing
// at depth z is drawn H pixels further up the screen than its base. So every solid
// thing in the 2D map (a tree, a post, a house, a cliff) is really a roof seen from
// above with a front wall below it. For each column of an object's pixels we pick a
// height from how tall it is on screen, stand its bottom rows up as the front wall, and
// lay the rest down as the roof. The sides and back reuse that column's front-wall art.
//
// Only an object's own pixels are raised: where a solid tile shows plain ground around
// the object (between fence posts, around a tree's canopy), those pixels are found by
// flood-filling ground colours in from the walkable tiles and stay on the ground.
//
// Everything here is plain JS so the Node tools can run it too.

export const CLS = { GROUND: 0, SOLID: 1, WATER: 2, HOLE: 3, LAVA: 4, BRIDGE: 5 };

const DEPTH = { [CLS.WATER]: -1, [CLS.HOLE]: -14, [CLS.LAVA]: -2, [CLS.BRIDGE]: -1 };
const DECK_CLEAR = 12; // room to swim under a bridge
const DECK_RAISE = 1; // bridges float this far above the ground they connect
const DECK_THICK = 2;

// Collision bits 0-3 mark which 8x8 quarter of a metatile is solid
// (bit 3 top-left, bit 2 top-right, bit 1 bottom-left, bit 0 bottom-right).
const QUARTER_BIT = [8, 4, 2, 1];
// Bridge collision values (constants/common/specialCollisionValues.s).
const BRIDGES = new Set([0x11, 0x12, 0x13, 0x19, 0x1a, 0x1b]);

export const NO_LEVEL = -32768;

// How tall (in pixels) an object column that is `len` pixels long on screen stands.
export function runHeight(len) {
  if (len <= 3) return 2;
  return Math.max(3, Math.min(22, Math.round(len * 0.45)));
}

// Per-tileset lookups: the class and colour of every pixel of every metatile, and the
// colours that make up its plain ground.
// tile(id) returns that metatile's 16x16 RGBA pixels.
export class TilesetModel {
  constructor(tile, collisions) {
    this.cls = new Uint8Array(256 * 256); // [id*256 + py*16 + px]
    this.color = new Uint32Array(256 * 256);
    this.collisions = collisions;
    const groundCount = new Map();
    // Impassable rocky water (dark wave bands): luma below which a pixel pokes out.
    this.wavy = new Float32Array(256);
    let groundTotal = 0;
    for (let id = 0; id < 256; id++) {
      const c = collisions[id];
      const px = tile(id);
      const hazard = c === 0x10 ? classifyHazard(px) : CLS.GROUND;
      // Impassable deep water (the sea at the map's edge) is solid but should look like water.
      const solidWater = c === 0x0f && isWaterTile(px);
      if (solidWater) {
        let sum = 0, dark = 0;
        for (let p = 0; p < 256; p++) {
          const l = 0.3 * px[p * 4] + 0.59 * px[p * 4 + 1] + 0.11 * px[p * 4 + 2];
          sum += l;
          if (l < 90) dark++;
        }
        if (dark > 256 * 0.3) this.wavy[id] = sum / 256;
      }
      for (let py = 0; py < 16; py++) for (let qx = 0; qx < 16; qx++) {
        const p = py * 16 + qx;
        let k = CLS.GROUND;
        if (solidWater) {
          k = CLS.WATER;
        } else if (c >= 1 && c <= 15) {
          if (c & QUARTER_BIT[(py >> 3) * 2 + (qx >> 3)]) k = CLS.SOLID;
        } else if (c === 0x10) {
          k = hazard;
        } else if (BRIDGES.has(c)) {
          k = CLS.BRIDGE;
        }
        const rgb = (px[p * 4] << 16) | (px[p * 4 + 1] << 8) | px[p * 4 + 2];
        this.cls[id * 256 + p] = k;
        this.color[id * 256 + p] = rgb;
        if (c === 0) {
          groundCount.set(rgb, (groundCount.get(rgb) || 0) + 1);
          groundTotal++;
        }
      }
    }
    // Ground colours: the dominant colours of walkable tiles (not speckles or outlines).
    this.ground = new Set();
    for (const [rgb, n] of groundCount) {
      const luma = 0.3 * (rgb >> 16) + 0.59 * ((rgb >> 8) & 255) + 0.11 * (rgb & 255);
      if (n > groundTotal * 0.08 && luma > 60) this.ground.add(rgb);
    }
  }
}

// Tall grass (overworld and Subrosia metatiles $f8/$f9, TILETYPE_GRASS): its darker
// blades stand up a few pixels. model.grass[id] holds the luma below which a pixel is a
// blade (0 = not grass).
export function markGrass(model, collisionMode) {
  model.grass = new Float32Array(256);
  if (collisionMode > 1) return;
  for (const id of [0xf8, 0xf9]) {
    if (model.collisions[id] !== 0) continue;
    let sum = 0;
    for (let p = 0; p < 256; p++) sum += luma(model.color[id * 256 + p]);
    model.grass[id] = sum / 256;
  }
}

// Water tiles are mostly the bright water blue. (Sunken City's blue trees are mostly a
// dark blue, so they stay solid objects.)
function isWaterTile(px) {
  let bright = 0;
  for (let i = 0; i < 256; i++) {
    const r = px[i * 4], b = px[i * 4 + 2];
    if (b > 200 && r < 100) bright++;
  }
  return bright >= 80;
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
// The chunk covers metatiles [cx, cx+cw) x [cy, cy+ch). A margin around it is examined
// so objects crossing the chunk edge get the same shape on both sides.
const MARGIN_Y = 96, MARGIN_X = 32;
const P_GROUND = 0, P_OBJECT = 1, P_CLIFF = 2, P_STAIRS = 3, P_ORGANIC = 4;

// Leafy pixels (tree crowns, bushes) are modelled as rounded mounds instead of walls.
function isLeafy(rgb) {
  const r = rgb >> 16, g = (rgb >> 8) & 255, b = rgb & 255;
  if (g > r + 12 && g > b + 12 && g > 50) return true; // green crowns
  return b > r + 40 && b > g + 20 && b > 90; // blue crowns (Sunken City)
}
// Pixels a crown may grow into: its dark outlines and green/yellow-green highlights
// (not trunks, roofs or anything else that happens to touch a tree).
function crownish(rgb) {
  const r = rgb >> 16, g = (rgb >> 8) & 255, b = rgb & 255;
  const sat = Math.max(r, g, b) - Math.min(r, g, b);
  return (luma(rgb) < 50 && sat < 60) || (g >= r - 8 && g >= b) || (b >= r + 20 && b >= g - 10);
}
function luma(rgb) {
  return 0.3 * (rgb >> 16) + 0.59 * ((rgb >> 8) & 255) + 0.11 * (rgb & 255);
}
// Parabolic profile: low, gentle steps at the crown's edge, rounded top.
const DOME_R = 9, DOME_CAP = 16, GROOVE = 2;
const domeHeight = (d) => {
  const t = Math.min(1, d / DOME_R);
  return Math.max(1, Math.round(DOME_CAP * (1 - (1 - t) * (1 - t))));
};

export function buildChunkHeights(map, models, cx, cy, cw, ch, elev) {
  const W = cw * 16, H = ch * 16;
  const wx0 = cx * 16 - MARGIN_X, wy0 = cy * 16 - MARGIN_Y;
  const ww = W + MARGIN_X * 2, wh = H + MARGIN_Y * 2;
  const mapPxH = map.mtH * 16, mapPxW = map.mtW * 16;

  // ---- gather the window ----
  const kind = new Uint8Array(ww * wh);
  const cls = new Uint8Array(ww * wh);
  const lvl = new Int16Array(ww * wh).fill(NO_LEVEL);
  const deckLvl = new Int16Array(ww * wh).fill(NO_LEVEL);
  const ground = new Uint8Array(ww * wh); // object pixel showing plain ground colour
  const rgbs = new Uint32Array(ww * wh);
  const blade = new Int8Array(ww * wh); // raised detail on walkable/water ground: grass blades, rocks in rapids
  for (let y = 0; y < wh; y++) {
    const py = wy0 + y;
    if (py < 0 || py >= mapPxH) continue;
    for (let x = 0; x < ww; x++) {
      const px = wx0 + x;
      if (px < 0 || px >= mapPxW) continue;
      const i = y * ww + x;
      const mi = (py >> 4) * map.mtW + (px >> 4);
      const m = models[map.tilesets[mi]];
      let c = CLS.GROUND, rgbIsGround = false;
      if (m) {
        const p = map.ids[mi] * 256 + (py & 15) * 16 + (px & 15);
        c = m.cls[p];
        rgbs[i] = m.color[p];
        rgbIsGround = m.ground.has(m.color[p]);
        if (m.grass && m.grass[map.ids[mi]]) blade[i] = luma(m.color[p]) < m.grass[map.ids[mi]] ? 4 : 1;
        else if (m.wavy[map.ids[mi]] && luma(m.color[p]) < m.wavy[map.ids[mi]]) blade[i] = 3;
      }
      cls[i] = c;
      let kd = 0;
      if (elev) {
        const ci = (py >> 3) * elev.cw + (px >> 3);
        kd = elev.kind[ci];
        lvl[i] = elev.level[ci];
        deckLvl[i] = elev.deck[ci];
      }
      kind[i] = kd === 2 ? P_STAIRS : kd === 1 ? P_CLIFF : c === CLS.SOLID ? P_OBJECT : P_GROUND;
      if (kind[i] === P_OBJECT && rgbIsGround) ground[i] = 1;
    }
  }

  // ---- silhouettes: ground-coloured object pixels reachable from walkable ground ----
  const queue = new Int32Array(ww * wh);
  let qh = 0, qt = 0;
  const tryAdd = (j) => { if (ground[j] === 1 && kind[j] === P_OBJECT) { ground[j] = 2; queue[qt++] = j; } };
  for (let i = 0; i < ww * wh; i++) {
    if (kind[i] !== P_GROUND) continue;
    const x = i % ww;
    if (x > 0) tryAdd(i - 1);
    if (x < ww - 1) tryAdd(i + 1);
    if (i >= ww) tryAdd(i - ww);
    if (i < ww * (wh - 1)) tryAdd(i + ww);
  }
  while (qh < qt) {
    const i = queue[qh++];
    const x = i % ww;
    if (x > 0) tryAdd(i - 1);
    if (x < ww - 1) tryAdd(i + 1);
    if (i >= ww) tryAdd(i - ww);
    if (i < ww * (wh - 1)) tryAdd(i + ww);
  }
  // A metatile that would lose most of its solid pixels this way (a pale stump or rock
  // drawn in the path's colours) is an object made of ground colours: keep it whole.
  {
    const mw = Math.ceil(ww / 16) + 1, mh = Math.ceil(wh / 16) + 1;
    const solid = new Int32Array(mw * mh), lost = new Int32Array(mw * mh);
    const mOf = (i) => (((wy0 + Math.floor(i / ww)) >> 4) - (wy0 >> 4)) * mw + (((wx0 + (i % ww)) >> 4) - (wx0 >> 4));
    for (let i = 0; i < ww * wh; i++) {
      if (kind[i] !== P_OBJECT) continue;
      const k = mOf(i);
      solid[k]++;
      if (ground[i] === 2) lost[k]++;
    }
    for (let i = 0; i < ww * wh; i++) {
      if (ground[i] !== 2) continue;
      const k = mOf(i);
      if (lost[k] > solid[k] * 0.7) { ground[i] = 1; continue; }
      kind[i] = P_GROUND; // stays at the ground level of its 8x8 cell
      cls[i] = CLS.GROUND;
    }
  }

  // ---- leafy mounds ----
  // A crown is the leafy pixels plus the highlights and outlines inside it (anything
  // but trunk browns), grown out from the leafy pixels. Its height is a smooth dome over
  // the distance to the crown's edge; dark outline pixels inside are pressed in a little,
  // which separates neighbouring crowns in a forest without breaking them into spikes.
  const dist = new Float32Array(ww * wh);
  const dark = new Uint8Array(ww * wh);
  {
    const q = [];
    for (let i = 0; i < ww * wh; i++) {
      if (kind[i] === P_OBJECT && isLeafy(rgbs[i])) { kind[i] = P_ORGANIC; q.push(i); }
    }
    for (let h = 0; h < q.length; h++) {
      const i = q[h], x = i % ww;
      for (const j of [x > 0 ? i - 1 : -1, x < ww - 1 ? i + 1 : -1, i - ww, i + ww]) {
        if (j < 0 || j >= ww * wh || kind[j] !== P_OBJECT || !crownish(rgbs[j])) continue;
        kind[j] = P_ORGANIC;
        q.push(j);
      }
    }
    // Leafy objects of any colour (autumn crowns, the Sunken City tree-hut): colourful
    // pixels speckled with dark leaf detail inside, unlike smooth roofs or plain rocks.
    {
      const seen = new Uint8Array(ww * wh);
      for (let i0 = 0; i0 < ww * wh; i0++) {
        if (kind[i0] !== P_OBJECT || seen[i0]) continue;
        const comp = [i0];
        seen[i0] = 1;
        for (let h = 0; h < comp.length; h++) {
          const j = comp[h], x = j % ww;
          for (const k of [x > 0 ? j - 1 : -1, x < ww - 1 ? j + 1 : -1, j - ww, j + ww]) {
            if (k >= 0 && k < ww * wh && kind[k] === P_OBJECT && !seen[k]) { seen[k] = 1; comp.push(k); }
          }
        }
        if (comp.length < 48) continue;
        let inner = 0, innerDark = 0, colourful = 0, red = 0;
        for (const j of comp) {
          const rgb = rgbs[j], r = rgb >> 16, g = (rgb >> 8) & 255, b = rgb & 255;
          const brown = r >= g && g >= b && g > 60 && r - b < 150; // wood, sand, roots, peach
          if (r > 150 && g < 100) red++;
          if (!brown && Math.max(r, g, b) - Math.min(r, g, b) > 90 && luma(rgb) >= 50) colourful++;
          const x = j % ww;
          if (x > 0 && x < ww - 1 && kind[j - 1] === P_OBJECT && kind[j + 1] === P_OBJECT && kind[j - ww] === P_OBJECT && kind[j + ww] === P_OBJECT) {
            inner++;
            if (luma(rgb) < 50) innerDark++;
          }
        }
        const dk = inner > 0 ? innerDark / inner : 0, col = colourful / comp.length;
        // Leafy and speckled (autumn crowns), or bark-like and very dark-veined (the
        // Sunken City tree-hut). Red roofs are speckled too but much less veined.
        if ((dk > 0.12 && col > 0.45 && red < 0.3 * comp.length) || (dk > 0.35 && col > 0.2)) {
          for (const j of comp) { kind[j] = P_ORGANIC; q.push(j); }
        }
      }
    }
    for (const i of q) { dist[i] = 1e9; dark[i] = luma(rgbs[i]) < 50 ? 1 : 0; }
  }
  const D1 = 1, D2 = 1.414;
  for (let y = 0; y < wh; y++) for (let x = 0; x < ww; x++) {
    const i = y * ww + x;
    if (kind[i] !== P_ORGANIC) continue;
    let d = dist[i];
    if (x > 0) d = Math.min(d, dist[i - 1] + D1);
    if (y > 0) {
      d = Math.min(d, dist[i - ww] + D1);
      if (x > 0) d = Math.min(d, dist[i - ww - 1] + D2);
      if (x < ww - 1) d = Math.min(d, dist[i - ww + 1] + D2);
    }
    if (x === 0 || y === 0 || x === ww - 1) d = Math.min(d, 16); // window edge: assume more crown beyond
    dist[i] = d;
  }
  for (let y = wh - 1; y >= 0; y--) for (let x = ww - 1; x >= 0; x--) {
    const i = y * ww + x;
    if (kind[i] !== P_ORGANIC) continue;
    let d = dist[i];
    if (x < ww - 1) d = Math.min(d, dist[i + 1] + D1);
    if (y < wh - 1) {
      d = Math.min(d, dist[i + ww] + D1);
      if (x < ww - 1) d = Math.min(d, dist[i + ww + 1] + D2);
      if (x > 0) d = Math.min(d, dist[i + ww - 1] + D2);
    }
    if (y === wh - 1) d = Math.min(d, 16);
    dist[i] = d;
  }

  // Crown size: the largest distance-to-edge nearby (a 2D max filter). A small bush
  // stays low; a full tree crown stands tree-tall with steep, rounded sides.
  const crownR = new Float32Array(ww * wh);
  {
    const tmp = new Float32Array(ww * wh);
    const RAD = 14;
    for (let y = 0; y < wh; y++) for (let x = 0; x < ww; x++) {
      const i = y * ww + x;
      if (kind[i] !== P_ORGANIC) continue;
      let m = 0;
      for (let k = Math.max(0, x - RAD); k <= Math.min(ww - 1, x + RAD); k++) if (kind[y * ww + k] === P_ORGANIC) m = Math.max(m, dist[y * ww + k]);
      tmp[i] = m;
    }
    for (let y = 0; y < wh; y++) for (let x = 0; x < ww; x++) {
      const i = y * ww + x;
      if (kind[i] !== P_ORGANIC) continue;
      let m = 0;
      for (let k = Math.max(0, y - RAD); k <= Math.min(wh - 1, y + RAD); k++) if (kind[k * ww + x] === P_ORGANIC) m = Math.max(m, tmp[k * ww + x]);
      crownR[i] = Math.max(1, Math.min(16, m));
    }
  }
  const crownHeight = (i) => {
    const R = crownR[i];
    const H = Math.max(3, Math.min(30, R * 1.9));
    const t = Math.min(1, dist[i] / R);
    const h = H * Math.sqrt(1 - (1 - t) * (1 - t));
    return Math.max(1, Math.round(h - (dark[i] ? GROOVE : 0)));
  };

  // ---- outputs, one per pixel cell of the chunk ----
  // Every cell has a top at height h. Its texture is the map pixel
  //   x: fx if set, else x + ox        y: fy if set, else z + oy
  // (a plain shift, or a row/column repeated), so the art is never stretched.
  // Walls: the cell's south face shows art rows ending at sF (sLen rows over the wall
  // height), its north face rows from nF (nLen rows, may run backwards), its east/west
  // faces columns from xF (xLen columns); faces without art repeat the top pixel.
  const N = W * H;
  const out = {
    W, H, originX: cx * 16, originZ: cy * 16,
    h: new Int16Array(N),
    ox: new Int16Array(N), oy: new Int16Array(N),
    fx: new Int32Array(N).fill(NONE), fy: new Int32Array(N).fill(NONE),
    sF: new Float32Array(N).fill(NONE), sLen: new Float32Array(N),
    nF: new Float32Array(N).fill(NONE), nLen: new Float32Array(N),
    xF: new Float32Array(N).fill(NONE), xLen: new Float32Array(N),
    wx: new Float32Array(N), wy: new Float32Array(N), // cliff walls: offset to the rim
    deck: new Int16Array(N).fill(NO_LEVEL),
    walk: new Int16Array(N), // height Link stands at (tall grass is walked through)
  };
  const cellOf = (wx, wy) => {
    const x = wx - MARGIN_X, z = wy - MARGIN_Y;
    return x >= 0 && z >= 0 && x < W && z < H ? z * W + x : -1;
  };
  const set = (wx, wy, height, o) => {
    const i = cellOf(wx, wy);
    if (i < 0) return;
    out.h[i] = height;
    out.walk[i] = o.walk ?? height;
    out.ox[i] = o.ox || 0; out.oy[i] = o.oy || 0;
    out.fx[i] = o.fx ?? NONE; out.fy[i] = o.fy ?? NONE;
    out.sF[i] = o.sF ?? NONE; out.sLen[i] = o.sLen || 0;
    out.nF[i] = o.nF ?? NONE; out.nLen[i] = o.nLen || 0;
    out.xF[i] = o.xF ?? NONE; out.xLen[i] = o.xLen || 0;
    out.wx[i] = o.wx || 0; out.wy[i] = o.wy || 0;
  };
  const W0 = (r) => r * ww; // row start in the window
  // Ground hidden in the 2D art (behind objects, on cliff tops) is filled from the
  // nearest whole metatile of plain walkable ground at the same height, shifted by whole
  // metatiles so its pattern lines up seamlessly.
  const mtw = Math.ceil((wx0 + ww) / 16) - Math.floor(wx0 / 16), mth = Math.ceil((wy0 + wh) / 16) - Math.floor(wy0 / 16);
  const mx0 = Math.floor(wx0 / 16), my0 = Math.floor(wy0 / 16);
  const plainLv = new Int32Array(mtw * mth).fill(NO_LEVEL - 1); // NO_LEVEL-1 = not computed
  const plainAt = (mx, my) => {
    if (mx < 0 || my < 0 || mx >= mtw || my >= mth) return NO_LEVEL;
    const k = my * mtw + mx;
    if (plainLv[k] !== NO_LEVEL - 1) return plainLv[k];
    let lv = NO_LEVEL;
    const px0 = (mx0 + mx) * 16 - wx0, py0 = (my0 + my) * 16 - wy0;
    if (px0 >= 0 && py0 >= 0 && px0 + 16 <= ww && py0 + 16 <= wh) {
      lv = lvl[W0(py0) + px0];
      outer: for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) {
        const i = W0(py0 + y) + px0 + x;
        if (kind[i] !== P_GROUND || cls[i] !== CLS.GROUND || ground[i] === 2 || blade[i] || lvl[i] !== lv) { lv = NO_LEVEL; break outer; }
      }
    }
    plainLv[k] = lv;
    return lv;
  };
  const plainFill = (x, rr, lv) => {
    const mx = Math.floor((wx0 + x) / 16) - mx0, my = Math.floor((wy0 + rr) / 16) - my0;
    let best = null, bestD = Infinity;
    for (let r = 1; r <= 6 && !best; r++) {
      for (let dv = -r; dv <= r; dv++) for (let du = -r; du <= r; du++) {
        if (Math.max(Math.abs(du), Math.abs(dv)) !== r) continue;
        const l = plainAt(mx + du, my + dv);
        if (l === NO_LEVEL || (lv !== NO_LEVEL && l !== lv)) continue;
        const d = du * du + dv * dv + (dv > 0 ? 0.5 : 0); // prefer the far side (behind)
        if (d < bestD) { bestD = d; best = { ox: du * 16, oy: dv * 16 }; }
      }
    }
    return best;
  };
  // Plain walkable ground (not the background showing between an object's parts).
  const openGround = (i) => kind[i] === P_GROUND && cls[i] === CLS.GROUND && ground[i] !== 2 && !blade[i];
  const fillY = (x, rr, dy, edgeRow) => {
    const p = plainFill(x, rr, lvl[W0(rr) + x]);
    if (p) return p;
    const step = dy < 0 ? -1 : 1;
    for (let d = dy, n = 0; n < 48; d += step, n++) {
      const sr = rr + d;
      if (sr < 0 || sr >= wh) break;
      if (openGround(W0(sr) + x)) return { oy: d };
    }
    return { fy: edgeRow };
  };
  const fillX = (k, y, dx, edgeCol) => {
    const p = plainFill(k, y, lvl[W0(y) + k]);
    if (p) return p;
    const step = dx < 0 ? -1 : 1;
    for (let d = dx, n = 0; n < 48; d += step, n++) {
      const sk = k + d;
      if (sk < 0 || sk >= ww) break;
      if (openGround(W0(y) + sk)) return { ox: d };
    }
    return { fx: edgeCol };
  };
  const levelOr = (v, d) => (v === NO_LEVEL ? d : v);
  // Under a bridge deck: show water. Shifting by whole metatiles to the nearest
  // all-water metatile keeps the ripple pattern continuous with the river around it.
  const waterUnder = (x, r) => {
    const isWaterMt = (xx, rr) => {
      const bx = xx - ((wx0 + xx) & 15), by = rr - ((wy0 + rr) & 15);
      for (const [u, v] of [[1, 1], [14, 1], [1, 14], [14, 14], [8, 8]]) {
        const X = bx + u, Y = by + v;
        if (X < 0 || Y < 0 || X >= ww || Y >= wh || cls[W0(Y) + X] !== CLS.WATER) return false;
      }
      return true;
    };
    for (let d = 1; d < 5; d++) {
      for (const [dx, dy] of [[0, -d], [0, d], [-d, 0], [d, 0], [-d, -d], [d, -d], [-d, d], [d, d]]) {
        if (isWaterMt(x + dx * 16, r + dy * 16)) return { ox: dx * 16, oy: dy * 16 };
      }
    }
    return {};
  };
  const face = (i) => isFace(rgbs[i]);

  // ---- one height per object: connected object pixels share the median column run ----
  const comp = new Int32Array(ww * wh).fill(-1);
  const compHt = [];
  {
    let next = 0;
    const st = [];
    for (let i = 0; i < ww * wh; i++) {
      if (kind[i] !== P_OBJECT || comp[i] >= 0) continue;
      comp[i] = next; st.push(i);
      while (st.length) {
        const j = st.pop(), x = j % ww;
        for (const k of [x > 0 ? j - 1 : -1, x < ww - 1 ? j + 1 : -1, j - ww, j + ww]) {
          if (k >= 0 && k < ww * wh && kind[k] === P_OBJECT && comp[k] < 0) { comp[k] = next; st.push(k); }
        }
      }
      next++;
    }
    const runs = Array.from({ length: next }, () => []);
    for (let x = 0; x < ww; x++) {
      let r = 0;
      while (r < wh) {
        const i = W0(r) + x;
        if (kind[i] !== P_OBJECT) { r++; continue; }
        let e = r;
        while (e < wh && kind[W0(e) + x] === P_OBJECT) e++;
        runs[comp[i]].push(r === 0 || e === wh ? Math.max(e - r, 64) : e - r);
        r = e;
      }
    }
    for (const list of runs) {
      list.sort((a, b) => a - b);
      compHt.push(runHeight(list[Math.floor(list.length * 0.6)] || 1));
    }
  }

  // ---- cliffs, in 2D so straight runs and corners follow the same rule ----
  // A cliff band is drawn as the red rim (the plateau's edge, seen from above) and the
  // brown face below it (a wall, which in the oblique art takes up screen rows). In 3D
  // the whole band is plateau: rim pixels show their own art on top, face pixels show
  // plateau ground, and where the band meets lower ground a vertical wall stands. Each
  // wall shows the face art found by walking from the wall towards the nearest rim pixel,
  // so a wall facing south, west or round a corner all read the right art.
  {
    const N2 = ww * wh;
    const isRimPx = new Uint8Array(N2);
    for (let i = 0; i < N2; i++) {
      if (kind[i] !== P_CLIFF) continue;
      if (!face(i) && luma(rgbs[i]) >= 40) isRimPx[i] = 1;
    }
    // Dark outline pixels touching the rim belong to it.
    for (let i = 0; i < N2; i++) {
      if (kind[i] !== P_CLIFF || isRimPx[i] || luma(rgbs[i]) >= 40) continue;
      const x = i % ww;
      if ((x > 0 && isRimPx[i - 1] === 1) || (x < ww - 1 && isRimPx[i + 1] === 1) || (i >= ww && isRimPx[i - ww] === 1) || (i + ww < N2 && isRimPx[i + ww] === 1)) isRimPx[i] = 2;
    }
    // Plateau level of rim pixels: the level of the nearest ground (BFS through rim).
    const hi = new Int16Array(N2).fill(NO_LEVEL);
    const q = [];
    for (let i = 0; i < N2; i++) if (kind[i] !== P_CLIFF && lvl[i] !== NO_LEVEL) { hi[i] = lvl[i]; q.push(i); }
    const seen = new Uint8Array(N2);
    for (let h = 0; h < q.length; h++) {
      const i = q[h], x = i % ww;
      for (const j of [x > 0 ? i - 1 : -1, x < ww - 1 ? i + 1 : -1, i - ww, i + ww]) {
        if (j < 0 || j >= N2 || kind[j] !== P_CLIFF || !isRimPx[j] || seen[j]) continue;
        seen[j] = 1;
        hi[j] = Math.max(hi[j] === NO_LEVEL ? -32767 : hi[j], hi[i]);
        q.push(j);
      }
    }
    // Face pixels: nearest rim pixel (feature transform, 8-connected).
    const near = new Int32Array(N2).fill(-1);
    const fq = [];
    for (let i = 0; i < N2; i++) if (kind[i] === P_CLIFF && isRimPx[i]) { near[i] = i; fq.push(i); }
    for (let h = 0; h < fq.length; h++) {
      const i = fq[h], x = i % ww, y = (i / ww) | 0;
      const src = near[i], sx = src % ww, sy = (src / ww) | 0;
      for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) {
        if (!dx && !dy) continue;
        const X = x + dx, Y = y + dy;
        if (X < 0 || Y < 0 || X >= ww || Y >= wh) continue;
        const j = Y * ww + X;
        if (kind[j] !== P_CLIFF) continue;
        if (near[j] >= 0) {
          const o = near[j], ox = o % ww, oy = (o / ww) | 0;
          if ((ox - X) ** 2 + (oy - Y) ** 2 <= (sx - X) ** 2 + (sy - Y) ** 2) continue;
        }
        near[j] = src;
        fq.push(j);
      }
    }
    // Fallback level for bands with no rim in view: the higher of the nearby ground.
    const anyLv = new Int16Array(N2).fill(NO_LEVEL);
    {
      const q2 = [];
      for (let i = 0; i < N2; i++) if (kind[i] !== P_CLIFF && lvl[i] !== NO_LEVEL) { anyLv[i] = lvl[i]; q2.push(i); }
      for (let h = 0; h < q2.length; h++) {
        const i = q2[h], x = i % ww;
        for (const j of [x > 0 ? i - 1 : -1, x < ww - 1 ? i + 1 : -1, i - ww, i + ww]) {
          if (j < 0 || j >= N2 || kind[j] !== P_CLIFF) continue;
          if (anyLv[j] === NO_LEVEL || anyLv[i] > anyLv[j]) { if (anyLv[j] === NO_LEVEL) q2.push(j); anyLv[j] = Math.max(anyLv[j], anyLv[i]); }
        }
      }
    }
    for (let y = MARGIN_Y; y < MARGIN_Y + H; y++) for (let x = MARGIN_X; x < MARGIN_X + W; x++) {
      const i = W0(y) + x;
      if (kind[i] !== P_CLIFF) continue;
      const src = near[i];
      let level = src >= 0 && hi[src] !== NO_LEVEL ? hi[src] : levelOr(anyLv[i], 0);
      if (isRimPx[i]) {
        set(x, y, level, {});
        continue;
      }
      const sx = src >= 0 ? src % ww : x, sy = src >= 0 ? (src / ww) | 0 : y;
      const o = { ...(plainFill(x, y, level) || {}), wx: sx - x, wy: sy - y };
      set(x, y, level, o);
    }
  }
  // ---- column pass (all window columns, so the row pass has its inputs) ----
  for (let x = 0; x < ww; x++) {
    const at = (r) => W0(r) + x;
    const levelAbove = (r) => (r > 0 ? lvl[at(r - 1)] : NO_LEVEL);
    const levelBelow = (e) => (e < wh ? lvl[at(e)] : NO_LEVEL);
    let r = 0;
    while (r < wh) {
      const k0 = kind[at(r)];
      if (k0 === P_ORGANIC) {
        const lv = levelOr(lvl[at(r)], 0);
        set(x, r, lv + crownHeight(at(r)), {});
        r++;
        continue;
      }
      if (k0 === P_GROUND) {
        const lv = levelOr(lvl[at(r)], 0);
        const c = cls[at(r)];
        const base = lv + (DEPTH[c] || 0);
        if (blade[at(r)]) set(x, r, base + blade[at(r)], { walk: base });
        else set(x, r, base, c === CLS.BRIDGE ? waterUnder(x, r) : {});
        if (c === CLS.BRIDGE) {
          const i = cellOf(x, r);
          if (i >= 0) out.deck[i] = Math.max(levelOr(deckLvl[at(r)], lv), base + DECK_CLEAR) + DECK_RAISE;
        }
        r++;
        continue;
      }
      if (k0 === P_CLIFF) { r++; continue; } // done by the cliff pass
      let e = r;
      while (e < wh && kind[at(e)] === k0) e++;
      const len = e - r;
      const y0 = wy0 + r, y1 = wy0 + e; // map rows of the run

      if (k0 === P_OBJECT) {
        // Undo the oblique projection: a box of height ht whose roof shows the top
        // rows 1:1 and whose front wall shows the bottom ht rows. The strip behind it
        // (hidden in the 2D art) is ground, filled with the row above the object.
        const lv = levelOr(lvl[at(r)], levelOr(levelBelow(e), 0));
        const ht = len <= 3 ? len : Math.min(compHt[comp[at(r)]], len - 2);
        for (let rr = r; rr < e; rr++) {
          if (rr - r < ht) set(x, rr, lv, fillY(x, rr, -ht, y0 - 1));
          else set(x, rr, lv + ht, { oy: -ht, sF: y1, sLen: ht, nF: y1 - 0.01, nLen: -ht });
        }
      } else {
        // Stairs: steps from the level above down to the level below.
        let a = levelAbove(r), b = levelBelow(e);
        if (a === NO_LEVEL) a = levelOr(b, 0);
        if (b === NO_LEVEL) b = a;
        const steps = Math.max(1, Math.round(len / 4));
        for (let rr = r; rr < e; rr++) {
          const s = Math.min(steps - 1, Math.floor((rr - r) * steps / len));
          set(x, rr, Math.round(a + (b - a) * (s + 0.5) / steps), {});
        }
      }
      r = e;
    }
  }

  return out;
}

const NONE = -2147483648;
export { NONE as NO_SRC };

// Cliff face art: mid browns (not the light peach or red of the rim, not outlines).
function isFace(rgb) {
  const r = rgb >> 16, g = (rgb >> 8) & 255, b = rgb & 255;
  const l = luma(rgb);
  return r >= g && g >= b && r - b > 60 && l > 40 && l < 175 && g > 0.35 * r;
}

function isRim(rgb) {
  const r = rgb >> 16, g = (rgb >> 8) & 255, b = rgb & 255;
  return r > 150 && g < 120 && b < 120;
}

// Build the triangle mesh of a chunk. Returns typed arrays:
//   position (x, y, z), normal, src (texture source pixel in map space)
// Top faces are merged into rectangles where height and texture mapping match.
// neighbor(x, z) gives { c, i } (a chunk and cell index) for cells outside the chunk.
export function meshChunk(c, neighbor) {
  const { W, H } = c;
  const pos = [], nor = [], src = [];
  const ox0 = c.originX, oz0 = c.originZ;

  const quad = (p, n, s) => {
    for (const k of [0, 1, 2, 0, 2, 3]) {
      pos.push(p[k][0], p[k][1], p[k][2]);
      nor.push(n[0], n[1], n[2]);
      src.push(s[k][0], s[k][1]);
    }
  };
  // Texture of a cell's top at map point (px, pz).
  const topS = (cc, i, px, pz) => [cc.fx[i] !== NONE ? cc.fx[i] + 0.5 : px + cc.ox[i], cc.fy[i] !== NONE ? cc.fy[i] + 0.5 : pz + cc.oy[i]];

  // ---- top faces (greedy) ----
  const done = new Uint8Array(W * H);
  const same = (a, b) => c.h[a] === c.h[b] && c.ox[a] === c.ox[b] && c.oy[a] === c.oy[b] && c.fx[a] === c.fx[b] && c.fy[a] === c.fy[b];
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
      const y = c.h[i];
      const x0 = ox0 + x, x1 = ox0 + x + w, z0 = oz0 + z, z1 = oz0 + z + d;
      const e = 0.001;
      quad([[x0, y, z0], [x0, y, z1], [x1, y, z1], [x1, y, z0]], [0, 1, 0],
        [topS(c, i, x0 + e, z0 + e), topS(c, i, x0 + e, z1 - e), topS(c, i, x1 - e, z1 - e), topS(c, i, x1 - e, z0 + e)]);
    }
  }

  // Cells outside the map are flat ground with no art.
  const VOID = { c: { h: [0], ox: [0], oy: [0], fx: [NONE], fy: [NONE], sF: [NONE], sLen: [0], nF: [NONE], nLen: [0], xF: [NONE], xLen: [0], wx: [0], wy: [0] }, i: 0 };
  const cell = (x, z) => {
    if (x >= 0 && z >= 0 && x < W && z < H) return { c, i: z * W + x };
    return (neighbor && neighbor(ox0 + x, oz0 + z)) || VOID;
  };
  const hOf = (r) => (r ? r.c.h[r.i] : 0);
  const t = (e, lo, hi) => (hi > lo ? (e - lo) / (hi - lo) : 0);

  // Texture for a point on a wall of cell r (the higher side). dir: 's', 'n', 'e', 'w'.
  // (px, pz): map point of the wall; e: height of the point.
  const wallS = (r, dir, px, pz, e, lo, hi) => {
    const cc = r.c, i = r.i;
    const f = t(e, lo, hi);
    if (cc.wx[i] || cc.wy[i]) {
      // cliff wall: walk from the wall towards the rim as the wall rises
      return [px + f * cc.wx[i], pz + f * cc.wy[i]];
    }
    if ((dir === 'e' || dir === 'w') && cc.xF[i] !== NONE) {
      return [cc.xF[i] + 0.01 + f * (cc.xLen[i] - 0.02), cc.fy[i] !== NONE ? cc.fy[i] + 0.5 : pz + cc.oy[i]];
    }
    if (dir !== 'n' && cc.sF[i] !== NONE) {
      const sx = cc.fx[i] !== NONE ? cc.fx[i] + 0.5 : px + cc.ox[i];
      return [sx, cc.sF[i] - 0.01 - f * (cc.sLen[i] - 0.02)];
    }
    if (dir === 'n' && cc.nF[i] !== NONE) {
      const sx = cc.fx[i] !== NONE ? cc.fx[i] + 0.5 : px + cc.ox[i];
      return [sx, cc.nF[i] + 0.01 + f * (cc.nLen[i] - 0.02 * Math.sign(cc.nLen[i]))];
    }
    // No art for this wall: carry on the surface texture over the edge (like the
    // voxels below the top), rather than repeating one pixel all the way down.
    const k = hi - e;
    if (dir === 's') return topS(cc, i, px, pz - k);
    if (dir === 'n') return topS(cc, i, px, pz + k);
    if (dir === 'e') return topS(cc, i, px - k, pz);
    return topS(cc, i, px + k, pz);
  };
  // Wall attributes that must match for two neighbouring wall pieces to merge.
  const wallKey = (r) => (r ? [r.c.h[r.i], r.c.sF[r.i], r.c.sLen[r.i], r.c.nF[r.i], r.c.nLen[r.i], r.c.xF[r.i], r.c.xLen[r.i], r.c.wx[r.i], r.c.wy[r.i], r.c.ox[r.i], r.c.oy[r.i], r.c.fx[r.i], r.c.fy[r.i]].join() : '0');

  // ---- walls between columns (facing +x / -x); this chunk owns its left edge ----
  for (let x = 0; x < W; x++) {
    let z = 0;
    while (z < H) {
      const A = cell(x - 1, z), B = cell(x, z);
      const a = hOf(A), b = hOf(B);
      if (a === b) { z++; continue; }
      const hiR = a > b ? A : B, loR = a > b ? B : A;
      const key = wallKey(hiR) + '|' + hOf(loR);
      let e = z + 1;
      while (e < H) {
        const A2 = cell(x - 1, e), B2 = cell(x, e);
        const a2 = hOf(A2), b2 = hOf(B2);
        if (a2 === b2 || (a2 > b2) !== (a > b)) break;
        if (wallKey(a2 > b2 ? A2 : B2) + '|' + hOf(a2 > b2 ? B2 : A2) !== key) break;
        e++;
      }
      const wx = ox0 + x, z0 = oz0 + z, z1 = oz0 + e;
      const lo = Math.min(a, b), hi = Math.max(a, b);
      const plusX = a > b;
      const px = plusX ? wx - 0.5 : wx + 0.5;
      const dir = plusX ? 'e' : 'w';
      const S = (zz, yy) => wallS(hiR, dir, px, zz, yy, lo, hi);
      if (plusX) {
        quad([[wx, hi, z1], [wx, lo, z1], [wx, lo, z0], [wx, hi, z0]], [1, 0, 0],
          [S(z1 - 0.01, hi), S(z1 - 0.01, lo), S(z0 + 0.01, lo), S(z0 + 0.01, hi)]);
      } else {
        quad([[wx, hi, z0], [wx, lo, z0], [wx, lo, z1], [wx, hi, z1]], [-1, 0, 0],
          [S(z0 + 0.01, hi), S(z0 + 0.01, lo), S(z1 - 0.01, lo), S(z1 - 0.01, hi)]);
      }
      z = e;
    }
  }

  // ---- walls between rows (facing +z = south / -z = north) ----
  for (let z = 0; z < H; z++) {
    let x = 0;
    while (x < W) {
      const A = cell(x, z - 1), B = cell(x, z);
      const a = hOf(A), b = hOf(B);
      if (a === b) { x++; continue; }
      const hiR = a > b ? A : B, loR = a > b ? B : A;
      const key = wallKey(hiR) + '|' + hOf(loR);
      let e = x + 1;
      while (e < W) {
        const A2 = cell(e, z - 1), B2 = cell(e, z);
        const a2 = hOf(A2), b2 = hOf(B2);
        if (a2 === b2 || (a2 > b2) !== (a > b)) break;
        if (wallKey(a2 > b2 ? A2 : B2) + '|' + hOf(a2 > b2 ? B2 : A2) !== key) break;
        e++;
      }
      const wz = oz0 + z, x0 = ox0 + x, x1 = ox0 + e;
      const lo = Math.min(a, b), hi = Math.max(a, b);
      if (a > b) {
        const S = (xx, yy) => wallS(hiR, 's', xx, wz - 0.5, yy, lo, hi);
        quad([[x0, hi, wz], [x0, lo, wz], [x1, lo, wz], [x1, hi, wz]], [0, 0, 1],
          [S(x0 + 0.01, hi), S(x0 + 0.01, lo), S(x1 - 0.01, lo), S(x1 - 0.01, hi)]);
      } else {
        const S = (xx, yy) => wallS(hiR, 'n', xx, wz + 0.5, yy, lo, hi);
        quad([[x1, hi, wz], [x1, lo, wz], [x0, lo, wz], [x0, hi, wz]], [0, 0, -1],
          [S(x1 - 0.01, hi), S(x1 - 0.01, lo), S(x0 + 0.01, lo), S(x0 + 0.01, hi)]);
      }
      x = e;
    }
  }

  // ---- bridge decks: slabs floating over the water ----
  const deck = c.deck;
  const used = new Uint8Array(W * H);
  for (let z = 0; z < H; z++) for (let x = 0; x < W; x++) {
    const i = z * W + x;
    if (deck[i] === NO_LEVEL || used[i]) continue;
    const top = deck[i];
    let w = 1;
    while (x + w < W && !used[i + w] && deck[i + w] === top) w++;
    let d = 1;
    outer2: while (z + d < H) {
      for (let k = 0; k < w; k++) { const j = (z + d) * W + x + k; if (used[j] || deck[j] !== top) break outer2; }
      d++;
    }
    for (let dz = 0; dz < d; dz++) for (let k = 0; k < w; k++) used[(z + dz) * W + x + k] = 1;
    const x0 = ox0 + x, x1 = ox0 + x + w, z0 = oz0 + z, z1 = oz0 + z + d, bot = top - DECK_THICK;
    quad([[x0, top, z0], [x0, top, z1], [x1, top, z1], [x1, top, z0]], [0, 1, 0],
      [[x0, z0], [x0, z1 - 0.01], [x1 - 0.01, z1 - 0.01], [x1 - 0.01, z0]]);
    quad([[x0, bot, z1], [x0, bot, z0], [x1, bot, z0], [x1, bot, z1]], [0, -1, 0],
      [[x0, z1 - 0.01], [x0, z0], [x1 - 0.01, z0], [x1 - 0.01, z1 - 0.01]]);
    quad([[x0, top, z1], [x0, bot, z1], [x1, bot, z1], [x1, top, z1]], [0, 0, 1],
      [[x0, z1 - 0.5], [x0, z1 - 0.5], [x1 - 0.01, z1 - 0.5], [x1 - 0.01, z1 - 0.5]]);
    quad([[x1, top, z0], [x1, bot, z0], [x0, bot, z0], [x0, top, z0]], [0, 0, -1],
      [[x1 - 0.01, z0 + 0.5], [x1 - 0.01, z0 + 0.5], [x0, z0 + 0.5], [x0, z0 + 0.5]]);
    quad([[x1, top, z1], [x1, bot, z1], [x1, bot, z0], [x1, top, z0]], [1, 0, 0],
      [[x1 - 0.5, z1 - 0.01], [x1 - 0.5, z1 - 0.01], [x1 - 0.5, z0], [x1 - 0.5, z0]]);
    quad([[x0, top, z0], [x0, bot, z0], [x0, bot, z1], [x0, top, z1]], [-1, 0, 0],
      [[x0 + 0.5, z0], [x0 + 0.5, z0], [x0 + 0.5, z1 - 0.01], [x0 + 0.5, z1 - 0.01]]);
  }

  return {
    position: new Float32Array(pos),
    normal: new Float32Array(nor),
    src: new Float32Array(src),
  };
}
