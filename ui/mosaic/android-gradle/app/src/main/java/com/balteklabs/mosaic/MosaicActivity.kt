package com.balteklabs.mosaic

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.net.Uri
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.balteklabs.voiceos.R
import com.balteklabs.voiceos.VoiceOSNotificationService

/**
 * Mosaic — a live-tile home screen on top of the VoiceOS agent backend.
 *
 * Same shape as the VoiceOS launcher: an embedded HTTP server (MosaicServer)
 * serves the web UI from assets/web/ to a full-screen WebView. Port 8742, so it
 * can run alongside VoiceOS (8741).
 *
 * Back and Home are forwarded to the page (window.mosaicBack / mosaicHome) so it
 * can close panels and leave edit mode, the way a phone shell should behave.
 */
class MosaicActivity : Activity() {

    companion object {
        // One server per process, not per Activity (see VoiceOS MainActivity:
        // a recreated Activity must not fight the old one for the port).
        @Volatile private var server: MosaicServer? = null
        @Volatile private var lastError: String? = null
        const val PORT = 8742
    }

    private lateinit var webView: WebView
    private val handler = Handler(Looper.getMainLooper())
    private val appUrl = "http://127.0.0.1:$PORT"
    @Volatile private var serverStarting = false

    // <input type="file"> (wallpaper photo): the WebView asks us to show a picker.
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val FILE_REQUEST = 2001

    private val SPLASH_HTML = """
        <!DOCTYPE html><html><head>
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <style>
          *{box-sizing:border-box;margin:0;padding:0}
          body{background:#000;height:100vh;display:flex;align-items:center;justify-content:center;
               font-family:'Segoe UI',Roboto,sans-serif;color:#fff}
          .g{display:grid;grid-template-columns:repeat(2,34px);gap:5px;margin-bottom:28px}
          .g i{width:34px;height:34px;background:#1ba1e2;animation:f 1.6s ease-in-out infinite}
          .g i:nth-child(2){animation-delay:.2s}.g i:nth-child(3){animation-delay:.4s}
          .g i:nth-child(4){animation-delay:.6s}
          @keyframes f{0%,60%,100%{transform:rotateX(0)}30%{transform:rotateX(90deg)}}
          .w{display:flex;flex-direction:column;align-items:center}
          h1{font-weight:300;font-size:34px}
          #s{position:fixed;bottom:44px;left:24px;right:24px;text-align:center;
             color:rgba(255,255,255,.4);font-size:12px;line-height:1.5}
        </style></head><body onclick="location.href='mosaic://retry'">
          <div class="w"><div class="g"><i></i><i></i><i></i><i></i></div><h1>mosaic</h1></div>
          <div id="s"></div>
        </body></html>
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyImmersive()

        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webView)
        configureWebView(webView)

        val needed = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
        if (needed.isNotEmpty()) requestPermissions(needed, 1001)

        startServer()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(webView: WebView) {
        webView.setBackgroundColor(Color.BLACK)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true          // tile layout + settings live in localStorage
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                if (url.startsWith("mosaic://retry")) { forceRestart(); return true }
                if (url.startsWith("mosaic://permission/sms")) { requestSmsPermission(); return true }
                return !(url.startsWith(appUrl) || url.startsWith("http://localhost:$PORT"))
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (url.startsWith(appUrl)) view.clearHistory()
            }

            @Suppress("DEPRECATION")
            override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
                if (failingUrl?.startsWith(appUrl) == true) {
                    lastError = description
                    showSplash()
                    handler.postDelayed({ loadApp() }, 1500)
                }
            }

            @android.annotation.TargetApi(Build.VERSION_CODES.O)
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                android.util.Log.w("Mosaic", "WebView renderer gone (crash=${detail.didCrash()}), recreating")
                recreateWebView()
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { request.grant(request.resources) }
            }

            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)      // a picker can only be open once
                fileCallback = callback
                return try {
                    startActivityForResult(params.createIntent(), FILE_REQUEST)
                    true
                } catch (_: android.content.ActivityNotFoundException) {
                    fileCallback = null
                    false
                }
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

    private fun serverAlive() = server?.isAlive == true
    private fun onSplash() = webView.url?.startsWith(appUrl) != true

    private fun loadApp() {
        if (serverAlive()) webView.loadUrl(appUrl) else startServer()
    }

    private fun showSplash() {
        webView.loadDataWithBaseURL(null, SPLASH_HTML, "text/html", "utf-8", null)
    }

    private fun splashStatus(text: String) {
        if (!onSplash()) return
        webView.evaluateJavascript(
            "var s=document.getElementById('s');if(s)s.textContent=" +
                com.google.gson.Gson().toJson(text) + ";", null)
    }

    private fun startServer() {
        if (serverStarting) return
        serverStarting = true
        if (onSplash()) showSplash()
        scheduleWatchdog()

        Thread {
            try {
                synchronized(MosaicActivity::class.java) {
                    if (!serverAlive()) {
                        try { server?.stop() } catch (_: Throwable) {}
                        val s = MosaicServer(applicationContext, PORT)
                        s.start()
                        server = s
                        lastError = null
                        android.util.Log.i("Mosaic", "Server started on port $PORT")
                        s.warmupOllama()
                        VoiceOSNotificationService.contextStore = s.contextStore
                    }
                }
                runOnUiThread { webView.loadUrl(appUrl) }
            } catch (t: Throwable) {
                lastError = "${t.javaClass.simpleName}: ${t.message}"
                android.util.Log.e("Mosaic", "Server failed to start: $lastError", t)
            } finally {
                serverStarting = false
            }
        }.apply { isDaemon = true; name = "mosaic-server-start" }.start()
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (!onSplash()) return
            splashStatus((lastError?.let { "Still starting… $it" } ?: "Still starting…") + "\nTap to retry")
            loadApp()
            handler.postDelayed(this, 4000)
        }
    }

    private fun scheduleWatchdog() {
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, 4000)
    }

    private fun forceRestart() {
        splashStatus("Restarting…")
        Thread {
            synchronized(MosaicActivity::class.java) {
                try { server?.stopWarmup(); server?.stop() } catch (_: Throwable) {}
                server = null
            }
            runOnUiThread { startServer() }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun applyImmersive() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    // ── SMS permission, asked for from the page ("allow texting") ─────────
    private val SMS_REQUEST = 1002

    private fun requestSmsPermission() {
        if (checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
            notifyPage("sms", true); return
        }
        requestPermissions(arrayOf(Manifest.permission.SEND_SMS), SMS_REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode != SMS_REQUEST) return
        val granted = results.firstOrNull() == PackageManager.PERMISSION_GRANTED
        notifyPage("sms", granted)
        // Denied without a prompt usually means Android's "restricted settings" block
        // (sideloaded apps) or "don't ask again": the fix lives on the app's info page.
        if (!granted) try {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")))
        } catch (_: Exception) {}
    }

    private fun notifyPage(what: String, granted: Boolean) {
        if (!onSplash()) webView.evaluateJavascript("window.mosaicPermission&&mosaicPermission('$what',$granted)", null)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_REQUEST) {
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCallback = null
        }
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        if (!serverStarting) {
            if (!serverAlive()) startServer()
            else if (onSplash()) { webView.loadUrl(appUrl); scheduleWatchdog() }
        }
        if (!onSplash()) webView.evaluateJavascript("window.mosaicResume&&mosaicResume()", null)
        applyImmersive()
    }

    // Home pressed while already home: back to the top of Start, like a phone shell.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME) && !onSplash())
            webView.evaluateJavascript("window.mosaicHome&&mosaicHome()", null)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // The page decides: close a panel, leave edit mode, return to Start.
        // A launcher never exits on Back.
        if (!onSplash()) webView.evaluateJavascript("window.mosaicBack&&mosaicBack()", null)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
