#!/usr/bin/env bash
# Builds the game data (assets/) from the oracles-disasm submodule. Needs Python 3 with Pillow.
# The data comes from the disassembly's sources, not from a ROM; it stays out of git.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
disasm="${1:-$here/disasm}"
if [ ! -f "$disasm/ages.s" ]; then
  echo "oracles-disasm not found at $disasm; run: git submodule update --init" >&2
  exit 1
fi
mkdir -p "$here/assets"
python3 "$here/tools/extract_world.py" "$disasm" "$here/assets"
python3 "$here/tools/extract_sprites.py" "$disasm" "$here/assets"
python3 "$here/tools/extract_objects.py" "$disasm" "$here/assets"
python3 "$here/tools/extract_audio.py" "$disasm" "$here/assets"
echo "assets ready in $here/assets"
