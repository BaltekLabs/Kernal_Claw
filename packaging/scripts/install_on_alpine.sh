#!/bin/sh
# install_on_alpine.sh — Baltek DTE direct installer for Alpine Linux
#
# Installs the full Wayland/VoiceOS stack directly onto a running Alpine system.
# Does NOT require a pre-built APK repo or abuild — everything is pulled from
# source and installed in-place.
#
# Run as root:
#   sh install_on_alpine.sh
#
# What this script does:
#   1.  Checks the environment
#   2.  Adds Alpine edge/testing repo (needed for Hyprland)
#   3.  Installs system packages via apk
#   4.  >> PAUSES to ask for your GitHub personal access token <<
#   5.  Sparse-clones only ui/vos/ + packaging/ from the repo
#   6.  Creates the 'baltek' system user
#   7.  Installs VoiceOS source to /opt/baltek/vos/
#   8.  Sets up Python venv + installs Python deps
#   9.  Installs config, scripts, OpenRC services
#   10. Enables seatd and baltek-vos-ui in the default runlevel

set -eu

# ─────────────────────────────────────────────────────────────────────────────
# Config
# ─────────────────────────────────────────────────────────────────────────────
REPO_OWNER="BaltekLabs"
REPO_NAME="linux"
REPO_BRANCH="main"          # branch to pull from (override with BALTEK_BRANCH)
REPO_BRANCH="${BALTEK_BRANCH:-$REPO_BRANCH}"

INSTALL_ROOT="/opt/baltek/vos"
CONF_DIR="/etc/baltek"
SBIN_DIR="/usr/local/sbin"
INITD_DIR="/etc/init.d"
LOG_DIR="/var/log/baltek"

CLONE_DIR="/tmp/baltek-src"

# ─────────────────────────────────────────────────────────────────────────────
# Helpers
# ─────────────────────────────────────────────────────────────────────────────
msg()  { printf '\n\033[1;34m>>> %s\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m  ✓ %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m  ! %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

# ─────────────────────────────────────────────────────────────────────────────
# Step 1 — Preflight checks
# ─────────────────────────────────────────────────────────────────────────────
msg "Step 1/10 — Preflight checks"

[ "$(id -u)" -eq 0 ] || die "This script must be run as root."

# Verify this is Alpine
if ! grep -qi 'alpine' /etc/os-release 2>/dev/null; then
  warn "This does not appear to be Alpine Linux. Continuing anyway."
fi

ARCH="$(apk --print-arch)"
ok "Architecture: $ARCH"
ok "Running as root"

# ─────────────────────────────────────────────────────────────────────────────
# Step 2 — Enable Alpine edge/testing repo (Hyprland lives there)
# ─────────────────────────────────────────────────────────────────────────────
msg "Step 2/10 — Enabling Alpine edge/testing repository"

# Detect the mirror already configured and derive edge URLs from it
CURRENT_MAIN=$(grep '^https\?://' /etc/apk/repositories | grep '/main$' | head -n1 || true)
if [ -n "$CURRENT_MAIN" ]; then
  MIRROR_BASE="${CURRENT_MAIN%/main}"
else
  MIRROR_BASE="https://dl-cdn.alpinelinux.org/alpine/edge"
fi

for repo in community testing; do
  REPO_LINE="${MIRROR_BASE}/${repo}"
  if ! grep -qF "$REPO_LINE" /etc/apk/repositories; then
    echo "$REPO_LINE" >> /etc/apk/repositories
    ok "Added: $REPO_LINE"
  else
    ok "Already present: $REPO_LINE"
  fi
done

apk update -q
ok "Package index updated"

# ─────────────────────────────────────────────────────────────────────────────
# Step 3 — Install system packages
# ─────────────────────────────────────────────────────────────────────────────
msg "Step 3/10 — Installing system packages"

PKGS="
  git
  python3
  py3-pip
  py3-virtualenv
  py3-pygame
  py3-psutil
  py3-aiohttp
  hyprland
  seatd
  wlroots
  mesa-dri-gallium
  libseat
  xdg-user-dirs
  openrc
  busybox
"

# shellcheck disable=SC2086
apk add --no-cache $PKGS
ok "System packages installed"

# Ollama is not in Alpine repos — check if already present
if command -v ollama >/dev/null 2>&1; then
  ok "Ollama already installed: $(ollama --version 2>/dev/null || echo 'version unknown')"
else
  warn "Ollama not found. Install it manually after this script completes:"
  warn "  https://ollama.com/download/linux"
  warn "  (ARM/aarch64 builds are available)"
fi

# ─────────────────────────────────────────────────────────────────────────────
# Step 4 — GitHub personal access token
# ─────────────────────────────────────────────────────────────────────────────
msg "Step 4/10 — GitHub authentication"

cat <<'EOF'

  The installer needs to clone:
    https://github.com/BaltekLabs/linux
  (only the ui/vos/ and packaging/ directories — not the kernel source)

  You need a GitHub Personal Access Token with at least:
    • Contents: read

  Create one at: https://github.com/settings/tokens
  Select "Fine-grained token" → Repository: BaltekLabs/linux → Contents: Read-only

EOF

printf '  Enter your GitHub token (input hidden): '
# Turn off echo so the token doesn't appear on screen
stty -echo 2>/dev/null || true
read -r GH_TOKEN
stty echo 2>/dev/null || true
printf '\n'

[ -n "$GH_TOKEN" ] || die "No token entered. Aborting."
ok "Token received"

# ─────────────────────────────────────────────────────────────────────────────
# Step 5 — Sparse clone (ui/vos + packaging only)
# ─────────────────────────────────────────────────────────────────────────────
msg "Step 5/10 — Cloning source (sparse — ui/vos + packaging only)"

GH_URL="https://${GH_TOKEN}@github.com/${REPO_OWNER}/${REPO_NAME}.git"

rm -rf "$CLONE_DIR"

# --filter=blob:none requires git ≥ 2.27 with partial clone support.
# Detect support and fall back to a plain shallow clone on older git.
GIT_VER="$(git --version | awk '{print $3}')"
GIT_MAJOR="$(echo "$GIT_VER" | cut -d. -f1)"
GIT_MINOR="$(echo "$GIT_VER" | cut -d. -f2)"

if [ "$GIT_MAJOR" -gt 2 ] || { [ "$GIT_MAJOR" -eq 2 ] && [ "$GIT_MINOR" -ge 27 ]; }; then
  # Modern git: partial clone + sparse checkout (downloads far less data)
  git clone --filter=blob:none --no-checkout --depth=1 --branch "$REPO_BRANCH" "$GH_URL" "$CLONE_DIR"
  cd "$CLONE_DIR"
  git sparse-checkout init --cone
  git sparse-checkout set ui/vos packaging
  git checkout "$REPO_BRANCH"
else
  # Older git: plain shallow clone, then delete unneeded dirs to save space
  warn "git $GIT_VER detected — sparse clone not supported, using shallow clone"
  git clone --depth=1 --branch "$REPO_BRANCH" "$GH_URL" "$CLONE_DIR"
  cd "$CLONE_DIR"
  # Remove large kernel dirs we don't need
  for d in arch block crypto drivers fs init ipc kernel lib mm net security sound; do
    rm -rf "${CLONE_DIR:?}/$d"
  done
fi

ok "Cloned: ui/vos/ and packaging/"

# Clear the token from environment and git credential cache
unset GH_TOKEN
git -C "$CLONE_DIR" remote set-url origin "https://github.com/${REPO_OWNER}/${REPO_NAME}.git"

# ─────────────────────────────────────────────────────────────────────────────
# Step 6 — Create 'baltek' system user
# ─────────────────────────────────────────────────────────────────────────────
msg "Step 6/10 — Creating baltek system user"

if ! id -u baltek >/dev/null 2>&1; then
  adduser -S -D -H -s /sbin/nologin -h /var/lib/baltek baltek
  ok "User 'baltek' created"
else
  ok "User 'baltek' already exists"
fi

# seat group grants hardware access via seatd (no root needed for Hyprland)
# video group needed for direct DRM framebuffer access
for grp in seat video; do
  addgroup "$grp" 2>/dev/null || true
  addgroup baltek "$grp" 2>/dev/null || true
  ok "baltek → $grp group"
done

# ─────────────────────────────────────────────────────────────────────────────
# Step 7 — Install VoiceOS source
# ─────────────────────────────────────────────────────────────────────────────
msg "Step 7/10 — Installing VoiceOS source to $INSTALL_ROOT"

VOS_SRC="$CLONE_DIR/ui/vos"
[ -d "$VOS_SRC/src" ] || die "ui/vos/src not found in clone — check branch name."

install -d "$INSTALL_ROOT"
cp -r "$VOS_SRC/src/." "$INSTALL_ROOT/"
install -m 0644 "$VOS_SRC/requirements.txt" "$INSTALL_ROOT/requirements.txt"
chown -R baltek:baltek "$INSTALL_ROOT"
ok "VoiceOS source installed"

# ─────────────────────────────────────────────────────────────────────────────
# Step 8 — Python venv + dependencies
# ─────────────────────────────────────────────────────────────────────────────
msg "Step 8/10 — Setting up Python virtualenv"

if [ ! -d "$INSTALL_ROOT/venv" ]; then
  python3 -m venv "$INSTALL_ROOT/venv"
fi

"$INSTALL_ROOT/venv/bin/pip" install --quiet \
  pygame psutil aiohttp

chown -R baltek:baltek "$INSTALL_ROOT/venv"
ok "Python deps installed"

# ─────────────────────────────────────────────────────────────────────────────
# Step 9 — Install config, scripts, and OpenRC services
# ─────────────────────────────────────────────────────────────────────────────
msg "Step 9/10 — Installing config files and services"

PKG_FILES="$CLONE_DIR/packaging/aports"
mkdir -p "$CONF_DIR" "$SBIN_DIR" "$INITD_DIR" "$LOG_DIR"

# ── Config files (baltek-dte-config) ────────────────────────────────────────
CONFIG_FILES="$PKG_FILES/baltek-dte-config/files"

install -m 0644 "$CONFIG_FILES/dte.conf"       "$CONF_DIR/dte.conf"
install -m 0644 "$CONFIG_FILES/vm.conf"        "$CONF_DIR/vm.conf"
ok "Installed dte.conf and vm.conf"

install -m 0755 "$CONFIG_FILES/baltek-mode-run"         "$SBIN_DIR/baltek-mode-run"
install -m 0755 "$CONFIG_FILES/baltek-vm-run"           "$SBIN_DIR/baltek-vm-run"
install -m 0755 "$CONFIG_FILES/baltek-bootmenu-install" "$SBIN_DIR/baltek-bootmenu-install"
ok "Installed sbin scripts (baltek-dte-config)"

install -m 0755 "$CONFIG_FILES/baltek-mode.initd" "$INITD_DIR/baltek-mode"
install -m 0755 "$CONFIG_FILES/baltek-vm.initd"   "$INITD_DIR/baltek-vm"
ok "Installed OpenRC services (baltek-dte-config)"

if [ -f "$CONFIG_FILES/99-baltek-dte.conf" ]; then
  install -d /etc/sysctl.d
  install -m 0644 "$CONFIG_FILES/99-baltek-dte.conf" /etc/sysctl.d/99-baltek-dte.conf
  ok "Installed sysctl tuning"
fi

# ── VoiceOS scripts + services (baltek-vos-ui) ──────────────────────────────
VOS_FILES="$PKG_FILES/baltek-vos-ui/files"

install -m 0755 "$VOS_FILES/baltek-vos-launch"   "$SBIN_DIR/baltek-vos-launch"
install -m 0755 "$VOS_FILES/baltek-vos-app"      "$SBIN_DIR/baltek-vos-app"
ok "Installed VoiceOS launcher scripts"

install -m 0755 "$VOS_FILES/baltek-vos-ui.initd" "$INITD_DIR/baltek-vos-ui"
ok "Installed baltek-vos-ui OpenRC service"

install -m 0644 "$VOS_FILES/hyprland-vos.conf"   "$CONF_DIR/hyprland-vos.conf"
ok "Installed Hyprland kiosk config"

# ─────────────────────────────────────────────────────────────────────────────
# Step 10 — Enable services
# ─────────────────────────────────────────────────────────────────────────────
msg "Step 10/10 — Enabling OpenRC services"

rc-update add seatd         default 2>/dev/null && ok "seatd → default runlevel" || warn "seatd already added"
rc-update add baltek-mode   default 2>/dev/null && ok "baltek-mode → default runlevel" || warn "baltek-mode already added"
rc-update add baltek-vos-ui default 2>/dev/null && ok "baltek-vos-ui → default runlevel" || warn "baltek-vos-ui already added"

# Clean up clone
rm -rf "$CLONE_DIR"

# ─────────────────────────────────────────────────────────────────────────────
# Done
# ─────────────────────────────────────────────────────────────────────────────
cat <<EOF


  ╔══════════════════════════════════════════════════════╗
  ║           Baltek DTE install complete                ║
  ╚══════════════════════════════════════════════════════╝

  Installed:
    /opt/baltek/vos/        — VoiceOS source + Python venv
    /etc/baltek/            — Runtime config (dte.conf, hyprland-vos.conf)
    /usr/local/sbin/        — Launcher scripts
    /etc/init.d/            — OpenRC services

EOF

if ! command -v ollama >/dev/null 2>&1; then
  cat <<'EOF'
  ┌─ ACTION REQUIRED ──────────────────────────────────────────────┐
  │ Ollama was not found. Install it before booting into DTE mode: │
  │   curl -fsSL https://ollama.com/install.sh | sh               │
  │   ollama pull mistral:latest                                   │
  └────────────────────────────────────────────────────────────────┘

EOF
fi

cat <<'EOF'
  Next steps:
    1. Install Ollama if not already done (see above)
    2. Add  baltek_mode=dte  to your kernel cmdline to activate DTE mode
    3. Reboot

  To activate immediately without rebooting (for testing):
    rc-service seatd start
    rc-service baltek-vos-ui start

EOF
