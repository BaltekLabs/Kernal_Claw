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
import android.view.View
import android.view.WindowManager
import android.webkit.WebResourceRequest
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

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            loadsImagesAutomatically = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                // Keep all navigation inside the WebView
                val host = request.url.host ?: ""
                return !(host == "127.0.0.1" || host == "localhost")
            }
        }

        // Grant WebView permission requests (microphone for speech recognition)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { request.grant(request.resources) }
            }
        }

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

        // Android 11+: request All Files Access so the Termux bridge can use /sdcard/
        // Both startActivity calls are wrapped independently — either can throw
        // ActivityNotFoundException on some OEM ROMs, which would crash onCreate and
        // prevent the server from starting.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                })
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (_: Exception) { /* no handler on this device — skip */ }
            }
        }

        // Start server then load page
        startServer()
    }

    private fun startServer() {
        try {
            server = VoiceOSServer(applicationContext, port)
            server!!.start()
            server!!.warmupOllama()
            // Give the server a moment to bind, then load the page
            handler.postDelayed({
                webView.loadUrl("http://127.0.0.1:$port/")
            }, 200)
        } catch (e: Exception) {
            // Server failed to start (port busy?); try loading anyway
            webView.loadUrl("http://127.0.0.1:$port/")
        }
    }

    override fun onResume() {
        super.onResume()
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
