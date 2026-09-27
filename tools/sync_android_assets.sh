#!/usr/bin/env bash
# Re-exports the Godot game data into the Android Studio project
# (android/build/assets). Run this after changing anything in the game.
#
#   GODOT=/path/to/godot4 tools/sync_android_assets.sh
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
godot="${GODOT:-godot}"
assets="$root/android/build/assets"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

"$godot" --headless --path "$root" --export-pack "Android" "$tmp/game.zip"

# Keep the engine command-line file (_cl_) Godot wrote; replace everything else.
find "$assets" -mindepth 1 -maxdepth 1 ! -name '_cl_' -exec rm -rf {} +
unzip -q -o "$tmp/game.zip" -d "$assets"
echo "Android assets updated in $assets"
