package com.balteklabs.voiceos

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.PermissionRequest
import android.app.Activity

class MainActivity : Activity() {

    companion object {
        // One server per process, not per Activity. Android can recreate the launcher
        // Activity while the old instance (and its server) still holds port 8741;
        // a per-Activity server then can't bind and the UI sits on the splash.
        @Volatile private var server: VoiceOSServer? = null
        @Volatile private var lastError: String? = null
    }

    private lateinit var webView: WebView
    private val port = 8741
    private val handler = Handler(Looper.getMainLooper())

    // Splash screen shown while NanoHTTPD starts up (~0.5–2 s on most devices).
    // Once the server is ready we reload from http://127.0.0.1:$port (same-origin,
    // so ES modules work, no CORS, no mixed-content issues).
    private val SPLASH_HTML = """
        <!DOCTYPE html><html><head>
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <style>
          *{box-sizing:border-box;margin:0;padding:0}
          body{background:#000;display:flex;align-items:center;justify-content:center;
               height:100vh;flex-direction:column;font-family:sans-serif}
          .logo{font-size:64px;color:#0096ff;margin-bottom:16px}
          h1{color:#fff;font-size:28px;font-weight:700;margin-bottom:6px}
          p{color:rgba(255,255,255,.35);font-size:12px;letter-spacing:.12em;
            text-transform:uppercase;margin-bottom:36px}
          .dots{display:flex;gap:8px}
          .dot{width:9px;height:9px;border-radius:50%;background:#0096ff;
               animation:p 1.2s ease-in-out infinite}
          .dot:nth-child(2){animation-delay:.2s}.dot:nth-child(3){animation-delay:.4s}
          @keyframes p{0%,80%,100%{transform:scale(.55);opacity:.35}
                       40%{transform:scale(1);opacity:1}}
          #s{position:fixed;bottom:48px;left:24px;right:24px;text-align:center;
             color:rgba(255,255,255,.35);font-size:12px;line-height:1.5}
        </style></head><body onclick="location.href='voiceos://retry'">
          <div class="logo">&#x2B21;</div>
          <h1>VoiceOS</h1>
          <p>by Baltek Labs</p>
          <div class="dots">
            <div class="dot"></div><div class="dot"></div><div class="dot"></div>
          </div>
          <div id="s"></div>
        </body></html>
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Full-screen immersive
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )

        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webView)
        configureWebView(webView)

        // Request runtime permissions
        val needed = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_SMS
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
        if (needed.isNotEmpty()) requestPermissions(needed, 1001)

        // Android 11+: request All Files Access for Termux bridge
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                })
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (_: Exception) { /* not available on this device */ }
            }
        }

        startServer()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(webView: WebView) {
        // Black instead of the default white, so any gap before/between page loads
        // matches the launcher instead of flashing a blank white screen.
        webView.setBackgroundColor(Color.BLACK)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true          // required for localStorage (welcome screen flag)
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            loadsImagesAutomatically = true
        }

        // Simple WebViewClient — no asset interception needed.
        // NanoHTTPD serves /static/* from assets/web/ (same origin, no CORS).
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                url: String
            ): Boolean {
                // Tapping the splash forces a restart attempt
                if (url.startsWith("voiceos://retry")) { forceRestart(); return true }
                // Only allow navigation to our local server
                return !(url.startsWith("http://127.0.0.1:$port")
                        || url.startsWith("http://localhost:$port"))
            }

            override fun onPageFinished(view: WebView, url: String) {
                // Drop the splash from history so Back can never return to it.
                if (url.startsWith(appUrl)) view.clearHistory()
            }

            @Suppress("DEPRECATION")
            override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
                // App page failed to load (server not listening yet) → splash + retry
                if (failingUrl?.startsWith(appUrl) == true) {
                    lastError = description
                    showSplash()
                    handler.postDelayed({ loadApp() }, 1500)
                }
            }

            // The renderer process can be killed while the launcher is in the
            // background. Without handling this the WebView is left blank (or the
            // app crashes). Swap in a fresh WebView and reload. (API 26+; never
            // called on older devices.)
            @android.annotation.TargetApi(Build.VERSION_CODES.O)
            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail
            ): Boolean {
                android.util.Log.w("VoiceOS", "WebView renderer gone (crash=${detail.didCrash()}), recreating")
                recreateWebView()
                return true
            }
        }

        // Grant WebView permission requests (microphone for speech recognition)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { request.grant(request.resources) }
            }
        }
    }

    private fun recreateWebView() {
        val parent = webView.parent as ViewGroup
        val params = webView.layoutParams
        parent.removeView(webView)
        webView.destroy()
        webView = WebView(this)
        configureWebView(webView)
        parent.addView(webView, 0, params)
        loadApp()
    }

    private val appUrl get() = "http://127.0.0.1:$port"

    private fun serverAlive() = server?.isAlive == true

    private fun loadApp() {
        if (serverAlive()) webView.loadUrl(appUrl) else startServer()
    }

    private fun showSplash() {
        // loadDataWithBaseURL, not loadData: loadData treats '#' (in the CSS colors)
        // as a URL fragment and truncates the page, leaving a blank white screen.
        webView.loadDataWithBaseURL(null, SPLASH_HTML, "text/html", "utf-8", null)
    }

    private fun onSplash() = webView.url?.startsWith(appUrl) != true

    /** Write a status line onto the splash (no-op once the app page is up). */
    private fun splashStatus(text: String) {
        if (!onSplash()) return
        val js = "var s=document.getElementById('s');if(s)s.textContent=" +
            com.google.gson.Gson().toJson(text) + ";"
        webView.evaluateJavascript(js, null)
    }

    private fun startServer() {
        if (serverStarting) return
        serverStarting = true
        // Show branded splash immediately so the user never sees a blank screen.
        if (onSplash()) showSplash()
        scheduleWatchdog()

        // Start NanoHTTPD in a background thread.  Once it's listening we swap in
        // the real app — same-origin HTTP, ES modules work, no CORS required.
        Thread {
            try {
                synchronized(MainActivity::class.java) {
                    if (!serverAlive()) {
                        try { server?.stop() } catch (_: Throwable) {}
                        val s = VoiceOSServer(applicationContext, port)
                        s.start()
                        server = s
                        lastError = null
                        android.util.Log.i("VoiceOS", "Server started on port $port")
                        s.warmupOllama()
                        VoiceOSNotificationService.contextStore = s.contextStore
                    }
                }
                runOnUiThread { webView.loadUrl(appUrl) }
            } catch (t: Throwable) {
                // Throwable, not Exception: an Error here used to leave serverStarting
                // stuck at true, so nothing ever retried.
                lastError = "${t.javaClass.simpleName}: ${t.message}"
                android.util.Log.e("VoiceOS", "Server failed to start: $lastError", t)
            } finally {
                serverStarting = false
            }
        }.apply { isDaemon = true; name = "voiceos-server-start" }.start()
    }

    @Volatile private var serverStarting = false

    /**
     * While the splash is showing, check every few seconds and retry. Whatever
     * went wrong (port held, start failed, load error), the launcher recovers on
     * its own instead of sitting on the splash forever.
     */
    private var watchdogTicks = 0
    private val watchdog = object : Runnable {
        override fun run() {
            if (!onSplash()) return
            watchdogTicks++
            splashStatus(
                (lastError?.let { "Still starting… $it" } ?: "Still starting…") +
                "\nTap to retry"
            )
            loadApp()
            handler.postDelayed(this, 4000)
        }
    }

    private fun scheduleWatchdog() {
        handler.removeCallbacks(watchdog)
        watchdogTicks = 0
        handler.postDelayed(watchdog, 4000)
    }

    private fun forceRestart() {
        splashStatus("Restarting…")
        Thread {
            synchronized(MainActivity::class.java) {
                try { server?.stopWarmup(); server?.stop() } catch (_: Throwable) {}
                server = null
            }
            runOnUiThread { startServer() }
        }.start()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        // Coming back to the launcher: make sure the server is up and the app page
        // (not a blank/splash page) is showing.
        if (!serverStarting) {
            if (!serverAlive()) startServer()
            else if (onSplash()) { webView.loadUrl(appUrl); scheduleWatchdog() }
        }
        // Re-assert immersive mode after any system dialog
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    override fun onBackPressed() {
        // Prevent back from exiting the launcher. Only step back within the app
        // itself — never back onto the splash.
        val list = webView.copyBackForwardList()
        val prev = if (list.currentIndex > 0) list.getItemAtIndex(list.currentIndex - 1) else null
        if (prev?.url?.startsWith(appUrl) == true) webView.goBack()
        // else: do nothing (launcher stays active)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        // The server is process-wide and outlives this Activity on purpose: a
        // recreated launcher picks it straight back up. It dies with the process.
    }
}
