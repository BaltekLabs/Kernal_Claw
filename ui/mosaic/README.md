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
| messaging | Unread count, latest messages from SMS and chat apps | Messaging panel (all / messages / calls) |
| phone | Missed calls | Calls |
| people | Initials of people to follow up with | Follow-ups, and agent outreach suggestions |
| tasks | Pending count, what's next | To do / done, add task |
| notifications | Count, latest | List, dismiss, agent summary |
| approvals | Actions the agent queued for your OK | Approve / dismiss |
| work | Work-profile app icons | Work apps panel (resume if paused) |
| battery, settings | — | Settings: accent color, tile density, agent mode, AI provider |
| shortcut | Last answer | Runs its saved prompt |
| any app | App icon | Launches the app |

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
