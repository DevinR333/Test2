#!/usr/bin/env python3
"""Extract an Xbox 360 disc image (XDVDFS / "XISO") to a folder.

Handles full disc rips (XGD2 / XGD3, with the video partition in front) and
trimmed xiso images. Used by build_apk.ps1 to pull default.xex, data/ and
$SystemUpdate/ out of your own Fable 2 ISO; nothing leaves your machine.

    python extract_xiso.py <image.iso> <out_dir> [--sha256]

--sha256 also prints the image's SHA-256 (compare it with the hash in the
Fable-2-Recomp README before building).
"""

import argparse
import hashlib
import os
import struct
import sys

SECTOR = 2048
MAGIC = b"MICROSOFT*XBOX*MEDIA"
# Game-partition offsets: trimmed xiso, XGD3, XGD2, XGD1.
PARTITION_OFFSETS = (0x0, 0x2080000, 0xFD90000, 0x18300000)
ATTR_DIRECTORY = 0x10


def find_partition(f):
    for base in PARTITION_OFFSETS:
        f.seek(base + 32 * SECTOR)
        if f.read(len(MAGIC)) == MAGIC:
            return base
    return None


class Extractor:
    def __init__(self, f, base, out_dir):
        self.f = f
        self.base = base
        self.out_dir = out_dir
        self.files = 0
        self.bytes = 0

    def read_at(self, sector, size):
        self.f.seek(self.base + sector * SECTOR)
        return self.f.read(size)

    def walk(self, table, offset, rel_dir):
        # Directory tables are binary trees of 4-byte-aligned entries:
        #   u16 left, u16 right (offsets in dwords), u32 sector, u32 size,
        #   u8 attributes, u8 name length, name.
        stack = [offset]
        while stack:
            off = stack.pop()
            if off + 14 > len(table):
                continue
            left, right, sector, size, attrs, name_len = struct.unpack_from("<HHIIBB", table, off)
            if left == 0xFFFF and right == 0xFFFF:
                continue  # padding / empty directory
            name = table[off + 14: off + 14 + name_len].decode("latin-1")
            if left:
                stack.append(left * 4)
            if right:
                stack.append(right * 4)
            if not name or "/" in name or "\\" in name or name in (".", ".."):
                raise ValueError(f"bad entry name {name!r} in {rel_dir or '/'}")
            rel = os.path.join(rel_dir, name)
            if attrs & ATTR_DIRECTORY:
                os.makedirs(os.path.join(self.out_dir, rel), exist_ok=True)
                if size:
                    self.walk(self.read_at(sector, size), 0, rel)
            else:
                self.copy_file(sector, size, rel)

    def copy_file(self, sector, size, rel):
        dst = os.path.join(self.out_dir, rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        if os.path.isfile(dst) and os.path.getsize(dst) == size:
            self.files += 1
            return  # already extracted (resume)
        self.f.seek(self.base + sector * SECTOR)
        remaining = size
        with open(dst, "wb") as out:
            while remaining:
                chunk = self.f.read(min(remaining, 8 << 20))
                if not chunk:
                    raise IOError(f"image truncated while reading {rel}")
                out.write(chunk)
                remaining -= len(chunk)
        self.files += 1
        self.bytes += size
        if self.files % 200 == 0:
            print(f"  {self.files} files, {self.bytes / 2**30:.2f} GiB", flush=True)


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            chunk = f.read(16 << 20)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("image")
    ap.add_argument("out_dir")
    ap.add_argument("--sha256", action="store_true", help="print the image SHA-256 first")
    args = ap.parse_args()

    if args.sha256:
        print("Hashing image (SHA-256)...", flush=True)
        print(f"SHA256 {sha256_of(args.image)}", flush=True)

    with open(args.image, "rb") as f:
        base = find_partition(f)
        if base is None:
            sys.exit("Not an Xbox 360 disc image (no XDVDFS volume found).")
        f.seek(base + 32 * SECTOR + len(MAGIC))
        root_sector, root_size = struct.unpack("<II", f.read(8))
        os.makedirs(args.out_dir, exist_ok=True)
        ex = Extractor(f, base, args.out_dir)
        f.seek(base + root_sector * SECTOR)
        ex.walk(f.read(root_size), 0, "")
    print(f"Extracted {ex.files} files to {args.out_dir}")


if __name__ == "__main__":
    main()
