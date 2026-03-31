"""
android/main.py — VoiceOS Android launcher entry point
=======================================================
This is the Buildozer/python-for-android entry point.
It:
  1. Starts the FastAPI server (server.py) on localhost:8741 in a background thread
  2. Opens a full-screen WebView pointing to http://localhost:8741
  3. Registers the activity as an Android HOME launcher

The Python AI backend (agent, LLM providers, tools, skills) runs in the same
process as a background asyncio thread. The WebView is the entire UI layer.
"""

import os
import sys
import threading
import asyncio
import logging
import time

# Add src/ to path so agent/server imports work
_base = os.path.dirname(os.path.abspath(__file__))
_src  = os.path.join(_base, '..', 'src')
if _src not in sys.path:
    sys.path.insert(0, os.path.normpath(_src))

logging.basicConfig(level=logging.INFO,
                    format='%(asctime)s %(name)s %(levelname)s %(message)s')
logger = logging.getLogger('voiceos.android')

SERVER_PORT = 8741
SERVER_URL  = f'http://127.0.0.1:{SERVER_PORT}'


# ── Kivy / Android WebView ───────────────────────────────────────
try:
    from kivy.app import App
    from kivy.uix.widget import Widget
    from kivy.clock import Clock
    from jnius import autoclass

    WebView      = autoclass('android.webkit.WebView')
    WebSettings  = autoclass('android.webkit.WebSettings')
    WebViewClient = autoclass('android.webkit.WebViewClient')
    LinearLayout  = autoclass('android.widget.LinearLayout')
    LayoutParams  = autoclass('android.view.ViewGroup$LayoutParams')
    PythonActivity = autoclass('org.kivy.android.PythonActivity')

    KIVY_AVAILABLE = True
except ImportError:
    KIVY_AVAILABLE = False
    logger.warning('Kivy/jnius not available — running in server-only mode')


# ── Server thread ────────────────────────────────────────────────
def _run_server():
    """Start the FastAPI/uvicorn server in a dedicated thread with its own event loop."""
    import uvicorn
    # Import here so sys.path is already set up
    config = uvicorn.Config(
        'server:app',
        host='127.0.0.1',
        port=SERVER_PORT,
        log_level='info',
        loop='asyncio',
    )
    server = uvicorn.Server(config)
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    loop.run_until_complete(server.serve())


def start_server():
    t = threading.Thread(target=_run_server, daemon=True, name='voiceos-server')
    t.start()
    # Wait until server is accepting connections
    import socket
    for _ in range(30):
        try:
            s = socket.create_connection(('127.0.0.1', SERVER_PORT), timeout=1)
            s.close()
            logger.info('Server is up on :%d', SERVER_PORT)
            return
        except (ConnectionRefusedError, OSError):
            time.sleep(0.3)
    logger.warning('Server did not come up in time — WebView may load slowly')


# ── Android WebView launcher ─────────────────────────────────────
if KIVY_AVAILABLE:
    class VoiceOSApp(App):
        def build(self):
            return Widget()  # placeholder; real UI is the WebView below

        def on_start(self):
            Clock.schedule_once(self._inject_webview, 0)

        def _inject_webview(self, dt):
            activity = PythonActivity.mActivity

            # Create WebView
            webview = WebView(activity)
            settings = webview.getSettings()
            settings.setJavaScriptEnabled(True)
            settings.setDomStorageEnabled(True)
            settings.setLoadWithOverviewMode(True)
            settings.setUseWideViewPort(True)
            settings.setBuiltInZoomControls(False)
            settings.setDisplayZoomControls(False)
            settings.setMediaPlaybackRequiresUserGesture(False)

            # Prevent external navigation
            webview.setWebViewClient(WebViewClient())

            # Full screen layout
            layout = activity.getWindow().getDecorView()
            params = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT,
            )
            activity.addContentView(webview, params)

            webview.loadUrl(SERVER_URL)
            logger.info('WebView loaded: %s', SERVER_URL)

    def main():
        start_server()
        VoiceOSApp().run()

else:
    # Non-Android / desktop fallback: just run the server and open browser
    def main():
        import webbrowser
        start_server()
        logger.info('Opening %s in browser', SERVER_URL)
        time.sleep(1)
        webbrowser.open(SERVER_URL)
        # Keep process alive
        try:
            while True:
                time.sleep(60)
        except KeyboardInterrupt:
            pass


if __name__ == '__main__':
    main()
