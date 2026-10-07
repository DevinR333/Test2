#!/usr/bin/env python3
"""Colours the disassembly's sprite sheets for the engine.

Writes into OUT_DIR:
  link.rgba  Link's frames (gfx/common/spr_link.png, already 16x16, 8 per row) in his green palette
  hud.rgba   hearts (row 0) and digits/symbols (row 1) from gfx_hud.png, without the status bar
  items.rgba item icons: for each game (Seasons, Ages) the three icon sheets the inventory loads
             (spr_item_icons_1_spr, _2, _3: 16 columns of 8x16 each, so an icon value v >= $80 is
             column v-$80 of the three side by side), once per sprite palette 0-7; row
             (game * 8 + palette) * 3 + sheet, 16 pixels high each

  inventory_seasons.rgba, inventory_ages.rgba  each game's original item page, 160x128
  inventory_cursor.rgba  the item page's bracket cursor (left; mirror it for the right)
  font.rgba  the games' 8x16 font, white, 16 glyphs per row in character-code order

Usage: extract_sprites.py DISASM_DIR OUT_DIR
"""
import os
import sys

from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from extract_world import Game, draw_tile  # noqa: E402


def save_rgba(im, path):
    """Raw image for the engine: u32 width, u32 height (little endian), then RGBA bytes."""
    im = im.convert("RGBA")
    with open(path, "wb") as f:
        f.write(im.size[0].to_bytes(4, "little") + im.size[1].to_bytes(4, "little") + im.tobytes())


def c5(r, g, b):
    return (r * 255 // 31, g * 255 // 31, b * 255 // 31, 255)


# standardSpritePaletteData, palette 0 (Link): colour 0 is transparent on sprites
LINK = [(0, 0, 0, 0), c5(0, 0, 0), c5(2, 21, 8), c5(31, 26, 17)]
# gfx_hud.png: colour 2 is the status bar's background, which the floating HUD drops. Hearts (row 0)
# fill with colour 1 and outline with 3; digits (row 1) are drawn in 3 and get a dark outline here.
HUD_HEARTS = [(0, 0, 0, 0), c5(31, 4, 4), (0, 0, 0, 0), c5(2, 2, 2)]
HUD_DIGITS = [(0, 0, 0, 0), c5(31, 31, 31), (0, 0, 0, 0), c5(31, 31, 31)]


def recolour(path, pal):
    im = Image.open(path)
    idx = im.load() if im.mode == "P" else im.convert("L").load()
    out = Image.new("RGBA", im.size)
    px = out.load()
    for y in range(im.size[1]):
        for x in range(im.size[0]):
            v = idx[x, y]
            if im.mode != "P":
                v = 3 - v * 4 // 256
            px[x, y] = pal[v & 3]
    return out


def outline(im):
    """Darkens every clear pixel next to a drawn one, so white text reads on any background."""
    src = im.load()
    out = im.copy()
    px = out.load()
    w, h = im.size
    for y in range(h):
        for x in range(w):
            if src[x, y][3]:
                continue
            if any(0 <= x + dx < w and 0 <= y + dy < h and src[x + dx, y + dy][3]
                   for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                px[x, y] = (16, 16, 16, 255)
    return out


def find_bin(root, game, name):
    for d in (f"gfx_compressible/{game}", "gfx_compressible/common", f"gfx/{game}", "gfx/common"):
        p = os.path.join(root, d, name + ".bin")
        if os.path.exists(p):
            with open(p, "rb") as f:
                return f.read()
    raise FileNotFoundError(name)


def inventory_page(root, game):
    """The item page of the game's inventory (GFXH_INVENTORY_SCREEN tiles, PALH_0a colours):
    map_inventory_screen_1 at w4TileMap+$40 and the text bar at +$1e0, so screen rows 2-17 (the
    status bar rows above are left out). 160x128."""
    g = Game(root, game)
    vram = g.build_vram((0, 0, 0, g.names["GFXH_INVENTORY_SCREEN"], 0, 0, 0, 0))
    pals = [[(31, 31, 31)] * 4 for _ in range(8)]
    for kind, first, count, label in g.pal_headers[g.pal_names["PALH_0a"]]:
        if kind == "Bg":
            cols = g.label_colours(label)
            for p in range(count):
                pals[first + p] = cols[p * 4:p * 4 + 4]
    rows = {}
    for name, at in (("inventory_screen_1", 2), ("inventory_textbar", 15)):
        tm, attrs = find_bin(root, game, "map_" + name), find_bin(root, game, "flg_" + name)
        for r in range(len(tm) // 32):
            rows[at + r] = (tm[r * 32:r * 32 + 32], attrs[r * 32:r * 32 + 32])
    im = Image.new("RGBA", (160, 128))
    for row in range(2, 18):
        tm, attrs = rows[row]
        for col in range(20):
            px = bytearray(16 * 16 * 4)
            draw_tile(px, 0, 0, vram, tm[col], attrs[col], pals)
            im.paste(Image.frombytes("RGBA", (16, 16), bytes(px)).crop((0, 0, 8, 8)), (col * 8, (row - 2) * 8))
    return im


def inventory_cursor(root):
    """The item page's cursor: sprite tile $0c (8x16, from gfx_inventory_hud_1 at $8000) in sprite
    palette 2, the left bracket; the right one is it mirrored (inventorySubscreen0_drawCursor)."""
    g = Game(root, "seasons")
    data = g.gfx_file("gfx_inventory_hud_1")
    pal = g.label_colours("standardSpritePaletteData")[8:12]
    rgba = [(0, 0, 0, 0)] + [c5(*c) for c in pal[1:]]
    im = Image.new("RGBA", (8, 16))
    px = im.load()
    for half in range(2):
        t = data[(0x0c + half) * 16:(0x0d + half) * 16]
        for y in range(8):
            lo, hi = t[y * 2], t[y * 2 + 1]
            for x in range(8):
                c = (lo >> (7 - x) & 1) | (hi >> (7 - x) & 1) << 1
                px[7 - x, half * 8 + y] = rgba[c]       # the left bracket is drawn x-flipped
    return im


def game_font(root):
    """gfx_font.png: 8x16 glyphs, 16 per row in character-code order, as white on clear."""
    im = Image.open(os.path.join(root, "gfx/common/gfx_font.png"))
    src = im.load()
    out = Image.new("RGBA", im.size)
    px = out.load()
    for y in range(im.size[1]):
        for x in range(im.size[0]):
            on = src[x, y] == 0 if im.mode == "P" else src[x, y] > 127     # colour 0 is the glyph
            px[x, y] = (255, 255, 255, 255) if on else (0, 0, 0, 0)
    return out


def find_png(root, game, name):
    for d in (f"gfx/{game}", "gfx/common", f"gfx_compressible/{game}", "gfx_compressible/common"):
        p = os.path.join(root, d, name + ".png")
        if os.path.exists(p):
            return p
    raise FileNotFoundError(name)


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(2)
    root, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    save_rgba(recolour(os.path.join(root, "gfx/common/spr_link.png"), LINK), os.path.join(out, "link.rgba"))
    hud_png = os.path.join(root, "gfx_compressible/seasons/gfx_hud.png")
    hud = recolour(hud_png, HUD_HEARTS)
    digits = outline(recolour(hud_png, HUD_DIGITS).crop((0, 8, 128, 16)))
    hud.paste(digits, (0, 8))
    save_rgba(hud, os.path.join(out, "hud.rgba"))
    sheets = []
    for game in ("seasons", "ages"):
        g = Game(root, game)
        cols = g.label_colours("standardSpritePaletteData")
        pals = [cols[p * 4:p * 4 + 4] for p in range(min(8, len(cols) // 4))]
        while len(pals) < 8:
            pals.append(pals[0])
        for pal in pals:
            rgba = [(0, 0, 0, 0)] + [c5(*c) for c in pal[1:]]
            for name in ("spr_item_icons_1_spr", "spr_item_icons_2", "spr_item_icons_3"):
                sheets.append(recolour(find_png(root, game, name), rgba))
    items = Image.new("RGBA", (128, 16 * len(sheets)))
    for i, s in enumerate(sheets):
        items.paste(s, (0, i * 16))
    save_rgba(items, os.path.join(out, "items.rgba"))
    for game in ("seasons", "ages"):
        save_rgba(inventory_page(root, game), os.path.join(out, f"inventory_{game}.rgba"))
    save_rgba(inventory_cursor(root), os.path.join(out, "inventory_cursor.rgba"))
    save_rgba(game_font(root), os.path.join(out, "font.rgba"))
    print("sprites written")


if __name__ == "__main__":
    main()
