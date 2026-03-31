What was committed
Bug fixes (kernel/ai/)
File	Bug	Fix
ai_core.c:562	client->flags — field doesn't exist on struct ai_client	Removed the invalid line; is_daemon = true already handles it
ai_proc.c	avenrun[] + FSHIFT undefined	Added #include <linux/sched/loadavg.h>
ai_claws.c	Same — avenrun[] in the uptime claw	Same fix
New files
arch/x86/configs/ai_defconfig — Complete kernel config:

CONFIG_AI_CORE=y + AI_PROC_INTERFACE + AI_BUILTIN_CLAWS
Virtio/E1000 NICs for QEMU testing
EFI stub for bare metal
Built-in initramfs with zstd compression
rootfs/init — The /init the kernel boots into:

Mounts proc/sysfs/devtmpfs
DHCP via udhcpc
Starts aicore as daemon
Launches dte on tty1, restarts on exit
rootfs/etc/aicore/config.json — Default config, fill in api_key

scripts/build-rootfs.sh — Downloads Alpine minirootfs, installs packages (curl, ncurses, musl), wires in your binaries

To build the ISO once you have the binaries
# 1. Build userspace tools (statically linked against musl for Alpine)
make -C tools/aicore install-rootfs CC=musl-gcc
make -C tools/dte    install-rootfs CC=musl-gcc

# 2. Configure kernel
make ai_defconfig

# 3. Build bootable ISO (uses existing make isoimage infrastructure)
make INITRAMFS_SOURCE=rootfs isoimage -j$(nproc)
# Output: arch/x86/boot/image.iso (~20-40 MB)

# 4. Test in QEMU
qemu-system-x86_64 -cdrom arch/x86/boot/image.iso -m 512M -nographic

The only thing left before a working ISO is building the actual aicore and dte binaries — everything else is wired up.

what binaries are needed



Two custom binaries need to be built — everything else comes from Alpine automatically:

You build these (2 binaries)
Binary	Source	Destination in rootfs
aicore	tools/aicore/	/usr/sbin/aicore
dte	tools/dte/	/usr/bin/dte
Alpine provides these automatically (via scripts/build-rootfs.sh)
Core shell + tools (busybox):

/bin/sh, /bin/mount, /bin/mkdir, /bin/mknod, /bin/sleep, /bin/hostname, /bin/cat
/sbin/udhcpc — DHCP client (used in /init to get network)
/sbin/modprobe — to load ai_core if built as module
/sbin/ip / /sbin/ifconfig
/usr/bin/sysctl
Runtime libraries (musl-based):

/lib/ld-musl-x86_64.so.1 — dynamic linker
/lib/libcurl.so.4 — HTTP for aicore → LLM API
/lib/libssl.so.3 + /lib/libcrypto.so.3 — TLS (HTTPS to Anthropic)
/lib/libncursesw.so.6 — terminal UI for DTE
/lib/libz.so.1 — zlib (curl dep)
musl libc (replaces glibc — this is why Alpine is perfect here)
pthread is part of musl itself — no separate libpthread.so needed.

How to build the two custom binaries
# Build aicore (needs libcurl-dev on build host)
cd tools/aicore
make CC=gcc CFLAGS="-O2 -I../../include -D_GNU_SOURCE" LDFLAGS="-lcurl -lpthread"
make install-rootfs        # copies to rootfs/usr/sbin/aicore

# Build dte (needs libncurses-dev on build host)
cd ../dte
make CC=gcc CFLAGS="-O2 -I../../include -D_GNU_SOURCE" LDFLAGS="-lncurses -lpthread"
make install-rootfs        # copies to rootfs/usr/bin/dte

For a fully static build (best for initramfs — no library path issues):

# aicore - static
make CC=musl-gcc LDFLAGS="-static -lcurl -lpthread -lssl -lcrypto -lz"

# dte - static
make CC=musl-gcc LDFLAGS="-static -lncursesw -lpthread"

A static build means the binaries bring their own libs and you don't need to worry about Alpine's lib paths in the initramfs at all. Strongly recommended for the initramfs approach.

Summary
rootfs/
├── init                    ← already done (shell script, no compile needed)
├── etc/aicore/config.json  ← already done (fill in api_key)
├── bin/  sbin/  lib/       ← Alpine provides via build-rootfs.sh
├── usr/sbin/aicore         ← YOU BUILD: cd tools/aicore && make install-rootfs
└── usr/bin/dte             ← YOU BUILD: cd tools/dte    && make install-rootfs

That's it. Two make install-rootfs calls after building, then make isoimage.
