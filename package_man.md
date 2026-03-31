# AI Linux DTE (Dual-Target Environment) Delivery: ISO + APK (Alpine)

Branch (source of truth for runtime behavior): `claude/ai-linux-integration-MfL1q`
UI integration branch: `claude/integrate-dot-vos-ui-68niB`

This doc defines a **single product** delivered two ways:

1) **Standalone ISO** (boot in a VM OR install to HDD/SSD)
2) **APK package set** installable onto an **already-installed Alpine**

And a **boot selector** (“DTE mode”) so a user can choose at boot:
- Normal Alpine
- Baltek DTE - VoiceOS UI (`baltek_ui=vos`) — animated circle + Ollama LLM *(default)*
- Baltek DTE - Chat/Shell (`baltek_ui=chat`) — plain shell fallback

---

## UI: VoiceOS (dot/vos interface)

The `ui/vos/` directory in this repo contains **VoiceOS** — an animated graphical
interface that acts as the primary AI interaction layer for Baltek DTE.

### What it looks like
- Black fullscreen window with an **animated morphing circle** at center
- Circle changes behaviour based on AI state:
  - **IDLE** — gentle organic drift
  - **LISTENING** — increased amplitude (user is typing)
  - **PROCESSING** — energetic, complex motion (Ollama generating)
  - **RESPONDING** — winding down
  - **ERROR** — high-intensity shake
- **Text input** slides up from bottom when any printable key is pressed
- **Response panel** expands to the right of the circle showing streamed LLM output

### Architecture
```
ui/vos/src/
├── main.py               # VoiceOS entry point (asyncio + pygame)
├── core/
│   ├── event/event_bus.py    # Async pub/sub event system
│   └── llm/
│       ├── action_system.py  # Action executor (fetch, respond, launch)
│       ├── mock_llm.py       # Offline mock for testing
│       ├── monitor/          # CPU/memory resource monitor
│       ├── registry/         # Model registry (tracks Ollama models)
│       └── router/           # Task → model router
└── llm/
│   ├── adapters/             # Ollama adapter (event-driven)
│   └── inference/            # Ollama streaming client
└── ui/
    ├── circle.py             # Animated circle with state machine
    ├── text_input.py         # Sliding keyboard input
    └── components/
        └── content_display.py  # Scrollable LLM response panel
```

### Runtime requirements
- Python 3.9+
- `pygame`, `psutil`, `aiohttp` (installed by `baltek-vos-ui` package)
- **Ollama** running locally with at least one model (e.g. `mistral:latest`)
- X server (started automatically by `baltek-vos-launch` via `xinit`)

### Switching modes at boot
The boot menu offers two Baltek DTE entries. To change the default, edit
`/etc/baltek/dte.conf` and set `BALTEK_UI_MODE=chat` or `BALTEK_UI_MODE=vos`.
You can also pass `baltek_ui=chat` on the kernel command line without editing files.

---

## 0) Design constraints / assumptions (make these true)
- Alpine base, **OpenRC**.
- Bootloader is typically **extlinux** on Alpine (GRUB possible; handled later).
- Your “special environment” is primarily **userspace + services** (QEMU / VM orchestration), not a full custom kernel.
- If you *do* require kernel patches: ship a **custom kernel package** too (covered in “Kernel fork” note).

---

## 1) High-level architecture

### 1.1 Package set (works on installed Alpine AND ISO)
Create these packages:

- `baltek-dte` *(meta)*  
  Depends on everything needed to run DTE.

- `baltek-dte-config` *(files + services)*  
  Installs:
  - `/etc/baltek/*.conf`
  - `/usr/local/sbin/baltek-dte-*` scripts
  - OpenRC services:
    - `baltek-mode` (decides if we are in DTE mode by reading kernel cmdline)
    - `baltek-vm` (starts the VM only when DTE mode is active)
  - Boot menu integration helper: `/usr/local/sbin/baltek-bootmenu-install`

Optional, recommended:
- `baltek-dte-assets` *(large artifacts)*  
  **Do NOT** bake big VM disk images into the config package unless you enjoy slow upgrades.
  Prefer: download/provision on first boot (curl/wget + checksum) or pull from local USB.

### 1.2 DTE mode switch (boot-time)
Use **one Alpine rootfs**, two boot entries:

- `LABEL alpine` → normal behavior
- `LABEL baltek-dte` → same kernel/initramfs/rootfs, adds `baltek_mode=dte` on cmdline

Then at boot:
- `baltek-mode` service reads `/proc/cmdline`
- If `baltek_mode=dte`, it enables/starts DTE services and optionally tweaks sysctl/net.
- If not, it does nothing.

This avoids “two installs”, keeps drift low, and upgrades are painless.

---

## 2) Repo layout (current)

```
ui/
  vos/                        ← VoiceOS UI source (from DOT submodule)
    src/
      main.py
      core/event/ llm/ ...
      llm/adapters/ inference/ ...
      ui/circle.py text_input.py components/
    requirements.txt

packaging/
  aports/
    baltek-dte/               ← meta package (depends on everything)
      APKBUILD
    baltek-dte-config/        ← OpenRC services + boot helper
      APKBUILD
      baltek-dte-config.post-install
      baltek-dte-config.post-deinstall
      files/
        baltek-mode.initd
        baltek-vm.initd
        baltek-mode-run       ← reads baltek_ui= to select vos or chat
        baltek-vm-run
        baltek-bootmenu-install  ← adds BOTH vos and chat boot entries
        dte.conf              ← default UI mode = vos
        vm.conf
        99-baltek-dte.conf
    baltek-vos-ui/            ← VoiceOS UI package
      APKBUILD
      baltek-vos-ui.post-install
      baltek-vos-ui.post-deinstall
      files/
        baltek-vos-launch     ← launcher (starts ollama + xinit + VoiceOS)
        baltek-vos-ui.initd   ← OpenRC service
        xinitrc-vos           ← minimal X session for VoiceOS
  scripts/
    build_apk_repo.sh
    build_iso.sh
    install_on_alpine.sh

DOT/                          ← git submodule (BaltekLabs/DOT)
  DotOS/                      ← simple Zen Circle visualizer (reference)
  vos/                        ← VoiceOS source (canonical upstream)
```

If you already have VM boot logic somewhere in the branch, you will **wrap it** into:
- `files/baltek-vm-run` (launcher)
- `files/vm.conf` (config)

---

## 3) Script: build APK repo (signed)
Create: `packaging/scripts/build_apk_repo.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail

# Builds APKs using abuild, signs, and produces an apk repo you can host.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APORTS_DIR="$ROOT/aports"
OUT_DIR="$ROOT/out/apkrepo"
KEY_DIR="$ROOT/keys"
REPO_NAME="baltek"

mkdir -p "$OUT_DIR" "$KEY_DIR"

echo "[1/6] Ensure alpine-sdk present (run inside Alpine build env)"
echo "      apk add alpine-sdk abuild openssl"

echo "[2/6] Ensure abuild key exists"
if ! ls "$KEY_DIR"/*.rsa >/dev/null 2>&1; then
  echo "Generating abuild signing key into $KEY_DIR ..."
  abuild-keygen -a -n
  # abuild-keygen writes into ~/.abuild by default; copy it
  mkdir -p "$HOME/.abuild"
  cp -a "$HOME/.abuild/"*.rsa "$KEY_DIR/" || true
  cp -a "$HOME/.abuild/"*.pub "$KEY_DIR/" || true
fi

echo "[3/6] Configure abuild to use local aports tree"
export APORTSDIR="$APORTS_DIR"
export REPODEST="$OUT_DIR"
export PACKAGER_PRIVKEY="$(ls "$KEY_DIR"/*.rsa | head -n1)"

echo "[4/6] Build packages"
for pkg in baltek-dte baltek-dte-config; do
  echo "Building $pkg ..."
  ( cd "$APORTS_DIR/$pkg" && abuild -r )
done

echo "[5/6] Index repo"
apk index -o "$OUT_DIR/$REPO_NAME".INDEX.tar.gz "$OUT_DIR"/*.apk
abuild-sign "$OUT_DIR/$REPO_NAME".INDEX.tar.gz

echo "[6/6] Done"
echo "Repo output: $OUT_DIR"
echo "Host it over HTTP (nginx, github pages, s3) and add to /etc/apk/repositories."
```

---

## 4) Package content (APKs)

### 4.1 `baltek-dte` (metapackage)
`packaging/aports/baltek-dte/APKBUILD`

```sh
pkgname=baltek-dte
pkgver=0.1.0
pkgrel=0
pkgdesc="Baltek DTE meta package (dependencies only)"
url="https://github.com/BaltekLabs/linux"
arch="all"
license="MIT"
depends="
  baltek-dte-config
  baltek-vos-ui
  qemu-system-x86_64
  qemu-img
  ovmf
  iproute2
  bridge-utils
  openssh
"
build() { :; }
package() { :; }
```

### 4.3 `baltek-vos-ui`
`packaging/aports/baltek-vos-ui/APKBUILD`

Installs the VoiceOS UI (Python + Pygame) from `ui/vos/src/` in this repo.
Depends on `python3 py3-pygame py3-psutil py3-aiohttp ollama xorg-server xinit`.

Post-install creates a Python venv at `/opt/baltek/vos/venv/` and pip-installs deps.

Key files installed:
- `/opt/baltek/vos/` — Python source tree
- `/usr/local/sbin/baltek-vos-launch` — launcher (starts Ollama + xinit + VoiceOS)
- `/etc/init.d/baltek-vos-ui` — OpenRC service (only active when `baltek_mode=dte`)
- `/etc/baltek/xinitrc-vos` — minimal X session script

### 4.2 `baltek-dte-config`
`packaging/aports/baltek-dte-config/APKBUILD`

```sh
pkgname=baltek-dte-config
pkgver=0.1.0
pkgrel=0
pkgdesc="Baltek DTE config + OpenRC services + boot menu helper"
url="https://github.com/BaltekLabs/linux"
arch="all"
license="MIT"
depends="openrc busybox"
install="$pkgname.post-install $pkgname.post-deinstall"

build() { :; }

package() {
  install -d "$pkgdir/etc/baltek"
  install -m 0644 "$srcdir/files/dte.conf" "$pkgdir/etc/baltek/dte.conf"
  install -m 0644 "$srcdir/files/vm.conf"  "$pkgdir/etc/baltek/vm.conf"

  install -d "$pkgdir/etc/init.d"
  install -m 0755 "$srcdir/files/baltek-mode.initd" "$pkgdir/etc/init.d/baltek-mode"
  install -m 0755 "$srcdir/files/baltek-vm.initd"   "$pkgdir/etc/init.d/baltek-vm"

  install -d "$pkgdir/usr/local/sbin"
  install -m 0755 "$srcdir/files/baltek-mode-run"       "$pkgdir/usr/local/sbin/baltek-mode-run"
  install -m 0755 "$srcdir/files/baltek-vm-run"         "$pkgdir/usr/local/sbin/baltek-vm-run"
  install -m 0755 "$srcdir/files/baltek-bootmenu-install" "$pkgdir/usr/local/sbin/baltek-bootmenu-install"

  install -d "$pkgdir/etc/sysctl.d"
  install -m 0644 "$srcdir/files/99-baltek-dte.conf" "$pkgdir/etc/sysctl.d/99-baltek-dte.conf"
}
```

`packaging/aports/baltek-dte-config/baltek-dte-config.post-install`
```sh
#!/bin/sh
set -e

# Always enable baltek-mode; it decides what to do at boot.
rc-update add baltek-mode default >/dev/null 2>&1 || true

# baltek-vm should *not* autostart in normal mode; baltek-mode will start it in DTE mode.
rc-update del baltek-vm default >/dev/null 2>&1 || true

mkdir -p /var/lib/baltek/vm /var/log/baltek

# Best-effort sysctl apply
sysctl --system >/dev/null 2>&1 || true

exit 0
```

`packaging/aports/baltek-dte-config/baltek-dte-config.post-deinstall`
```sh
#!/bin/sh
set -e
rc-update del baltek-mode default >/dev/null 2>&1 || true
rc-update del baltek-vm default >/dev/null 2>&1 || true
exit 0
```

---

## 5) Services and scripts installed by `baltek-dte-config`

### 5.1 `baltek-mode` service (decides DTE mode)
`packaging/aports/baltek-dte-config/files/baltek-mode.initd`
```sh
#!/sbin/openrc-run
name="baltek-mode"
description="Baltek mode selector (starts DTE services when baltek_mode=dte is on cmdline)"
command="/usr/local/sbin/baltek-mode-run"
command_background="no"

depend() {
  need localmount
  after modules
  before net
}

start() {
  ebegin "Baltek mode selector"
  "$command"
  eend $?
}
```

`packaging/aports/baltek-dte-config/files/baltek-mode-run`
```sh
#!/bin/sh
set -e

CMDLINE="$(cat /proc/cmdline 2>/dev/null || true)"

# If not in DTE mode, do nothing.
echo "$CMDLINE" | grep -q "baltek_mode=dte" || exit 0

# DTE mode: start/enable what you need.
# Keep it deterministic and idempotent.

# Optional sysctl (already applied on install, but safe)
sysctl --system >/dev/null 2>&1 || true

# Start VM service
rc-service baltek-vm start || true

exit 0
```

### 5.2 `baltek-vm` service (starts VM)
`packaging/aports/baltek-dte-config/files/baltek-vm.initd`
```sh
#!/sbin/openrc-run
name="baltek-vm"
description="Start Baltek VM"
command="/usr/local/sbin/baltek-vm-run"
command_background="yes"
pidfile="/run/baltek-vm.pid"
output_log="/var/log/baltek/vm.out"
error_log="/var/log/baltek/vm.err"

depend() {
  need net
  after firewall
}

start_pre() {
  checkpath -d -m 0755 /var/log/baltek
  checkpath -d -m 0755 /var/lib/baltek/vm
}

start() {
  ebegin "Starting ${name}"
  start-stop-daemon --start --background     --make-pidfile --pidfile "$pidfile"     --stdout "$output_log" --stderr "$error_log"     --exec "$command"
  eend $?
}

stop() {
  ebegin "Stopping ${name}"
  start-stop-daemon --stop --pidfile "$pidfile"
  eend $?
}
```

`packaging/aports/baltek-dte-config/files/vm.conf`
```sh
VM_NAME="baltek-guest"
VM_DISK="/var/lib/baltek/vm/disk.qcow2"
VM_RAM_MB="4096"
VM_CPUS="4"

OVMF_CODE="/usr/share/OVMF/OVMF_CODE.fd"
OVMF_VARS="/var/lib/baltek/vm/OVMF_VARS.fd"

NET_MODE="user"  # user|tap
```

`packaging/aports/baltek-dte-config/files/baltek-vm-run`
```sh
#!/bin/sh
set -e

CONF="/etc/baltek/vm.conf"
[ -f "$CONF" ] && . "$CONF"

: "${VM_DISK:=/var/lib/baltek/vm/disk.qcow2}"
: "${VM_RAM_MB:=2048}"
: "${VM_CPUS:=2}"
: "${OVMF_CODE:=/usr/share/OVMF/OVMF_CODE.fd}"
: "${OVMF_VARS:=/var/lib/baltek/vm/OVMF_VARS.fd}"
: "${NET_MODE:=user}"

# Persistent OVMF vars
if [ -f "$OVMF_CODE" ] && [ ! -f "$OVMF_VARS" ]; then
  if [ -f /usr/share/OVMF/OVMF_VARS.fd ]; then
    cp /usr/share/OVMF/OVMF_VARS.fd "$OVMF_VARS"
  elif [ -f /usr/share/OVMF/OVMF_VARS.ms.fd ]; then
    cp /usr/share/OVMF/OVMF_VARS.ms.fd "$OVMF_VARS"
  fi
fi

# Provision disk if absent (replace with your branch-specific logic if needed)
if [ ! -f "$VM_DISK" ]; then
  qemu-img create -f qcow2 "$VM_DISK" 40G
fi

NET_ARGS=""
case "$NET_MODE" in
  user)
    NET_ARGS="-netdev user,id=n1 -device virtio-net-pci,netdev=n1"
    ;;
  tap)
    NET_ARGS="-netdev tap,id=n1,ifname=tap0,script=no,downscript=no -device virtio-net-pci,netdev=n1"
    ;;
  *)
    echo "Unknown NET_MODE: $NET_MODE" >&2
    exit 1
    ;;
esac

exec qemu-system-x86_64   -name "${VM_NAME:-baltek-guest}"   -machine q35,accel=kvm   -cpu host   -smp "$VM_CPUS"   -m "$VM_RAM_MB"   -drive if=pflash,format=raw,readonly=on,file="$OVMF_CODE"   -drive if=pflash,format=raw,file="$OVMF_VARS"   -drive file="$VM_DISK",if=virtio,cache=none,discard=unmap   $NET_ARGS   -nographic
```

### 5.3 Boot menu installer (extlinux)
`packaging/aports/baltek-dte-config/files/baltek-bootmenu-install`

Adds **two** Baltek DTE entries to extlinux:
- `baltek_ui=vos` — launches VoiceOS graphical UI (default)
- `baltek_ui=chat` — drops to plain shell (fallback / debug)

The `baltek-mode-run` script reads whichever option was set at boot and starts
the appropriate service (`baltek-vos-ui` or nothing extra for chat mode).
```sh
#!/bin/sh
set -e

CONF="/boot/extlinux.conf"

if [ ! -f "$CONF" ]; then
  echo "extlinux.conf not found at $CONF. This system may be using GRUB."
  echo "If GRUB: implement /etc/grub.d/40_baltek_dte + grub-mkconfig."
  exit 1
fi

# Only add once
grep -q "LABEL baltek-dte" "$CONF" && { echo "baltek-dte entry already present"; exit 0; }

# Append a new label. Reuse the existing kernel/initramfs lines from DEFAULT if possible.
# Minimal, generic approach: assumes vmlinuz-lts + initramfs-lts exist.
cat >> "$CONF" <<'EOF'

LABEL baltek-dte
  MENU LABEL Baltek DTE (baltek_mode=dte)
  LINUX /vmlinuz-lts
  INITRD /initramfs-lts
  APPEND root=UUID=REPLACE_ME ro quiet baltek_mode=dte
EOF

echo
echo "Added baltek-dte entry to $CONF"
echo "IMPORTANT: replace root=UUID=REPLACE_ME with your actual root UUID."
echo "Find it with: blkid | grep ' TYPE="ext4"' (or your FS) then edit $CONF."
```

---

## 6) Script: install on an existing Alpine system
Create: `packaging/scripts/install_on_alpine.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail

# Installs Baltek DTE onto an already-installed Alpine system.
# Assumes you host the apk repo somewhere.

REPO_URL="${1:-}"
if [[ -z "$REPO_URL" ]]; then
  echo "Usage: $0 <apk-repo-url>"
  echo "Example: $0 https://yourdomain.example/alpine/baltek"
  exit 1
fi

echo "[1/5] Add apk repo"
echo "$REPO_URL" | sudo tee -a /etc/apk/repositories >/dev/null

echo "[2/5] Update + install"
sudo apk update
sudo apk add baltek-dte

echo "[3/5] Install boot menu entry (extlinux)"
sudo /usr/local/sbin/baltek-bootmenu-install || true

echo "[4/5] Ensure mode selector enabled"
sudo rc-update add baltek-mode default || true

echo "[5/5] Done"
echo "Reboot and choose 'Baltek DTE' at boot (or set as default in extlinux.conf)."
```

---

## 7) Build a standalone ISO (VM boot + disk install)
Alpine builds ISOs using `mkimage` profiles. Your agent should create a profile that:
- adds your repo
- installs `baltek-dte`
- includes extlinux entry for DTE by default (optional)

Create: `packaging/scripts/build_iso.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail

# Builds a custom Alpine ISO that includes Baltek DTE packages.
# Run inside an Alpine environment with alpine-sdk + alpine-conf + alpine-mkimage.

ISO_OUT_DIR="$(pwd)/out/iso"
APK_REPO_URL="${APK_REPO_URL:-""}"   # export this if your repo is remote
PROFILE_NAME="baltek"
ARCH="${ARCH:-x86_64}"
ALPINE_BRANCH="${ALPINE_BRANCH:-edge}"  # or v3.20, v3.21, etc.

mkdir -p "$ISO_OUT_DIR"

echo "[1/5] Install tooling"
apk add --no-cache alpine-sdk alpine-conf alpine-mkimage syslinux xorriso squashfs-tools || true

echo "[2/5] Create mkimage profile"
PROFILE_DIR="/usr/share/alpine-mkimage/profiles"
PROFILE_FILE="$PROFILE_DIR/$PROFILE_NAME.sh"

cat > "$PROFILE_FILE" <<'EOF'
profile_balteK() {
  profile_standard
  title="Baltek DTE"
  desc="Alpine with Baltek DTE packages and boot entry"
  # Add packages that should be on the ISO
  apks="$apks baltek-dte"
}
EOF

# NOTE: mkimage expects function naming; some versions want: profile_baltek vs profile_balteK
# Agent should validate the exact profile naming required by the alpine-mkimage version used.

echo "[3/5] If using remote repo, ensure it is in /etc/apk/repositories"
if [ -n "${APK_REPO_URL}" ]; then
  grep -qF "$APK_REPO_URL" /etc/apk/repositories || echo "$APK_REPO_URL" >> /etc/apk/repositories
  apk update
fi

echo "[4/5] Build ISO"
mkimage --tag "$ALPINE_BRANCH" --arch "$ARCH" --profile "$PROFILE_NAME" --outdir "$ISO_OUT_DIR"

echo "[5/5] Done"
ls -lh "$ISO_OUT_DIR"
```

**Agent action item:** mkimage profile function naming is version-sensitive. The agent should run `ls /usr/share/alpine-mkimage/profiles` and inspect an existing profile to match conventions exactly.

---

## 8) Mapping your branch behavior into this packaging
Your branch already “boots into a VM”. To convert it cleanly:

1) Identify the entrypoint logic (what currently triggers the VM boot):
   - OpenRC service?
   - initramfs hook?
   - rc.local/local.d?
   - custom init?

2) Move that orchestration logic into:
   - `/usr/local/sbin/baltek-vm-run`
   - `/etc/baltek/vm.conf` for parameters
   - Optional: `/usr/local/sbin/baltek-vm-provision` (download/provision disk)

3) Make it conditional on **DTE mode**:
   - Do **not** start VM in normal boot.
   - Only start it when `baltek_mode=dte`.

4) Ensure logs + pid management are stable:
   - write pid into `/run/baltek-vm.pid`
   - log stdout/stderr to `/var/log/baltek/`

---

## 9) Kernel fork note (only if needed)
If the branch truly requires kernel patches:
- create an additional package:
  - `baltek-kernel-lts` (or `baltek-kernel-virt`) built the Alpine way
- ISO includes that kernel by default
- installed-alpine path: user installs your kernel package + updates extlinux entry to point at it.

Do **not** ship a random `bzImage` blob and call it a day.

---

## 10) Agent checklist (execution order)

1) ✅ Create the `packaging/` layout and files (done in `claude/integrate-dot-vos-ui-68niB`).
2) ✅ Copy `ui/vos/` source tree from DOT submodule into repo (done).
3) Build APKs with `build_apk_repo.sh` in an Alpine build env.
4) Host the repo (simple nginx container is fine).
5) Validate install on vanilla Alpine:
   - add repo
   - `apk add baltek-dte`
   - run `baltek-bootmenu-install`
   - reboot → choose “Baltek DTE - VoiceOS UI” for graphical AI interface
   - reboot → choose “Baltek DTE - Chat/Shell” for terminal fallback
6) Pull an Ollama model: `ollama pull mistral:latest`
7) Build ISO with `build_iso.sh` and validate:
   - boot ISO in VM → DTE available with VoiceOS UI
   - install to disk → both boot entries work

---

## 11) Where your agent must adapt (expected)
- extlinux kernel/initramfs names differ (`/vmlinuz-virt` vs `-lts`).
- root UUID must be inserted into extlinux APPEND.
- if you use GRUB instead of extlinux, implement GRUB entry install too.
- networking: bridging/tap setup may need `ifupdown-ng` configs or a dedicated OpenRC service.

---

## 12) Quick smoke tests

### On installed Alpine
```sh
apk add baltek-dte
rc-status
cat /proc/cmdline
rc-service baltek-mode restart
tail -n 200 /var/log/baltek/vm.out
```

### DTE mode detection
- Normal boot: `cat /proc/cmdline` should NOT contain `baltek_mode=dte`
- DTE boot: it SHOULD contain `baltek_mode=dte` and `baltek-vm` should be running.

---

If you want to harden this further:
- add a healthcheck that the VM is up (QMP socket, ssh ping, etc.)
- add rollback-safe updates (apk version pinning + staged reboot)
