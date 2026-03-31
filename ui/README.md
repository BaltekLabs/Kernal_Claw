# Baltek DTE - UI Interfaces

This directory contains the user interface layer for Baltek DTE.

## VoiceOS (`vos/`) — primary interface

**VoiceOS** is a full-screen graphical AI terminal built with Python + Pygame.
It communicates with a local [Ollama](https://ollama.com) server for on-device LLM inference.

### Usage

1. Start Ollama and pull a model:
   ```sh
   ollama serve &
   ollama pull mistral:latest
   ```

2. Run VoiceOS:
   ```sh
   cd vos
   pip install -r requirements.txt
   python src/main.py
   ```

### Controls

| Key | Action |
|-----|--------|
| Any printable key | Activates text input (slides up from bottom) |
| `Enter` | Submits prompt to Ollama |
| `Backspace` | Delete last character |
| `Space` (when not typing) | Trigger demo state cycle |
| `Escape` | Quit |
| `Scroll wheel` | Scroll response panel |

### Circle states

The central animated circle reflects the current AI state:

| State | Circle behaviour |
|-------|-----------------|
| IDLE | Gentle drift |
| LISTENING | Higher amplitude |
| PROCESSING | Complex, energetic |
| RESPONDING | Winding down |
| ERROR | High-intensity shake |

### Architecture

```
src/
├── main.py               # Async main loop (asyncio + pygame)
├── core/
│   ├── event/event_bus.py    # Priority-based async event bus
│   └── llm/
│       ├── action_system.py  # Action dispatch (fetch, respond, launch)
│       ├── mock_llm.py       # Offline testing mock
│       ├── monitor/          # CPU/RAM resource watcher
│       ├── registry/         # Ollama model registry
│       └── router/           # Task → model routing
└── llm/
│   ├── adapters/             # Ollama event adapter
│   └── inference/            # Async streaming Ollama client
└── ui/
    ├── circle.py             # Animated morphing circle
    ├── text_input.py         # Sliding text entry
    └── components/
        └── content_display.py  # Scrollable LLM response panel
```

## DotOS (`../DOT/DotOS/`) — reference visualizer

`DotOS` is a simpler standalone Pygame "Zen Circle" visualization from the DOT
submodule. It has no LLM integration and serves as the original visual prototype
that evolved into VoiceOS. It is not packaged for Baltek DTE but can be run
directly: `python DOT/DotOS/base.py`.

## Switching in Baltek DTE

At boot, the extlinux menu offers:

- **Baltek DTE - VoiceOS UI** (`baltek_ui=vos`) — launches VoiceOS *(default)*
- **Baltek DTE - Chat/Shell** (`baltek_ui=chat`) — drops to plain shell

You can change the default by editing `/etc/baltek/dte.conf`:
```sh
BALTEK_UI_MODE=chat   # or vos
```
