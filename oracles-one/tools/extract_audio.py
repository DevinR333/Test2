#!/usr/bin/env python3
"""Builds the music and sound effects of both games from the oracles-disasm sources.

The audio data (audio/{game}/soundChannelData.s and the files it includes) is a list of macros
(note, rest, vol, env, duty, vibrato, goto...) that each stand for the bytes the games' sound
engine (code/audio.s) reads. This assembles them back into those bytes, so src/audio.c can play
them the way the engine does.

Writes OUT_DIR/audio.bin (little endian):
  "OAUD", u16 version 1
  per game (seasons, ages):
    u32 data length, the channel data
    u16 sound count, then per sound: u8 channel count, per channel u8 channel, u32 offset
    8 x 256 bytes: the music of every room, per group (musicAssignments.s)
    u16 named count, then per name: char[24] name, u8 sound id (MUS_*/SND_* in constants)
  16 x 16 bytes: the wave channel's waveforms (audio/*/waveforms.s, by index)
  noise table: u8 count, then (note, NR42, NR43) per entry

Usage: extract_audio.py DISASM_DIR OUT_DIR
"""
import os
import re
import struct
import sys

GAMES = ("seasons", "ages")
NOTES = ["c", "cs", "d", "ds", "e", "f", "fs", "g", "gs", "a", "as", "b"]
NOTE_VALUE = {f"{n}{o}": (o - 1) * 12 + i for o in range(1, 9) for i, n in enumerate(NOTES)}


def num(t):
    t = t.strip().rstrip(",")
    if t.startswith("$"):
        return int(t[1:], 16)
    if t.startswith("%"):
        return int(t[1:], 2)
    if t in NOTE_VALUE:
        return NOTE_VALUE[t]
    return int(t, 0)


class Assembler:
    def __init__(self, root, game):
        self.root, self.game = root, game
        self.data = bytearray()
        self.labels, self.fixups, self.aliases = {}, [], {}
        self.defines = {"ROM_SEASONS" if game == "seasons" else "ROM_AGES", "BUILD_VANILLA"}
        self.fallback = None

    def lines(self, rel):
        for raw in open(os.path.join(self.root, rel)):
            line = raw.split(";", 1)[0].strip()
            if line:
                yield line

    def assemble(self, rel):
        """Assembles a file (following .include), handling .ifdef/.else/.endif and .rept/.endr."""
        body = []
        stack = []                       # .ifdef states: (active)
        for line in self.lines(rel):
            word = line.split()[0]
            if word == ".ifdef":
                stack.append(line.split()[1] in self.defines)
                continue
            if word == ".ifndef":
                stack.append(line.split()[1] not in self.defines)
                continue
            if word == ".else":
                stack[-1] = not stack[-1]
                continue
            if word == ".endif":
                stack.pop()
                continue
            if not all(stack):
                continue
            body.append(line)
        self.run(body)

    def run(self, body):
        i = 0
        while i < len(body):
            line = body[i]
            if line.startswith(".rept"):
                count = num(line.split()[1])
                depth, j = 1, i + 1
                while depth:
                    if body[j].startswith(".rept"):
                        depth += 1
                    elif body[j].startswith(".endr"):
                        depth -= 1
                    j += 1
                for _ in range(count):
                    self.run(body[i + 1:j - 1])
                i = j
                continue
            self.line(line)
            i += 1

    def line(self, line):
        m = re.match(r"([A-Za-z_@][\w@]*):$", line)
        if m:
            self.labels[m.group(1)] = len(self.data)
            return
        p = line.replace(",", " ").split()
        op, args = p[0], p[1:]
        if op == ".include":
            self.assemble(args[0].strip('"'))
        elif op in (".define", ".redefine"):
            if args[0] == "MUSIC_CHANNEL_FALLBACK":
                self.fallback = args[1]
            elif len(args) >= 2 and args[1] == "MUSIC_CHANNEL_FALLBACK":
                self.aliases[args[0]] = self.fallback
            elif len(args) >= 2:
                self.aliases[args[0]] = args[1]
        elif op == ".db":
            self.data += bytes(num(a) & 0xff for a in args)
        elif op == ".dw":
            for a in args:
                self.fixups.append((len(self.data), a))
                self.data += b"\0\0"
        elif op == "note":
            # note NAME LEN (also numbers for the noise channel)
            for k in range(0, len(args) - 1, 2):
                self.data += bytes((num(args[k]) & 0xff, num(args[k + 1]) & 0xff))
        elif op == "rest":
            self.data += bytes((0x60, num(args[0])))
        elif op == "rest2":
            self.data += bytes((0x61, num(args[0])))
        elif op == "vol":
            self.data.append(0xd0 | num(args[0]))
        elif op == "env":
            self.data += bytes((0xe0 | num(args[0]), num(args[1])))
        elif op in ("cmdf0", "duty", "cmdf8", "vibrato", "cmdfd"):
            code = {"cmdf0": 0xf0, "duty": 0xf6, "cmdf8": 0xf8, "vibrato": 0xf9, "cmdfd": 0xfd}[op]
            self.data += bytes((code, num(args[0])))
        elif op in ("cmdf1", "cmdf2", "cmdf3", "cmdf4", "cmdf5", "cmdff"):
            self.data.append(int(op[3:], 16))
        elif op == "goto":
            self.data.append(0xfe)
            self.fixups.append((len(self.data), args[0]))
            self.data += b"\0\0\0"            # a 24-bit offset into the blob (the original's bank + pointer)
        # anything else (sections, .dsb padding) carries no sound

    def resolve(self, name):
        seen = 0
        while name in self.aliases and seen < 8:
            name, seen = self.aliases[name], seen + 1
        return self.labels.get(name)

    def finish(self):
        for at, name in self.fixups:
            off = self.resolve(name)
            if off is None:
                off = 0xffffff
            if self.data[at - 1] == 0xfe:
                self.data[at:at + 3] = off.to_bytes(3, "little")
            else:
                self.data[at:at + 2] = (off & 0xffff).to_bytes(2, "little")


def sound_table(root, game, asm):
    """Sound id -> [(channel, offset)] from soundPointers.s and soundChannelPointers.s."""
    names = []
    for raw in open(os.path.join(root, f"audio/{game}/soundPointers.s")):
        m = re.search(r"m_soundPointer\s+(\w+)", raw.split(";", 1)[0])
        if m:
            names.append(m.group(1))
    chans, cur, pending = {}, None, []
    for raw in open(os.path.join(root, f"audio/{game}/soundChannelPointers.s")):
        line = raw.split(";", 1)[0].strip()
        m = re.match(r"(\w+):$", line)
        if m:
            pending.append(m.group(1))
            continue
        if line.startswith(".db"):
            v = num(line.split()[1])
            if v == 0xff:
                for p in pending:
                    chans[p] = cur or []
                pending, cur = [], None
            else:
                cur = (cur or []) + [[v, None]]
        elif line.startswith(".dw") and cur:
            cur[-1][1] = line.split()[1]
    out = []
    for n in names:
        lst = []
        for ch, label in chans.get(n, []):
            off = asm.resolve(label) if label else None
            lst.append((ch, 0xffffffff if off is None else off))
        out.append(lst)
    return out


def room_music(root, game):
    """8 groups x 256 rooms of music ids (musicAssignments.s)."""
    groups, pending = [None] * 8, []
    for raw in open(os.path.join(root, f"data/{game}/musicAssignments.s")):
        line = raw.split(";", 1)[0].strip()
        m = re.match(r"group(\d)Music:$", line)
        if m:
            pending.append(int(m.group(1)))
            continue
        m = re.match(r'\.incbin\s+"([^"]+)"', line)
        if m:
            data = open(os.path.join(root, m.group(1)), "rb").read()[:256].ljust(256, b"\0")
            for g in pending:
                groups[g] = data
            pending = []
    return b"".join(g or bytes(256) for g in groups)


def constant_ids(root, game):
    """MUS_* and SND_* -> id (constants/common/music.s, an .enum with game branches)."""
    ids, val, stack = {}, 0, []
    for raw in open(os.path.join(root, "constants/common/music.s")):
        line = raw.split(";", 1)[0].strip()
        if not line:
            continue
        p = line.split()
        if p[0] == ".ifdef":
            stack.append(p[1] == ("ROM_SEASONS" if game == "seasons" else "ROM_AGES"))
            continue
        if p[0] == ".else":
            stack[-1] = not stack[-1]
            continue
        if p[0] == ".endif":
            stack.pop()
            continue
        if not all(stack):
            continue
        if p[0] == ".enum":
            val = num(p[1])
        elif len(p) >= 2 and p[1] in ("db", ".db"):
            ids[p[0]] = val
            val += 1
        elif p[0] == ".define" and len(p) >= 3 and p[1].startswith(("SND_", "MUS_")):
            try:
                ids[p[1]] = num(p[2])
            except ValueError:
                pass
    return ids


def waveforms(root):
    wf = {}
    for game in GAMES + ("common",):
        path = os.path.join(root, f"audio/{game}/waveforms.s")
        if not os.path.exists(path):
            continue
        cur = None
        for raw in open(path):
            line = raw.split(";", 1)[0].strip()
            m = re.match(r"m_waveform\s+\$(\w+)", line)
            if m:
                cur = int(m.group(1), 16)
                continue
            if cur is not None and line.startswith(".db"):
                wf.setdefault(cur, bytes(num(t) for t in line[3:].split()))
                cur = None
    return b"".join(wf.get(i, bytes(16)).ljust(16, b"\0")[:16] for i in range(0x30))


def noise_table(root):
    for game in ("common",) + GAMES:
        path = os.path.join(root, f"audio/{game}/noise.s")
        if os.path.exists(path):
            rows = []
            for raw in open(path):
                line = raw.split(";", 1)[0].strip()
                if line.startswith(".db"):
                    v = [num(t) for t in line[3:].split()]
                    if len(v) == 3:
                        rows.append(v)
            return bytes([len(rows)]) + b"".join(bytes(r) for r in rows)
    return b"\0"


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(2)
    root, out = sys.argv[1], sys.argv[2]
    blob = b"OAUD" + struct.pack("<H", 1)
    report = []
    for game in GAMES:
        asm = Assembler(root, game)
        asm.assemble(f"audio/{game}/soundChannelData.s")
        asm.finish()
        sounds = sound_table(root, game, asm)
        blob += struct.pack("<I", len(asm.data)) + bytes(asm.data)
        blob += struct.pack("<H", len(sounds))
        for lst in sounds:
            blob += bytes([len(lst)]) + b"".join(struct.pack("<BI", ch, off) for ch, off in lst)
        blob += room_music(root, game)
        ids = constant_ids(root, game)
        named = [(n, v) for n, v in ids.items() if v < len(sounds)]
        blob += struct.pack("<H", len(named))
        for n, v in named:
            blob += n.encode()[:23].ljust(24, b"\0") + bytes([v])
        report.append(f"{game}: {len(sounds)} sounds, {len(asm.data)} bytes")
    blob += waveforms(root) + noise_table(root)
    with open(os.path.join(out, "audio.bin"), "wb") as f:
        f.write(blob)
    print(", ".join(report))


if __name__ == "__main__":
    main()
