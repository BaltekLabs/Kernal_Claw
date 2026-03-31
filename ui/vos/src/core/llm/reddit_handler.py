import aiohttp
import asyncio
from typing import List, Dict, Any
import json
import datetime

class RedditContentHandler:
    def __init__(self):
        self.session = None
        self.headers = {
            'User-Agent': 'DotOS/0.1 (by /u/YourUsername)'  # Replace with your Reddit username
        }
        
    async def initialize(self):
        if not self.session:
            self.session = aiohttp.ClientSession()
    
    async def cleanup(self):
        if self.session:
            await self.session.close()
            
    async def fetch_subreddit(self, subreddit: str, limit: int = 10) -> List[Dict[str, Any]]:
        """Fetch posts from a subreddit"""
        await self.initialize()
        
        url = f'https://www.reddit.com/r/{subreddit}/hot.json?limit={limit}'
        try:
            async with self.session.get(url, headers=self.headers) as response:
                if response.status == 200:
                    data = await response.json()
                    return self._process_reddit_response(data)
                else:
                    print(f"Error fetching subreddit: {response.status}")
                    return []
        except Exception as e:
            print(f"Error fetching subreddit data: {e}")
            return []
            
    def _process_reddit_response(self, response_data: Dict) -> List[Dict[str, Any]]:
        """Process Reddit API response into our format"""
        posts = []
        
        try:
            for post in response_data['data']['children']:
                post_data = post['data']
                
                # Convert timestamp to readable format
                created_time = datetime.datetime.fromtimestamp(post_data['created_utc'])
                time_str = created_time.strftime("%Y-%m-%d %H:%M:%S")
                
                processed_post = {
                    'title': post_data['title'],
                    'score': post_data['score'],
                    'author': post_data['author'],
                    'num_comments': post_data['num_comments'],
                    'url': post_data['url'],
                    'created': time_str,
                    'upvote_ratio': post_data['upvote_ratio'],
                    'is_video': post_data['is_video'],
                    'permalink': f"https://reddit.com{post_data['permalink']}",
                    'flair': post_data.get('link_flair_text', None)
                }
                
                # Add text content if available
                if 'selftext' in post_data and post_data['selftext']:
                    processed_post['content'] = post_data['selftext']
                
                posts.append(processed_post)
                
        except Exception as e:
            print(f"Error processing Reddit data: {e}")
            
        return posts

# Usage example
async def test_reddit_handler():
    handler = RedditContentHandler()
    posts = await handler.fetch_subreddit('wallstreetbets')
    print(json.dumps(posts[0], indent=2))  # Print first post
    await handler.cleanup()

if __name__ == "__main__":
    asyncio.run(test_reddit_handler())