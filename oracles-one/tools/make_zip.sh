#!/usr/bin/env bash
# Packs the source and the APK builders into dist/OraclesOne-source.zip (no game data, no
# disassembly: build_apk downloads and generates those on the user's machine).
set -euo pipefail
cd "$(dirname "$0")/.."
rm -rf dist/OraclesOne && mkdir -p dist/OraclesOne
git ls-files -co --exclude-standard | grep -v -e '^disasm' -e '^dist/' | tar -cf - -T - | tar -xf - -C dist/OraclesOne
chmod +x dist/OraclesOne/build_apk.sh dist/OraclesOne/android/gradlew dist/OraclesOne/tools/*.sh
(cd dist && rm -f OraclesOne-source.zip && zip -qr OraclesOne-source.zip OraclesOne)
echo "dist/OraclesOne-source.zip: $(du -h dist/OraclesOne-source.zip | cut -f1)"
