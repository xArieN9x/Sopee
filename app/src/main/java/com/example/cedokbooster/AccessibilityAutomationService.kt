package com.example.cedokbooster

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.util.ArrayDeque

class AccessibilityAutomationService : AccessibilityService() {

    companion object {
        private const val TAG = "AccessibilityAutomation"
        const val FORCE_CLOSE_PANDA = "com.example.cedokbooster.FORCE_CLOSE_PANDA"
        const val DO_ALL_JOB_TRIGGER = "DO_ALL_JOB_TRIGGER"
    }
        
    private val storageKeys = arrayOf("Storage usage", "Storage usage ", "Storage", "Storage & cache", "App storage")
    private val forceStopKeys = arrayOf("Force stop", "Force stop ", "Force Stop", "Paksa berhenti", "Paksa Hentikan")
    private val confirmOkKeys = arrayOf("OK", "Yes", "Confirm", "Ya", "Force stop ", "Force stop")
    private val clearCacheKeys = arrayOf("Clear cache", "Clear cache ", "Clear Cache", "Kosongkan cache")

    private val handler = Handler(Looper.getMainLooper())
    private var isAutomationRunning = false
    private var currentStep = 0

    // ==================== FUNGSI UTAMA DARI PROJEK LAMA ====================
    fun findAndClick(vararg keys: String, maxRetries: Int = 3, delayMs: Long = 700L): Boolean {
        repeat(maxRetries) {
            val root = rootInActiveWindow
            if (root != null) {
                for (k in keys) {
                    // Exact text matches
                    val nodes = root.findAccessibilityNodeInfosByText(k)
                    if (!nodes.isNullOrEmpty()) {
                        for (n in nodes) {
                            if (n.isClickable) { 
                                n.performAction(AccessibilityNodeInfo.ACTION_CLICK); 
                                return true 
                            }
                            var p = n.parent
                            while (p != null) {
                                if (p.isClickable) { 
                                    p.performAction(AccessibilityNodeInfo.ACTION_CLICK); 
                                    return true 
                                }
                                p = p.parent
                            }
                        }
                    }
                    // Content-desc scan
                    val desc = findNodeByDescription(root, k)
                    if (desc != null) { 
                        desc.performAction(AccessibilityNodeInfo.ACTION_CLICK); 
                        return true 
                    }
                    // ViewId fallback
                    val idNode = findNodeByViewId(root, k)
                    if (idNode != null) {
                        if (idNode.isClickable) { 
                            idNode.performAction(AccessibilityNodeInfo.ACTION_CLICK); 
                            return true 
                        }
                        var p = idNode.parent
                        while (p != null) {
                            if (p.isClickable) { 
                                p.performAction(AccessibilityNodeInfo.ACTION_CLICK); 
                                return true 
                            }
                            p = p.parent
                        }
                    }
                }
                // Try scroll to reveal
                root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
            Thread.sleep(delayMs)
        }
        return false
    }

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
    // ==================== AKHIR FUNGSI DARI PROJEK LAMA ====================

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                "DO_ALL_JOB_TRIGGER" -> {
                    Log.d(TAG, "════════════════════════════════")
                    Log.d(TAG, "DO_ALL_JOB_TRIGGER received")
                    if (!isAutomationRunning) {
                        startAutomationSequence()
                    }
                }
                FORCE_CLOSE_PANDA -> {
                    Log.d(TAG, "FORCE_CLOSE_PANDA received")
                    performForceClosePanda()
                }
                "com.example.cedokbooster.GPS_LOCK_ACHIEVED" -> {
                    Log.d(TAG, "GPS_LOCK_ACHIEVED received - Launching Panda")
                    handler.postDelayed({
                        launchPandaApp()
                    }, 1000)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        
        val filter = IntentFilter().apply {
            addAction("DO_ALL_JOB_TRIGGER")
            addAction(Companion.FORCE_CLOSE_PANDA)
            addAction("com.example.cedokbooster.GPS_LOCK_ACHIEVED")
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(broadcastReceiver, filter)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility Service Connected")
        
        val config = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }
        this.serviceInfo = config
    }

    private fun startAutomationSequence() {
        if (isAutomationRunning) return
        
        Log.i(TAG, "=== STARTING AUTOMATION SEQUENCE ===")
        isAutomationRunning = true
        currentStep = 0
    
        Log.d(TAG, "Step 1: Force Closing Panda app")
        performForceClosePanda()
        
        handler.postDelayed({
            Log.d(TAG, "Step 2: Clearing Panda app cache")
            performClearCache()
        }, 3000)
        
        handler.postDelayed({
            Log.d(TAG, "Step 3: Airplane Mode ON")
            toggleAirplaneMode(true)
        }, 5000)
        
        handler.postDelayed({
            Log.d(TAG, "Step 4: Airplane Mode OFF")
            toggleAirplaneMode(false)
        }, 8000)
        
        handler.postDelayed({
            Log.d(TAG, "Step 5: Restarting CoreEngine")
            restartCoreEngine()
        }, 10000)
        
        handler.postDelayed({
            Log.d(TAG, "Step 6: Launching Panda app")
            launchPandaApp()
            isAutomationRunning = false
            Log.i(TAG, "=== AUTOMATION SEQUENCE COMPLETE ===")
        }, 14000)
    }

    private fun performForceClosePanda() {
        handler.post {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.parse("package:com.logistics.rider.foodpanda")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                Log.d(TAG, "Opened Panda app info")
                
                // TIMING DARI PROJEK LAMA: 1200ms
                handler.postDelayed({
                    findAndClickForceStop()
                }, 1200)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error opening app info: ${e.message}")
            }
        }
    }

    private fun findAndClickForceStop() {
        val clicked = findAndClick(*forceStopKeys)
        
        if (clicked) {
            Log.d(TAG, "Force Stop button clicked")
            // TIMING DARI PROJEK LAMA: 700ms
            handler.postDelayed({
                findAndClickConfirm()
            }, 700)
        } else {
            Log.d(TAG, "Force Stop button not found")
        }
    }

    private fun findAndClickConfirm() {
        val clicked = findAndClick(*confirmOkKeys)
        
        if (clicked) {
            Log.d(TAG, "Confirmation button clicked")
            // BACK & HOME DARI PROJEK LAMA
            handler.postDelayed({
                performGlobalAction(GLOBAL_ACTION_BACK)
                handler.postDelayed({
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }, 300)
            }, 1200)
        } else {
            Log.d(TAG, "Confirmation button NOT FOUND")
        }
    }

    private fun performClearCache() {
        handler.post {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:com.logistics.rider.foodpanda")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Log.d(TAG, "Opened app info for cache clearing")
            
            handler.postDelayed({
                findAndClickStorage()
            }, 1500)
        }
    }

    private fun findAndClickStorage() {
        val clicked = findAndClick(*storageKeys)
        
        if (clicked) {
            Log.d(TAG, "Storage button clicked")
            handler.postDelayed({
                findAndClickClearCache()
            }, 1000)
        } else {
            Log.d(TAG, "Storage button not found")
        }
    }

    private fun findAndClickClearCache() {
        val clicked = findAndClick(*clearCacheKeys)
        
        if (clicked) {
            Log.d(TAG, "Clear Cache button clicked")
        } else {
            Log.d(TAG, "Clear Cache button not found")
        }
    }

    private fun toggleAirplaneMode(turnOn: Boolean) {
        handler.post {
            try {
                if (turnOn) {
                    Settings.Global.putInt(
                        contentResolver,
                        Settings.Global.AIRPLANE_MODE_ON,
                        1
                    )
                    val intent = Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).apply {
                        putExtra("state", true)
                    }
                    sendBroadcast(intent)
                    Log.d(TAG, "Airplane Mode turned ON")
                } else {
                    Settings.Global.putInt(
                        contentResolver,
                        Settings.Global.AIRPLANE_MODE_ON,
                        0
                    )
                    val intent = Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).apply {
                        putExtra("state", false)
                    }
                    sendBroadcast(intent)
                    Log.d(TAG, "Airplane Mode turned OFF")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error toggling airplane mode: ${e.message}")
            }
        }
    }

    private fun restartCoreEngine() {
        val intent = Intent("RESTART_CORE_ENGINE")
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        Log.d(TAG, "Sent RESTART_CORE_ENGINE broadcast")
    }

    private fun launchPandaApp() {
        handler.post {
            try {
                val intent = packageManager.getLaunchIntentForPackage("com.logistics.rider.foodpanda")
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    startActivity(intent)
                    Log.d(TAG, "Panda app launched")
                } else {
                    Log.e(TAG, "No launch intent for Panda app")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error launching Panda app: ${e.message}")
            }
        }
    }

    // Function lama (untuk compatibility)
    private fun findButtonByDescription(rootNode: AccessibilityNodeInfo, description: String) {
        val node = findNodeByDescription(rootNode, description)
        if (node != null) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.let {
            if (it.packageName == "com.logistics.rider.foodpanda") {
                Log.d(TAG, "Panda window event: ${it.className}")
            }
        }
    }

    override fun onInterrupt() {
        Log.i(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy")
        handler.removeCallbacksAndMessages(null)
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(broadcastReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver: ${e.message}")
        }
        super.onDestroy()
    }
}
