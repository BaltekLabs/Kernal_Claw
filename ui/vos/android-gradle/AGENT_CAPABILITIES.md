# VoiceOS Mobile Agent — Capability Reference

**Platform:** Android launcher (WebView + NanoHTTPD backend)
**Input:** Voice (default, tap mic) or keyboard (tap keyboard icon)
**Gestures:** Tap = chat, Swipe up = apps drawer, Swipe down = settings, Swipe right = history

---

## Operating Modes

### Assistant Mode
Responds only when you speak or type. Uses skill routing to match your request to the right tool set (communication, calendar, device control, web research, memory). Up to 8 reasoning steps per request.

### Agent Mode
Everything in Assistant, plus autonomous background monitoring. A heartbeat system runs probes on independent timers, each with a focused prompt and limited tool access:

| Probe | Interval | What it does |
|-------|----------|-------------|
| **Notification triage** | 2 min | Reads notifications, SMS, and call log. Creates tasks for unanswered messages from real people, missed calls, and actionable alerts. Skips ads and system noise. |
| **Calendar check** | 30 min | Scans today's calendar. Creates preparation tasks for events within 2 hours. Sets alarms for events starting within 30 minutes. |
| **Task review** | 10 min | Reviews the task list. Takes action on high-priority items — web searches for info, sets deadline alarms, updates task notes, marks completed tasks done. |
| **Battery watch** | 15 min | Alerts only when battery drops below 20% and not charging. |

Agent probes surface results as brief notices at the bottom of the screen. Tasks they create appear in the task overlay.

---

## Tool Inventory

### Information (read-only)

| Tool | What it does |
|------|-------------|
| `web_search` | Search the web, returns summarized results |
| `read_calendar` | Read upcoming calendar events (configurable days ahead) |
| `get_battery` | Battery percentage and charging status |
| `get_volume` | Current media volume (0-100) |
| `get_device_info` | Time, date, battery, WiFi network |
| `get_notifications` | Recent notifications from all apps (title, text, timestamp) |
| `read_sms` | SMS inbox, optionally filtered by contact name/number |
| `get_recent_calls` | Call log — incoming, outgoing, missed |
| `recall` | Retrieve previously stored memory notes |
| `get_clipboard` | Read current clipboard text |

### Device Control

| Tool | What it does |
|------|-------------|
| `launch_app` | Open any installed app by name |
| `set_alarm` | Set an alarm (hour, minute, optional label) |
| `set_volume` | Set media volume (0-100) |
| `set_brightness` | Set screen brightness (0-100) |
| `toggle_wifi` | Enable/disable WiFi (Android 10+ opens settings panel) |
| `toggle_bluetooth` | Enable/disable Bluetooth (Android 12+ opens settings) |
| `toggle_dnd` | Enable/disable Do Not Disturb |
| `set_clipboard` | Copy text to clipboard |

### Calendar and Tasks

| Tool | What it does |
|------|-------------|
| `create_event` | Create a calendar event (supports natural language times like "tomorrow at 3pm") |
| `list_tasks` | List all tasks with status and priority |
| `add_task` | Create a new task (title, priority: high/medium/low, optional notes) |
| `update_task` | Update a task's status (pending/in_progress/blocked/done) or notes |
| `complete_task` | Mark a task as done |

### Memory

| Tool | What it does |
|------|-------------|
| `remember` | Store a note for later recall (persists across sessions) |
| `recall` | Retrieve all stored notes |

### Communication (requires user approval)

These tools queue actions for review — nothing is sent until you approve.

| Tool | What it does |
|------|-------------|
| `call_contact` | Opens phone dialer with number pre-filled |
| `send_sms` | Opens SMS app with recipient and message pre-filled |
| `draft_email` | Queues an email draft (to, subject, body) for review |
| `send_whatsapp` | Queues a WhatsApp message for review |
| `navigate` | Opens Maps with destination pre-filled |

---

## Skill Routing

When you send a message, the agent matches trigger words to route your request to a specialized skill. Each skill gets a focused system prompt and a filtered set of tools.

| Skill | Triggers on | Tools available |
|-------|------------|----------------|
| **Communication** | text, sms, message, email, call, whatsapp, notification, reply, send | SMS, calls, notifications, email, WhatsApp, contacts |
| **Calendar & Tasks** | calendar, schedule, event, meeting, reminder, alarm, task, todo, deadline | Calendar CRUD, tasks CRUD, alarms |
| **Device Control** | open, launch, volume, brightness, wifi, bluetooth, battery, clipboard | App launch, hardware toggles, device info |
| **Web Research** | search, look up, what is, weather, news, price, how to | Web search, memory |
| **Memory** | remember, remind me, note, forget, recall | Store and retrieve notes |

If no skill matches, all tools are available.

---

## Interaction Patterns

### Direct Commands
> "Set an alarm for 7:30 AM"
> "Turn the volume to 50"
> "Open Spotify"

Single tool call, immediate execution.

### Multi-Step Agentic Flows
> "Text Mom that I'll be late and add a reminder to call her tonight"

The agent chains multiple tools: `send_sms` (queued for approval) then `add_task` or `set_alarm`. Up to 8 steps per request.

### Proactive Task Creation (Agent Mode)
No user input needed. The agent autonomously:
- Creates "Reply to [name]" tasks when it detects unanswered messages
- Creates "Prepare for [event]" tasks before calendar events
- Sets alarms for imminent meetings
- Advances tasks by searching the web or recalling stored info

### Queued Actions
Communication tools (SMS, email, WhatsApp, calls, navigation) go through an approval queue. The agent drafts the action, you review and approve or dismiss.

---

## Data Sources

| Source | How accessed | Permissions needed |
|--------|-------------|-------------------|
| Notifications | `VoiceOSNotificationService` (NotificationListenerService) | Notification access (granted in Android Settings) |
| SMS | ContentResolver query on SMS inbox | READ_SMS |
| Call log | ContentResolver query on call log | READ_CALL_LOG |
| Calendar | ContentResolver query on CalendarProvider | READ/WRITE_CALENDAR |
| Contacts | ContentResolver query on ContactsContract | READ_CONTACTS |
| Installed apps | PackageManager `queryIntentActivities` | None |
| Battery/WiFi | System services | None |
| Tasks | SharedPreferences (local, on-device) | None |
| Memory notes | SharedPreferences (local, on-device) | None |
| Action queue | SharedPreferences (local, on-device) | None |

---

## LLM Providers

Hot-swappable via settings panel (swipe down):

| Provider | Tool calling method |
|----------|-------------------|
| **Anthropic (Claude)** | Native `tools` API with `tool_use`/`tool_result` content blocks |
| **OpenAI** | `function_calling` / `tool_calls` in response |
| **Groq** | Same as OpenAI (OpenAI-compatible endpoint) |
| **Ollama** (local) | Structured XML prompt, regex-parsed `<tool_call>` blocks |

---

## App Drawer

The installed apps list is cached for 15 minutes. Newly installed apps appear the next time the drawer is opened after the cache expires. Apps are searchable via the search bar at the top of the drawer.
