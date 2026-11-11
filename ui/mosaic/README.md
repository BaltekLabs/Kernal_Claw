# Mosaic

A personal-phone launcher in the spirit of Windows Phone: a Start screen of
flexible **live tiles**, running on the **VoiceOS agent** underneath.

Mosaic is the personal-use take on VoiceOS. Same agent, tools, tasks, CRM,
comms and work-profile support, but no kernel or industrial pieces, and an
interface built around tiles instead of the nodal-net canvas.

## Using it

| Gesture | What happens |
|---|---|
| Tap a tile | Opens it: an app, a panel (tasks, messaging, people…), or the agent |
| Press and hold a tile | Edit mode. Drag to move; the tile's buttons **unpin**, **resize** (small → medium → wide → large) and **recolor** |
| `add tile` (in edit mode) | Pin a live tile or create an **agent shortcut** (a tile that runs a saved request and shows its latest answer) |
| Swipe left / → button | App list (personal and work apps) with a letter jump list. Press and hold an app to pin it |
| Type in app search | Filters apps. If nothing matches, the agent gets the request |
| Back | Closes a panel, leaves edit mode, or returns to Start |
| Home (while home) | Back to the top of Start |

### Tiles

| Tile | Live content | Opens |
|---|---|---|
| ask mosaic | Agent state ring, latest heartbeat finding, last answer | Agent (voice or text) |
| clock | Time and date | Agent: upcoming alarms |
| calendar | Today's date, next events (flips) | Agenda |
| messaging | Unread count, latest messages | Conversations from texts and chat apps (Signal, WhatsApp, RCS, DMs…). Tabs: all / unread / texts / one per chat app. Tap a conversation to see its latest messages |
| phone | Missed calls | Notifications → calls |
| people | Initials of people to follow up with | Everyone you keep up with. Search names, tags and numbers; tabs for all / follow up / out of touch / each relationship type. Tap a tag to filter by it. Each person has a contact card: call, text, follow-up bell, agent actions (draft a message, catch me up, suggest a plan, remind me), relationship, keep-in-touch frequency, how they're doing, tags, phone, birthday, notes, and recent messages and calls |
| tasks | Pending count, what's next | To do / done, add task |
| notifications | Count, latest | Tabs by type: all / messages / calls / email / other. Tap to open the app; dismiss, clear a tab, or have the agent summarize it |
| approvals | Actions the agent queued for your OK | Approve / dismiss |
| work | Work-profile app icons | Work apps panel (resume if paused) |
| battery, settings | — | Settings: accent color, tile density, agent mode, AI provider |
| shortcut | Last answer | Runs its saved prompt |
| any app | App icon | Launches the app |

### Looks and wallpapers

Settings → start → **look**:

- **classic**: solid accent tiles on black (Windows Phone 8).
- **glass**: frosted, raised tiles floating over a wallpaper (Windows 10 Mobile). Panels, app bars and the app list frost over the wallpaper too.
- **see-through**: Start stays black and the wallpaper shows only through the tiles, staying put as they scroll over it (Windows Phone 8.1).

Pick a built-in wallpaper or your own photo; photos are cropped to the screen and kept on the phone. There's also a slider to dim the wallpaper. Tile colors still work: they frost in glass and tint in see-through.

Text uses Selawik, Microsoft's open-source stand-in for Segoe UI (SIL Open Font License, `assets/web/fonts/Selawik-OFL.txt`).

### Choosing the model

Mosaic starts on **Claude Opus 5.5**, with **Haiku 5.5** for quick questions
(VoiceOS starts on Ollama). Tap the model name at the top of Ask Mosaic, or go to
Settings → ai, to switch. There's a tab for each provider (claude, openai, grok,
ollama) where you can:

- pick a model, or type in any other model ID
- choose the cheaper model that handles quick questions
- paste and test your API key
- for Ollama, set the server address and choose from the models installed on it

If a reply looks like a key or model error, Ask Mosaic points you back to the picker.

### Messages vs. notifications

Chat-app messages are captured from their notifications, so an app only shows up
under Messaging once notification access is on and its notifications include the
message text. For Signal, that's Settings → Notifications → Show → "Name and
message". Notification types come from Android's own category, with the app's
package name as a fallback. The notification list lives in memory, so it starts
empty after the app restarts.

Turning on **agent mode** in settings starts the VoiceOS heartbeat. The agent
then checks in on its own and posts what it notices to the agent tile.

## Architecture

```
MosaicActivity (WebView, port 8742)
   └─ MosaicServer  extends VoiceOSServer       ← shared from ui/vos (not forked)
        ├─ /api/glance   (Mosaic-only: battery, calendar, recent comms)
        └─ everything else: /api/agent, tasks, queue, crm, notifications, apps, …
assets/web/
   app.js      shell: pager, app list, back/home
   grid.js     tile packing, live-face rotation, edit mode (drag / resize / unpin / color)
   catalog.js  tile types: sizes, faces, counts, tap actions; default layout
   panels.js   pivot panels (tasks, messaging, people, calendar, …, settings)
   agent.js    agent stream (XHR), voice in/out, agent-mode heartbeat
   store.js    API helpers + polled data store tiles subscribe to
```

The Gradle build compiles the VoiceOS Kotlin sources straight from
`ui/vos/android-gradle/app/src/main/java`, so agent and tool improvements made
there reach Mosaic automatically. The app id is `com.balteklabs.mosaic`, so it
installs alongside VoiceOS. Settings and API keys are stored per app, so Mosaic
needs its own AI provider setup (Settings → ai).

Tile layout, accent color and preferences are saved in the WebView's
localStorage.

## Build

```bash
cd ui/mosaic/android-gradle
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

Java 17 and the Android SDK are required, the same as VoiceOS. `local.properties`
must point at the SDK.
