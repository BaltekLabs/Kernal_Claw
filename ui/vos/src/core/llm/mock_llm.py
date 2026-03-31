from typing import Dict, Any
import asyncio

class MockLLM:
    """Simulates LLM responses for testing"""
    
    def __init__(self):
        self.commands = {
            "wsb": {
                'action_type': 'fetch_content',
                'content_type': 'SOCIAL',
                'source': 'web',
                'parameters': {
                    'url': 'https://reddit.com/r/wallstreetbets',
                    'extraction_type': 'feed'
                }
            },
            "wallstreetbets": {
                'action_type': 'fetch_content',
                'content_type': 'SOCIAL',
                'source': 'web',
                'parameters': {
                    'url': 'https://reddit.com/r/wallstreetbets',
                    'extraction_type': 'feed'
                }
            },
            "calculator": {
                'action_type': 'launch_app',
                'app_name': 'calculator',
                'parameters': {}
            },
            "notepad": {
                'action_type': 'launch_app',
                'app_name': 'notepad',
                'parameters': {}
            }
        }
    
    async def process_input(self, text: str) -> Dict[str, Any]:
        """Process input text and return simulated LLM response"""
        text = text.lower().strip()
        print(f"MockLLM processing: {text}")  # Debug print
        
        # Check for exact matches
        if text in self.commands:
            print(f"Found exact match for: {text}")  # Debug print
            return self.commands[text]
        
        # Check for partial matches
        for key, response in self.commands.items():
            if key in text:
                print(f"Found partial match: {key} in {text}")  # Debug print
                return response
        
        # Default response if no match found
        print("No match found, returning default response")  # Debug print
        return {
            'action_type': 'respond',
            'content_type': 'TEXT',
            'message': "I don't understand that command yet."
        }