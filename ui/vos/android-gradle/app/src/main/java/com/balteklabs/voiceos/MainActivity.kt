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

    private lateinit var webView: WebView
    private var server: VoiceOSServer? = null
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
        </style></head><body>
          <div class="logo">&#x2B21;</div>
          <h1>VoiceOS</h1>
          <p>by Baltek Labs</p>
          <div class="dots">
            <div class="dot"></div><div class="dot"></div><div class="dot"></div>
          </div>
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
                // Only allow navigation to our local server
                return !(url.startsWith("http://127.0.0.1:$port")
                        || url.startsWith("http://localhost:$port"))
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

    private fun startServer(attempt: Int = 0) {
        if (serverStarting) return
        serverStarting = true
        // Show branded splash immediately so the user never sees a blank screen.
        showSplash()

        // Start NanoHTTPD in a background thread.  Once it's listening we swap in
        // the real app — same-origin HTTP, ES modules work, no CORS required.
        Thread {
            try {
                if (!serverAlive()) {
                    server?.stop()
                    server = VoiceOSServer(applicationContext, port).also { it.start() }
                    android.util.Log.i("VoiceOS", "Server started on port $port")
                    server!!.warmupOllama()
                    VoiceOSNotificationService.contextStore = server!!.contextStore
                }
                runOnUiThread {
                    serverStarting = false
                    webView.loadUrl(appUrl)
                }
            } catch (e: Exception) {
                android.util.Log.e("VoiceOS", "Server failed to start (attempt ${attempt + 1}): ${e.message}", e)
                // Typically the port is still held by a previous instance; retry.
                handler.postDelayed({
                    serverStarting = false
                    if (attempt < 10) startServer(attempt + 1)
                }, 1000)
            }
        }.apply { isDaemon = true; name = "voiceos-server-start" }.start()
    }

    @Volatile private var serverStarting = false

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
            else if (webView.url?.startsWith(appUrl) != true) webView.loadUrl(appUrl)
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
        // Prevent back from exiting the launcher
        if (webView.canGoBack()) webView.goBack()
        // else: do nothing (launcher stays active)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        server?.stopWarmup()
        server?.stop()
    }
}
