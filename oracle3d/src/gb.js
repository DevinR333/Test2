// Thin wrapper around the WasmBoy core. It runs the game, reads and writes Game Boy
// memory by bank, and decodes the game's RAM structures that the 3D renderer needs.
// Used by both the browser app and the Node capture tool.

// Offsets inside WasmBoy's linear memory (see WasmBoy's core/constants.ts).
const VRAM = 0x0800; // 2 banks of $2000
const WRAM = 0x4800; // 8 banks of $1000
const HIGH = 0xc800; // $e000-$ffff (OAM, IO, HRAM)
const PALETTES = 0x10800; // 8 BG palettes then 8 sprite palettes, 8 bytes each

// RAM labels from oracles-disasm (include/wram.s, include/hram.s), as named in the
// .sym file the decomp writes. tools/capture-world.mjs re-reads them from the .sym and
// stores them with the captured world, so a rebuilt decomp with shifted RAM still works.
export const SYMBOL_NAMES = [
  'wActiveGroup', 'wActiveRoom', 'wRoomPack', 'wRoomStateModifier', 'wActiveCollisions',
  'wRoomIsLarge', 'wScrollMode', 'wScreenTransitionDirection', 'wScreenOffsetY', 'wScreenOffsetX',
  'wRoomCollisions', 'wRoomLayout', 'w3TileMappingData', 'w3TileCollisions',
  'wWarpDestGroup', 'wWarpDestRoom', 'wWarpTransition', 'wWarpDestPos', 'wWarpTransition2',
  'w1Link', 'hCameraY', 'hCameraX', 'wPaletteThread_mode', 'roomPackSeasonTable',
  'wLinkObjectIndex', 'wTextIsActive', 'wDungeonIndex', 'wOpenedMenuType',
];

// The 5-bit GBC colour channels, with the usual curve to look like a GBC screen
// rather than raw RGB555 (less washed-out greens).
const LUT5 = new Uint8Array(32);
for (let i = 0; i < 32; i++) LUT5[i] = Math.round(Math.pow(i / 31, 0.92) * 255);

export function rgb555(v) {
  return [LUT5[v & 31], LUT5[(v >> 5) & 31], LUT5[(v >> 10) & 31]];
}

export class GB {
  constructor(exports, symbols) {
    this.e = exports;
    this.m = new Uint8Array(exports.memory.buffer);
    this.sym = symbols;
    this.romBase = exports.CARTRIDGE_ROM_LOCATION.valueOf();
    this.frameBase = exports.FRAME_LOCATION.valueOf();
  }

  loadRom(rom) {
    this.m.set(rom, this.romBase);
    // config(bootRom, gbc, audioBatch, gfxBatch, timersBatch, noScanline, audioAccumulate, tileRender, tileCache, audioDebug)
    this.e.config(0, 1, 0, 0, 0, 0, 1, 0, 0, 0);
  }

  frame(buttons = 0) {
    const b = (bit) => ((buttons >> bit) & 1);
    // setJoypadState(up, right, down, left, a, b, select, start)
    this.e.setJoypadState(b(BTN.UP), b(BTN.RIGHT), b(BTN.DOWN), b(BTN.LEFT), b(BTN.A), b(BTN.B), b(BTN.SELECT), b(BTN.START));
    this.e.executeFrame();
  }

  // Address of a WRAM byte. $c000-$cfff is bank 0; $d000-$dfff is the given bank.
  wramIndex(addr, bank = 1) {
    return addr < 0xd000 ? WRAM + (addr - 0xc000) : WRAM + bank * 0x1000 + (addr - 0xd000);
  }

  rd(addr, bank = 1) {
    if (addr >= 0xe000) return this.m[HIGH + (addr - 0xe000)];
    return this.m[this.wramIndex(addr, bank)];
  }

  wr(addr, v, bank = 1) {
    if (addr >= 0xe000) this.m[HIGH + (addr - 0xe000)] = v;
    else this.m[this.wramIndex(addr, bank)] = v;
  }

  s(name) {
    const v = this.sym[name];
    if (v === undefined) throw new Error(`missing symbol ${name}`);
    return v;
  }

  // Read a RAM variable by its decomp name.
  v(name, offset = 0) {
    const { bank, addr } = this.s(name);
    return this.rd(addr + offset, bank || 1);
  }

  setv(name, value, offset = 0) {
    const { bank, addr } = this.s(name);
    this.wr(addr + offset, value, bank || 1);
  }

  romByte(bank, addr) {
    return this.m[this.romBase + bank * 0x4000 + (addr - (bank ? 0x4000 : 0))];
  }

  setRomByte(bank, addr, v) {
    this.m[this.romBase + bank * 0x4000 + (addr - (bank ? 0x4000 : 0))] = v;
  }

  io(reg) {
    return this.m[HIGH + (0xff00 + reg - 0xe000)];
  }

  // 15-bit colour of palette entry. obj=true for sprite palettes.
  color(pal, idx, obj = false) {
    const o = PALETTES + (obj ? 0x40 : 0) + pal * 8 + idx * 2;
    return this.m[o] | (this.m[o + 1] << 8);
  }

  // Pixel colour index (0-3) of an 8x8 tile in VRAM.
  // tileAddr is a VRAM offset ($0000-$17ff) in the given bank.
  tilePixel(bank, tileAddr, x, y) {
    const a = VRAM + bank * 0x2000 + tileAddr + y * 2;
    const lo = this.m[a], hi = this.m[a + 1];
    return ((lo >> (7 - x)) & 1) | (((hi >> (7 - x)) & 1) << 1);
  }

  // VRAM offset of a BG tile index, honouring LCDC bit 4 ($8000 vs $8800 addressing).
  bgTileAddr(t) {
    if (this.io(0x40) & 0x10) return t * 16;
    return 0x1000 + ((t << 24) >> 24) * 16;
  }

  // Draw all 256 metatiles of the loaded tileset into a 256x256 RGBA image (16x16 grid).
  // Also returns the collision byte of each metatile.
  metatileAtlas() {
    const map = this.s('w3TileMappingData');
    const coll = this.s('w3TileCollisions');
    const rgba = new Uint8Array(256 * 256 * 4);
    const collisions = new Uint8Array(256);
    const pals = [];
    for (let p = 0; p < 8; p++) for (let c = 0; c < 4; c++) pals.push(rgb555(this.color(p, c)));
    for (let id = 0; id < 256; id++) {
      collisions[id] = this.rd(coll.addr + id, coll.bank);
      const ox = (id & 15) * 16, oy = (id >> 4) * 16;
      for (let q = 0; q < 4; q++) {
        const t = this.rd(map.addr + id * 8 + q, map.bank);
        const at = this.rd(map.addr + id * 8 + 4 + q, map.bank);
        const addr = this.bgTileAddr(t), vb = (at >> 3) & 1, pal = at & 7;
        for (let y = 0; y < 8; y++) for (let x = 0; x < 8; x++) {
          const c = this.tilePixel(vb, addr, at & 0x20 ? 7 - x : x, at & 0x40 ? 7 - y : y);
          const [r, g, b] = pals[pal * 4 + c];
          const i = ((oy + (q >> 1) * 8 + y) * 256 + ox + (q & 1) * 8 + x) * 4;
          rgba[i] = r; rgba[i + 1] = g; rgba[i + 2] = b; rgba[i + 3] = 255;
        }
      }
    }
    return { rgba, collisions };
  }

  // Current room layout (metatile ids), width x height.
  roomLayout() {
    const large = this.v('wRoomIsLarge');
    const w = large ? 15 : 10, h = large ? 11 : 8;
    const base = this.s('wRoomLayout');
    const out = new Uint8Array(w * h);
    for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) out[y * w + x] = this.rd(base.addr + y * 16 + x, base.bank);
    return { w, h, tiles: out };
  }

  screen() {
    return this.m.subarray(this.frameBase, this.frameBase + 160 * 144 * 3);
  }
}

export const BTN = { RIGHT: 0, LEFT: 1, UP: 2, DOWN: 3, A: 4, B: 5, SELECT: 6, START: 7 };

// Parse a WLA-DX .sym file (as written by the oracles-disasm build) into
// { name: { bank, addr } } for the names we need.
export function parseSym(text, names = SYMBOL_NAMES) {
  const want = new Set(names);
  const out = {};
  for (const line of text.split('\n')) {
    // Labels look like "03:d000 w3TileMappingData"; struct instances like "0000d000 w1Link".
    const m = line.match(/^([0-9a-f]{2}):([0-9a-f]{4}) (\S+)$/i) || line.match(/^()([0-9a-f]{8}) (\S+)$/i);
    if (m && want.has(m[3]) && !out[m[3]]) {
      const addr = parseInt(m[2], 16) & 0xffff;
      out[m[3]] = { bank: m[1] ? parseInt(m[1], 16) : 1, addr };
    }
  }
  for (const n of names) if (!out[n]) throw new Error(`symbol ${n} not found in .sym`);
  return out;
}
