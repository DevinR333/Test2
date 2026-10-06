// Works out how high each patch of ground is, so plateaus stand above the lowlands.
//
// The map is split into 8x8 cells (the game's collision resolution). Cells that are
// cliff walls or stairs separate the rest into regions of connected ground. Each cliff
// band tells us how two regions relate: in the oblique art, a tall band of brown cliff
// face below a red rim means the ground north of it is higher by roughly the band's
// height; a thin band (just the rim) is the top edge of a drop, so the north side is
// lower. Votes from every column are combined and region heights are solved from the
// largest region outward.

// Overworld metatiles (collision mode 0) that are cliff walls. These ids are the same in
// every overworld tileset (see the atlas rows $20-$6f).
const CLIFF_IDS = new Set();
const range = (a, b) => { for (let i = a; i <= b; i++) CLIFF_IDS.add(i); };
range(0x22, 0x23); range(0x25, 0x2a);
range(0x30, 0x3e); range(0x40, 0x4c); range(0x50, 0x5c); range(0x60, 0x63); range(0x65, 0x6c);
[0x94, 0x95, 0xa7, 0xaa, 0xcc, 0xcd, 0xce, 0xcf].forEach((i) => CLIFF_IDS.add(i));

// Ledges you jump down to the south: always "north is higher", even though they are thin.
const LEDGE_DOWN = new Set([0x54, 0x94, 0x95, 0x9a, 0xcc, 0xcd, 0xce, 0xcf, 0xfe, 0xff]);
const STAIRS = new Set([0xd0]);

export const CELL = { REGION: 0, CLIFF: 1, RAMP: 2 };
const NORTH_DROP = 16; // assumed height of drops whose wall faces away from the camera
const MAX_LEVEL = 96;
const MIN_LEVEL = -48;

const QUARTER_BIT = [8, 4, 2, 1];

export function computeElevation(map, collisionModeOf) {
  const cw = map.mtW * 2, ch = map.mtH * 2;
  const kind = new Uint8Array(cw * ch);
  const ledge = new Uint8Array(cw * ch);
  for (let cy = 0; cy < ch; cy++) for (let cx = 0; cx < cw; cx++) {
    const mi = (cy >> 1) * map.mtW + (cx >> 1);
    const ts = map.tilesets[mi];
    if (ts === 0xffff || collisionModeOf(ts) !== 0) continue;
    const id = map.ids[mi];
    const m = map.models[ts];
    const c = m ? m.collisions[id] : 0;
    const q = (cy & 1) * 2 + (cx & 1);
    const i = cy * cw + cx;
    if (STAIRS.has(id)) kind[i] = CELL.RAMP;
    else if (CLIFF_IDS.has(id) && c >= 1 && c <= 15 && (c & QUARTER_BIT[q])) {
      kind[i] = CELL.CLIFF;
      if (LEDGE_DOWN.has(id)) ledge[i] = 1;
    }
  }

  // Regions: 4-connected non-cliff, non-stairs cells.
  const region = new Int32Array(cw * ch).fill(-1);
  const sizes = [];
  const stack = [];
  for (let i = 0; i < cw * ch; i++) {
    if (kind[i] !== CELL.REGION || region[i] >= 0) continue;
    const id = sizes.length;
    let n = 0;
    region[i] = id; stack.push(i);
    while (stack.length) {
      const j = stack.pop(); n++;
      const x = j % cw, y = (j / cw) | 0;
      if (x > 0) visit(j - 1); if (x < cw - 1) visit(j + 1);
      if (y > 0) visit(j - cw); if (y < ch - 1) visit(j + cw);
    }
    sizes.push(n);
    function visit(k) { if (kind[k] === CELL.REGION && region[k] < 0) { region[k] = id; stack.push(k); } }
  }

  // Votes from vertical cliff runs: level(north) - level(south).
  const votes = new Map(); // "a,b" -> [sum, count]  (a < b)
  const vote = (a, b, d) => {
    if (a === b) return;
    if (a > b) { [a, b] = [b, a]; d = -d; }
    const k = a * 1e6 + b;
    const v = votes.get(k);
    if (v) { v[0] += d; v[1]++; } else votes.set(k, [d, 1]);
  };
  for (let cx = 0; cx < cw; cx++) {
    let cy = 0;
    while (cy < ch) {
      if (kind[cy * cw + cx] !== CELL.CLIFF) { cy++; continue; }
      let e = cy, isLedge = false;
      while (e < ch && kind[e * cw + cx] === CELL.CLIFF) { if (ledge[e * cw + cx]) isLedge = true; e++; }
      if (cy > 0 && e < ch) {
        const a = region[(cy - 1) * cw + cx], b = region[e * cw + cx];
        if (a >= 0 && b >= 0) {
          const len = (e - cy) * 8;
          if (isLedge || len >= 24) vote(a, b, Math.max(8, len - 8));
          else vote(a, b, -NORTH_DROP);
        }
      }
      cy = e;
    }
  }

  // Solve: grow from the biggest region, always taking the best-supported edge next.
  const n = sizes.length;
  const level = new Float32Array(n);
  const known = new Uint8Array(n);
  const adj = Array.from({ length: n }, () => []);
  for (const [k, [sum, count]] of votes) {
    const a = Math.floor(k / 1e6), b = k % 1e6;
    adj[a].push([b, sum / count, count]);
    adj[b].push([a, -sum / count, count]);
  }
  const order = [...sizes.keys()].sort((p, q) => sizes[q] - sizes[p]);
  for (const root of order) {
    if (known[root]) continue;
    known[root] = 1; level[root] = 0;
    const frontier = [];
    const push = (r) => { for (const [o, d, c] of adj[r]) if (!known[o]) frontier.push([c, r, o, d]); };
    push(root);
    while (frontier.length) {
      let bi = 0;
      for (let i = 1; i < frontier.length; i++) if (frontier[i][0] > frontier[bi][0]) bi = i;
      const [, from, to, d] = frontier[bi];
      frontier.splice(bi, 1);
      if (known[to]) continue;
      known[to] = 1;
      level[to] = level[from] - d; // adj stores d = level(from) - level(to)
      push(to);
    }
  }
  const out = new Int16Array(cw * ch).fill(-32768);
  for (let i = 0; i < cw * ch; i++) {
    if (region[i] >= 0) out[i] = Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, Math.round(level[region[i]])));
  }
  return { cw, ch, kind, level: out };
}
