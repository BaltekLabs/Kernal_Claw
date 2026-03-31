#!/bin/bash
# build-iso.sh — one-shot build script for the AI Linux ISO
# Run from the repo root: bash build-iso.sh

set -e

REPO="$(cd "$(dirname "$0")" && pwd)"
DEPS="$REPO/.build-deps"

# ---- Prepend local build tools to PATH --------------------------------
export PATH="$DEPS/usr/bin:$PATH"
export BISON_PKGDATADIR="$DEPS/usr/share/bison"
export M4="$DEPS/usr/bin/m4"
export HOSTCFLAGS="-I$DEPS/usr/include"
export HOSTLDFLAGS="-L$DEPS/usr/lib/x86_64-linux-gnu -L/usr/lib/x86_64-linux-gnu"
export LD_LIBRARY_PATH="$DEPS/usr/lib/x86_64-linux-gnu:/usr/lib/x86_64-linux-gnu"

# ---- Verify required tools --------------------------------------------
for tool in flex bison m4 make gcc grub-mkimage; do
    if ! command -v "$tool" &>/dev/null; then
        echo "ERROR: $tool not found"
        exit 1
    fi
done
echo "[OK] Build tools found"

# ---- Kernel config ----------------------------------------------------
echo ""
echo "==> Step 1: make ai_defconfig"
cd "$REPO"
make ai_defconfig

echo ""
echo "==> Step 1b: Compile aicore daemon"
make -C "$REPO/tools/aicore" \
    CC=gcc \
    CFLAGS="-O2 -Wall -D_GNU_SOURCE -I$DEPS/usr/include -I$DEPS/usr/include/x86_64-linux-gnu" \
    LDFLAGS="-L$DEPS/usr/lib/x86_64-linux-gnu -Wl,-rpath,/lib/x86_64-linux-gnu -lcurl -lssl -lcrypto -lz -lpthread"
install -m 755 "$REPO/tools/aicore/aicore" "$REPO/rootfs/usr/sbin/aicore"

echo ""
echo "==> Step 1c: Copy VoS UI sources into rootfs"
rm -rf "$REPO/rootfs/usr/local/lib/vos"
mkdir -p "$REPO/rootfs/usr/local/lib/vos"
cp -r "$REPO/ui/vos/src/." "$REPO/rootfs/usr/local/lib/vos/"
echo "[OK] VoS sources copied to rootfs/usr/local/lib/vos/"

echo ""
echo "==> Step 2: Build kernel bzImage (make bzImage -j$(nproc))"
echo "    This will take several minutes..."
make bzImage -j$(nproc) 2>&1

echo ""
echo "==> Step 3: Build hybrid BIOS+UEFI ISO"
ISOROOT="$(mktemp -d)"
WORKDIR="$(mktemp -d)"
trap "rm -rf '$WORKDIR' '$ISOROOT'" EXIT

mkdir -p "$ISOROOT/boot/grub"
cp "$REPO/arch/x86/boot/bzImage" "$ISOROOT/boot/vmlinuz"

cat > "$ISOROOT/boot/grub/grub.cfg" <<'GRUBCFG'
set default=0
set timeout=3

menuentry "AI Linux" {
    linux /boot/vmlinuz console=tty1 console=ttyS0,115200n8
}
GRUBCFG

# BIOS El Torito boot image: cdboot.img + core.img
grub-mkimage \
    --directory=/usr/lib/grub/i386-pc \
    --prefix=/boot/grub \
    --output="$WORKDIR/core.img" \
    --format=i386-pc \
    --compression=auto \
    biosdisk iso9660 configfile normal search minicmd linux echo all_video
cat /usr/lib/grub/i386-pc/cdboot.img "$WORKDIR/core.img" > "$ISOROOT/boot/grub/bios.img"
cp -a /usr/lib/grub/i386-pc "$ISOROOT/boot/grub/"

# EFI binary
grub-mkimage \
    --directory="$DEPS/usr/lib/grub/x86_64-efi" \
    --prefix=/boot/grub \
    --output="$WORKDIR/bootx64.efi" \
    --format=x86_64-efi \
    --compression=auto \
    part_gpt part_msdos fat iso9660 configfile normal search linux echo all_video
cp -a "$DEPS/usr/lib/grub/x86_64-efi" "$ISOROOT/boot/grub/"

# FAT EFI system partition image
dd if=/dev/zero of="$ISOROOT/efi.img" bs=512 count=5760 2>/dev/null
mformat -i "$ISOROOT/efi.img" -f 2880 ::
mmd -i "$ISOROOT/efi.img" ::/EFI ::/EFI/BOOT
mcopy -i "$ISOROOT/efi.img" "$WORKDIR/bootx64.efi" ::/EFI/BOOT/bootx64.efi

# Assemble hybrid ISO (BIOS El Torito + UEFI El Torito + hybrid MBR)
"$DEPS/usr/bin/xorriso" -as mkisofs \
    -r -J -joliet-long \
    -V "AILINUX" \
    --grub2-mbr /usr/lib/grub/i386-pc/boot_hybrid.img \
    -partition_offset 16 \
    --mbr-force-bootable \
    -c '/boot.catalog' \
    -b '/boot/grub/bios.img' \
    -no-emul-boot -boot-load-size 4 -boot-info-table --grub2-boot-info \
    -eltorito-alt-boot \
    -e '/efi.img' \
    -no-emul-boot \
    -o "$REPO/arch/x86/boot/image.iso" \
    "$ISOROOT" 2>&1

echo ""
echo "=========================================================="
echo " ISO built: arch/x86/boot/image.iso  ($(du -sh $REPO/arch/x86/boot/image.iso | cut -f1))"
echo " Type: $(file $REPO/arch/x86/boot/image.iso | cut -d: -f2 | xargs)"
echo "=========================================================="
echo ""
echo "Test with QEMU (BIOS):"
echo "  qemu-system-x86_64 -cdrom arch/x86/boot/image.iso -boot d -m 512M -nographic"
echo ""
echo "Test with QEMU (UEFI):"
echo "  qemu-system-x86_64 -cdrom arch/x86/boot/image.iso -m 512M -bios /usr/share/ovmf/OVMF.fd"
echo ""
echo "For GNOME Boxes: use this ISO file directly — it supports both BIOS and UEFI."
echo ""
echo "Remember to fill in your API key:"
echo "  \$EDITOR rootfs/etc/aicore/config.json"
