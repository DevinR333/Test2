#!/usr/bin/env python3
"""Builds the game's world data from the oracles-disasm sources.

Reads room layouts, tilesets, graphics and palettes straight from the disassembly (no ROM) and
writes, into the output directory:

  metatiles.rgba  every distinct 16x16 metatile used by the worlds, 64 per row, full colour
  <world>.map     one file per world (see WORLDS): header, then per metatile a u16 atlas index
                  and a u8 collision byte; rooms are laid edge to edge so the world is seamless
  <world>.names   area name per screen (Holodrum and Labrynna only), one line each, row by row
  <world>.png     a preview of the whole world (only with --previews)

Map file layout (little endian):
  char[4] "OWLD", u16 version (1), u16 width, u16 height (in metatiles), u16 room_w, u16 room_h,
  then width*height u16 atlas indices, then width*height u8 collision values.

Usage: extract_world.py DISASM_DIR OUT_DIR [--previews]
"""
import os
import re
import struct
import sys

from PIL import Image


def save_rgba(im, path):
    """Raw image for the engine: u32 width, u32 height (little endian), then RGBA bytes."""
    im = im.convert("RGBA")
    with open(path, "wb") as f:
        f.write(im.size[0].to_bytes(4, "little") + im.size[1].to_bytes(4, "little") + im.tobytes())

ROOM_W, ROOM_H = 10, 8          # small (overworld) rooms, in metatiles
ATLAS_COLS = 64
VOID = 0xffff                   # map cell with nothing in it

# name, game, room group, season (Seasons only: 0 spring .. 3 winter), grid size in rooms
WORLDS = [
    ("holodrum", "seasons", 0, "auto", 16, 16),      # every area in its own default season
    ("holodrum_spring", "seasons", 0, 0, 16, 16),
    ("holodrum_summer", "seasons", 0, 1, 16, 16),
    ("holodrum_autumn", "seasons", 0, 2, 16, 16),
    ("holodrum_winter", "seasons", 0, 3, 16, 16),
    ("labrynna", "ages", 0, None, 14, 14),
]


def num(s):
    s = s.strip()
    if s.startswith("$"):
        return int(s[1:], 16)
    if s.lower().startswith("0x"):
        return int(s, 16)
    return int(s)


def lines_of(path):
    with open(path) as f:
        for line in f:
            line = line.split(";", 1)[0].strip()
            if line:
                yield line


class Game:
    def __init__(self, root, game):
        self.root, self.game = root, game
        self.gfx_headers = self._headers(f"data/{game}/gfxHeaders.s", "m_GfxHeaderStart")
        self.unique_headers = self._headers(f"data/{game}/uniqueGfxHeaders.s", "m_UniqueGfxHeaderStart")
        self.pal_headers, self.pal_names = self._palette_headers()
        self.pal_data = self._palette_data()
        self.tilesets = self._tilesets()
        self._gfx_cache = {}
        self.layout_files = self._layout_files()

    # --- gfx -------------------------------------------------------------------------------
    def _headers(self, rel, start_macro):
        """Returns {index: [(file, dest, size_tiles or None, src_offset)]} plus names in .names."""
        out, names, cur = {}, {}, None
        for line in lines_of(os.path.join(self.root, rel)):
            m = re.match(start_macro + r"\s+(\$?\w+)\s*,\s*(\w+)", line)
            if m:
                cur = num(m.group(1))
                names[m.group(2)] = cur
                out[cur] = []
                continue
            m = re.match(r"m_GfxHeader(\w*)\s+(.*)", line)
            if m and cur is not None and m.group(1) in ("", "ForceMode"):
                args = [a.strip() for a in m.group(2).split(",")]
                if not re.fullmatch(r"\$[0-9a-fA-F]+", args[1]):
                    continue        # loads into RAM, not VRAM
                size = num(args[2]) if len(args) > 2 and m.group(1) == "" else None
                offset = num(args[3]) if len(args) > 3 and m.group(1) == "" else 0
                out[cur].append((args[0], num(args[1]), size, offset))
        out_names = getattr(self, "names", {})
        out_names.update(names)
        self.names = out_names
        return out

    def gfx_file(self, name):
        if name in self._gfx_cache:
            return self._gfx_cache[name]
        for d in (f"gfx/{self.game}", "gfx/common", f"gfx_compressible/{self.game}", "gfx_compressible/common"):
            p = os.path.join(self.root, d, name + ".png")
            if os.path.exists(p):
                data = png_to_2bpp(p)
                self._gfx_cache[name] = data
                return data
            p = os.path.join(self.root, d, name + ".bin")
            if os.path.exists(p):
                with open(p, "rb") as f:
                    data = f.read()
                self._gfx_cache[name] = data
                return data
        raise FileNotFoundError(name)

    def _layout_files(self):
        """tileset layout index -> (mappings file, collisions file); several indices share files."""
        out, pending = {}, []
        for line in lines_of(os.path.join(self.root, f"data/{self.game}/tilesetHeaders.s")):
            m = re.match(r"tilesetLayoutGroup(\w\w):$", line)
            if m:
                pending.append(int(m.group(1), 16))
                continue
            m = re.match(r"m_TilesetLayoutHeader\s+\$0([01])\s+(\w+)", line)
            if m:
                for idx in pending:
                    out.setdefault(idx, [None, None])[int(m.group(1))] = m.group(2)
                if m.group(1) == "1":
                    pending = []
        return out

    # --- palettes ----------------------------------------------------------------------------
    def _palette_headers(self):
        out, names, cur = {}, {}, None
        for line in lines_of(os.path.join(self.root, f"data/{self.game}/paletteHeaders.s")):
            m = re.match(r"m_PaletteHeaderStart\s+(\$?\w+)\s*,\s*(\w+)", line)
            if m:
                cur = num(m.group(1))
                names[m.group(2)] = cur
                out[cur] = []
                continue
            m = re.match(r"m_PaletteHeader(Bg|Spr)\s+(\w+)\s*,\s*(\w+)\s*,\s*(\w+)", line)
            if m and cur is not None:
                out[cur].append((m.group(1), num(m.group(2)), num(m.group(3)), m.group(4)))
        return out, names

    def _palette_data(self):
        data, cur = {}, None
        for line in lines_of(os.path.join(self.root, f"data/{self.game}/paletteData.s")):
            m = re.match(r"(\w+):$", line)
            if m:
                cur = m.group(1)
                data[cur] = []
                continue
            m = re.match(r"m_RGB16\s+(\$?\w+)\s+(\$?\w+)\s+(\$?\w+)", line)
            if m and cur is not None:
                # stacked labels stay empty; label_colours reads on into the next one
                data[cur].append(tuple(num(m.group(i)) for i in (1, 2, 3)))
        return data

    def label_colours(self, label):
        """Colours from `label` on, continuing into the following labels like the ROM does."""
        keys = list(self.pal_data.keys())
        i = keys.index(label)
        out = []
        for k in keys[i:]:
            out.extend(self.pal_data[k])
        return out

    # --- tilesets ----------------------------------------------------------------------------
    def _tilesets(self):
        """Returns list indexed by tileset number; each entry a list of 8-tuples of raw tokens
        (one per season for Seasons' seasonal tilesets, else a single entry)."""
        lines = list(lines_of(os.path.join(self.root, f"data/{self.game}/tilesets.s")))
        labels, order, cur = {}, [], None
        seasonal = []
        for line in lines:
            m = re.match(r"(\w+):$", line)
            if m:
                cur = m.group(1)
                labels[cur] = []
                continue
            m = re.match(r"m_SeasonalTileset\s+(\w+)", line)
            if m and cur == "tilesetData":
                labels[cur].append(("seasonal", m.group(1)))
                continue
            m = re.match(r"\.db\s+(.*)", line)
            if m and cur is not None:
                for tok in m.group(1).split(","):
                    labels[cur].append(tok.strip())
        main = labels["tilesetData"]
        out, buf = [], []
        for tok in main:
            if isinstance(tok, tuple):
                vals = labels[tok[1]]
                out.append([self._resolve(vals[i * 8:i * 8 + 8]) for i in range(4)])
                continue
            buf.append(tok)
            if len(buf) == 8:
                out.append([self._resolve(buf)])
                buf = []
        return out

    def _resolve(self, toks):
        res = []
        for t in toks:
            if t.startswith("$") or t[0].isdigit():
                res.append(num(t))
            elif t.startswith("UNIQUE_GFXH_"):
                res.append(self.unique_names[t])
            elif t.startswith("GFXH_"):
                res.append(self.names[t])
            elif t.startswith("PALH_"):
                res.append(self.pal_names[t])
            else:
                raise ValueError(t)
        return tuple(res)

    @property
    def unique_names(self):
        if not hasattr(self, "_unique_names"):
            self._unique_names = {}
            for line in lines_of(os.path.join(self.root, f"data/{self.game}/uniqueGfxHeaders.s")):
                m = re.match(r"m_UniqueGfxHeaderStart\s+(\$?\w+)\s*,\s*(\w+)", line)
                if m:
                    self._unique_names[m.group(2)] = num(m.group(1))
        return self._unique_names

    def tileset(self, index, season):
        variants = self.tilesets[index]
        return variants[season if len(variants) == 4 and season is not None else 0]

    # --- rendering ---------------------------------------------------------------------------
    def build_vram(self, ts):
        vram = [bytearray(0x2000), bytearray(0x2000)]
        def load(entries):
            for name, dest, size, offset in entries:
                bank, addr = dest & 1, dest & 0xfff0
                if not 0x8000 <= addr < 0xa000:
                    continue
                data = self.gfx_file(name)[offset:]
                if size is not None:
                    data = data[:size * 16]
                end = min(len(data), 0xa000 - addr)
                vram[bank][addr - 0x8000:addr - 0x8000 + end] = data[:end]
        load(self.gfx_headers.get(ts[3], []))
        load(self.unique_headers.get(ts[2], []))
        return vram

    def build_palettes(self, ts):
        pals = [[(31, 31, 31), (20, 20, 20), (10, 10, 10), (0, 0, 0)] for _ in range(8)]
        for kind, first, count, label in self.pal_headers.get(ts[4], []):
            if kind != "Bg":
                continue
            cols = self.label_colours(label)
            for p in range(count):
                pals[first + p] = cols[p * 4:p * 4 + 4]
        return pals

    def metatiles(self, ts):
        """Returns (list of 256 16x16 RGBA images as bytes, collision bytes)."""
        vram = self.build_vram(ts)
        pals = self.build_palettes(ts)
        mfile, cfile = self.layout_files[ts[5]]
        with open(os.path.join(self.root, f"tileset_layouts/{self.game}/{mfile}.bin"), "rb") as f:
            mapping = f.read()
        with open(os.path.join(self.root, f"tileset_layouts/{self.game}/{cfile}.bin"), "rb") as f:
            coll = f.read()
        tiles = []
        for m in range(256):
            px = bytearray(16 * 16 * 4)
            for q in range(4):
                idx, attr = mapping[m * 8 + q], mapping[m * 8 + 4 + q]
                draw_tile(px, (q & 1) * 8, (q >> 1) * 8, vram, idx, attr, pals)
            tiles.append(bytes(px))
        return tiles, coll


def png_to_2bpp(path):
    im = Image.open(path)
    if im.mode != "P":
        im = im.convert("L")
        lut = lambda v: 3 - (v * 4 // 256)
    else:
        lut = lambda v: v & 3
    w, h = im.size
    px = im.load()
    out = bytearray()
    for ty in range(h // 8):
        for tx in range(w // 8):
            for y in range(8):
                lo = hi = 0
                for x in range(8):
                    c = lut(px[tx * 8 + x, ty * 8 + y])
                    lo |= (c & 1) << (7 - x)
                    hi |= (c >> 1 & 1) << (7 - x)
                out += bytes((lo, hi))
    return bytes(out)


def draw_tile(px, ox, oy, vram, idx, attr, pals):
    bank = attr >> 3 & 1
    base = 0x1000 + idx * 16 if idx < 128 else 0x0800 + (idx - 128) * 16
    pal = pals[attr & 7]
    xf, yf = attr & 0x20, attr & 0x40
    for y in range(8):
        sy = 7 - y if yf else y
        lo, hi = vram[bank][base + sy * 2], vram[bank][base + sy * 2 + 1]
        for x in range(8):
            sx = 7 - x if xf else x
            c = (lo >> (7 - sx) & 1) | (hi >> (7 - sx) & 1) << 1
            r, g, b = pal[c]
            o = ((oy + y) * 16 + ox + x) * 4
            px[o:o + 4] = bytes((r * 255 // 31, g * 255 // 31, b * 255 // 31, 255))


def default_seasons(root):
    """Per overworld room, the season Seasons shows on arrival (code/bank1.s determineSeasonForRoomPack):
    the room's pack indexes roomPackSeasonTable; Horon Village (pack 0) gets spring here instead of a
    random season, and the companion region ($f0+) gets summer, its layout for Ricky."""
    with open(os.path.join(root, "rooms/seasons/roomPacks.bin"), "rb") as f:
        packs = f.read()
    table = []
    for line in lines_of(os.path.join(root, "data/seasons/roomPackSeasonTable.s")):
        if line.startswith(".db"):
            table += [num(t) for t in line[3:].split()]
    return [0 if p == 0 else 1 if p >= 0xf0 else table[p] for p in packs]


def map_texts(root, game):
    """TX_03xx strings: the area names the map screen shows."""
    names, cur, grab = {}, None, False
    with open(os.path.join(root, f"text/{game}/text.yaml")) as f:
        for line in f:
            m = re.match(r"\s*- name: TX_03([0-9a-f]{2})$", line)
            if m:
                cur, grab = int(m.group(1), 16), False
                continue
            if cur is not None and line.strip().startswith("text:"):
                grab = True
                continue
            if grab and line.strip():
                text = re.sub(r"\\[a-z]+\([^)]*\)", "", line).strip()
                if text and cur not in names:
                    names[cur] = text
                grab = False
    return names


def area_names(root, game, gw, gh, maku_room):
    """Per overworld screen, its area name from presentMapTextIndices. Entries with bit 7 set pick
    their text in code from story progress (code/bank2.s mapGetRoomText); those take the Maku
    Tree's name on its screen and their neighbour's name elsewhere."""
    vals = []
    for line in lines_of(os.path.join(root, f"data/{game}/mapTextAndPopups.s")):
        if line.startswith("pastMapTextIndices"):
            break
        if line.startswith(".db"):
            vals += [num(t) for t in line[3:].split()]
    texts = map_texts(root, game)
    maku = next((k for k, v in texts.items() if v.endswith("Maku Tree")), None)
    names = [None] * (gw * gh)
    for i in range(gw * gh):
        v = vals[i]
        if i == (maku_room >> 4) * gw + (maku_room & 15) and maku is not None:
            names[i] = texts[maku]
        elif not v & 0x80:
            names[i] = texts.get(v)
    for _ in range(4):                       # fill special screens from their neighbours
        for i in range(gw * gh):
            if names[i] is None:
                x, y = i % gw, i // gw
                for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
                    if 0 <= nx < gw and 0 <= ny < gh and names[ny * gw + nx]:
                        names[i] = names[ny * gw + nx]
                        break
    return [n or "" for n in names]


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(2)
    root, out = sys.argv[1], sys.argv[2]
    previews = "--previews" in sys.argv
    os.makedirs(out, exist_ok=True)
    games = {g: Game(root, g) for g in ("seasons", "ages")}
    atlas, atlas_index, ts_cache = [], {}, {}
    seasons_auto = default_seasons(root)
    for name, game, group, season, gw, gh in WORLDS:
        g = games[game]
        with open(os.path.join(root, f"rooms/{game}/group{group}Tilesets.bin"), "rb") as f:
            room_ts = f.read()
        W, H = gw * ROOM_W, gh * ROOM_H
        cells = [0] * (W * H)
        colls = [0] * (W * H)
        for r in range(gw * gh):
            room = (r // gw) * 16 + r % gw          # rooms are numbered on a 16-wide grid
            room_season = seasons_auto[room] if season == "auto" else season
            ts = g.tileset(room_ts[room] & 0x7f, room_season)
            key = (game, ts)
            if key not in ts_cache:
                ts_cache[key] = g.metatiles(ts)
            tiles, coll = ts_cache[key]
            path = os.path.join(root, f"rooms/{game}/small/room{ts[6]:02x}{room:02x}.bin")
            with open(path, "rb") as f:
                layout = f.read()
            rx, ry = r % gw, r // gw
            if len(set(layout[:ROOM_W * ROOM_H])) <= 2:
                # unused filler screen (the "N"/"X" rooms): nothing to draw, nothing to walk on
                for i in range(ROOM_W * ROOM_H):
                    x, y = rx * ROOM_W + i % ROOM_W, ry * ROOM_H + i // ROOM_W
                    cells[y * W + x], colls[y * W + x] = VOID, 0xff
                continue
            for i, mt in enumerate(layout[:ROOM_W * ROOM_H]):
                img = tiles[mt]
                if img not in atlas_index:
                    atlas_index[img] = len(atlas)
                    atlas.append(img)
                x, y = rx * ROOM_W + i % ROOM_W, ry * ROOM_H + i // ROOM_W
                cells[y * W + x] = atlas_index[img]
                colls[y * W + x] = coll[mt]
        with open(os.path.join(out, name + ".map"), "wb") as f:
            f.write(b"OWLD" + struct.pack("<HHHHH", 1, W, H, ROOM_W, ROOM_H))
            f.write(struct.pack(f"<{W * H}H", *cells))
            f.write(bytes(colls))
        if season in ("auto", None):
            # one name per screen, row by row, for the banner shown on entering a new area
            maku_room = 0xc9 if game == "seasons" else 0x38
            with open(os.path.join(out, name + ".names"), "w") as f:
                f.write("\n".join(area_names(root, game, gw, gh, maku_room)) + "\n")
        if previews:
            im = Image.new("RGBA", (W * 16, H * 16))
            for i, c in enumerate(cells):
                if c == VOID:
                    continue
                im.paste(Image.frombytes("RGBA", (16, 16), atlas[c]), ((i % W) * 16, (i // W) * 16))
            im.save(os.path.join(out, name + ".png"))
        print(f"{name}: {W}x{H} metatiles, atlas now {len(atlas)}")
    rows = (len(atlas) + ATLAS_COLS - 1) // ATLAS_COLS
    sheet = Image.new("RGBA", (ATLAS_COLS * 16, rows * 16))
    for i, img in enumerate(atlas):
        sheet.paste(Image.frombytes("RGBA", (16, 16), img), ((i % ATLAS_COLS) * 16, (i // ATLAS_COLS) * 16))
    save_rgba(sheet, os.path.join(out, "metatiles.rgba"))


if __name__ == "__main__":
    main()
