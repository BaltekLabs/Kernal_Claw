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
  mkdir -p "$HOME/.abuild"
  cp -a "$HOME/.abuild/"*.rsa "$KEY_DIR/" || true
  cp -a "$HOME/.abuild/"*.pub "$KEY_DIR/" || true
fi

echo "[3/6] Configure abuild to use local aports tree"
export APORTSDIR="$APORTS_DIR"
export REPODEST="$OUT_DIR"
export PACKAGER_PRIVKEY="$(ls "$KEY_DIR"/*.rsa | head -n1)"

echo "[4/6] Build packages"
for pkg in baltek-dte-config baltek-vos-ui baltek-dte; do
  echo "Building $pkg ..."
  ( cd "$APORTS_DIR/$pkg" && abuild -r )
done

echo "[5/6] Index repo"
apk index -o "$OUT_DIR/$REPO_NAME".INDEX.tar.gz "$OUT_DIR"/*.apk
abuild-sign "$OUT_DIR/$REPO_NAME".INDEX.tar.gz

echo "[6/6] Done"
echo "Repo output: $OUT_DIR"
echo "Host it over HTTP (nginx, github pages, s3) and add to /etc/apk/repositories."
