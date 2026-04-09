#!/usr/bin/env bash
# =============================================================
# VoiceOS APK build script
# Run this on a Linux machine (or WSL2) with internet access.
# Usage: bash build-apk.sh
# =============================================================
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "==> Checking system dependencies..."
MISSING=()
for cmd in java javac git zip unzip python3 pip3; do
    command -v "$cmd" &>/dev/null || MISSING+=("$cmd")
done

if [ ${#MISSING[@]} -gt 0 ]; then
    echo "ERROR: Missing required tools: ${MISSING[*]}"
    echo "Install them with:"
    echo "  sudo apt-get install -y openjdk-17-jdk git zip unzip python3 python3-pip \\"
    echo "    autoconf libtool pkg-config zlib1g-dev libncurses5-dev cmake libffi-dev libssl-dev"
    exit 1
fi

echo "==> Java version: $(java -version 2>&1 | head -1)"
echo "==> Python version: $(python3 --version)"

echo "==> Installing Buildozer and Cython..."
pip3 install --user --upgrade buildozer==1.5.0 cython

export PATH="$HOME/.local/bin:$PATH"

echo "==> Starting Buildozer Android debug build..."
echo "    (First run downloads ~800 MB of Android SDK + NDK — this will take a while)"
buildozer -v android debug

echo ""
echo "==> Build complete! APK location:"
ls -lh bin/*.apk 2>/dev/null || echo "No APK found in bin/ — check build output above."
