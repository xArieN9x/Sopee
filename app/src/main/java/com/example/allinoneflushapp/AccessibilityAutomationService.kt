package com.example.allinoneflushapp

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi

class AccessibilityAutomationService : AccessibilityService() {

    companion object {
        private val handler = Handler(Looper.getMainLooper())
        private const val AIRPLANE_DELAY = 8000L
        private val storageKeys = arrayOf("Storage usage", "Storage", "Storage & cache", "App storage")
        private val forceStopKeys = arrayOf("Force stop", "Force Stop", "Paksa berhenti", "Paksa Hentikan")
        private val confirmOkKeys = arrayOf("Force stop", "OK", "Yes", "Confirm", "Ya")
        private val clearCacheKeys = arrayOf("Clear cache", "Clear Cache", "Kosongkan cache", "Bersihkan cache")

        /**
         * Open App Info for package, then attempt Force Stop + Clear Cache sequence.
         * This triggers a chain of Accessibility actions with retries and small delays.
         */
        fun requestClearAndForceStop(packageName: String) {
            val ctx = AppGlobals.applicationContext
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.parse("package:$packageName")
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            ctx.startActivity(intent)

            // wait for App Info to open
            handler.postDelayed({
                val svc = AppGlobals.accessibilityService ?: return@postDelayed

                // 1) Try click Force Stop on App Info screen
                // use multiple variants, will click if found
                val clickedForceStop = svc.findAndClick(*forceStopKeys)

                if (clickedForceStop) {
                    // 2) Wait shortly then click Force Stop in dialog (Realme shows "Force stop")
                    handler.postDelayed({
                        // try exact "Force stop" text first, then fallback ids
                        svc.clickForceStopInDialog()
                    }, 700)
                } else {
                    // If Force Stop button not found on main App Info, try scroll/search & then storage flow
                    // continue to storage sequence anyway after some delay
                }

                // 3) After force-stop attempt, go to Storage usage and clear cache
                handler.postDelayed({
                    svc.findAndClick(*storageKeys) // open Storage usage
                    handler.postDelayed({
                        svc.findAndClick(*clearCacheKeys) // click Clear cache
                        // go back to App Info after clearing
                        handler.postDelayed({ svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }, 800)
                    }, 900)
                }, 1700)
            }, 1200)
        }

        /**
         * Toggle Airplane mode via Quick Settings tiles.
         * Use double QS open and multiple text/id attempts for Realme/ColorOS.
         */
        fun requestToggleAirplane() {
            val svc = AppGlobals.accessibilityService ?: return

            // Open quick settings panel fully (two swipes)
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            handler.postDelayed({
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            }, 200)

            // try to click airplane tile after QS opened
            handler.postDelayed({
                // common labels to search
                val keys = arrayOf("Airplane mode", "Airplane", "Flight mode", "Mod Pesawat", "Mod Penerbangan", "Mod Pesawat")
                var clicked = svc.findAndClick(*keys)
                if (!clicked) {
                    // fallback: try scanning common tile labels / ids
                    clicked = svc.findAndClick("Airplane")
                }

                // wait ON for specified delay, then toggle OFF
                handler.postDelayed({
                    // try to click again to disable
                    svc.findAndClick(*keys)
                    // return to home screen to continue flow
                    svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                }, AIRPLANE_DELAY)
            }, 500)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppGlobals.accessibilityService = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    // -----------------------
    // Robust find & click API
    // -----------------------

    /**
     * Public find-and-click that accepts multiple candidate texts.
     * Returns true if any click succeeded.
     */
    fun findAndClick(vararg keys: String, maxRetries: Int = 6, delayMs: Long = 650L): Boolean {
        repeat(maxRetries) {
            val root = rootInActiveWindow
            if (root != null) {
                for (k in keys) {
                    // 1) Exact text search (visible text)
                    val nodes = root.findAccessibilityNodeInfosByText(k)
                    if (!nodes.isNullOrEmpty()) {
                        for (n in nodes) {
                            if (performClickNodeOrParent(n)) return true
                        }
                    }

                    // 2) contentDescription search
                    val descNode = findNodeByDescription(root, k)
                    if (descNode != null) {
                        if (performClickNodeOrParent(descNode)) return true
                    }

                    // 3) viewIdResourceName search (partial match)
                    val idNode = findNodeByViewId(root, k)
                    if (idNode != null) {
                        if (performClickNodeOrParent(idNode)) return true
                    }
                }

                // try scroll forward to reveal hidden buttons
                try {
                    root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                } catch (_: Exception) {}
            }
            Thread.sleep(delayMs)
        }
        return false
    }

    /**
     * Try click node if clickable, otherwise attempt to click first clickable ancestor.
     * Returns true if a click action was performed.
     */
    private fun performClickNodeOrParent(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        try {
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
            var p = node.parent
            while (p != null) {
                if (p.isClickable) {
                    p.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
                p = p.parent
            }
        } catch (_: Exception) {}
        return false
    }

    // -----------------------
    // Helpers: content-desc & view-id based search
    // -----------------------

    private fun findNodeByDescription(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeFirst()
            try {
                val cd = n.contentDescription
                if (cd != null && cd.toString().contains(text, true)) return n
            } catch (_: Exception) {}
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        return null
    }

    private fun findNodeByViewId(root: AccessibilityNodeInfo, idPart: String): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeFirst()
            try {
                val vid = n.viewIdResourceName
                if (vid != null && vid.contains(idPart, true)) return n
            } catch (_: Exception) {}
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        return null
    }

    // -----------------------
    // Realme / ColorOS specific fallbacks for Force Stop dialog
    // -----------------------

    /**
     * Click the "Force stop" button inside the confirmation dialog reliably.
     * We first try text match "Force stop", then fallback to common dialog ids.
     */
    fun clickForceStopInDialog(): Boolean {
        val root = rootInActiveWindow ?: return false

        // 1) try text match (Realme shows "Force stop")
        val textCandidates = arrayOf("Force stop", "Force Stop", "Paksa berhenti", "Force stop this app?")
        for (t in textCandidates) {
            val nodes = root.findAccessibilityNodeInfosByText(t)
            if (!nodes.isNullOrEmpty()) {
                for (n in nodes) {
                    // prefer clicking clickable node or its clickable parent
                    if (performClickNodeOrParent(n)) return true
                }
            }
        }

        // 2) fallback: android dialog ids
        val androidDialogIds = arrayOf("android:id/button1", "android:id/button2", "android:id/button3")
        for (id in androidDialogIds) {
            try {
                val nodes = root.findAccessibilityNodeInfosByViewId(id)
                if (!nodes.isNullOrEmpty()) {
                    for (n in nodes) {
                        if (performClickNodeOrParent(n)) return true
                    }
                }
            } catch (_: Exception) {}
        }

        // 3) fallback: ColorOS / Realme id variants
        val colorOsIds = arrayOf(
            "com.coloros.safecenter:id/affirm",
            "com.android.settings:id/btn_right",
            "com.android.settings:id/button1",
            "com.android.settings:id/confirm_button"
        )
        for (id in colorOsIds) {
            try {
                val nodes = root.findAccessibilityNodeInfosByViewId(id)
                if (!nodes.isNullOrEmpty()) {
                    for (n in nodes) {
                        if (performClickNodeOrParent(n)) return true
                    }
                }
            } catch (_: Exception) {}
        }

        // 4) last fallback: search for clickable buttons inside dialog container
        try {
            val titles = root.findAccessibilityNodeInfosByViewId("android:id/alertTitle")
            if (!titles.isNullOrEmpty()) {
                val title = titles.first()
                var parent = title.parent
                if (parent != null) {
                    for (i in 0 until parent.childCount) {
                        val c = parent.getChild(i)
                        if (c != null && c.isClickable) {
                            c.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            return true
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return false
    }
}
