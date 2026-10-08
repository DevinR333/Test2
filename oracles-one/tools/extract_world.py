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
  char[4] "OWLD", u16 version (2), u16 width, u16 height (in metatiles), u16 room_w, u16 room_h,
  then width*height u16 atlas indices, width*height u8 collision values, width*height u8 metatiles.

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
ATLAS_COLS = 128
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
    """The disassembly's own conversion (tools/gfx/gfx.py png_to_2bpp): colours ranked by brightness
    (white is 0, or black for "spr_" files, which are also stored interleaved as 8x16 columns), plus
    whatever the file's .properties override (invert, interleave, tile_padding)."""
    props = {"invert": os.path.basename(path).startswith("spr_"), "interleave": os.path.basename(path).startswith("spr_"),
             "tile_padding": 0}
    pp = os.path.splitext(path)[0] + ".properties"
    if os.path.exists(pp):
        for line in open(pp):
            k, _, v = line.partition(":")
            v = v.strip()
            if k.strip() in props and v not in ("", "null"):
                props[k.strip()] = int(v) if v.isdigit() else v.lower() == "true"
    im = Image.open(path).convert("RGBA")
    w, h = im.size
    px = im.load()
    palette = []
    for y in range(h):
        for x in range(w):
            if px[x, y] not in palette and len(palette) < 4:
                palette.append(px[x, y])
    for grey in ((255, 255, 255, 255), (0, 0, 0, 255), (0x55, 0x55, 0x55, 255), (0xaa, 0xaa, 0xaa, 255)):
        if len(palette) >= 4:
            break
        if grey not in palette:
            palette.append(grey)
    palette.sort(key=sum)
    if not props["invert"]:
        palette.reverse()
    index = {c: i for i, c in enumerate(palette)}
    darkest = sorted(palette, key=sum)[0]
    tiles = []
    for ty in range(h // 8):
        for tx in range(w // 8):
            t = bytearray()
            for y in range(8):
                lo = hi = 0
                for x in range(8):
                    c = index.get(px[tx * 8 + x, ty * 8 + y], index[darkest])
                    lo |= (c & 1) << (7 - x)
                    hi |= (c >> 1 & 1) << (7 - x)
                t += bytes((lo, hi))
            tiles.append(bytes(t))
    if props["interleave"]:
        cols = max(w // 8, 1)
        rows = [tiles[i:i + cols] for i in range(0, len(tiles), cols)]
        out = []
        for top, bottom in zip(rows[::2], rows[1::2]):
            for a, b in zip(top, bottom):
                out += [a, b]
        tiles = out
    data = b"".join(tiles)
    return data[:len(data) - props["tile_padding"] * 16] if props["tile_padding"] else data


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


def area_names(root, game, gw, gh, maku_room, table="presentMapTextIndices"):
    """Per overworld screen, its area name from the map screen's table (presentMapTextIndices, or
    pastMapTextIndices: Labrynna's past, Subrosia). Entries with bit 7 set pick their text in code
    from story progress (code/bank2.s mapGetRoomText); those take the Maku Tree's name on its screen
    and their neighbour's name elsewhere."""
    vals, on = [], False
    for line in lines_of(os.path.join(root, f"data/{game}/mapTextAndPopups.s")):
        if line.endswith(":") or line.split(":")[0].endswith("MapTextIndices"):
            on = line.startswith(table)
            continue
        if on and line.startswith(".db"):
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


# ---- rooms, areas and warps -------------------------------------------------------------------

GAMES = ("seasons", "ages")
LARGE_W, LARGE_H, LARGE_STRIDE = 15, 11, 16     # dungeon rooms: 15x11 metatiles in 16-byte rows


def tileset_tables(root, game):
    """group -> 256 tileset bytes (data/{game}/tilesetAssignments.s; several groups share one)."""
    out, pending = {}, []
    for line in lines_of(os.path.join(root, f"data/{game}/tilesetAssignments.s")):
        m = re.match(r"group(\d)Tilesets:", line)
        if m:
            pending.append(int(m.group(1)))
            continue
        m = re.match(r'\.incbin "(.*)"', line)
        if m and pending:
            with open(os.path.join(root, m.group(1)), "rb") as f:
                data = f.read()
            for g in pending:
                out[g] = data
            pending = []
    return out


class Rooms:
    """Renders any room of either game into the shared metatile atlas."""

    def __init__(self, root):
        self.root = root
        self.games = {g: Game(root, g) for g in GAMES}
        self.tables = {g: tileset_tables(root, g) for g in GAMES}
        self.seasons_auto = default_seasons(root)
        self.atlas, self.atlas_index, self.ts_cache = [], {}, {}
        self.ts_ids = {}                         # (game, tileset) -> index in tiles.bin

    def ts_index(self, game, ts):
        return self.ts_ids.setdefault((game, ts), len(self.ts_ids))

    def tileset_cells(self, game, ts):
        """The atlas cell and collision of all 256 metatiles of a tileset (for tiles that change)."""
        key = (game, ts)
        if key not in self.ts_cache:
            self.ts_cache[key] = self.games[game].metatiles(ts)
        tiles, coll = self.ts_cache[key]
        cells = []
        for img in tiles:
            if img not in self.atlas_index:
                self.atlas_index[img] = len(self.atlas)
                self.atlas.append(img)
            cells.append(self.atlas_index[img])
        return cells, coll

    def tileset(self, game, group, room, season):
        g = self.games[game]
        tsv = self.tables[game][group][room] & 0x7f
        if game == "seasons" and group == 0:
            season = self.seasons_auto[room] if season == "auto" else season
        else:
            season = None
        return g.tileset(tsv, season)

    def aliases(self, game):
        """roomLayoutData.s stacks labels on one file: room labels -> the file they share."""
        if not hasattr(self, "_aliases"):
            self._aliases = {}
        if game not in self._aliases:
            out, pending = {}, []
            for line in lines_of(os.path.join(self.root, f"data/{game}/roomLayoutData.s")):
                m = re.match(r"(room[0-9a-f]{4}):$", line)
                if m:
                    pending.append(m.group(1))
                    continue
                m = re.match(r"m_RoomLayoutData\s+(room[0-9a-f]{4})", line)
                if m:
                    for label in pending:
                        out[label] = m.group(1)
                    pending = []
            self._aliases[game] = out
        return self._aliases[game]

    def layout(self, game, ts, room):
        name = f"room{ts[6]:02x}{room:02x}"
        for candidate in (name, self.aliases(game).get(name)):
            if not candidate:
                continue
            for kind, size in (("small", (ROOM_W, ROOM_H, ROOM_W)), ("large", (LARGE_W, LARGE_H, LARGE_STRIDE))):
                p = os.path.join(self.root, f"rooms/{game}/{kind}/{candidate}.bin")
                if os.path.exists(p):
                    with open(p, "rb") as f:
                        return f.read(), size
        return None, None

    def size(self, game, group, room):
        ts = self.tileset(game, group, room, "auto")
        data, size = self.layout(game, ts, room)
        return None if data is None else size[:2]

    def special_cells(self, game, group, room, season="auto"):
        """(floor cell, floor collision, opened-chest cell, opened-chest collision, closed-chest cell,
        closed-chest collision, dungeon index): what keyblocks/key doors ($a0, TILEINDEX_STANDARD_FLOOR)
        and chests ($f0) turn into here, and a chest ($f1) for chests that appear during play."""
        ts = self.tileset(game, group, room, season)
        key = (game, ts)
        if key not in self.ts_cache:
            self.ts_cache[key] = self.games[game].metatiles(ts)
        tiles, coll = self.ts_cache[key]
        out = []
        for mt in (0xa0, 0xf0, 0xf1):
            img = tiles[mt]
            if img not in self.atlas_index:
                self.atlas_index[img] = len(self.atlas)
                self.atlas.append(img)
            out += [self.atlas_index[img], coll[mt]]
        return tuple(out) + (ts[0] & 0x0f,)

    def render(self, game, group, room, season="auto"):
        """(w, h, cells, collisions, metatile ids, collision mode) or None when there's no layout."""
        ts = self.tileset(game, group, room, season)
        data, size = self.layout(game, ts, room)
        if data is None:
            return None
        w, h, stride = size
        key = (game, ts)
        if key not in self.ts_cache:
            self.ts_cache[key] = self.games[game].metatiles(ts)
        tiles, coll = self.ts_cache[key]
        cells, colls, mts = [], [], []
        for y in range(h):
            for x in range(w):
                mt = data[y * stride + x]
                img = tiles[mt]
                if img not in self.atlas_index:
                    self.atlas_index[img] = len(self.atlas)
                    self.atlas.append(img)
                cells.append(self.atlas_index[img])
                colls.append(coll[mt])
                mts.append(mt)
        return w, h, cells, colls, mts, ts[0] >> 4, self.ts_index(game, ts)


class Area:
    def __init__(self, game, kind, group, name, rooms_w, rooms_h, room_w, room_h):
        self.game, self.kind, self.group, self.name = game, kind, group, name
        self.rooms_w, self.rooms_h, self.room_w, self.room_h = rooms_w, rooms_h, room_w, room_h
        self.room_ids = [0xffff] * (rooms_w * rooms_h)
        self.coll_mode = [0] * (rooms_w * rooms_h)
        self.room_names = [""] * (rooms_w * rooms_h)
        self.specials = [(VOID, 0xff, VOID, 0xff, VOID, 0xff, 0xf)] * (rooms_w * rooms_h)
        self.state = [0] * (rooms_w * rooms_h)   # per screen: the season object conditions test
        self.tsidx = [0xffff] * (rooms_w * rooms_h)  # per screen: its tileset in tiles.bin
        W, H = rooms_w * room_w, rooms_h * room_h
        self.cells, self.colls, self.mts = [VOID] * (W * H), [0xff] * (W * H), [0] * (W * H)

    def put(self, rx, ry, room, rendered, specials=None):
        w, h, cells, colls, mts, mode, tsi = rendered
        self.tsidx[ry * self.rooms_w + rx] = tsi
        if specials:
            self.specials[ry * self.rooms_w + rx] = specials
        W = self.rooms_w * self.room_w
        self.room_ids[ry * self.rooms_w + rx] = room
        self.coll_mode[ry * self.rooms_w + rx] = mode
        for y in range(h):
            for x in range(w):
                i = (ry * self.room_h + y) * W + rx * self.room_w + x
                self.cells[i], self.colls[i], self.mts[i] = cells[y * w + x], colls[y * w + x], mts[y * w + x]

    def pack(self):
        n = self.rooms_w * self.rooms_h
        W, H = n and self.rooms_w * self.room_w, self.rooms_h * self.room_h
        out = struct.pack("<BBBB", GAMES.index(self.game), self.kind, self.group, 0)
        out += struct.pack("<HHHH", self.rooms_w, self.rooms_h, self.room_w, self.room_h)
        out += self.name.encode()[:31].ljust(32, b"\0")
        out += struct.pack(f"<{n}H", *self.room_ids) + bytes(self.coll_mode)
        out += b"".join(nm.encode()[:31].ljust(32, b"\0") for nm in self.room_names)
        out += b"".join(struct.pack("<HBHBHBB", *sp) for sp in self.specials)
        out += bytes(self.state)
        out += struct.pack(f"<{n}H", *self.tsidx)
        out += struct.pack(f"<{W * H}H", *self.cells) + bytes(self.colls) + bytes(self.mts)
        return out


AREA_OVERWORLD, AREA_ROOM, AREA_DUNGEON = 0, 1, 2


def overworld(rooms, game, group, name, gw, gh, names=None, season="auto"):
    a = Area(game, AREA_OVERWORLD, group, name, gw, gh, ROOM_W, ROOM_H)
    for r in range(gw * gh):
        room = (r // gw) * 16 + r % gw          # rooms are numbered on a 16-wide grid
        rr = rooms.render(game, group, room, season)
        if rr is None or len(set(rr[4])) <= 2:
            continue                            # unused filler screen (the "N"/"X" rooms)
        a.put(r % gw, r // gw, room, rr, rooms.special_cells(game, group, room, season))
        if names:
            a.room_names[r] = names[r]
        if game == "seasons" and group == 0:
            a.state[r] = rooms.seasons_auto[room] if season == "auto" else season
    return a


def dungeon_floors(root, rooms, game):
    """One area per dungeon floor: dungeonLayouts.s holds an 8x8 room grid per floor; the group is
    4 or 5 per dungeonData.s. Cropped to the rooms used."""
    layouts, cur = {}, None
    for line in lines_of(os.path.join(root, f"data/{game}/dungeonLayouts.s")):
        m = re.match(r"(\w+):$", line)
        if m:
            cur = m.group(1)
            layouts[cur] = []
            continue
        if cur and line.startswith(".db"):
            layouts[cur] += [num(t) for t in line[3:].split()]
    order = list(layouts.keys())
    flat = []
    for k in order:
        flat += layouts[k]
    areas, used = [], set()
    d = 0
    for line in lines_of(os.path.join(root, f"data/{game}/dungeonData.s")):
        m = re.match(r"m_DungeonData\s+>wGroup(\d)RoomFlags,\s*(\$?\w+),\s*(\w+),\s*(\$?\w+)", line)
        if not m:
            continue
        group, label, floors = int(m.group(1)), m.group(3), num(m.group(4))
        start = sum(len(layouts[k]) for k in order[:order.index(label)])
        for f in range(floors):
            grid = flat[start + f * 64:start + f * 64 + 64]
            cells = [(i % 8, i // 8, r) for i, r in enumerate(grid) if r and rooms.size(game, group, r)]
            if not cells:
                continue
            x0, x1 = min(c[0] for c in cells), max(c[0] for c in cells)
            y0, y1 = min(c[1] for c in cells), max(c[1] for c in cells)
            w, h = rooms.size(game, group, cells[0][2])
            a = Area(game, AREA_DUNGEON, group, f"LEVEL {d}", x1 - x0 + 1, y1 - y0 + 1, w, h)
            for x, y, r in cells:
                rr = rooms.render(game, group, r)
                if (rr[0], rr[1]) == (w, h):
                    a.put(x - x0, y - y0, r, rr, rooms.special_cells(game, group, r))
                    used.add((group, r))
            areas.append(a)
        d += 1
    return areas, used


def warps(root, game):
    """(sources, dests). sources: (group, room, kind 0 whole-screen/1 position, mask or YX, dest
    index, dest group, transition); dests: (group, index, room, YX, parameter, transition)."""
    lines = list(lines_of(os.path.join(root, f"data/{game}/warpSources.s")))
    labels, cur = {}, None
    for line in lines:
        m = re.match(r"(\w+):$", line)
        if m:
            cur = m.group(1)
            labels[cur] = []
        elif cur:
            labels[cur].append(line)
    sources = []
    for g in range(8):
        for line in labels.get(f"group{g}WarpSources", []):
            p = line.split()
            if p[0] == "m_StandardWarp":
                mask, room, di, dg, tr = (num(x) for x in p[1:6])
                sources.append((g, room, 0, mask, di, dg, tr))
            elif p[0] == "m_PointerWarp":
                room = num(p[1])
                for l2 in labels.get(p[2], []):
                    q = l2.split()
                    if q[0] == "m_PositionWarp":
                        yx, di, dg, tr = (num(x) for x in q[1:5])
                        sources.append((g, room, 1, yx, di, dg, tr))
    dests, cur = [], None
    for line in lines_of(os.path.join(root, f"data/{game}/warpDestinations.s")):
        m = re.match(r"group(\d)WarpDestTable:", line)
        if m:
            cur, idx = int(m.group(1)), 0
            continue
        if cur is not None and line.startswith("m_WarpDest"):
            room, yx, param, tr = (num(x) for x in line.split()[1:5])
            dests.append((cur, idx, room, yx, param, tr))
            idx += 1
    return sources, dests


def warp_tiles(root, game):
    """collision mode -> metatile ids that start a warp when Link steps on them (warpTiles.s)."""
    order, lists, cur = [], {}, []
    for line in lines_of(os.path.join(root, f"data/{game}/tile_properties/warpTiles.s")):
        m = re.match(r"\.dw @(\w+)", line)
        if m:
            order.append(m.group(1))
            continue
        m = re.match(r"@(\w+):", line)
        if m:
            cur.append(m.group(1))
            continue
        if line.startswith(".db") and cur:
            v = num(line[3:].split()[0])
            if v == 0:
                cur = []
                continue
            for name in cur:
                lists.setdefault(name, []).append(v)
    return [lists.get(n, []) for n in order]


def treasure_names(root):
    """TREASURE_* name -> index (constants/common/treasure.s)."""
    out = {}
    for line in lines_of(os.path.join(root, "constants/common/treasure.s")):
        m = re.match(r"(TREASURE_\w+)\s+db", line)
        if m:
            out[m.group(1)] = len(out)
    return out


def treasure_objects(root, game):
    """TREASURE_OBJECT_* name -> (treasure index, parameter, pickup text TX_00xx or $ff)."""
    tnames = treasure_names(root)
    out, cur = {}, None
    for line in lines_of(os.path.join(root, f"data/{game}/treasureObjectData.s")):
        m = re.match(r"m_BeginTreasureSubids\s+(\w+)", line)
        if m:
            cur = tnames.get(m.group(1))
            continue
        m = re.match(r"(?:/\*\s*\$(\w+)\s*\*/\s*)?m_TreasureSubid\s+(.*)", line)
        if m:
            args = [a.strip() for a in m.group(2).split(",")]
            index = int(m.group(1), 16) if m.group(1) else cur
            out[args[4]] = (index, num(args[1]), num(args[2]))
    return out


def text_strings(root, game, high):
    """TX_{high}xx -> the message, control codes dropped, lines joined by newlines."""
    out, cur, lines = {}, None, None
    with open(os.path.join(root, f"text/{game}/text.yaml")) as f:
        for raw in f:
            m = re.match(r"\s*- name: TX_([0-9a-f]{2})([0-9a-f]{2})$", raw)
            if m:
                if cur is not None:
                    out[cur] = "\n".join(lines).strip()
                cur = int(m.group(2), 16) if int(m.group(1), 16) == high else None
                lines = None
                continue
            if cur is None:
                continue
            if raw.strip().startswith("text:"):
                lines = []
                continue
            if lines is not None and raw.startswith("      "):
                text = re.sub(r"\\col\([^)]*\)", "", raw.strip())
                text = re.sub(r"\\(sym|item)\([^)]*\)", "", text)
                text = re.sub(r"\\[a-z]+(\([^)]*\))?", "", text)
                lines.append(text)
    if cur is not None and lines:
        out[cur] = "\n".join(lines).strip()
    return out


def chests(root, rooms, game):
    """(group, room, YX, treasure, parameter, opened, text) for every chest (chestData.s)."""
    objs = treasure_objects(root, game)
    texts = text_strings(root, game, 0)
    out, groups = [], []
    for line in lines_of(os.path.join(root, f"data/{game}/chestData.s")):
        m = re.match(r"chestGroup(\d)Data:", line)
        if m:
            groups.append(int(m.group(1)))
            continue
        m = re.match(r"m_ChestData\s+(\$?\w+),\s*(\$?\w+),\s*(\w+)", line)
        if m:
            yx, room, obj = num(m.group(1)), num(m.group(2)), m.group(3)
            t, param, tx = objs.get(obj, (0, 0, 0xff))
            for g in groups:
                out.append((g, room, yx, t, param, texts.get(tx, "") if tx != 0xff else ""))
            continue
        if line.startswith(".db") and "$ff" in line:
            groups = []
    return out


# ---- tile properties (data/{game}/tile_properties) ---------------------------------------------

def defines(root, *files):
    out = {}
    for f in files:
        for line in lines_of(os.path.join(root, f)):
            m = re.match(r"\.define\s+(\w+)\s+(\$?\w+)", line)
            if m:
                try:
                    out[m.group(1)] = num(m.group(2))
                except ValueError:
                    pass
    return out


def prop_table(path, consts):
    """A tile_properties file: the pointer table's label order and each label's rows of numbers."""
    order, rows, cur, pending = [], {}, None, []
    for line in lines_of(path):
        m = re.match(r"(@?\w+):", line)
        if m:
            name = m.group(1)
            if cur is not None and not rows[cur]:
                pending.append(cur)
            else:
                pending = []
            cur = name
            rows[cur] = []
            continue
        m = re.match(r"(?:\.dw|dbrel)\s+(@\w+)", line)
        if m:
            order.append(m.group(1))
            continue
        if cur and line.startswith(".db"):
            vals = []
            for t in line[3:].replace(",", " ").split():
                t = t.lstrip("<")
                vals.append(consts[t] if t in consts else num(t) if re.match(r"^(\$[0-9a-fA-F]+|%[01]+|\d+)$", t) else 0)
            rows[cur].append(vals)
            for p in pending:
                rows[p] = rows[cur]
    return order, rows


def tile_properties(root, game):
    """Per collision mode (6), 256 records: cliff angle ($ff none), hazard bits, tile type, breakable
    mode ($ff none), interactable byte ($ff none), pushed: tile left behind, tile it becomes."""
    consts = defines(root, "constants/common/directions.s", "constants/common/tileTypes.s")
    d = os.path.join(root, f"data/{game}/tile_properties")
    modes_order, _ = prop_table(os.path.join(d, "cliffTiles.s"), consts)
    out = [[[0xff, 0, 0, 0xff, 0xff, 0, 0, 0] for _ in range(256)] for _ in range(6)]

    def each(fname, fn):
        order, rows = prop_table(os.path.join(d, fname), consts)
        for mode, label in enumerate(modes_order[:6]):
            # Seasons' pushable table goes by group (8 entries); match its labels by name instead
            for r in rows.get(label, []):
                if len(r) >= 2 and r[0] != 0:
                    fn(out[mode][r[0] & 0xff], r)

    each("cliffTiles.s", lambda t, r: t.__setitem__(0, r[1]))
    each("hazards.s", lambda t, r: t.__setitem__(1, r[1]))
    each("tileTypeMappings.s", lambda t, r: t.__setitem__(2, r[1]))
    each("breakableTiles.s", lambda t, r: t.__setitem__(3, r[1]))
    each("interactableTiles.s", lambda t, r: t.__setitem__(4, r[1]))

    def push(t, r):
        if len(r) >= 3:
            t[5], t[6] = r[1], r[2]
    each("pushableTiles.s", push)
    # breakable modes: m_BreakableTileData sources(8) sources(8) sources(4) drop flags result
    modes = []
    for line in lines_of(os.path.join(d, "breakableTiles.s")):
        m = re.match(r"m_BreakableTileData\s+%([01]+)\s+%([01]+)\s+%([01]+)\s+(\$?\w+)\s+(\$?\w+)\s+(\$?\w+)", line)
        if m:
            bits = m.group(1) + m.group(2) + m.group(3)
            src = sum(1 << i for i, b in enumerate(bits) if b == "1")
            modes.append((src, num(m.group(4)), num(m.group(5)), num(m.group(6))))
    return out, modes


def signs(root, game):
    """(group, room, YX, text) for every sign (signText.s)."""
    texts = text_strings(root, game, 0x2e)
    out, groups = [], []
    for line in lines_of(os.path.join(root, f"data/{game}/signText.s")):
        m = re.match(r"signTextGroup(\d)Data:", line)
        if m:
            groups.append(int(m.group(1)))
            continue
        if line.startswith(".db") and groups:
            vals = [t.strip() for t in line[3:].split(",")]
            if len(vals) == 3 and vals[2].startswith("<TX_2e"):
                tx = int(vals[2][6:], 16)
                for g in groups:
                    out.append((g, num(vals[1]), num(vals[0]), texts.get(tx, "")))
                continue
        if line.startswith(".db") or line.startswith(".dw"):
            groups = [] if not line.startswith(".db") else groups
        if line.strip() == ".db $00":
            groups = []
    return out


def write_tiles(root, rooms, season_ts, path):
    """tiles.bin: every tileset's 256 metatiles (atlas cell, collision), Holodrum's tileset per
    screen in each season, the tile property tables of both games, and the signs."""
    sets = sorted(rooms.ts_ids.items(), key=lambda kv: kv[1])
    with open(path, "wb") as f:
        f.write(b"OTIL" + struct.pack("<HH", 1, len(sets)))
        for (game, ts), _ in sets:
            cells, coll = rooms.tileset_cells(game, ts)
            f.write(struct.pack("<256H", *cells) + bytes(coll))
        for s in range(4):
            f.write(struct.pack("<256H", *(season_ts[s] + [0xffff] * (256 - len(season_ts[s])))))
        all_signs = []
        for gi, game in enumerate(GAMES):
            props, modes = tile_properties(root, game)
            for mode in range(6):
                for t in props[mode]:
                    f.write(bytes(v & 0xff for v in t))
            f.write(struct.pack("<H", len(modes)))
            for src, drop, flags, result in modes:
                f.write(struct.pack("<IBBB", src, drop, flags, result))
            all_signs += [(gi,) + sg for sg in signs(root, game)]
        f.write(struct.pack("<H", len(all_signs)))
        for gi, g, room, yx, text in all_signs:
            f.write(struct.pack("<BBBB", gi, g, room, yx) + text.encode("ascii", "replace")[:119].ljust(120, b"\0"))


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(2)
    root, out = sys.argv[1], sys.argv[2]
    previews = "--previews" in sys.argv
    os.makedirs(out, exist_ok=True)
    rooms = Rooms(root)
    areas = []
    hol_names = area_names(root, "seasons", 16, 16, 0xc9)
    areas.append(overworld(rooms, "seasons", 0, "HOLODRUM", 16, 16, hol_names))
    areas.append(overworld(rooms, "seasons", 1, "SUBROSIA", 11, 8, area_names(root, "seasons", 11, 8, -1, "pastMapTextIndices")))
    areas.append(overworld(rooms, "ages", 0, "LABRYNNA", 14, 14, area_names(root, "ages", 14, 14, 0x38)))
    areas.append(overworld(rooms, "ages", 1, "LABRYNNA PAST", 14, 14, area_names(root, "ages", 14, 14, -1, "pastMapTextIndices")))
    placed = set()
    for a in areas:
        placed |= {(GAMES.index(a.game), a.group, r) for r in a.room_ids if r != 0xffff}
    all_sources, all_dests = [], []
    for gi, game in enumerate(GAMES):
        floors, used = dungeon_floors(root, rooms, game)
        areas += floors
        placed |= {(gi, g, r) for g, r in used}
        sources, dests = warps(root, game)
        all_sources += [(gi,) + s for s in sources]
        all_dests += [(gi,) + d for d in dests]
        # every other room a warp leads to gets an area of its own
        for g, idx, room, yx, param, tr in dests:
            if (gi, g, room) in placed:
                continue
            rr = rooms.render(game, g, room)
            if rr is None:
                continue
            a = Area(game, AREA_ROOM, g, "", 1, 1, rr[0], rr[1])
            a.put(0, 0, room, rr, rooms.special_cells(game, g, room))
            areas.append(a)
            placed.add((gi, g, room))
    with open(os.path.join(out, "areas.bin"), "wb") as f:
        f.write(b"OARE" + struct.pack("<HH", 4, len(areas)))
        for a in areas:
            f.write(a.pack())
    # Holodrum in each season, for the Rod of Seasons: same layout as the HOLODRUM area
    season_ts = []
    for s, name in enumerate(("spring", "summer", "autumn", "winter")):
        a = overworld(rooms, "seasons", 0, name, 16, 16, season=s)
        season_ts.append(a.tsidx)
        W, H = 16 * ROOM_W, 16 * ROOM_H
        with open(os.path.join(out, f"holodrum_{name}.map"), "wb") as f:
            f.write(b"OWLD" + struct.pack("<HHHHH", 2, W, H, ROOM_W, ROOM_H))
            f.write(struct.pack(f"<{W * H}H", *a.cells))
            f.write(bytes(a.colls) + bytes(a.mts))
    with open(os.path.join(out, "warps.bin"), "wb") as f:
        tiles = [warp_tiles(root, g) for g in GAMES]
        f.write(b"OWRP" + struct.pack("<HHH", 1, len(all_sources), len(all_dests)))
        for s in all_sources:
            f.write(bytes(s))
        for d in all_dests:
            f.write(bytes(d))
        for t in tiles:                          # per game: 8 collision modes, 16 tiles each, 0-ended
            for mode in range(8):
                lst = (t[mode] if mode < len(t) else [])[:15]
                f.write(bytes(lst + [0] * (16 - len(lst))))
    with open(os.path.join(out, "chests.bin"), "wb") as f:
        all_chests = [(gi,) + c for gi, game in enumerate(GAMES) for c in chests(root, rooms, game)]
        f.write(b"OCHS" + struct.pack("<HH", 1, len(all_chests)))
        for gi, g, room, yx, t, param, text in all_chests:
            tb = text.encode("ascii", "replace")[:159]
            f.write(struct.pack("<BBBBBB", gi, g, room, yx, t, param) + tb.ljust(160, b"\0"))
    write_tiles(root, rooms, season_ts, os.path.join(out, "tiles.bin"))
    rows = (len(rooms.atlas) + ATLAS_COLS - 1) // ATLAS_COLS
    sheet = Image.new("RGBA", (ATLAS_COLS * 16, rows * 16))
    for i, img in enumerate(rooms.atlas):
        sheet.paste(Image.frombytes("RGBA", (16, 16), img), ((i % ATLAS_COLS) * 16, (i // ATLAS_COLS) * 16))
    save_rgba(sheet, os.path.join(out, "metatiles.rgba"))
    if previews:
        for i, a in enumerate(areas):
            if a.kind == AREA_ROOM:
                continue
            W, H = a.rooms_w * a.room_w, a.rooms_h * a.room_h
            im = Image.new("RGBA", (W * 16, H * 16))
            for j, c in enumerate(a.cells):
                if c != VOID:
                    im.paste(Image.frombytes("RGBA", (16, 16), rooms.atlas[c]), ((j % W) * 16, (j // W) * 16))
            im.save(os.path.join(out, f"area{i:03d}_{a.game}_{a.name.replace(' ', '_')}.png"))
    kinds = [sum(1 for a in areas if a.kind == k) for k in range(3)]
    print(f"{len(areas)} areas ({kinds[0]} overworlds, {kinds[2]} dungeon floors, {kinds[1]} rooms), "
          f"{len(all_sources)} warps, {len(all_chests)} chests, atlas {len(rooms.atlas)} metatiles ({ATLAS_COLS * 16}x{rows * 16})")


if __name__ == "__main__":
    main()
