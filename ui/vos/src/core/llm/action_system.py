import asyncio
import subprocess
from typing import Dict, Any
from core.event.event_bus import Event, Priority
from .reddit_handler import RedditContentHandler

class ActionExecutor:
    def __init__(self, event_bus):
        self.event_bus = event_bus
        self.reddit_handler = RedditContentHandler()
        
    async def initialize(self):
        await self.reddit_handler.initialize()
        
    async def cleanup(self):
        await self.reddit_handler.cleanup()
    
    async def execute_action(self, action: Dict[str, Any]):
        """Execute actions based on LLM/Mock decision"""
        try:
            print(f"Executing action: {action}")
            action_type = action.get('action_type')
            
            # Set circle to processing state
            await self.event_bus.emit(Event(
                type="state_change",
                data="PROCESSING",
                priority=Priority.HIGH
            ))
            
            if action_type == 'fetch_content':
                await self._handle_content_fetch(action)
            elif action_type == 'launch_app':
                await self._handle_app_launch(action)
            elif action_type == 'respond':
                await self._handle_response(action)
            
            # Return to idle state
            await self.event_bus.emit(Event(
                type="state_change",
                data="IDLE",
                priority=Priority.HIGH
            ))
            
        except Exception as e:
            print(f"Error executing action: {e}")
            await self.event_bus.emit(Event(
                type="state_change",
                data="ERROR",
                priority=Priority.HIGH
            ))
    
    async def _handle_content_fetch(self, action: Dict[str, Any]):
        """Handle content fetching actions"""
        source = action.get('source')
        if source == 'web' and 'reddit' in action['parameters'].get('url', ''):
            # Extract subreddit name from url
            url = action['parameters']['url']
            subreddit = url.split('/')[-1] if url.endswith('/') else url.split('/')[-1]
            
            # Fetch posts
            posts = await self.reddit_handler.fetch_subreddit(subreddit)
            
            if posts:
                await self.event_bus.emit(Event(
                    type="fetch_content",
                    data={'content_type': 'reddit', 'posts': posts},
                    priority=Priority.NORMAL
                ))
            else:
                await self.event_bus.emit(Event(
                    type="display_message",
                    data={'message': "Failed to fetch Reddit content"},
                    priority=Priority.HIGH
                ))
    
    async def _handle_app_launch(self, action: Dict[str, Any]):
        """Handle application launch actions"""
        app_name = action.get('app_name')
        try:
            if app_name == 'calculator':
                subprocess.Popen('calc.exe')
            elif app_name == 'notepad':
                subprocess.Popen('notepad.exe')
        except Exception as e:
            print(f"Error launching application {app_name}: {e}")
    
    async def _handle_response(self, action: Dict[str, Any]):
        """Handle text responses"""
        message = action.get('message', '')
        await self.event_bus.emit(Event(
            type="display_message",
            data={'message': message},
            priority=Priority.NORMAL
        ))