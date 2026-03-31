#!/usr/bin/env bash
set -euo pipefail

# Installs Baltek DTE (with VoiceOS UI) onto an already-installed Alpine system.

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

echo "[3/5] Install boot menu entries (extlinux)"
sudo /usr/local/sbin/baltek-bootmenu-install || true

echo "[4/5] Ensure mode selector enabled"
sudo rc-update add baltek-mode default || true

echo "[5/5] Done"
echo "Two boot entries are now available:"
echo "  'Baltek DTE - VoiceOS UI'    → Animated circle + Ollama LLM (baltek_ui=vos)"
echo "  'Baltek DTE - Chat/Shell'    → Plain shell fallback (baltek_ui=chat)"
echo "Reboot and select your preferred mode at the boot menu."
