import asyncio
import json
import logging
import re
import urllib.request
import urllib.error

from core.event.event_bus import EventBus, Event, Priority

logger = logging.getLogger(__name__)


def _strip_markdown(text: str) -> str:
    """Convert markdown to plain readable text."""
    lines = []
    for line in text.split('\n'):
        # Headers: ## Heading → Heading
        line = re.sub(r'^#{1,6}\s*', '', line)
        # Horizontal rules
        if re.match(r'^\s*[-*_]{3,}\s*$', line):
            continue
        # Bold/italic: **text** or *text* → text
        line = re.sub(r'\*{1,3}([^*\n]+)\*{1,3}', r'\1', line)
        # Inline code: `text` → text
        line = re.sub(r'`([^`]*)`', r'\1', line)
        # Bullet points: - item or * item → item
        line = re.sub(r'^\s*[-*+]\s+', '', line)
        # Numbered lists: 1. item → item
        line = re.sub(r'^\s*\d+\.\s+', '', line)
        lines.append(line)
    return '\n'.join(lines)


class AicoreAdapter:
    AICORE_URL = "http://localhost:8080/api/chat"

    def __init__(self, event_bus: EventBus):
        self.event_bus = event_bus

    async def initialize(self):
        self.event_bus.subscribe("generate_request", self._handle_generate_request)
        logger.info("AicoreAdapter initialized")

    async def cleanup(self):
        pass

    def _call_aicore(self, message: str) -> str:
        body = json.dumps({"message": message}).encode()
        req = urllib.request.Request(
            self.AICORE_URL,
            data=body,
            headers={"Content-Type": "application/json"},
            method="POST"
        )
        try:
            with urllib.request.urlopen(req, timeout=120) as resp:
                result = json.loads(resp.read())
                if result.get("ok"):
                    return _strip_markdown(result.get("reply", ""))
                return "Error: " + result.get("error", "unknown error")
        except urllib.error.URLError as e:
            return f"Connection error: {e.reason}"
        except Exception as e:
            return f"Error: {str(e)}"

    async def _handle_generate_request(self, event: Event):
        data = event.data
        prompt = data.get("prompt", "") if isinstance(data, dict) else str(data)
        logger.info(f"Sending to aicore: {prompt[:80]}")
        try:
            loop = asyncio.get_event_loop()
            reply = await loop.run_in_executor(None, self._call_aicore, prompt)
            logger.info(f"Aicore reply ({len(reply)} chars): {reply[:120]}")
            await self.event_bus.emit(Event(
                type="generation_chunk",
                data={"response": reply, "done": False},
                priority=Priority.MEDIUM
            ))
            await self.event_bus.emit(Event(
                type="generation_chunk",
                data={"response": "", "done": True},
                priority=Priority.MEDIUM
            ))
        except Exception as e:
            logger.error(f"AicoreAdapter error: {e}")
            await self.event_bus.emit(Event(
                type="generation_chunk",
                data={"response": f"Error: {str(e)}", "done": True},
                priority=Priority.MEDIUM
            ))
