# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Is

Kernal_Claw is a custom AI-integrated Linux kernel distribution (based on Linux 7.0.0-rc5) plus a multi-platform AI interface system branded as **VoiceOS** by **Baltek Labs**. It ships as a bootable ISO, an Alpine APK, and an Android launcher APK.

## Architecture: Three Tiers

### Tier 1 — Kernel AI Subsystem (`kernel/ai/`)
Custom loadable kernel modules written in C:
- `ai_core.c` — spinlock-protected client registry, exposes `/proc/ai/status`
- `ai_claws.c` — per-CPU load tracking hooks
- `ai_proc.c` — `/proc/ai/metrics` interface
- Public API: `ai_register_client()`, `ai_unregister_client()`, `ai_get_proc_root()`

Configure with `CONFIG_AI_CORE`, `CONFIG_AI_PROC_INTERFACE`, `CONFIG_AI_BUILTIN_CLAWS` in Kconfig.

### Tier 2 — Userspace Daemon (`tools/aicore/`, `tools/dte/`)
- **aicore** (C + libcurl + pthreads): registers with the kernel module, exposes an HTTP API, includes an embedded HTML UI (`ui_html.h` is auto-generated from `ui.html`)
- **dte** (C + libncursesw): text-mode AI chat terminal for TTY environments

### Tier 3 — AI Interface Layer (`ui/vos/`)
Multi-platform UI with a shared architecture:
- **Python desktop** (`src/main.py`): Pygame + asyncio with animated nodal-net canvas
- **Android launcher** (`android-gradle/`): Kotlin WebView that loads a NanoHTTPD web server on port 8741; the web app (`app/src/main/assets/web/`) is vanilla HTML/CSS/JS served over localhost
- **Event bus** (`src/core/event/event_bus.py`): async priority-based pub/sub used throughout
- **Multi-provider LLM** (`src/llm/providers/`): hot-swappable Ollama, OpenAI, Anthropic, Groq

## Build Commands

### Android APK (primary active development target)
```bash
cd ui/vos/android-gradle
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```
- Requires Java 17 and Android SDK at `C:\Users\Admin\AppData\Local\Android\Sdk`
- Do NOT use buildozer — it's Linux-only and the attempts in `ui/vos/.buildozer/` failed
- Gradle wrapper was sourced from the Slate project

### Kernel
```bash
make ai_defconfig          # load AI-enabled config
make -j$(nproc)            # build kernel + modules
```
Custom defconfig lives at `arch/x86/configs/ai_defconfig`.

### Kernel AI modules only
```bash
make -C . M=kernel/ai modules
```

### Userspace tools (inside Alpine/musl environment)
```bash
make -C tools/aicore
make -C tools/aicore install-rootfs   # copies to rootfs/usr/sbin/aicore

make -C tools/dte
make -C tools/dte install-rootfs      # copies to rootfs/usr/bin/dte
```
Both link statically via musl-gcc. Dependencies: libcurl + openssl (aicore), libncursesw (dte).

### Alpine APK packages
```bash
cd packaging/scripts
./build_apk_repo.sh        # builds + signs all APKBUILDs under packaging/aports/
./build_iso.sh             # builds bootable ISO (run inside Alpine)
./install_on_alpine.sh     # installs on an already-running Alpine system
```

## Android App Architecture

The Android launcher is a WebView shell — all UI lives in `app/src/main/assets/web/`:

| File | Role |
|------|------|
| `index.html` | DOM structure; all panels are always in DOM, shown/hidden via CSS classes |
| `app.js` (ES module) | All state, gestures, LLM calls, voice input, task system |
| `circle.js` | Animated nodal-net canvas; `CircleState` enum drives animation intensity |
| `style.css` | Fixed-position layout over canvas; panels slide via `transform` / `clip-path` |

**`VoiceOSServer.kt`** is the NanoHTTPD HTTP server embedded in the app. It handles:
- `/api/agent` — ReAct loop (up to 8 steps) that dispatches tool calls
- `/api/heartbeat` — autonomous agent check (up to 3 steps, runs on 30s timer in Agent mode)
- `/api/tasks` CRUD — SharedPrefs-backed task persistence
- `/api/apps`, `/api/launch`, `/api/contacts`, `/api/call`, `/api/sms`, `/api/maps`
- `/api/provider`, `/api/keys`, `/api/status`, `/api/clear`
- Tool execution for: launch_app, web_search, read_calendar, create_event, set_alarm, get_battery, get/set_volume, remember/recall, call_contact (confirm), send_sms (confirm), navigate (confirm), list/add/update/complete_task

**Tool use is provider-specific:**
- Claude: native `tools` API with `tool_use` content blocks
- OpenAI/Groq: `function_calling` / `tool_calls` in `choices[0].message`
- Ollama: structured XML prompt (`<tool_call>{...}</tool_call>`) parsed with regex

**Streaming:** Uses `XMLHttpRequest` with `onprogress` — NOT `fetch().getReader()`. Android WebView buffers fetch streams; XHR progressive streaming works correctly.

**Input modes:** Voice (default on tap, uses `webkitSpeechRecognition`) → keyboard opt-in via ⌨ button. `WebChromeClient.onPermissionRequest` auto-grants mic to WebView.

**Panel system:** `activePanel` state tracks which overlay is open. Panels are shown/hidden by adding/removing `.visible` CSS class. Gesture engine: tap → input, swipe-up → apps drawer, swipe-down → settings, swipe-right → history.

## Boot & Init (`rootfs/init`)

PID 1 shell script that:
1. Mounts proc/sysfs/devtmpfs, creates device nodes via mdev
2. Loads AI kernel modules (`modprobe ai_core ai_proc ai_claws`)
3. Starts `aicore` daemon in background
4. Sets up networking (udhcpc DHCP on all NICs)
5. Spawns three TTYs: tty1=DTE chat, tty2=shell, tty3=VoiceOS GUI (X + Pygame)

## Deployment Modes

| Mode | Entry point | Boot trigger |
|------|-------------|--------------|
| Android launcher | `MainActivity.kt` → NanoHTTPD → WebView | App launch |
| Alpine APK | `baltek-vos-launch` → Python server | OpenRC `baltek-vos-ui` service |
| Bootable ISO | `rootfs/init` → aicore + dte + VoiceOS | Direct boot |
| DTE mode (Alpine) | `baltek-mode-run` reads `/proc/cmdline` for `baltek_mode=dte` | Boot menu entry |

## Key Paths

- Android assets (web UI): `ui/vos/android-gradle/app/src/main/assets/web/`
- Android Kotlin source: `ui/vos/android-gradle/app/src/main/java/com/balteklabs/voiceos/`
- Python VoiceOS source: `ui/vos/src/`
- Kernel AI subsystem: `kernel/ai/`
- Init script: `rootfs/init`
- Alpine packages: `packaging/aports/`
- Build docs: `build_instruct.md`, `package_man.md`
