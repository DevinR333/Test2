#!/usr/bin/env python3
"""Builds the objects placed in rooms (objects/{game}/*.s) and their sprites.

Writes into OUT_DIR:
  sprites.rgba  the animation frames of every placed interaction, enemy and part, packed in rows
  objects.bin   (little endian)
    "OOBJ", u16 version 2, u16 sprite count, u16 object count
    sprites: u8 game, u8 kind (0 interaction, 1 enemy, 2 part), u8 id, u8 subid,
             u8 radius y, u8 radius x, s8 damage (quarter hearts, negative), u8 health,
             u8 frame count (<= 4), u8 secret told, u8 secret taken ($ff none), u16 text length,
             the text (what the character says first), then per frame: u16 x, u16 y (in
             sprites.rgba), u8 w, u8 h, s16 origin x, s16 origin y (top-left relative to the
             object's position), u8 duration
    objects: u8 game, u8 group, u8 room, u8 kind, u8 id, u8 subid, u8 y, u8 x (in the room),
             u8 count (random enemies; else 1), u8 random (1: place at random), u8 condition
             (bit n: present in room state n, i.e. Holodrum's season; $ff always)

Usage: extract_objects.py DISASM_DIR OUT_DIR
"""
import os
import re
import struct
import sys

from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from extract_world import Game, lines_of, num  # noqa: E402

GAMES = ("seasons", "ages")
KIND_INTERACTION, KIND_ENEMY, KIND_PART = 0, 1, 2
MAX_FRAMES = 4


def c5(c):
    return (c[0] * 255 // 31, c[1] * 255 // 31, c[2] * 255 // 31, 255)


def labelled(path, pattern=r"(\w+):"):
    """label -> lines after it (stacked labels share the lines)."""
    out, pending, cur = {}, [], None
    for line in lines_of(path):
        m = re.match(pattern + r"\s*$", line)
        if m:
            if cur is not None and not out[cur]:
                pending.append(cur)
            else:
                pending = []
            cur = m.group(1)
            out[cur] = []
            continue
        if cur is None:
            continue
        out[cur].append(line)
        for p in pending:
            out[p] = out[cur]
    return out


class Sprites:
    def __init__(self, root, game):
        self.root, self.game = root, game
        self.g = Game(root, game)
        d = f"data/{game}/"
        self.gfx_headers = self._gfx_headers(d + "objectGfxHeaders.s")
        self.inter_data = self._object_data(d + "interactionData.s", "m_InteractionData", "m_InteractionSubidData")
        self.enemy_data = self._object_data(d + "enemyData.s", "m_EnemyData", "m_EnemySubidData")
        # parts: plain 8-byte rows (gfx, collision mode, radius, damage, health, tile base, flags)
        self.part_data = {i: [tuple(num(t) for t in l[3:].split())] for i, l in
                          enumerate(l for l in labelled(os.path.join(root, d + "partData.s")).get("partData", []) if l.startswith(".db"))}
        self.extra_enemy = [tuple(num(t) for t in l[3:].split()) for l in labelled(os.path.join(root, d + "enemyData.s")).get("extraEnemyData", []) if l.startswith(".db")]
        self.anims = {k: labelled(os.path.join(root, d + f)) for k, f in
                      ((KIND_INTERACTION, "interactionAnimations.s"), (KIND_ENEMY, "enemyAnimations.s"), (KIND_PART, "partAnimations.s"))}
        self.oams = {k: labelled(os.path.join(root, d + f)) for k, f in
                     ((KIND_INTERACTION, "interactionOamData.s"), (KIND_ENEMY, "enemyOamData.s"), (KIND_PART, "partOamData.s"))}
        cols = self.g.label_colours("standardSpritePaletteData")
        self.pals = [cols[p * 4:p * 4 + 4] for p in range(min(8, len(cols) // 4))]
        while len(self.pals) < 8:
            self.pals.append(self.pals[0])

    def _gfx_headers(self, rel):
        """header index -> concatenated tile data of its files (a run ends at one marked ', 1')."""
        entries = []
        for line in lines_of(os.path.join(self.root, rel)):
            m = re.match(r"(?:/\*\s*\$(\w+)\s*\*/\s*)?m_ObjectGfxHeader\s+(\w+)(?:\s*,\s*(\w+))?", line)
            if m:
                entries.append((m.group(2), m.group(3) == "1"))
        out = {}
        for i in range(len(entries)):
            data = b""
            for j in range(i, len(entries)):
                try:
                    part = self.g.gfx_file(entries[j][0])
                except FileNotFoundError:
                    part = b""
                # each file fills one VRAM slot of $20 tiles (loadObjectGfx: b = $1f)
                data += part[:0x200].ljust(0x200, b"\0")
                if entries[j][1]:
                    break
            out[i] = data
        return out

    def _object_data(self, rel, macro, sub_macro):
        """id -> list (per subid) of argument tuples; a single entry applies to every subid."""
        table = labelled(os.path.join(self.root, rel))
        out = {}
        idx = 0
        for line in lines_of(os.path.join(self.root, rel)):
            m = re.match(r"(?:/\*\s*(?:\$|0x)(\w+)\s*\*/\s*)?" + macro + r"\s+(.*)", line)
            if not m:
                continue
            if m.group(1):
                idx = int(m.group(1), 16)
            args = m.group(2).split()
            ptr = [a for a in args if not a.startswith("$")]
            if ptr:
                fixed = [num(a) for a in args if a.startswith("$")]
                subs = []
                for l2 in table.get(ptr[0], []):
                    m2 = re.match(sub_macro + r"\s+(.*)", l2)
                    if m2:
                        subs.append(tuple(fixed + [num(a) for a in m2.group(1).replace(",", " ").split()]))
                out[idx] = subs or [tuple(fixed)]
            else:
                out[idx] = [tuple(num(a) for a in args)]
            idx += 1
        return out

    def params(self, kind, oid, subid):
        """(gfx header, tile base, palette, animation, extra enemy stats)."""
        table = {KIND_INTERACTION: self.inter_data, KIND_ENEMY: self.enemy_data, KIND_PART: self.part_data}[kind]
        rows = table.get(oid)
        if not rows:
            return None
        row = rows[subid] if subid < len(rows) else rows[0]
        if kind == KIND_ENEMY:
            if len(row) < 4:
                return None
            gfx, extra, palbase = row[0], row[2] & 0x7f, row[3]
            stats = self.extra_enemy[extra] if extra < len(self.extra_enemy) else (6, 6, 0xfc, 2)
            return gfx, (palbase & 0x0f) * 2, (palbase >> 4) & 7, 0, stats
        if len(row) < 3:
            return None
        gfx, base, b2 = row[0], row[1], row[2]
        if kind == KIND_PART:
            if len(row) < 7:
                return None
            r, dmg, hp = row[2], row[3], row[4]
            return row[0], row[5] & 0x7f, row[6] & 7, 0, (r >> 4, r & 15, dmg, hp)
        return gfx, base & 0x7f, (b2 >> 4) & 7, b2 & 0x0f, None

    def frames(self, kind, oid, subid):
        p = self.params(kind, oid, subid)
        if p is None:
            return None, None
        gfx, base, pal, anim, stats = p
        if gfx == 0:
            return None, stats        # no graphics: controllers, triggers, script runners
        tiles = self.gfx_headers.get(gfx, b"")
        names = {KIND_INTERACTION: "interaction", KIND_ENEMY: "enemy", KIND_PART: "part"}[kind]
        anims = self.anims[kind].get(f"{names}{oid:02x}Animations", [])
        ptrs = [l.split()[1] for l in self.anims[kind].get(f"{names}{oid:02x}OamDataPointers", []) if l.startswith(".dw")]
        anim_labels = [l.split()[1] for l in anims if l.startswith(".dw")]
        if not anim_labels or not ptrs or not tiles:
            return None, stats
        label = anim_labels[anim if anim < len(anim_labels) else 0]
        out = []
        for line in self.anims[kind].get(label, []):
            if not line.startswith(".db") or len(out) >= MAX_FRAMES:
                break
            vals = [num(t) for t in line[3:].split()]
            if len(vals) < 2:
                break
            dur, oi = vals[0], vals[1] // 2
            if oi >= len(ptrs):
                break
            img = self.render(self.oams[kind].get(ptrs[oi], []), tiles, base, pal)
            if img:
                out.append(img + (dur,))
        return out, stats

    def render(self, oam_lines, tiles, base, objpal):
        vals = []
        for l in oam_lines:
            if l.startswith(".db"):
                vals += [num(t) for t in l[3:].split()]
        if not vals or vals[0] == 0:
            return None
        n, ents = vals[0], []
        for i in range(n):
            e = vals[1 + i * 4:5 + i * 4]
            if len(e) == 4:
                y, x = (e[0] - 256 if e[0] >= 128 else e[0]) - 16, (e[1] - 256 if e[1] >= 128 else e[1]) - 8
                ents.append((y, x, e[2], e[3]))
        if not ents:
            return None
        x0, y0 = min(e[1] for e in ents), min(e[0] for e in ents)
        x1, y1 = max(e[1] for e in ents) + 8, max(e[0] for e in ents) + 16
        im = Image.new("RGBA", (x1 - x0, y1 - y0))
        px = im.load()
        for y, x, t, attr in reversed(ents):     # earlier OAM entries draw on top
            pal = self.pals[(attr + objpal) & 7]
            rgba = [None] + [c5(c) for c in pal[1:]]
            t = (t + base) & 0xfe
            for half in range(2):
                off = (t + half) * 16
                data = tiles[off:off + 16]
                if len(data) < 16:
                    continue
                for row in range(8):
                    sy = 7 - row if attr & 0x40 else row
                    lo, hi = data[sy * 2], data[sy * 2 + 1]
                    for col in range(8):
                        sx = 7 - col if attr & 0x20 else col
                        c = (lo >> (7 - sx) & 1) | (hi >> (7 - sx) & 1) << 1
                        if c:
                            yy = (half if not attr & 0x40 else 1 - half) * 8 + row
                            px[x - x0 + col, y - y0 + yy] = rgba[c]
        return im, x0, y0


# ---- what characters say (scripts/{game}/*.s, object_code/{game}/interactions/*.s) -------------

TEXT_OPS = ("showtext", "showtextlowindex", "rungenericnpc", "rungenericnpclowindex", "showtextnonexitable",
            "showtextnonexitablelowindex", "showtextdifferentforlinked")


def all_texts(root, game):
    """TX_xxxx -> the message (as extract_world.text_strings, every group in one pass)."""
    out, cur, lines = {}, None, None
    with open(os.path.join(root, f"text/{game}/text.yaml")) as f:
        for raw in f:
            m = re.match(r"\s*- name: TX_([0-9a-f]{4})$", raw)
            if m:
                if cur is not None and lines is not None:
                    out[cur] = "\n".join(lines).strip()
                cur, lines = int(m.group(1), 16), None
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


class Dialogue:
    """(id, subid) -> the first thing a character's script says."""

    def __init__(self, root, game):
        self.texts = all_texts(root, game)
        self.scripts, self.sections, order = {}, {}, []
        files = [os.path.join(root, f"scripts/{game}/{f}") for f in ("scripts.s", "scripts2.s", "scriptHelper.s")]
        files.append(os.path.join(root, "scripts/common/commonScripts.s"))
        section = None
        for f in files:
            if not os.path.exists(f):
                continue
            cur = None
            for raw in open(f):
                m = re.match(r";\s*(INTERAC_\w+)", raw.strip())
                if m:
                    section = m.group(1)
                    continue
                line = raw.split(";", 1)[0].strip()
                if not line:
                    continue
                m = re.match(r"([A-Za-z_]\w*):", line)
                if m:
                    cur = m.group(1)
                    self.scripts[cur] = []
                    if section:
                        self.sections.setdefault(section, []).append(cur)
                    continue
                if cur:
                    self.scripts[cur].append(line)
        # interaction id -> its code's script references, in order
        consts = {}
        for line in lines_of(os.path.join(root, f"constants/{game}/interactions.s")):
            m = re.match(r"\.define\s+(INTERAC_\w+)\s+\$(\w+)", line)
            if m:
                consts[int(m.group(2), 16)] = m.group(1)
        self.names = consts
        self.code = {}
        d = os.path.join(root, f"object_code/{game}/interactions")
        for fn in sorted(os.listdir(d)):
            ids, refs, txs = [], [], []
            for line in lines_of(os.path.join(d, fn)):
                m = re.match(r"interactionCode([0-9a-f]{2}):", line)
                if m:
                    ids.append(int(m.group(1), 16))
                for r in re.findall(r"(?:mainScripts|scripts2|commonScripts)\.(\w+)", line):
                    refs.append(r)
                for t in re.findall(r"[<>]?TX_([0-9a-f]{4})", line):
                    txs.append(int(t, 16))
            for i in ids:
                self.code[i] = (len(ids), refs, txs)

    def script_text(self, label, depth=0):
        for line in self.scripts.get(label, []):
            p = line.replace(",", " ").split()
            if not p:
                continue
            if p[0] in TEXT_OPS and len(p) > 1:
                m = re.match(r"<?TX_([0-9a-f]{4})", p[1])
                if m:
                    return self.texts.get(int(m.group(1), 16))
            if p[0] == "scriptjump" and len(p) > 1 and depth < 3 and not p[1].startswith(("@", "-", "+")):
                return self.script_text(p[1], depth + 1)
        return None

    def text(self, oid, subid):
        shared, refs, txs = self.code.get(oid, (0, [], []))
        cands = []
        if shared == 1 and refs:
            cands.append(refs[subid] if subid < len(refs) else refs[0])
        sec = self.sections.get(self.names.get(oid, ""), [])
        if sec:
            cands.append(sec[subid] if subid < len(sec) else sec[0])
            cands += sec
        if shared == 1:
            cands += refs
        for c in cands:
            t = self.script_text(c)
            if t:
                return t
        # characters whose code picks the text (genericNpcScript with a textID)
        for tx in (txs if shared == 1 else []):
            if tx in self.texts and tx >> 8 not in (0x00,):
                return self.texts[tx]
        return None


# Who tells which linked secret (the linked game's NPCs running linkedGameNpcScript, with the secret
# index each sets) and who takes it (askforsecret in their scripts). Secret numbers as src/secrets.h.
SECRETS = ["CLOCK_SHOP", "GRAVEYARD", "SUBROSIAN", "DIVER", "SMITH", "PIRATE", "TEMPLE", "DEKU", "BIGGORON", "RUUL",
           "KING_ZORA", "FAIRY", "TROY", "PLEN", "LIBRARY", "TOKAY", "MAMAMU", "TINGLE", "ELDER", "SYMMETRY"]
ANY = None
SECRET_TELLERS = {   # (game, id, subid) -> secret
    (0, 0xe7, ANY): "KING_ZORA", (0, 0xd8, ANY): "FAIRY", (0, 0xbc, ANY): "TROY", (0, 0xbd, ANY): "TROY",
    (0, 0xbe, ANY): "TROY", (0, 0x30, 0x25): "PLEN", (0, 0xcb, ANY): "LIBRARY", (0, 0xdb, 0): "TOKAY",
    (0, 0xdb, 1): "MAMAMU", (0, 0xdb, 2): "SYMMETRY", (0, 0xd5, ANY): "TINGLE", (0, 0x3b, 7): "ELDER",
    (1, 0x3d, 4): "CLOCK_SHOP", (1, 0x3d, 5): "RUUL", (1, 0xcb, ANY): "GRAVEYARD", (1, 0x4e, 3): "SUBROSIAN",
    (1, 0x4e, 4): "SMITH", (1, 0xcd, ANY): "DIVER", (1, 0x3b, 6): "PIRATE", (1, 0xd5, ANY): "TEMPLE",
    (1, 0xd6, ANY): "DEKU", (1, 0x66, 0x0f): "BIGGORON",
}
SECRET_TAKERS = {
    (0, 0x24, 3): "RUUL", (0, 0x40, ANY): "PIRATE", (0, 0x52, ANY): "BIGGORON", (0, 0xa4, ANY): "SMITH",
    (0, 0xca, ANY): "CLOCK_SHOP", (0, 0xcb, ANY): "GRAVEYARD", (0, 0xcc, ANY): "SUBROSIAN", (0, 0xcd, ANY): "DIVER",
    (0, 0xd5, ANY): "TEMPLE", (0, 0xd6, ANY): "DEKU",
    (1, 0x30, 2): "ELDER", (1, 0x48, 0x19): "TOKAY", (1, 0x49, ANY): "FAIRY", (1, 0x9c, ANY): "KING_ZORA",
    (1, 0xc8, ANY): "TINGLE", (1, 0xca, ANY): "TROY", (1, 0xcc, ANY): "PLEN", (1, 0x52, 0): "LIBRARY",
    (1, 0x53, ANY): "MAMAMU", (1, 0xbf, 8): "SYMMETRY", (1, 0xbf, 9): "SYMMETRY",
}


def secret_of(table, game, oid, subid):
    s = table.get((game, oid, subid)) or table.get((game, oid, ANY))
    return SECRETS.index(s) if s else 0xff


def object_lists(root, game):
    """(group, room) -> [(kind, id, subid, y, x, count, random, condition)] as at the start of a game."""
    files = [os.path.join(root, f"objects/{game}", f) for f in sorted(os.listdir(os.path.join(root, f"objects/{game}"))) if f.endswith(".s")]
    labels = {}
    for f in files:
        labels.update(labelled(f))

    def walk(label, cond, seen):
        out = []
        if label in seen:
            return out
        seen = seen | {label}
        flags = 0
        for line in labels.get(label, []):
            p = line.replace(",", " ").split()
            op, args = p[0], [a for a in p[1:]]
            if op == "obj_Condition":
                cond = num(args[0])
            elif op == "obj_Interaction":
                v = [num(a) for a in args]
                y, x = (v[2], v[3]) if len(v) >= 4 else (0, 0)
                out.append((KIND_INTERACTION, v[0], v[1], y, x, 1, 0 if len(v) >= 4 else 2, cond))
            elif op == "obj_RandomEnemy":
                v = [num(a) for a in args]
                out.append((KIND_ENEMY, v[1], v[2], 0, 0, max(1, v[0] >> 5 & 7), 1, cond))
            elif op == "obj_SpecificEnemyA":
                v = [num(a) for a in args]
                if len(v) == 5:
                    flags = v[0]
                    v = v[1:]
                out.append((KIND_ENEMY, v[0], v[1], v[2], v[3], 1, 0, cond))
            elif op == "obj_SpecificEnemyB":
                v = [num(a) for a in args]
                out.append((KIND_ENEMY, v[0], v[1], v[2], v[3], 1, 0, cond))
            elif op == "obj_Part":
                v = [num(a) for a in args]
                y, x = ((v[2] >> 4) * 16 + 8, (v[2] & 15) * 16 + 8) if len(v) == 3 else (v[2], v[3])
                out.append((KIND_PART, v[0], v[1], y, x, 1, 0, cond))
            elif op in ("obj_Pointer", "obj_BeforeEvent"):
                out += walk(args[0], cond, seen)
            elif op in ("obj_End", "obj_EndPointer"):
                break
        return out

    result = {}
    for label in labels:
        m = re.match(r"group(\d)Map([0-9a-f]{2})ObjectData$", label)
        if m:
            result[(int(m.group(1)), int(m.group(2), 16))] = walk(label, 0xff, set())
    return result


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(2)
    root, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    objects, sprites, sprite_index, talk = [], [], {}, {}
    for gi, game in enumerate(GAMES):
        sp = Sprites(root, game)
        dialogue = Dialogue(root, game)
        lists = object_lists(root, game)
        # the bosses scripts bring in: General Onox where the Din crystal hangs (his throne room), and
        # Ganon, who rises when Twinrova falls (condition 0: never placed, spawned by the game)
        if game == "seasons":
            lists.setdefault((5, 0x91), []).append((KIND_ENEMY, 0x02, 0, 0x48, 0x78, 1, 0, 0xff))
            lists.setdefault((5, 0x9e), []).append((KIND_ENEMY, 0x04, 0, 0x48, 0x78, 1, 0, 0x00))
        else:
            lists.setdefault((5, 0xf5), []).append((KIND_ENEMY, 0x04, 0, 0x48, 0x78, 1, 0, 0x00))
        for (group, room), objs in sorted(lists.items()):
            for kind, oid, subid, y, x, count, rnd, cond in objs:
                key = (gi, kind, oid, subid)
                if key not in sprite_index:
                    frames, stats = sp.frames(kind, oid, subid)
                    if kind == KIND_INTERACTION:
                        talk[key] = (dialogue.text(oid, subid) or "", secret_of(SECRET_TELLERS, gi, oid, subid),
                                     secret_of(SECRET_TAKERS, gi, oid, subid))
                    sprite_index[key] = len(sprites)
                    sprites.append((key, frames or [], stats))
                objects.append((gi, group, room, kind, oid, subid, y, x, count, rnd, cond))
    # pack frames into rows of a 1024-wide sheet, each distinct picture once
    placed, x, y, row_h, seen = [], 0, 0, 0, {}
    for key, frames, stats in sprites:
        pf = []
        for im, ox, oy, dur in frames:
            k = (im.size, im.tobytes())
            if k not in seen:
                if x + im.width > 1024:
                    x, y, row_h = 0, y + row_h, 0
                seen[k] = (x, y)
                x += im.width
                row_h = max(row_h, im.height)
            fx, fy = seen[k]
            pf.append((fx, fy, im, ox, oy, dur))
        placed.append(pf)
    sheet = Image.new("RGBA", (1024, max(1, y + row_h)))
    for pf in placed:
        for fx, fy, im, ox, oy, dur in pf:
            sheet.paste(im, (fx, fy))     # a repeated picture lands on itself
    with open(os.path.join(out, "sprites.rgba"), "wb") as f:
        f.write(sheet.size[0].to_bytes(4, "little") + sheet.size[1].to_bytes(4, "little") + sheet.tobytes())
    with open(os.path.join(out, "objects.bin"), "wb") as f:
        f.write(b"OOBJ" + struct.pack("<HHH", 2, len(sprites), len(objects)))
        for (key, frames, stats), pf in zip(sprites, placed):
            ry, rx, dmg, hp = stats if stats else (0, 0, 0, 0)
            f.write(struct.pack("<BBBBBBbBB", *key, ry & 0xff, rx & 0xff, dmg - 256 if dmg >= 128 else dmg, hp & 0xff, len(pf)))
            text, tell, take = talk.get(key, ("", 0xff, 0xff))
            tb = text.encode("ascii", "replace")[:399]
            f.write(struct.pack("<BBH", tell, take, len(tb)) + tb)
            for fx, fy, im, ox, oy, dur in pf:
                f.write(struct.pack("<HHBBhhB", fx, fy, min(im.width, 255), min(im.height, 255), ox, oy, dur))
        for o in objects:
            f.write(bytes(o))
    drawn = sum(1 for s in placed if s)
    print(f"{len(objects)} objects, {len(sprites)} kinds ({drawn} with sprites), sheet {sheet.size[0]}x{sheet.size[1]}")


if __name__ == "__main__":
    main()
