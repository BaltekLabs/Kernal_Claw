# Kernal Claw 🦀

![Colonel Claw mascot](/scripts/colonel_claw.png)

> **Note:** The repository name currently says `Kernal_Claw`; this will be corrected to `Kernel_Claw`.

**Kernal Claw** is a custom Linux kernel distribution and multi-platform AI interface system. We are exploring what becomes possible when AI agent behavior is integrated *close to the operating system core* instead of living only in user-space apps.

By putting intelligence where system truth already exists (scheduler, memory, I/O, process state, security context), Kernal Claw reduces the lag between detection and response. This enables powerful, context-aware policy enforcement and deterministic automation.

## 🚀 The Vision: Kernel-Aware Agency

Traditional user-space agents have blind spots and delayed visibility. Kernal Claw provides:
- **Lower-latency decisions** from direct system telemetry.
- **Richer context** across process, memory, and device behavior.
- **Stronger policy enforcement paths** anchored in core system mechanisms.
- **Autonomous remediation loops** (Detect → Reason → Act) for common failure classes.

## 🏗️ Architecture

Kernal Claw is designed around a robust three-tier architecture:

### Tier 1: Kernel AI Subsystem (`kernel/ai/`)
Custom loadable kernel modules written in C that expose the OS directly to the AI agent.
- **`ai_core.c`**: Spinlock-protected client registry, exposes `/proc/ai/status`.
- **`ai_claws.c`**: Per-CPU load tracking hooks.
- **`ai_proc.c`**: `/proc/ai/metrics` interface.

### Tier 2: Userspace Daemon (`tools/aicore/`, `tools/dte/`)
- **aicore**: A C-based daemon (with `libcurl` and `pthreads`) that registers with the kernel module, exposes an HTTP API, and hosts an embedded HTML UI.
- **dte**: A text-mode AI chat terminal (`libncursesw`) for TTY environments.

### Tier 3: AI Interface Layer ("VoiceOS" - `ui/vos/`)
A multi-platform UI with a shared event-bus architecture, supporting hot-swappable LLMs (Ollama, OpenAI, Anthropic, Groq).
- **Desktop**: Python/Pygame-based animated nodal-net canvas.
- **Android Launcher**: Kotlin WebView application interacting with an embedded NanoHTTPD web server.

## 🛠️ Quick Start & Build Instructions

Kernal Claw can be deployed in multiple ways: as a fully bootable standalone ISO, an Alpine Linux APK, or an Android launcher.

### 1. Bootable ISO & Kernel
Configure and build the AI-enabled kernel:
```bash
make ai_defconfig          # Load AI-enabled config
make -j$(nproc)            # Build kernel + modules
```
Build the userspace tools (inside an Alpine/musl environment) and generate the ISO:
```bash
make -C tools/aicore install-rootfs CC=musl-gcc
make -C tools/dte install-rootfs CC=musl-gcc
make INITRAMFS_SOURCE=rootfs isoimage -j$(nproc)
# Output: arch/x86/boot/image.iso
```

### 2. Alpine APK Packages
We provide a Dual-Target Environment (DTE) that works natively on Alpine:
```bash
cd packaging/scripts
./build_apk_repo.sh        # Builds and signs APKs
./build_iso.sh             # Builds bootable Alpine ISO
```

### 3. Android App (Active Development Target)
Build the Android VoiceOS launcher using Gradle (requires Java 17):
```bash
cd ui/vos/android-gradle
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

## 💬 Operator-in-the-Loop

Safety and transparency are critical. Kernal Claw features human override and approval patterns for high-impact changes, providing explainable action trails for trust and postmortem analysis. Tools execute using provider-specific structured calling (e.g., Anthropic `tool_use`, OpenAI `function_calling`, Ollama XML prompts).

## 🦀 Meet the Mascot
Colonel Claw is our crab mascot — a playful **Kernel/Colonel** pun, with claws that match the name!

```text
            __     __
      _.-""  "-.-"  ""-._
    .'   _  Colonel Claw  '.
   /   .'o\            /o'. \
  |   /___/  .-====-.  \___\ |
  |   \   \ (  KERN  ) /   / |
   \   '.__\ '-====-' /__.' /
    '._    _\  /  \  /_   _.'
       "-.'  \/ /\ \/  '.-"
          \__/ /  \ \__/
            /_/    \_\
```

## Practical use of this README

Use this file to communicate:

- The **technical goal**: kernel-aware agent capabilities.
- The **operational value**: faster, safer, context-rich automation.
- The **project posture**: serious systems work with light branding.

## Positioning statement

Kernal_Claw is about advancing **trusted, kernel-aware autonomous operations** — where deep system context improves decision quality, response time, and controllability.
