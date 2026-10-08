#!/usr/bin/env bash
# Builds OraclesOne.apk on Linux or macOS: ./build_apk.sh
#
# Needs git, Python 3 and curl/unzip, and an internet connection. Java 17, the Android SDK and NDK,
# SDL and the oracles-disasm data are downloaded into .buildtools and disasm next to this file.
# The first run takes 15-30 minutes; later runs reuse the downloads.
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$PWD"
BT="$ROOT/.buildtools"
DISASM_COMMIT=21c924afdc8a13225e849214479d5113da2ec226
mkdir -p "$BT"
fail() { echo "$*" >&2; exit 1; }

command -v git >/dev/null || fail "git is not installed (Linux: your package manager; macOS: xcode-select --install)."
command -v python3 >/dev/null || fail "Python 3 is not installed (https://www.python.org/downloads/)."
command -v curl >/dev/null || fail "curl is not installed."
command -v unzip >/dev/null || fail "unzip is not installed."

case "$(uname -s)" in
  Darwin) os=mac; jdk_os=mac ;;
  *) os=linux; jdk_os=linux ;;
esac
case "$(uname -m)" in
  arm64|aarch64) arch=aarch64 ;;
  *) arch=x64 ;;
esac

# --- Java 17 (an installed Java 17 or newer is used as is) -----------------------------------------
java_major() { "$1" -version 2>&1 | grep -m1 'version' | sed -E 's/.*version "([0-9]+).*/\1/'; }
if [ -z "${JAVA_HOME:-}" ] && command -v java >/dev/null && [ "$(java_major java)" -ge 17 ] 2>/dev/null; then
  JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)" 2>/dev/null || command -v java)")")"
fi
if [ -n "${JAVA_HOME:-}" ] && [ "$(java_major "$JAVA_HOME/bin/java")" -ge 17 ] 2>/dev/null; then
  echo "Using Java in $JAVA_HOME"
elif [ ! -x "$BT/jdk/bin/java" ] && [ ! -x "$BT/jdk/Contents/Home/bin/java" ]; then
  echo "Downloading Java 17..."
  curl -fL "https://api.adoptium.net/v3/binary/latest/17/ga/$jdk_os/$arch/jdk/hotspot/normal/eclipse" -o "$BT/jdk.tar.gz"
  mkdir -p "$BT/jdk-unpack"
  tar -xzf "$BT/jdk.tar.gz" -C "$BT/jdk-unpack"
  mv "$BT"/jdk-unpack/* "$BT/jdk"
  rm -rf "$BT/jdk-unpack" "$BT/jdk.tar.gz"
fi
if [ -z "${JAVA_HOME:-}" ] || [ ! "$(java_major "$JAVA_HOME/bin/java")" -ge 17 ] 2>/dev/null; then
  if [ -d "$BT/jdk/Contents/Home" ]; then JAVA_HOME="$BT/jdk/Contents/Home"; else JAVA_HOME="$BT/jdk"; fi
fi
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

# --- Android SDK, NDK and CMake -------------------------------------------------------------------
SDK="$BT/android-sdk"
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$SDKMANAGER" ]; then
  echo "Downloading the Android command-line tools..."
  curl -fL "https://dl.google.com/android/repository/commandlinetools-$os-11076708_latest.zip" -o "$BT/cmdline-tools.zip"
  mkdir -p "$SDK/cmdline-tools"
  unzip -q "$BT/cmdline-tools.zip" -d "$SDK/cmdline-tools-unzip"
  mv "$SDK/cmdline-tools-unzip/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$SDK/cmdline-tools-unzip" "$BT/cmdline-tools.zip"
fi
echo "Installing Android SDK parts (accepting their licences)..."
yes | "$SDKMANAGER" --sdk_root="$SDK" --licenses >/dev/null || true
"$SDKMANAGER" --sdk_root="$SDK" "platforms;android-35" "build-tools;35.0.0" "ndk;27.2.12479018" "cmake;3.22.1" "platform-tools"

# --- The game data, from the oracles-disasm disassembly ---------------------------------------------
if [ ! -f "$ROOT/disasm/ages.s" ]; then
  echo "Downloading oracles-disasm..."
  rm -rf "$ROOT/disasm"
  git init -q "$ROOT/disasm"
  git -C "$ROOT/disasm" fetch -q --depth 1 https://github.com/Stewmath/oracles-disasm "$DISASM_COMMIT"
  git -C "$ROOT/disasm" checkout -q FETCH_HEAD
fi
[ -x "$BT/venv/bin/python" ] || python3 -m venv "$BT/venv"
"$BT/venv/bin/python" -m pip install -q pillow
echo "Building the game data..."
mkdir -p "$ROOT/assets"
"$BT/venv/bin/python" "$ROOT/tools/extract_world.py" "$ROOT/disasm" "$ROOT/assets"
"$BT/venv/bin/python" "$ROOT/tools/extract_sprites.py" "$ROOT/disasm" "$ROOT/assets"
"$BT/venv/bin/python" "$ROOT/tools/extract_objects.py" "$ROOT/disasm" "$ROOT/assets"
"$BT/venv/bin/python" "$ROOT/tools/extract_audio.py" "$ROOT/disasm" "$ROOT/assets"

# --- The APK ---------------------------------------------------------------------------------------
echo "sdk.dir=$SDK" > "$ROOT/android/local.properties"
echo "Building the APK..."
(cd "$ROOT/android" && chmod +x gradlew && ./gradlew assembleRelease --no-daemon)
cp "$ROOT/android/app/build/outputs/apk/release/app-release.apk" "$ROOT/OraclesOne.apk"
echo
echo "Done: $ROOT/OraclesOne.apk"
echo "Copy it to your handheld or phone and open it to install (allow installing from unknown sources)."
