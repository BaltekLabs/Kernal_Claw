#!/bin/sh
# /usr/local/sbin/first-boot-gui.sh
# Runs once on first boot to install packages needed for VoS (tty3 UI).
# Marks completion with /etc/.gui-installed so it never runs again.

FLAG=/etc/.gui-installed

[ -f "$FLAG" ] && exit 0

echo ""
echo "=== First Boot: Installing VoS UI packages ==="
echo ""
echo "Network interfaces: $(ls /sys/class/net/ 2>/dev/null | tr '\n' ' ')"
echo "Waiting for network (up to 90s)..."

for i in $(seq 90); do
    ip route show 2>/dev/null | grep -q "^default" && break
    printf "."
    sleep 1
done
echo ""
echo "Route: $(ip route show 2>/dev/null | head -1)"

if ! ip route show 2>/dev/null | grep -q "^default"; then
    echo ""
    echo "ERROR: No network after 90s. VoS packages not installed."
    echo "From tty2, connect network and run: /usr/local/sbin/first-boot-gui.sh"
    sleep 10
    exit 1
fi

echo "Network up. Updating package index..."
for attempt in 1 2 3; do
    apk update && break
    echo "apk update attempt $attempt failed, retrying..."
    sleep 3
done
apk update || { echo "ERROR: apk update failed after 3 attempts."; sleep 10; exit 1; }

echo "Installing python3, pygame, and Xorg..."
apk add --no-cache \
    python3 \
    py3-pygame \
    py3-psutil \
    xorg-server \
    xf86-input-evdev \
    eudev \
    dropbear || { echo "ERROR: Package install failed."; sleep 5; exit 1; }

# Set root password and start SSH
echo "root:ailinux" | chpasswd 2>/dev/null || true
mkdir -p /etc/dropbear
dropbear -R -p 22 2>/var/log/dropbear.log &
echo "SSH ready — connect with: ssh root@<VM-IP> (password: ailinux)"

touch "$FLAG"
echo ""
echo "=== Packages installed. Starting VoS UI... ==="
echo ""
sleep 1
exit 0
