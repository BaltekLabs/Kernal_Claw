#!/usr/bin/env bash
# build_android.sh — Build VoiceOS Android Launcher APK
# Usage: bash build_android.sh [debug|release]
#
# Requirements (run once to set up):
#   pip install buildozer cython
#   sudo apt-get install -y git zip unzip openjdk-17-jdk python3-pip \
#       autoconf libtool pkg-config zlib1g-dev libncurses5-dev \
#       libncursesw5-dev libtinfo5 cmake libffi-dev libssl-dev
#
# Output: ui/vos/bin/voiceos-1.0.0-arm64-v8a-debug.apk  (or release)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
VOS_DIR="$REPO_ROOT/ui/vos"
OUT_DIR="$REPO_ROOT/packaging/out"
BUILD_TYPE="${1:-debug}"

echo "=== VoiceOS Android APK builder ==="
echo "  Build type : $BUILD_TYPE"
echo "  Source dir : $VOS_DIR"
echo "  Output dir : $OUT_DIR"
echo ""

# ── Pre-flight checks ────────────────────────────────────────────
if ! command -v buildozer &>/dev/null; then
    echo "ERROR: buildozer not found. Install with: pip install buildozer cython"
    exit 1
fi

if ! command -v java &>/dev/null; then
    echo "ERROR: Java not found. Install OpenJDK 17:"
    echo "  sudo apt-get install openjdk-17-jdk"
    exit 1
fi

# ── Build ─────────────────────────────────────────────────────────
cd "$VOS_DIR"

if [ "$BUILD_TYPE" = "release" ]; then
    echo "Building RELEASE APK (requires keystore)..."
    echo ""
    echo "Set these env vars before running for release signing:"
    echo "  KEYSTORE_PATH, KEYSTORE_ALIAS, KEYSTORE_PASS, KEY_PASS"
    echo ""

    if [ -n "${KEYSTORE_PATH:-}" ]; then
        buildozer android release
    else
        echo "No keystore configured — building unsigned release:"
        buildozer android release
        echo ""
        echo "Sign with: apksigner sign --ks your.keystore bin/*.apk"
    fi
else
    echo "Building DEBUG APK..."
    buildozer android debug
fi

# ── Copy output ──────────────────────────────────────────────────
mkdir -p "$OUT_DIR"
APK=$(find "$VOS_DIR/bin" -name "*.apk" | head -1)
if [ -n "$APK" ]; then
    cp "$APK" "$OUT_DIR/"
    echo ""
    echo "=== Build complete ==="
    echo "APK: $OUT_DIR/$(basename "$APK")"
    echo ""
    echo "Install on device:"
    echo "  adb install -r $OUT_DIR/$(basename "$APK")"
else
    echo "ERROR: No APK found in $VOS_DIR/bin/"
    exit 1
fi
