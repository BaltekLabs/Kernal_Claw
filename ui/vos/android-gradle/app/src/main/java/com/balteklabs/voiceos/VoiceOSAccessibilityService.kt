package com.balteklabs.voiceos

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.HardwareRenderer
import android.graphics.Path
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * VoiceOSAccessibilityService — provides screenshot capture and gesture dispatch
 * for the VoiceOS AI agent.
 *
 * Capabilities (all require the user to enable in Settings › Accessibility › VoiceOS):
 *   takeScreenshot()          — returns base64 JPEG, API 30+
 *   dispatchTap(x, y)         — tap at pixel coord
 *   dispatchSwipe(...)        — swipe / scroll gesture
 *   pressBack / pressHome     — global actions
 *   extractScreenText()       — walks accessibility tree for text, faster than vision
 *   getScreenSize()           — returns (widthPx, heightPx)
 *
 * Usage: all calls go through the companion object static methods which delegate
 * to the live [instance]. Returns null/false gracefully when the service is not
 * enabled rather than throwing.
 */
class VoiceOSAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "VoiceOSA11y"

        @Volatile
        var instance: VoiceOSAccessibilityService? = null
            private set

        fun isAvailable(): Boolean = instance != null

        /** Capture screen as base64 JPEG. Returns null if service unavailable or API < 30. */
        fun takeScreenshot(timeoutMs: Long = 6_000): String? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                Log.w(TAG, "takeScreenshot requires API 30+, device is ${Build.VERSION.SDK_INT}")
                return null
            }
            return instance?.captureScreen(timeoutMs)
        }

        /** Tap at (x, y) pixel coordinates. Returns false if service unavailable. */
        fun tap(x: Float, y: Float, timeoutMs: Long = 3_000): Boolean =
            instance?.dispatchTap(x, y, timeoutMs) ?: false

        /** Swipe from (x1,y1) to (x2,y2) over [durationMs] ms. */
        fun swipe(
            x1: Float, y1: Float, x2: Float, y2: Float,
            durationMs: Long = 300, timeoutMs: Long = 5_000
        ): Boolean = instance?.dispatchSwipe(x1, y1, x2, y2, durationMs, timeoutMs) ?: false

        fun pressBack(): Boolean {
            instance?.performGlobalAction(GLOBAL_ACTION_BACK) ?: return false
            return true
        }

        fun pressHome(): Boolean {
            instance?.performGlobalAction(GLOBAL_ACTION_HOME) ?: return false
            return true
        }

        /** Walk the accessibility tree and extract visible text with element roles. */
        fun getScreenText(): String = instance?.extractScreenText() ?: ""

        /** Returns actual screen pixel dimensions (width, height). */
        fun getScreenSize(): Pair<Int, Int> {
            val svc = instance ?: return Pair(1080, 1920)
            val dm = svc.resources.displayMetrics
            return Pair(dm.widthPixels, dm.heightPixels)
        }

        /** Type text into the currently focused input field. */
        fun typeText(text: String): Boolean = instance?.performTypeText(text) ?: false

        /** Long-press at pixel coordinates. */
        fun longPress(x: Float, y: Float, durationMs: Long = 800L, timeoutMs: Long = 5_000): Boolean =
            instance?.dispatchLongPress(x, y, durationMs, timeoutMs) ?: false

        /** Pull down the notification shade. */
        fun pullNotificationShade(): Boolean {
            instance?.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) ?: return false
            return true
        }

        /** Open Quick Settings panel. */
        fun openQuickSettings(): Boolean {
            instance?.performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS) ?: return false
            return true
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected (API ${Build.VERSION.SDK_INT})")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        Log.i(TAG, "Accessibility service destroyed")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* not used */ }
    override fun onInterrupt() { /* not used */ }

    // ── Screenshot ─────────────────────────────────────────────────────────

    @SuppressLint("NewApi")   // guarded by Build.VERSION_CODES.R check in companion
    private fun captureScreen(timeoutMs: Long): String? {
        val latch   = CountDownLatch(1)
        var base64: String? = null

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    try {
                        // ScreenshotResult gives a HardwareBuffer — wrap into a hardware Bitmap,
                        // then copy to software config so we can compress it.
                        val hwBuffer = result.hardwareBuffer
                        val hwBitmap = Bitmap.wrapHardwareBuffer(hwBuffer, null)
                        hwBuffer.close()
                        if (hwBitmap == null) { latch.countDown(); return }

                        val bmp = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
                        hwBitmap.recycle()

                        val scaled = scaleBitmap(bmp, 1080)
                        bmp.recycle()

                        val out = ByteArrayOutputStream()
                        scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
                        scaled.recycle()

                        base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                    } catch (e: Exception) {
                        Log.e(TAG, "Screenshot encode failed: ${e.message}")
                    } finally {
                        latch.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.e(TAG, "Screenshot capture failed, errorCode=$errorCode")
                    latch.countDown()
                }
            }
        )

        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return base64
    }

    private fun scaleBitmap(src: Bitmap, maxSide: Int): Bitmap {
        val w = src.width; val h = src.height
        if (w <= maxSide && h <= maxSide) return src.copy(src.config ?: Bitmap.Config.ARGB_8888, false)
        val scale = maxSide.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(src, (w * scale).toInt(), (h * scale).toInt(), true)
    }

    // ── Gesture dispatch ───────────────────────────────────────────────────

    private fun dispatchTap(x: Float, y: Float, timeoutMs: Long): Boolean {
        val path    = Path().apply { moveTo(x, y) }
        val stroke  = GestureDescription.StrokeDescription(path, 0L, 50L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchAndWait(gesture, timeoutMs)
    }

    private fun dispatchSwipe(
        x1: Float, y1: Float, x2: Float, y2: Float,
        durationMs: Long, timeoutMs: Long
    ): Boolean {
        val path    = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val stroke  = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(50L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchAndWait(gesture, timeoutMs)
    }

    private fun dispatchLongPress(x: Float, y: Float, durationMs: Long, timeoutMs: Long): Boolean {
        val path    = Path().apply { moveTo(x, y) }
        val stroke  = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(400L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchAndWait(gesture, timeoutMs)
    }

    private fun performTypeText(text: String): Boolean {
        return try {
            // Try to set text on the focused input node
            val root = rootInActiveWindow ?: return false
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            root.recycle()
            if (focused != null) {
                val args = android.os.Bundle()
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                val result = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                focused.recycle()
                result
            } else {
                // No focused input — use PASTE approach: set clipboard then paste
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "typeText failed: ${e.message}")
            false
        }
    }

    private fun dispatchAndWait(gesture: GestureDescription, timeoutMs: Long): Boolean {
        val latch   = CountDownLatch(1)
        var success = false
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription) { success = true; latch.countDown() }
            override fun onCancelled(g: GestureDescription) { latch.countDown() }
        }, Handler(Looper.getMainLooper()))
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return success
    }

    // ── Screen text extraction ─────────────────────────────────────────────

    private fun extractScreenText(): String {
        val root = rootInActiveWindow ?: return "(Accessibility tree unavailable)"
        val sb   = StringBuilder()
        walkNode(root, sb, 0)
        root.recycle()
        return sb.toString().trim().take(4000)
    }

    private fun walkNode(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (node == null || depth > 14) return

        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        val label = when {
            !text.isNullOrBlank() -> text
            !desc.isNullOrBlank() -> "[${desc}]"
            else -> null
        }

        if (!label.isNullOrBlank() && label.length > 1) {
            val indent = "  ".repeat(minOf(depth, 5))
            val role = buildString {
                if (node.isClickable) append("▶ ")
                if (node.isEditable)  append("[input] ")
                if (node.isCheckable) append("[${if (node.isChecked) "✓" else "○"}] ")
            }
            sb.appendLine("$indent$role$label")
        }

        for (i in 0 until node.childCount) {
            walkNode(node.getChild(i), sb, depth + 1)
        }
        node.recycle()
    }
}
