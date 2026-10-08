#!/usr/bin/env python3
"""Builds the objects placed in rooms (objects/{game}/*.s) and their sprites.

Writes into OUT_DIR:
  sprites.rgba  the animation frames of every placed interaction, enemy and part, packed in rows
  objects.bin   (little endian)
    "OOBJ", u16 version 4, u16 sprite count, u16 object count
    sprites: u8 game, u8 kind (0 interaction, 1 enemy, 2 part), u8 id, u8 subid,
             u8 radius y, u8 radius x, s8 damage (quarter hearts, negative), u8 health,
             u8 frame count (<= 4), u8 secret told, u8 secret taken ($ff none), u16 text length,
             the text (what the character says first), u8 when shown (0 always, 1 Horon stage
             mask, 2 Sunken City stage, 3 from Ages progress), u16 its mask / stage / progress,
             u8 Ages progress function (0 none), u8 table offset, u8 text count, (u16 length,
             text) per progress, u8 gift count, (u8 treasure, u8 parameter, u16 length, text)
             per gift, then per frame: u16 x, u16 y (in
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
                text = raw.strip().replace("\\up", "+").replace("\\down", "-")
                text = re.sub(r"\\col\([^)]*\)", "", text)
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


# ---- who shows up and what they say as the story moves on ----------------------------------------

def seasons_conditions(root):
    """(id, subid) -> ("stage", mask) for Horon Village's conditional NPCs (conditionalHoronNPCLookupTable:
    seen only at listed checkNPCStage stages), or ("sunken", stage) for the Sunken City ones."""
    path = os.path.join(root, "object_code/seasons/interactions/miscNpcs.s")
    blocks, cur, order = {}, None, []
    for raw in open(path):
        line = raw.split(";", 1)[0].strip()
        m = re.match(r"(@{0,2}\w+):$", line)
        if m:
            cur = m.group(1)
            blocks[cur] = []
            order.append(cur)
            continue
        if cur and line:
            blocks[cur].append(line)
    for i in range(len(order) - 2, -1, -1):          # stacked labels share the next one's lines
        if not blocks[order[i]]:
            blocks[order[i]] = blocks[order[i + 1]]
    top = [l.split()[1] for l in blocks.get("conditionalHoronNPCLookupTable", []) if l.startswith(".dw")]
    per_b = []
    for lab in top:
        subs = [l.split()[1] for l in blocks.get(lab, []) if l.startswith(".dw")]
        lists = []
        for sl in subs:
            name = sl.replace("@@", "")
            data = []
            for l in blocks.get("@@" + name, blocks.get(sl, [])):
                if l.startswith(".db"):
                    data += [num(t) for t in l[3:].split()]
            mask = 0
            for v in data:
                if v == 0:
                    break
                mask |= 1 << (v - 1)
            lists.append(mask)
        per_b.append(lists)
    out = {}
    for oid, b in ((0x2d, 0), (0x37, 1), (0x3c, 2), (0x3d, 3), (0x80, 7)):
        if b < len(per_b):
            for sub, mask in enumerate(per_b[b]):
                out[(oid, sub)] = ("stage", mask)
    for hi in range(3):
        if 4 + hi < len(per_b):
            for lo, mask in enumerate(per_b[4 + hi]):
                out[(0x3e, hi << 4 | lo)] = ("stage", mask)
    for lo in range(5):
        out[(0x3e, 0x30 | lo)] = ("sunken", lo)
    for oid in (0x36, 0x39):
        for sub in range(5):
            out[(oid, sub)] = ("sunken", sub)
    return out


def ages_progress(root, dialogue):
    """(id, subid) -> (progress function 1/2, least progress shown at, table offset, [texts]) for the
    Ages characters whose script comes from a table by getGameProgress_1/_2."""
    out = {}
    d = os.path.join(root, "object_code/ages/interactions")
    for fn in sorted(os.listdir(d)):
        text = open(os.path.join(d, fn)).read()
        if "getGameProgress" not in text:
            continue
        ids = [int(x, 16) for x in re.findall(r"interactionCode([0-9a-f]{2}):", text)]
        lines = [l.split(";", 1)[0].strip() for l in text.splitlines()]
        tables, cur = {}, None
        for l in lines:
            m = re.match(r"(@\w+):$", l)
            if m:
                cur = m.group(1)
                tables.setdefault(cur, [])
                continue
            if cur and l.startswith(".dw"):
                r = re.findall(r"mainScripts\.(\w+)", l)
                if r:
                    tables[cur].append(r[0])
        i = 0
        while i < len(lines):
            subs = []
            while i < len(lines) and re.match(r"@initSubid([0-9a-f]{2}):$", lines[i]):
                subs.append(int(re.match(r"@initSubid([0-9a-f]{2}):$", lines[i]).group(1), 16))
                i += 1
            if not subs:
                i += 1
                continue
            fnum, least, off, table = None, 0, 0, None
            j = i
            while j < len(lines) and not re.match(r"@\w+:$", lines[j]):
                l = lines[j]
                m = re.search(r"getGameProgress_([12])", l)
                if m:
                    fnum = int(m.group(1))
                m = re.match(r"cp \$([0-9a-f]{2})", l)
                if m and fnum and j + 1 < len(lines) and "interactionDelete" in lines[j + 1] and "jp c" in lines[j + 1]:
                    least = int(m.group(1), 16)
                m = re.match(r"sub \$([0-9a-f]{2})", l)
                if m and fnum:
                    off = int(m.group(1), 16)
                m = re.match(r"ld hl,(@\w+)", l)
                if m and fnum and m.group(1) in tables:
                    table = tables[m.group(1)]
                j += 1
            if fnum and table:
                texts = [dialogue.script_text(t) or "" for t in table]
                for oid in ids[:1]:
                    for sub in subs:
                        out[(oid, sub)] = (fnum, least, off, texts)
            i = j
    return out


# Story items characters hand over in their scripts (giveitem): what each kind of character gives.
SKIP_GIFTS = ("TREASURE_GASHA_SEED", "TREASURE_TRADEITEM", "TREASURE_RING", "TREASURE_BOMBS", "TREASURE_HEART_CONTAINER",
              "TREASURE_ORE_CHUNKS", "TREASURE_BIGGORON_SWORD", "TREASURE_BOMBCHUS", "TREASURE_BOMB_UPGRADE")


def gifts(root, game, dialogue):
    """interaction id -> [(treasure, parameter, pickup text)] from the giveitem lines of its scripts."""
    from extract_world import treasure_objects
    tre = enum_ids(root, "constants/common/treasure.s", game, "TREASURE_")
    objs = treasure_objects(root, game)
    by_tp = {}
    for name, (t, prm, tx) in objs.items():
        by_tp.setdefault((t, prm), tx)
    texts = dialogue.texts
    out = {}
    names = dialogue.names
    for oid in set(list(dialogue.code.keys()) + list(names.keys())):
        shared, refs, txs = dialogue.code.get(oid, (0, [], []))
        labels = list(refs) if shared == 1 else []
        labels += dialogue.sections.get(names.get(oid, ""), [])
        found = []
        for lab in labels:
            for line in dialogue.scripts.get(lab, []):
                p = line.replace(",", " ").split()
                if not p or p[0] != "giveitem" or len(p) < 2:
                    continue
                name = p[1]
                if name.startswith(SKIP_GIFTS) or name.startswith("ITEM_"):
                    continue
                if name.startswith("TREASURE_OBJECT_"):
                    if name not in objs:
                        continue
                    t, prm, tx = objs[name]
                else:
                    if name not in tre:
                        continue
                    t = tre[name]
                    prm = num(p[2]) if len(p) > 2 else 0
                    tx = by_tp.get((t, prm), by_tp.get((t, 0), 0xff))
                text = texts.get(tx, "") if tx != 0xff else ""
                if (t, prm) not in [(a, b) for a, b, _ in found]:
                    found.append((t, prm, text))
        if found:
            out[oid] = found[:4]
    return out


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

    by_table = {}
    for label in labels:
        m = re.match(r"group(\d)Map([0-9a-f]{2})ObjectData$", label)
        if m:
            by_table[(int(m.group(1)), int(m.group(2), 16))] = walk(label, 0xff, set())
    # objectDataGroupTable: which table each group reads (Seasons' groups 1-3 share group 1's)
    tables = []
    for line in lines_of(os.path.join(root, f"objects/{game}/pointers.s")):
        m = re.match(r"\.dw\s+group(\d)ObjectDataTable", line)
        if m:
            tables.append(int(m.group(1)))
        elif tables and not line.startswith(".dw"):
            break
    result = {}
    for group, src in enumerate(tables[:8]):
        for (g, room), objs in by_table.items():
            if g == src:
                result[(group, room)] = objs
    return result


def companions(root, out):
    """companions.rgba + companions.bin: Ricky, Dimitri and Moosh (special objects $0b-$0d) from
    data/seasons/specialObjectAnimationData.s: each animation's frames, and each gfx frame drawn from
    its spr_ file with its OAM layout and palettes. Little endian, per companion:
      u16 animations, per animation: u8 frame count, u8 loop frame ($ff holds), (u8 duration, u8 gfx)
      u16 gfx frames, per frame: u16 x, u16 y, u8 w, u8 h, s8 origin x, s8 origin y"""
    g = Game(root, "seasons")
    cols = g.label_colours("standardSpritePaletteData")
    pals = [cols[p * 4:p * 4 + 4] for p in range(min(8, len(cols) // 4))]
    blocks, order, cur = {}, [], None
    for line in lines_of(os.path.join(root, "data/seasons/specialObjectAnimationData.s")):
        m = re.match(r"(\w+):$", line)
        if m:
            cur = m.group(1)
            blocks[cur] = []
            order.append(cur)
            continue
        if cur:
            blocks[cur].append(line)
    for i in range(len(order) - 2, -1, -1):
        if not blocks[order[i]]:
            blocks[order[i]] = blocks[order[i + 1]]
    oam, cur = {}, None
    for line in lines_of(os.path.join(root, "data/seasons/specialObjectOamData.s")):
        m = re.match(r"(\w+):$", line)
        if m:
            cur = m.group(1)
            oam[cur] = []
            continue
        if cur and line.startswith(".db"):
            oam[cur] += [num(t) for t in line[3:].split()]
    images, blob = [], b""
    # palettes from commonCode.s's special object table: Ricky 3, Dimitri 2, Moosh 1
    for obj, fname, objpal in ((0x0b, "spr_ricky", 3), (0x0c, "spr_dimitri", 2), (0x0d, "spr_moosh", 1)):
        tiles = g.gfx_file(fname)
        gp = []
        for l in blocks[f"specialObject{obj:02x}GfxPointers"]:
            m = re.match(r"m_SpecialObjectGfxPointer\s+(\$?\w+)\s+\w+\s+(\$?\w+)", l)
            if m:
                gp.append((num(m.group(1)), num(m.group(2)) // 16))
        op = [l.split()[1] for l in blocks[f"specialObject{obj:02x}OamDataPointers"] if l.startswith(".dw")]
        ap = [l.split()[1] for l in blocks[f"specialObject{obj:02x}AnimationDataPointers"] if l.startswith(".dw")]
        # animations: frames until a loop (relative jump) or a hold
        part = struct.pack("<H", len(ap))
        for lab in ap:
            i = order.index(lab)
            seq, loop = [], 0xff
            j = i
            while j < len(order) and len(seq) < 32:
                if j != i and not order[j].startswith("animationLoop"):
                    break
                stop = False
                for l in blocks[order[j]]:
                    if l.startswith("m_AnimationLoop"):
                        k = order.index(l.split()[1])
                        loop = sum(1 for q in range(i, k) for x in blocks[order[q]] if x.startswith(".db"))
                        stop = True
                        break
                    if l.startswith(".db"):
                        v = [num(t) for t in l[3:].split()]
                        if len(v) == 3:
                            seq.append(v[:2])
                if stop:
                    break
                j += 1
            if loop != 0xff and loop >= len(seq):
                loop = 0xff
            part += bytes([len(seq), loop]) + b"".join(bytes(f) for f in seq)
        part += struct.pack("<H", len(gp))
        for o, tb in gp:
            d = oam.get(op[o] if o < len(op) else "", [0])
            n = d[0] if d else 0
            ents = []
            for k in range(n):
                y, x, t, a = d[1 + k * 4:5 + k * 4]
                ents.append(((y - 256 if y >= 128 else y) - 16, (x - 256 if x >= 128 else x) - 8, t, a))
            if not ents:
                images.append(None)
                part += struct.pack("<HHBBbb", 0, 0, 0, 0, 0, 0)
                continue
            x0, y0 = min(e[1] for e in ents), min(e[0] for e in ents)
            x1, y1 = max(e[1] for e in ents) + 8, max(e[0] for e in ents) + 16
            im = Image.new("RGBA", (x1 - x0, y1 - y0))
            px = im.load()
            for y, x, t, a in reversed(ents):
                rgba = [None] + [c5(c) for c in pals[(a + objpal) & 7][1:]]
                t = (t + tb) & 0xfffe
                for half in range(2):
                    data = tiles[(t + half) * 16:(t + half) * 16 + 16]
                    if len(data) < 16:
                        continue
                    for row in range(8):
                        sy = 7 - row if a & 0x40 else row
                        lo, hi = data[sy * 2], data[sy * 2 + 1]
                        for col in range(8):
                            sx = 7 - col if a & 0x20 else col
                            c = (lo >> (7 - sx) & 1) | (hi >> (7 - sx) & 1) << 1
                            if c:
                                yy = (half if not a & 0x40 else 1 - half) * 8 + row
                                px[x - x0 + col, y - y0 + yy] = rgba[c]
            images.append(im)
            part += struct.pack("<HHBBbb", len(images) - 1, 0, im.width, im.height, x0, y0)   # x/y fixed below
        blob += part
    # pack the pictures in rows and patch their places in
    W, x, y, row = 512, 0, 0, 0
    places = []
    for im in images:
        if im is None:
            places.append((0, 0))
            continue
        if x + im.width > W:
            x, y, row = 0, y + row, 0
        places.append((x, y))
        x += im.width
        row = max(row, im.height)
    sheet = Image.new("RGBA", (W, max(1, y + row)))
    for im, (px_, py_) in zip(images, places):
        if im is not None:
            sheet.paste(im, (px_, py_))
    with open(os.path.join(out, "companions.rgba"), "wb") as f:
        f.write(sheet.size[0].to_bytes(4, "little") + sheet.size[1].to_bytes(4, "little") + sheet.tobytes())
    # rewrite: the gfx records carry an image index in x; turn it into the sheet position
    outb, p = bytearray(), 0
    for _ in range(3):
        na = blob[p] | blob[p + 1] << 8
        outb += blob[p:p + 2]; p += 2
        for _ in range(na):
            nf = blob[p]
            outb += blob[p:p + 2 + nf * 2]; p += 2 + nf * 2
        ng = blob[p] | blob[p + 1] << 8
        outb += blob[p:p + 2]; p += 2
        for _ in range(ng):
            idx, _y, w, h, ox, oy = struct.unpack_from("<HHBBbb", blob, p)
            px_, py_ = places[idx] if w else (0, 0)
            outb += struct.pack("<HHBBbb", px_, py_, w, h, ox, oy)
            p += 8
    with open(os.path.join(out, "companions.bin"), "wb") as f:
        f.write(bytes(outb))


def game_lines(root, rel, game):
    """A source file's lines with .ifdef ROM_SEASONS / ROM_AGES resolved for one game."""
    stack = []
    for raw in open(os.path.join(root, rel)):
        line = re.sub(r"/\*.*?\*/", "", raw.split(";", 1)[0]).strip()
        if not line:
            continue
        w = line.split()[0]
        if w == ".ifdef":
            stack.append(line.split()[1] == ("ROM_SEASONS" if game == "seasons" else "ROM_AGES"))
        elif w == ".ifndef":
            stack.append(line.split()[1] != ("ROM_SEASONS" if game == "seasons" else "ROM_AGES"))
        elif w.startswith(".if"):
            stack.append(True)
        elif w == ".else":
            stack[-1] = not stack[-1]
        elif w == ".endif":
            stack.pop()
        elif all(stack):
            yield line


def enum_ids(root, rel, game, prefix):
    ids, val = {}, 0
    for line in game_lines(root, rel, game):
        p = line.split()
        if p[0] == ".enum":
            val = num(p[1])
        elif len(p) >= 2 and p[0].startswith(prefix) and p[1] in ("db", ".db"):
            ids[p[0]] = val
            val += 1
        elif p[0] == ".define" and len(p) >= 3 and p[1].startswith(prefix):
            try:
                ids[p[1]] = num(p[2])
            except ValueError:
                pass
    return ids


def shops(root, out):
    """shops.bin: what shop items sell. u16 count, then (u8 game, u8 interaction id, u8 subid,
    u8 currency (0 rupees, 1 ore chunks, 2 bombs, 3 ember, 4 scent, 5 gale seeds), u16 price,
    u8 treasure, u8 parameter) each. From shopItem.s (both games) and Seasons' subrosianShop.s."""
    rows = []
    rupees = [0, 1, 2, 5, 10, 20, 40, 30, 60, 70, 25, 50, 100, 200, 400, 150, 300, 500, 900, 80, 999]
    for gi, game in enumerate(GAMES):
        tre = enum_ids(root, "constants/common/treasure.s", game, "TREASURE_")
        rv = enum_ids(root, "constants/common/rupeeValues.s", game, "RUPEEVAL_")
        consts = {**tre, **rv, "SPECIALOBJECT_RICKY": 0x0b, "SPECIALOBJECT_DIMITRI": 0x0c, "SPECIALOBJECT_MOOSH": 0x0d}

        def val(t):
            t = t.strip().rstrip(",").lstrip("<")
            if t in consts:
                return consts[t]
            try:
                return num(t)
            except ValueError:
                return 0
        cur, prices, gives = None, [], []
        for line in game_lines(root, "object_code/common/interactions/shopItem.s", game):
            m = re.match(r"(\w+):$", line)
            if m:
                cur = m.group(1)
                continue
            if line.startswith(".db") and cur == "shopItemPrices":
                prices.append(val(line[3:].split()[0]))
            elif line.startswith(".db") and cur == "shopItemTreasureToGive":
                v = line[3:].split()
                gives.append((val(v[0]), val(v[1]) if len(v) > 1 else 0))
        for sub, (t, prm) in enumerate(gives):
            price = rupees[prices[sub]] if sub < len(prices) and prices[sub] < len(rupees) else 0
            if t:
                rows.append((gi, 0x47, sub, 0, price, t, prm & 0xff))
        if game == "seasons":
            cur, costs, gives = None, [], []
            for line in game_lines(root, "object_code/seasons/interactions/subrosianShop.s", game):
                m = re.match(r"(@?\w+):$", line)
                if m:
                    cur = m.group(1)
                    continue
                if cur == "@table_779e" and line.startswith(".db"):
                    costs.append([t.strip() for t in line[3:].split(",")])
                elif cur == "@table_77da" and line.startswith(".db"):
                    v = [t.strip() for t in line[3:].split(",")]
                    gives.append((val(v[0]), val(v[1])))
            other = {"<wNumBombs": 2, "<wNumEmberSeeds": 3, "<wNumScentSeeds": 4, "<wNumGaleSeeds": 5}
            for sub, (t, prm) in enumerate(gives):
                c = costs[sub] if sub < len(costs) else ["0", "$00", "$00", "$00"]
                ore = val(c[3])
                if ore:
                    rows.append((gi, 0x81, sub, 1, rupees[ore] if ore < len(rupees) else 0, t, prm))
                elif c[1] in other:
                    rows.append((gi, 0x81, sub, other[c[1]], val(c[2]), t, prm))
                elif t:
                    rows.append((gi, 0x81, sub, 1, 0, t, prm))
    with open(os.path.join(out, "shops.bin"), "wb") as f:
        f.write(struct.pack("<H", len(rows)) + b"".join(struct.pack("<BBBBHBB", *r) for r in rows))
    return len(rows)


def rings(root, out):
    """rings.bin: the 64 rings' names (TX_3040+) and descriptions (TX_3080+), 24 + 64 bytes each."""
    texts = all_texts(root, "seasons")
    with open(os.path.join(out, "rings.bin"), "wb") as f:
        for i in range(64):
            name = texts.get(0x3040 + i, "").replace("\n", " ").strip()
            desc = texts.get(0x3080 + i, "").strip()
            f.write(name.encode("ascii", "replace")[:23].ljust(24, b"\0") + desc.encode("ascii", "replace")[:63].ljust(64, b"\0"))


def gasha(root, out):
    """gasha.bin: per game 16 spot ranks (gashaSpot.s @gashaSpotRanks), then 5 ranks x 5 maturity rows x
    10 prize weights, then the 5 ring tiers (treasureAndDrops.s ringTierTable): u8 count + rings."""
    ring_ids = enum_ids(root, "constants/common/rings.s", "seasons", "")
    blob = b""
    for game in GAMES:
        cur, ranks, weights = None, [], {}
        for line in game_lines(root, "object_code/common/interactions/gashaSpot.s", game):
            m = re.match(r"(@\w+):$", line)
            if m:
                cur = m.group(1)
                continue
            if cur == "@gashaSpotRanks" and line.startswith("dbrel"):
                ranks.append(int(re.search(r"@rank(\d)Spot", line).group(1)))
            m2 = re.match(r"@rank(\d)Spot", cur or "")
            if m2 and line.startswith(".db"):
                weights.setdefault(int(m2.group(1)), []).append([num(t) for t in line[3:].split()])
        blob += bytes((ranks + [4] * 16)[:16])
        for r in range(5):
            rows = (weights.get(r, []) + [[0] * 10] * 5)[:5]
            for row in rows:
                blob += bytes((row + [0] * 10)[:10])
    cur, tiers = None, {}
    for line in game_lines(root, "code/treasureAndDrops.s", "seasons"):
        m = re.match(r"(@?\w+):$", line)
        if m:
            cur = m.group(1)
            continue
        m2 = re.match(r"@tier(\d)$", cur or "")
        if m2 and line.startswith(".db"):
            tiers.setdefault(int(m2.group(1)), []).extend(ring_ids.get(t, 0) for t in line[3:].split())
    for t in range(5):
        lst = tiers.get(t, [])[:16]
        blob += bytes([len(lst)] + lst)
    with open(os.path.join(out, "gasha.bin"), "wb") as f:
        f.write(blob)


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
        conds = seasons_conditions(root) if game == "seasons" else {}
        progress = ages_progress(root, dialogue) if game == "ages" else {}
        gives = gifts(root, game, dialogue)
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
                                     secret_of(SECRET_TAKERS, gi, oid, subid), conds.get((oid, subid)),
                                     progress.get((oid, subid)), gives.get(oid, []))
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
        f.write(b"OOBJ" + struct.pack("<HHH", 4, len(sprites), len(objects)))
        for (key, frames, stats), pf in zip(sprites, placed):
            ry, rx, dmg, hp = stats if stats else (0, 0, 0, 0)
            f.write(struct.pack("<BBBBBBbBB", *key, ry & 0xff, rx & 0xff, dmg - 256 if dmg >= 128 else dmg, hp & 0xff, len(pf)))
            text, tell, take, cond, prog, gv = talk.get(key, ("", 0xff, 0xff, None, None, []))
            tb = text.encode("ascii", "replace")[:399]
            f.write(struct.pack("<BBH", tell, take, len(tb)) + tb)
            # when the character shows up: 0 always, 1 at the Horon stages in the mask, 2 at one
            # Sunken City stage, 3 from an Ages progress on
            if cond and cond[0] == "stage":
                f.write(struct.pack("<BH", 1, cond[1]))
            elif cond:
                f.write(struct.pack("<BH", 2, cond[1]))
            elif prog:
                f.write(struct.pack("<BH", 3, prog[1]))
            else:
                f.write(struct.pack("<BH", 0, 0))
            # what they say at each Ages progress (function, table offset, texts)
            if prog:
                fnum, least, off, texts = prog
                f.write(bytes([fnum, off, min(len(texts), 8)]))
                for t in texts[:8]:
                    b = t.encode("ascii", "replace")[:399]
                    f.write(struct.pack("<H", len(b)) + b)
            else:
                f.write(bytes([0, 0, 0]))
            # story items the character hands over: (treasure, parameter, text)
            f.write(bytes([len(gv)]))
            for t, prm, gtext in gv:
                b = gtext.encode("ascii", "replace")[:159]
                f.write(bytes([t & 0xff, prm & 0xff]) + struct.pack("<H", len(b)) + b)
            for fx, fy, im, ox, oy, dur in pf:
                f.write(struct.pack("<HHBBhhB", fx, fy, min(im.width, 255), min(im.height, 255), ox, oy, dur))
        for o in objects:
            f.write(bytes(o))
    companions(root, out)
    rings(root, out)
    gasha(root, out)
    print("shop items:", shops(root, out))
    drawn = sum(1 for s in placed if s)
    print(f"{len(objects)} objects, {len(sprites)} kinds ({drawn} with sprites), sheet {sheet.size[0]}x{sheet.size[1]}")


if __name__ == "__main__":
    main()
