#!/bin/sh
# scripts/build-rootfs.sh
#
# Downloads the Alpine Linux minirootfs and installs required packages
# into rootfs/.  Run this before "make isoimage".
#
# Usage:
#   sh scripts/build-rootfs.sh
#
# Requires: curl, tar, apk (or Docker/chroot if cross-arch)

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ROOTFS="$REPO_ROOT/rootfs"

ALPINE_VERSION="3.21"
ALPINE_ARCH="x86_64"
ALPINE_MIRROR="https://dl-cdn.alpinelinux.org/alpine"
MINIROOTFS_URL="$ALPINE_MIRROR/v${ALPINE_VERSION}/releases/${ALPINE_ARCH}/alpine-minirootfs-${ALPINE_VERSION}.0-${ALPINE_ARCH}.tar.gz"
MINIROOTFS_TAR="/tmp/alpine-minirootfs.tar.gz"

echo "==> Downloading Alpine ${ALPINE_VERSION} minirootfs..."
curl -L -o "$MINIROOTFS_TAR" "$MINIROOTFS_URL"

echo "==> Extracting into $ROOTFS ..."
# Preserve our custom files: init, etc/aicore/config.json
# Save them, extract Alpine, restore.

mkdir -p "$ROOTFS"

# Preserve custom files
cp "$ROOTFS/init"                        /tmp/ai_init_save     2>/dev/null || true
cp "$ROOTFS/etc/aicore/config.json"      /tmp/ai_config_save   2>/dev/null || true

# Extract Alpine minirootfs (skip if it would overwrite our init)
tar -xzf "$MINIROOTFS_TAR" -C "$ROOTFS" --exclude='./init' 2>/dev/null || \
tar -xzf "$MINIROOTFS_TAR" -C "$ROOTFS"

# Restore custom files
[ -f /tmp/ai_init_save ]   && cp /tmp/ai_init_save   "$ROOTFS/init"
chmod +x "$ROOTFS/init"
mkdir -p "$ROOTFS/etc/aicore"
[ -f /tmp/ai_config_save ] && cp /tmp/ai_config_save "$ROOTFS/etc/aicore/config.json"

echo "==> Installing Alpine packages via apk..."
# Use apk in a chroot if running as root, otherwise note which packages are needed.
if [ "$(id -u)" -eq 0 ]; then
    # Set up resolv.conf for DNS inside chroot
    cp /etc/resolv.conf "$ROOTFS/etc/resolv.conf" 2>/dev/null || true

    chroot "$ROOTFS" /bin/sh -c "
        apk update
        apk add --no-cache \
            busybox \
            busybox-extras \
            curl \
            libcurl \
            ncurses \
            ncursesw \
            openssl \
            libssl3 \
            zlib \
            musl \
            ca-certificates
    "
else
    echo ""
    echo "WARNING: Not running as root — skipping apk install."
    echo "Alpine packages required in rootfs:"
    echo "  busybox, busybox-extras, curl, libcurl, ncurses, openssl,"
    echo "  libssl3, zlib, musl, ca-certificates"
    echo ""
    echo "Run this script as root, or manually chroot into $ROOTFS"
    echo "and run: apk add --no-cache busybox curl libcurl ncurses openssl musl"
fi

echo "==> Creating essential directory structure..."
mkdir -p "$ROOTFS/proc" "$ROOTFS/sys" "$ROOTFS/dev" "$ROOTFS/run"
mkdir -p "$ROOTFS/tmp"  "$ROOTFS/var/log"
chmod 1777 "$ROOTFS/tmp"

# Ensure usr/sbin and usr/bin exist for aicore + dte
mkdir -p "$ROOTFS/usr/sbin" "$ROOTFS/usr/bin"

echo ""
echo "==> rootfs ready at $ROOTFS"
echo ""
echo "Next steps:"
echo "  1. Fill in your API key:"
echo "     \$EDITOR $ROOTFS/etc/aicore/config.json"
echo "  2. Build and install the userspace binaries:"
echo "     make -C tools/aicore install-rootfs"
echo "     make -C tools/dte    install-rootfs"
echo "  3. Build the kernel + ISO:"
echo "     make ai_defconfig"
echo "     make INITRAMFS_SOURCE=rootfs isoimage -j\$(nproc)"
