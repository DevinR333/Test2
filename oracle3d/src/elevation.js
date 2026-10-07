// Works out how high each patch of ground is, so plateaus stand above the lowlands.
//
// The map is split into 8x8 cells (the game's collision resolution). Cliff walls,
// stairs and bridges separate the rest into regions: connected land, or connected
// water. Then every cliff, shore and bridge says something about two regions:
//  - a tall band of cliff face below a red rim: the north side is one storey higher;
//  - a thin band (just the rim): the top edge of a drop, the north side is a storey lower;
//  - water touching land with no cliff between: same level (a beach);
//  - a bridge: the land at both ends is at the same level.
// Regions get their levels from the largest one outward, trusting the best-supported
// relations first.

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
export const STOREY = 16; // height of one cliff
const MAX_LEVEL = STOREY * 6;
const MIN_LEVEL = -STOREY * 3;
const NONE = -32768;

const QUARTER_BIT = [8, 4, 2, 1];
const CLS_WATER = 2, CLS_BRIDGE = 5;

// Colours of cliff art: browns of the face, reds and peach of the rim, black outlines.
function cliffColour(rgb) {
  const r = rgb >> 16, g = (rgb >> 8) & 255, b = rgb & 255;
  const l = 0.3 * r + 0.59 * g + 0.11 * b;
  if (l < 40) return true;
  if (r > 150 && g < 120 && b < 120) return true; // red
  if (r > 90 && g < 60 && b < 80) return true; // dark red
  return r >= g && g >= b && r - b > 50; // browns, tans, peach
}

export function computeElevation(map, collisionModeOf) {
  const cw = map.mtW * 2, ch = map.mtH * 2, n = cw * ch;
  const kind = new Uint8Array(n);
  const ledge = new Uint8Array(n);
  const wet = new Uint8Array(n);
  const bridge = new Uint8Array(n);
  const maybeCliff = new Uint8Array(n);
  const blueLand = new Uint8Array(n); // walkable shallows drawn as water (Sunken City)
  for (let cy = 0; cy < ch; cy++) for (let cx = 0; cx < cw; cx++) {
    const mi = (cy >> 1) * map.mtW + (cx >> 1);
    const ts = map.tilesets[mi];
    if (ts === 0xffff) continue;
    const id = map.ids[mi];
    const m = map.models[ts];
    const i = cy * cw + cx;
    const pc = m ? m.cls[id * 256 + ((cy & 1) * 8 + 4) * 16 + (cx & 1) * 8 + 4] : 0;
    if (pc === CLS_WATER) wet[i] = 1;
    if (pc === CLS_BRIDGE) bridge[i] = 1;
    if (m && pc === 0) {
      const rgb = m.color[id * 256 + ((cy & 1) * 8 + 4) * 16 + (cx & 1) * 8 + 4];
      const r = rgb >> 16, g = (rgb >> 8) & 255, b = rgb & 255;
      if (b > r + 40 && b > g) blueLand[i] = 1;
    }
    if (collisionModeOf(ts) !== 0) continue;
    const c = m ? m.collisions[id] : 0;
    const q = (cy & 1) * 2 + (cx & 1);
    if (STAIRS.has(id)) kind[i] = CELL.RAMP;
    else if (CLIFF_IDS.has(id) && c >= 1 && c <= 15 && (c & QUARTER_BIT[q])) {
      kind[i] = CELL.CLIFF;
      if (LEDGE_DOWN.has(id)) ledge[i] = 1;
    } else if (c >= 1 && c <= 15 && (c & QUARTER_BIT[q]) && m) {
      // Solid cell drawn in cliff colours (brown face, red rim, dark cave mouths):
      // becomes cliff if it touches a known cliff (decided below).
      let n2 = 0;
      for (let y = 0; y < 8; y++) for (let x = 0; x < 8; x++) {
        const rgb = m.color[id * 256 + ((cy & 1) * 8 + y) * 16 + (cx & 1) * 8 + x];
        if (cliffColour(rgb)) n2++;
      }
      // Not part of a tree or bush (any leafy green in the metatile).
      let green = 0;
      for (let p = 0; p < 256; p++) {
        const rgb = m.color[id * 256 + p], r = rgb >> 16, g = (rgb >> 8) & 255, b = rgb & 255;
        if (g > r + 12 && g > b + 12) green++;
      }
      if (n2 > 48 && green < 12) maybeCliff[i] = 1;
    }
  }
  // Fill short gaps in a cliff band (cave mouths, doors in the face): a run of
  // cliff-coloured solid cells with cliff on both sides of it in the same row.
  for (let cy = 0; cy < ch; cy++) {
    let cx = 0;
    while (cx < cw) {
      if (!maybeCliff[cy * cw + cx] || kind[cy * cw + cx] !== CELL.REGION) { cx++; continue; }
      let e = cx;
      while (e < cw && maybeCliff[cy * cw + e] && kind[cy * cw + e] === CELL.REGION) e++;
      if (e - cx <= 6 && cx > 0 && e < cw && kind[cy * cw + cx - 1] === CELL.CLIFF && kind[cy * cw + e] === CELL.CLIFF) {
        for (let k = cx; k < e; k++) kind[cy * cw + k] = CELL.CLIFF;
      }
      cx = e;
    }
  }
  const neighbours = (i) => {
    const x = i % cw, out = [];
    if (x > 0) out.push(i - 1);
    if (x < cw - 1) out.push(i + 1);
    if (i >= cw) out.push(i - cw);
    if (i < n - cw) out.push(i + cw);
    return out;
  };

  // ---- regions: connected land or connected water (not cliffs, stairs, bridges) ----
  const region = new Int32Array(n).fill(-1);
  const sizes = [];
  const regionWet = [];
  const isOpen = (i) => kind[i] === CELL.REGION && !bridge[i];
  for (let i = 0; i < n; i++) {
    if (!isOpen(i) || region[i] >= 0) continue;
    const id = sizes.length;
    let count = 0;
    const stack = [i];
    region[i] = id;
    while (stack.length) {
      const j = stack.pop(); count++;
      for (const k of neighbours(j)) if (isOpen(k) && region[k] < 0 && wet[k] === wet[i]) { region[k] = id; stack.push(k); }
    }
    sizes.push(count);
    regionWet.push(wet[i]);
  }

  const regionBlue = new Float32Array(sizes.length);
  for (let i = 0; i < n; i++) if (region[i] >= 0 && blueLand[i]) regionBlue[region[i]]++;
  for (let r = 0; r < sizes.length; r++) regionBlue[r] /= sizes[r];

  if (globalThis.DEBUG_CELL) { const c = globalThis.DEBUG_CELL; const r = region[c]; console.log('cell', c, 'region', r, 'size', sizes[r], 'blue', regionBlue[r], 'wet', wet[c], 'blueLand', blueLand[c], 'kind', kind[c]); }
  // ---- relations between regions: level(a) - level(b) = d, with a weight ----
  const votes = new Map();
  const vote = (a, b, d, w) => {
    if (a < 0 || b < 0 || a === b) return;
    if (a > b) { [a, b] = [b, a]; d = -d; }
    const k = a * 1e6 + b;
    const v = votes.get(k);
    if (v) { v[0] += d * w; v[1] += w; } else votes.set(k, [d * w, w]);
  };

  // How often each water region meets land across a cliff, and directly (a shore).
  const cliffHits = new Map();
  const bump = (m, k, n = 1) => m.set(k, (m.get(k) || 0) + n);
  const cliffNote = (a, b) => {
    if (a >= 0 && regionWet[a]) bump(cliffHits, a);
    if (b >= 0 && regionWet[b]) bump(cliffHits, b);
  };

  // Cliff bands, scanning each column of cells.
  for (let cx = 0; cx < cw; cx++) {
    let cy = 0;
    while (cy < ch) {
      if (kind[cy * cw + cx] !== CELL.CLIFF) { cy++; continue; }
      let e = cy, isLedge = false;
      while (e < ch && kind[e * cw + cx] === CELL.CLIFF) { if (ledge[e * cw + cx]) isLedge = true; e++; }
      if (cy > 0 && e < ch) {
        const a = region[(cy - 1) * cw + cx], b = region[e * cw + cx];
        const len = (e - cy) * 8;
        cliffNote(a, b);
        if (isLedge || len >= 24) vote(a, b, STOREY, 1);
        else vote(a, b, -STOREY, 0.5);
      }
      cy = e;
    }
  }
  // East/west cliff strips, scanning each row of cells: the red rim sits on the
  // plateau side of the strip.
  const isRed = (rgb) => (rgb >> 16) > 150 && ((rgb >> 8) & 255) < 120 && (rgb & 255) < 120;
  for (let cy = 0; cy < ch; cy++) {
    let cx = 0;
    while (cx < cw) {
      if (kind[cy * cw + cx] !== CELL.CLIFF) { cx++; continue; }
      let e = cx;
      while (e < cw && kind[cy * cw + e] === CELL.CLIFF) e++;
      if (cx > 0 && e < cw) {
        const a = region[cy * cw + cx - 1], b = region[cy * cw + e];
        if (a >= 0 && b >= 0 && a !== b) cliffNote(a, b);
        if (a >= 0 && b >= 0 && a !== b) {
          // Where along the strip are the red pixels? (sample the cells' middle row)
          let sum = 0, cnt = 0;
          const py = cy * 8 + 4;
          for (let px = cx * 8; px < e * 8; px++) {
            const mi = (py >> 4) * map.mtW + (px >> 4);
            const m = map.models[map.tilesets[mi]];
            if (m && isRed(m.color[map.ids[mi] * 256 + (py & 15) * 16 + (px & 15)])) { sum += px; cnt++; }
          }
          if (cnt) {
            const mid = (cx + e) * 4;
            const pos = sum / cnt;
            if (Math.abs(pos - mid) > 1) vote(a, b, pos < mid ? STOREY : -STOREY, 1);
          }
        }
      }
      cx = e;
    }
  }
  // Shores.
  for (let i = 0; i < n; i++) {
    if (region[i] < 0) continue;
    const x = i % cw;
    for (const j of [x < cw - 1 ? i + 1 : -1, i + cw < n ? i + cw : -1]) {
      if (j < 0 || region[j] < 0 || wet[j] === wet[i]) continue;
      const l = wet[i] ? region[j] : region[i];
      // Walkable shallows drawn as water (Sunken City, fords) are level with the water.
      vote(region[i], region[j], 0, regionBlue[l] > 0.5 ? 6 : 0.15);
    }
  }
  // Bridges: the land regions touching one bridge are level with each other.
  const seen = new Uint8Array(n);
  for (let i = 0; i < n; i++) {
    if (!bridge[i] || seen[i]) continue;
    const lands = new Set();
    const stack = [i];
    seen[i] = 1;
    while (stack.length) {
      const j = stack.pop();
      for (const k of neighbours(j)) {
        if (bridge[k] && !seen[k]) { seen[k] = 1; stack.push(k); } else if (region[k] >= 0 && !regionWet[region[k]]) lands.add(region[k]);
      }
    }
    const list = [...lands];
    for (let a = 1; a < list.length; a++) vote(list[0], list[a], 0, 50);
  }

  // ---- solve: grow from the biggest region, best-supported relation first ----
  const R = sizes.length;
  const level = new Float32Array(R);
  const known = new Uint8Array(R);
  const adj = Array.from({ length: R }, () => []);
  for (const [k, [sum, w]] of votes) {
    const a = Math.floor(k / 1e6), b = k % 1e6;
    const d = Math.round(sum / w / STOREY) * STOREY; // whole storeys
    adj[a].push([b, d, w]);
    adj[b].push([a, -d, w]);
  }
  const order = [...sizes.keys()].sort((p, q) => sizes[q] - sizes[p]);
  for (const root of order) {
    if (known[root]) continue;
    known[root] = 1; level[root] = 0;
    const frontier = [];
    const push = (r) => { for (const [o, d, w] of adj[r]) if (!known[o]) frontier.push([w, r, o, d]); };
    push(root);
    while (frontier.length) {
      let bi = 0;
      for (let f = 1; f < frontier.length; f++) if (frontier[f][0] > frontier[bi][0]) bi = f;
      const [, from, to, d] = frontier[bi];
      frontier[bi] = frontier[frontier.length - 1];
      frontier.pop();
      if (known[to]) continue;
      known[to] = 1;
      level[to] = level[from] - d; // d = level(from) - level(to)
      push(to);
    }
  }

  // Water bordering walkable shallows keeps the level the shallows gave it.
  const shoreLevel = new Set();
  for (const k of votes.keys()) {
    const a = Math.floor(k / 1e6), b = k % 1e6;
    if (regionWet[a] && regionBlue[b] > 0.5) shoreLevel.add(a);
    if (regionWet[b] && regionBlue[a] > 0.5) shoreLevel.add(b);
  }

  // Water never stands above the land around it: pull each water region down to its
  // lowest neighbouring land.
  const shoreMin = new Float32Array(R).fill(Infinity);
  for (let i = 0; i < n; i++) {
    if (region[i] < 0 || !wet[i]) continue;
    for (const k of neighbours(i)) {
      if (region[k] >= 0 && !wet[k]) shoreMin[region[i]] = Math.min(shoreMin[region[i]], level[region[k]]);
    }
  }
  for (const k of votes.keys()) {
    // land across a cliff from water counts too
    const a = Math.floor(k / 1e6), b = k % 1e6;
    if (regionWet[a] && !regionWet[b]) shoreMin[a] = Math.min(shoreMin[a], level[b]);
    if (regionWet[b] && !regionWet[a]) shoreMin[b] = Math.min(shoreMin[b], level[a]);
  }
  for (let r = 0; r < R; r++) if (regionWet[r] && !shoreLevel.has(r) && isFinite(shoreMin[r])) level[r] = Math.min(level[r], shoreMin[r]);

  // Islets: land whose edge (cliffs aside) is mostly water, and which no bridge or
  // stairs reach, is a sandbank you swim onto: it sits just above the water.
  // Walkable shallows drawn as water (Sunken City) lie level with the water.
  const touchW = new Map(), touchAll = new Map(), wlv = new Map(), reached = new Set();
  for (let i = 0; i < n; i++) {
    const r = region[i];
    if (r < 0 || regionWet[r]) continue;
    for (const k of neighbours(i)) {
      if (region[k] === r) continue;
      if (bridge[k] || kind[k] === CELL.RAMP) { reached.add(r); continue; }
      if (region[k] < 0) continue;
      bump(touchAll, r);
      if (regionWet[region[k]]) { bump(touchW, r); wlv.set(r, Math.max(wlv.get(r) ?? -1e9, level[region[k]])); }
    }
  }
  for (const [r, nW] of touchW) {
    if (regionBlue[r] > 0.5) { level[r] = wlv.get(r); continue; }
    // Every cliff it shares goes up from it: it is a beach at the foot of cliffs.
    const underCliffs = adj[r].every(([o, , w]) => w < 0.5 || regionWet[o] || level[o] > level[r]);
    const islet = nW > 0.6 * touchAll.get(r) && sizes[r] < 400;
    if (((islet && !reached.has(r)) || (underCliffs && sizes[r] < 1200)) && level[r] > wlv.get(r)) level[r] = wlv.get(r) + 3;
  }

  // ---- per-cell output ----
  const out = new Int16Array(n).fill(NONE);
  for (let i = 0; i < n; i++) {
    if (region[i] >= 0) out[i] = Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, Math.round(level[region[i]])));
  }
  // Bridges: the deck sits at the land level at its ends; underneath is the water level.
  const deck = new Int16Array(n).fill(NONE);
  const spread = (target, pick) => {
    const q = [];
    for (let i = 0; i < n; i++) {
      if (!bridge[i]) continue;
      let best = NONE;
      for (const k of neighbours(i)) if (region[k] >= 0 && pick(k)) best = Math.max(best, out[k]);
      if (best !== NONE) { target[i] = best; q.push(i); }
    }
    for (let h = 0; h < q.length; h++) {
      const i = q[h];
      for (const k of neighbours(i)) if (bridge[k] && target[k] === NONE) { target[k] = target[i]; q.push(k); }
    }
  };
  spread(deck, (k) => !wet[k]);
  // One height per bridge: the highest of its ends.
  const done = new Uint8Array(n);
  for (let i = 0; i < n; i++) {
    if (!bridge[i] || done[i]) continue;
    const comp = [i];
    done[i] = 1;
    let best = NONE;
    for (let h = 0; h < comp.length; h++) {
      best = Math.max(best, deck[comp[h]]);
      for (const k of neighbours(comp[h])) if (bridge[k] && !done[k]) { done[k] = 1; comp.push(k); }
    }
    for (const j of comp) deck[j] = best;
  }
  const under = new Int16Array(n).fill(NONE);
  spread(under, (k) => wet[k]);
  for (let i = 0; i < n; i++) {
    if (!bridge[i]) continue;
    out[i] = under[i] !== NONE ? under[i] : deck[i] !== NONE ? deck[i] - 4 : 0;
  }
  return { cw, ch, kind, level: out, deck };
}
