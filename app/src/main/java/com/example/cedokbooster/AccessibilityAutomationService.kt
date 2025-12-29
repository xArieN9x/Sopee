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

    // Dalam broadcastReceiver - TAMBAH handler untuk GPS_LOCK_ACHIEVED
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
                // ✅ TAMBAH handler untuk GPS lock
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
        
        // Register broadcast receiver
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
        
        // Configure service
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
    
        // Step 1: Force Close Panda App (segera)
        Log.d(TAG, "Step 1: Force Closing Panda app")
        performForceClosePanda()
        
        // Step 2: Clear Cache (tunggu 3 saat)
        handler.postDelayed({
            Log.d(TAG, "Step 2: Clearing Panda app cache")
            performClearCache()
        }, 3000)
        
        // Step 3: Airplane Mode ON (tunggu 2 saat selepas cache)
        handler.postDelayed({
            Log.d(TAG, "Step 3: Airplane Mode ON")
            toggleAirplaneMode(true)
        }, 5000)
        
        // Step 4: Airplane Mode OFF (tunggu 3 saat)
        handler.postDelayed({
            Log.d(TAG, "Step 4: Airplane Mode OFF")
            toggleAirplaneMode(false)
        }, 8000)
        
        // Step 5: Restart CoreEngine (tunggu 2 saat)
        handler.postDelayed({
            Log.d(TAG, "Step 5: Restarting CoreEngine")
            restartCoreEngine()
        }, 10000)
        
        // Step 6: Launch Panda App (tunggu 4 saat)
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
                // Buka app info untuk Panda
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.parse("package:com.logistics.rider.foodpanda")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                Log.d(TAG, "Opened Panda app info")
                
                // Tunggu 1.5 saat untuk app info load, kemudian cari butang Force Stop
                handler.postDelayed({
                    findAndClickForceStop()
                }, 1500)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error opening app info: ${e.message}")
            }
        }
    }

    private fun findAndClickForceStop() {
        val rootNode = rootInActiveWindow ?: return
        
        // Cari butang "Force Stop" menggunakan keys dari project lama
        for (key in forceStopKeys) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(key)
            if (nodes.isNotEmpty()) {
                Log.d(TAG, "Found Force Stop button: $key")
                nodes[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
                
                // Tunggu untuk confirmation dialog, kemudian click OK/Confirm
                handler.postDelayed({
                    findAndClickConfirm()
                }, 1000)
                return
            }
        }
        
        Log.d(TAG, "Force Stop button not found, trying alternative")
        // Alternative: Cari butang dengan description atau ID
        findButtonByDescription(rootNode, "Force stop")
    }

    private fun findAndClickConfirm() {
        val rootNode = rootInActiveWindow ?: return
        
        // Cari butang confirmation (OK/Yes/Confirm)
        for (key in confirmOkKeys) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(key)
            if (nodes.isNotEmpty()) {
                Log.d(TAG, "Found confirmation button: $key")
                nodes[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return
            }
        }
    }

    private fun performClearCache() {
        handler.post {
            // Buka storage settings untuk Panda
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:com.logistics.rider.foodpanda")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Log.d(TAG, "Opened app info for cache clearing")
            
            // Tunggu 1.5 saat, kemudian cari "Storage" button
            handler.postDelayed({
                findAndClickStorage()
            }, 1500)
        }
    }

    private fun findAndClickStorage() {
        val rootNode = rootInActiveWindow ?: return
        
        // Cari butang "Storage" menggunakan keys dari project lama
        for (key in storageKeys) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(key)
            if (nodes.isNotEmpty()) {
                Log.d(TAG, "Found Storage button: $key")
                nodes[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
                
                // Tunggu 1 saat, kemudian cari "Clear Cache" button
                handler.postDelayed({
                    findAndClickClearCache()
                }, 1000)
                return
            }
        }
        
        Log.d(TAG, "Storage button not found")
    }

    private fun findAndClickClearCache() {
        val rootNode = rootInActiveWindow ?: return
        
        // Cari butang "Clear Cache" menggunakan keys dari project lama
        for (key in clearCacheKeys) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(key)
            if (nodes.isNotEmpty()) {
                Log.d(TAG, "Found Clear Cache button: $key")
                nodes[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return
            }
        }
        
        Log.d(TAG, "Clear Cache button not found")
    }

    private fun toggleAirplaneMode(turnOn: Boolean) {
        handler.post {
            try {
                // Buka quick settings atau airplane mode settings
                if (turnOn) {
                    // Untuk turn ON airplane mode
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
                    // Untuk turn OFF airplane mode
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
        // Hantar broadcast untuk restart CoreEngine
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

    private fun findButtonByDescription(rootNode: AccessibilityNodeInfo, description: String) {
        for (i in 0 until rootNode.childCount) {
            val child = rootNode.getChild(i)
            if (child != null) {
                if (child.contentDescription?.toString()?.contains(description, true) == true) {
                    child.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return
                }
                findButtonByDescription(child, description)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Untuk monitor events jika perlu
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
