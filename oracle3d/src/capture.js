// Builds the data for the seamless 3D world from an Oracle of Seasons ROM.
//
// The game does the decoding itself. For every room and season we tell the game to warp
// there (writing wWarpDest* the way the gale seed menu does), let it load the room, and
// read back the room layout, the tileset's metatile graphics and its collision table.
// Every distinct 16x16 metatile image goes into one shared pool; a tileset animation
// frame is just 256 indices into that pool.
//
// Runs anywhere with WebAssembly: the Node tool (tools/capture-world.mjs) and a Web
// Worker inside the app (so native builds never ship any of the game's graphics).
import { GB, BTN } from './gb.js';

export const WORLD_VERSION = 2;
export const POOL_COLS = 256; // metatiles per pool row (the pool is 4096 px wide)

const ANIM_FRAMES = 4, ANIM_STEP = 8;

// Two 32-bit FNV-1a hashes in one pass; together they make a safe dedupe key.
function hash2(bytes, start = 0, end = bytes.length) {
  let a = 0x811c9dc5, b = 0x01000193 ^ 0x5bd1e995;
  for (let i = start; i < end; i++) {
    a = Math.imul(a ^ bytes[i], 0x01000193);
    b = Math.imul(b ^ bytes[i], 0x5bd1e995) ^ (b >>> 15);
  }
  return `${(a >>> 0).toString(36)}.${(b >>> 0).toString(36)}`;
}

export function romTitle(rom) {
  let s = '';
  for (let i = 0x134; i < 0x13f && rom[i]; i++) s += String.fromCharCode(rom[i]);
  return s;
}

export function romId(rom) {
  return hash2(rom);
}

// wasmModule: compiled WebAssembly.Module of the WasmBoy core.
// onProgress(done, total, message) is called now and then.
export function captureWorld({ wasmModule, rom, symbols, onProgress = () => {} }) {
  const imports = { index: { consoleLog() {}, consoleLogTimeout() {} }, env: { abort() { throw new Error('wasm abort'); } } };
  const newGB = () => new GB(new WebAssembly.Instance(wasmModule, imports).exports, symbols);

  // ---- boot a new game and play until Link is standing in Holodrum ----
  onProgress(0, 1, 'Starting the game');
  let snapshot = null;
  {
    const gb = newGB();
    gb.loadRom(rom);
    for (let f = 0; f < 6000 && !snapshot; f++) {
      // Mash Start/A through the title screen, file select and name entry.
      let b = 0;
      if (f > 300 && f % 120 < 5) b |= 1 << BTN.START;
      if (f > 300 && f % 120 > 60 && f % 120 < 65) b |= 1 << BTN.A;
      gb.frame(b);
      if (f > 2600 && f % 30 === 0 && gb.v('w1Link') !== 0 && gb.v('wActiveGroup') === 0 && gb.v('wPaletteThread_mode') === 0) {
        for (let i = 0; i < 120; i++) gb.frame();
        gb.e.saveState();
        snapshot = new Uint8Array(gb.m);
      }
    }
    if (!snapshot) throw new Error('Could not reach gameplay with this ROM.');
  }

  // ---- metatile pool ----
  const pool = []; // Uint8Array(1024) each
  const poolIndex = new Map();
  const tile = new Uint8Array(1024);
  function addFrame(rgba) {
    const idx = new Uint16Array(256);
    for (let id = 0; id < 256; id++) {
      const ox = (id & 15) * 16, oy = (id >> 4) * 16;
      for (let y = 0; y < 16; y++) tile.set(rgba.subarray(((oy + y) * 256 + ox) * 4, ((oy + y) * 256 + ox + 16) * 4), y * 64);
      const key = hash2(tile);
      let p = poolIndex.get(key);
      if (p === undefined) { p = pool.length; poolIndex.set(key, p); pool.push(tile.slice()); }
      idx[id] = p;
    }
    return idx;
  }

  // ---- tilesets ----
  // What makes a tileset look the way it does: metatile mappings, collisions, BG
  // palettes and BG tile graphics. Hashing these is much cheaper than drawing the atlas.
  const map = symbols.w3TileMappingData, coll = symbols.w3TileCollisions;
  function tilesetKey(gb) {
    const m = gb.m;
    const w3 = gb.wramIndex(map.addr, map.bank);
    const c3 = gb.wramIndex(coll.addr, coll.bank);
    return [
      hash2(m, w3, w3 + 0x800), hash2(m, c3, c3 + 0x100),
      hash2(m, 0x10800, 0x10840), // BG palettes
      hash2(m, 0x0800 + 0x0800, 0x0800 + 0x1800), // VRAM bank 0 $8800-$97ff
      hash2(m, 0x2800 + 0x0800, 0x2800 + 0x1800), // VRAM bank 1
      gb.io(0x40) & 0x10,
    ].join('|');
  }
  const tilesets = []; // { frames: [Uint16Array], collisions, collisionMode }
  const tilesetByKey = new Map();
  const frameRefs = new Map(); // frame key -> Uint16Array (shared between tilesets)

  function frameOf(gb, key) {
    let f = frameRefs.get(key);
    if (!f) { f = addFrame(gb.metatileAtlas().rgba); frameRefs.set(key, f); }
    return f;
  }

  function captureRoom(group, room, season) {
    const gb = newGB();
    gb.m.set(snapshot);
    gb.e.loadState();
    // Force the season: every room pack's default season becomes `season`.
    const t = symbols.roomPackSeasonTable;
    for (let i = 0; i < 0x20; i++) gb.setRomByte(t.bank, t.addr + i, season);
    gb.setv('wRoomStateModifier', season);
    gb.setv('wWarpDestGroup', 0x80 | group);
    gb.setv('wWarpDestRoom', room);
    gb.setv('wWarpTransition', 0);
    gb.setv('wWarpDestPos', 0x55);
    gb.setv('wWarpTransition2', 1); // instant
    let ok = false;
    for (let f = 0; f < 300; f++) {
      gb.frame();
      if (f > 8 && gb.v('wActiveRoom') === room && gb.v('wActiveGroup') === group && gb.v('wWarpTransition2') === 0) { ok = true; break; }
    }
    if (!ok) return null;
    for (let i = 0; i < 10; i++) gb.frame();
    for (let i = 0; i < 60 && gb.v('wPaletteThread_mode') !== 0; i++) gb.frame();
    const layout = gb.roomLayout();
    if (layout.w !== 10) return null;

    const key0 = tilesetKey(gb);
    let ts = tilesetByKey.get(key0);
    if (ts === undefined) {
      // New tileset: sample a few frames so animated tiles (water, flowers) move.
      const frames = [];
      const seen = new Set();
      for (let k = 0; k < ANIM_FRAMES; k++) {
        const key = tilesetKey(gb);
        if (!seen.has(key)) { seen.add(key); frames.push(frameOf(gb, key)); }
        for (let i = 0; i < ANIM_STEP; i++) gb.frame();
      }
      const collisions = new Uint8Array(256);
      for (let i = 0; i < 256; i++) collisions[i] = gb.rd(coll.addr + i, coll.bank);
      ts = tilesets.length;
      tilesets.push({ frames, collisions, collisionMode: gb.v('wActiveCollisions') });
      tilesetByKey.set(key0, ts);
    }
    return { layout: layout.tiles, pack: gb.v('wRoomPack'), tileset: ts };
  }

  // ---- every room ----
  const plan = [{ group: 0, seasons: [0, 1, 2, 3] }, { group: 1, seasons: [0] }];
  const total = plan.reduce((n, p) => n + p.seasons.length * 256, 0);
  let done = 0;
  const worlds = [];
  for (const { group, seasons } of plan) {
    const world = { group, width: 16, height: 16, seasons: [] };
    for (const season of seasons) {
      const rooms = [];
      for (let room = 0; room < 256; room++) {
        const r = captureRoom(group, room, season);
        rooms.push(r ? { pack: r.pack, tileset: r.tileset, layout: toB64(r.layout) } : null);
        if (++done % 8 === 0) onProgress(done, total, group === 0 ? `Holodrum, ${['spring', 'summer', 'autumn', 'winter'][season]}` : 'Subrosia');
      }
      world.seasons.push(rooms);
    }
    worlds.push(world);
  }

  // ---- assemble ----
  const rows = Math.ceil(pool.length / POOL_COLS);
  const width = POOL_COLS * 16, height = rows * 16;
  const pixels = new Uint8Array(width * height * 4);
  pool.forEach((buf, p) => {
    const ox = (p % POOL_COLS) * 16, oy = Math.floor(p / POOL_COLS) * 16;
    for (let y = 0; y < 16; y++) pixels.set(buf.subarray(y * 64, y * 64 + 64), ((oy + y) * width + ox) * 4);
  });
  const t = symbols.roomPackSeasonTable;
  const seasonTable = [];
  for (let i = 0; i < 0x20; i++) seasonTable.push(rom[t.bank * 0x4000 + t.addr - 0x4000 + i]);
  const json = {
    version: WORLD_VERSION,
    romId: romId(rom),
    symbols,
    seasonTable,
    poolCols: POOL_COLS,
    poolSize: pool.length,
    tilesets: tilesets.map((ts) => ({
      frames: ts.frames.map((f) => toB64(new Uint8Array(f.buffer))),
      collisionMode: ts.collisionMode,
      collisions: toB64(ts.collisions),
    })),
    worlds,
  };
  onProgress(total, total, 'Done');
  return { json, pool: { width, height, data: pixels } };
}

function toB64(bytes) {
  let s = '';
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
  return btoa(s);
}
