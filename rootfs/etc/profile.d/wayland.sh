#!/bin/sh
# /etc/profile.d/wayland.sh
# Sourced by every login shell — sets up Wayland environment so
# GUI apps (Firefox, etc.) can connect to the running sway compositor.

export XDG_RUNTIME_DIR=/run/user/0

# Auto-detect the active Wayland socket sway created
for _w in /run/user/0/wayland-*; do
    [ -S "$_w" ] && export WAYLAND_DISPLAY="${_w##*/}" && break
done
unset _w

# Tell Firefox (and other GTK/Qt apps) to use native Wayland, not X11
export MOZ_ENABLE_WAYLAND=1
export GDK_BACKEND=wayland
export QT_QPA_PLATFORM=wayland
