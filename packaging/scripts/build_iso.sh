#!/usr/bin/env bash
set -euo pipefail

# Builds a custom Alpine ISO that includes Baltek DTE packages.
# Run inside an Alpine environment with alpine-sdk + alpine-conf + alpine-mkimage.

ISO_OUT_DIR="$(pwd)/out/iso"
APK_REPO_URL="${APK_REPO_URL:-""}"
PROFILE_NAME="baltek"
ARCH="${ARCH:-x86_64}"
ALPINE_BRANCH="${ALPINE_BRANCH:-edge}"

mkdir -p "$ISO_OUT_DIR"

echo "[1/5] Install tooling"
apk add --no-cache alpine-sdk alpine-conf alpine-mkimage syslinux xorriso squashfs-tools || true

echo "[2/5] Create mkimage profile"
PROFILE_DIR="/usr/share/alpine-mkimage/profiles"
PROFILE_FILE="$PROFILE_DIR/$PROFILE_NAME.sh"

cat > "$PROFILE_FILE" <<'EOF'
profile_baltek() {
  profile_standard
  title="Baltek DTE"
  desc="Alpine with Baltek DTE packages (VoiceOS UI + VM)"
  apks="$apks baltek-dte"
}
EOF

echo "[3/5] If using remote repo, ensure it is in /etc/apk/repositories"
if [ -n "${APK_REPO_URL}" ]; then
  grep -qF "$APK_REPO_URL" /etc/apk/repositories || echo "$APK_REPO_URL" >> /etc/apk/repositories
  apk update
fi

echo "[4/5] Build ISO"
mkimage --tag "$ALPINE_BRANCH" --arch "$ARCH" --profile "$PROFILE_NAME" --outdir "$ISO_OUT_DIR"

echo "[5/5] Done"
ls -lh "$ISO_OUT_DIR"
