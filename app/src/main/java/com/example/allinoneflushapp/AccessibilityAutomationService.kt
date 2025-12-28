// AccessibilityAutomationService.kt
package com.example.allinoneflushapp

import android.accessibilityservice.AccessibilityServiceInfo  // Import yang hilang!
import java.text.SimpleDateFormat  // Untuk SimpleDateFormat
import java.util.Locale  // Untuk Locale

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.*
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.util.*

class AccessibilityAutomationService : AccessibilityService() {

    companion object {
        const val TAG = "AccessibilityAutomation"
        private const val PACKAGE_PANDA = "com.logistics.rider.foodpanda" // Ganti dengan package name sebenar
        private const val ACTION_DO_ALL_JOB = "DO_ALL_JOB_TRIGGER"
        private const val ACTION_GPS_LOCK_ACHIEVED = "GPS_LOCK_ACHIEVED"
    }

    // Status & Flags
    private var isAutomationRunning = false
    private var isWaitingForGpsLock = false
    private var currentDnsMode: String? = null // "A" atau "B"
    private var retryCount = 0
    private val MAX_RETRIES = 2

    // Handler untuk delay dan retry
    private lateinit var handler: Handler
    private val automationRunnable = Runnable { startAutomationSequence() }

    // Floating Widget (untuk debug/log)
    private var floatingWindow: FrameLayout? = null
    private var logTextView: TextView? = null

    // Broadcast Receiver
    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_DO_ALL_JOB -> {
                    Log.i(TAG, "Received DO_ALL_JOB trigger")
                    if (isAutomationRunning) {
                        logToFloatingWidget("Automation sedang berjalan, skip trigger baru")
                        return
                    }
                    // Dapatkan DNS mode dari CoreEngine status
                    currentDnsMode = getCurrentDnsModeFromEngine()
                    startAutomationSequence()
                }
                ACTION_GPS_LOCK_ACHIEVED -> {
                    Log.i(TAG, "Received GPS_LOCK_ACHIEVED signal")
                    isWaitingForGpsLock = false
                    // Teruskan dengan langkah seterusnya selepas GPS lock
                    handler.post { performPostGpsLockActions() }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        handler = Handler(Looper.getMainLooper())
        setupBroadcastReceiver()
        showFloatingDebugWidget()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility Service Connected")
        logToFloatingWidget("Service Ready")
        
        // Configure service (berdasarkan original code)
        val config = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                   AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
        }
        this.serviceInfo = config
    }

    private fun setupBroadcastReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_DO_ALL_JOB)
            addAction(ACTION_GPS_LOCK_ACHIEVED)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(broadcastReceiver, filter)
        Log.d(TAG, "Broadcast receiver registered")
    }

    /**
     * URUTAN AUTOMASI UTAMA (tanpa VPN dependency)
     */
    private fun startAutomationSequence() {
        if (isAutomationRunning) {
            Log.w(TAG, "Automation sequence already running")
            return
        }

        Log.i(TAG, "=== STARTING AUTOMATION SEQUENCE ===")
        logToFloatingWidget("Start Automation")
        isAutomationRunning = true
        retryCount = 0

        // 1. FORCE CLOSE PANDA
        handler.postDelayed({
            if (isAppRunning(PACKAGE_PANDA)) {
                forceCloseApp(PACKAGE_PANDA)
                logToFloatingWidget("Force Close Panda")
            }
            clearAppCache(PACKAGE_PANDA)
        }, 500)

        // 2. TOGGLE AIRPLANE MODE (dengan delay)
        handler.postDelayed({
            toggleAirplaneMode(true)
            logToFloatingWidget("Airplane ON")
        }, 1500)

        handler.postDelayed({
            toggleAirplaneMode(false)
            logToFloatingWidget("Airplane OFF")
        }, 3500)

        // 3. TUNGGU GPS LOCK (CoreEngine akan hantar signal)
        handler.postDelayed({
            logToFloatingWidget("Waiting GPS Lock...")
            isWaitingForGpsLock = true
            // Timeout untuk GPS lock (30 saat)
            handler.postDelayed({
                if (isWaitingForGpsLock) {
                    Log.w(TAG, "GPS Lock timeout, continuing anyway")
                    isWaitingForGpsLock = false
                    performPostGpsLockActions()
                }
            }, 30000)
        }, 5000)
    }

    /**
     * LANGKAH SELEPAS GPS LOCK
     */
    private fun performPostGpsLockActions() {
        Log.i(TAG, "Performing post-GPS lock actions")
        
        // 4. LAUNCH PANDA APP
        handler.postDelayed({
            launchApp(PACKAGE_PANDA)
            logToFloatingWidget("Launching Panda")
        }, 1000)

        // 5. MONITOR PANDA LAUNCH & PERFORM SETUP JIKA PERLU
        handler.postDelayed({
            monitorPandaSetup()
        }, 3000)

        // 6. COMPLETION
        handler.postDelayed({
            isAutomationRunning = false
            logToFloatingWidget("Automation Complete")
            Log.i(TAG, "=== AUTOMATION SEQUENCE COMPLETE ===")
            
            // Hantar status completion ke MainActivity
            sendAutomationCompleteBroadcast()
        }, 8000)
    }

    /**
     * MONITOR PANDA SETUP (tap allow/continue jika ada popup)
     */
    private fun monitorPandaSetup() {
        handler.post(object : Runnable {
            override fun run() {
                val rootNode = rootInActiveWindow ?: return
                
                // Cari button Allow, Continue, OK, etc.
                val allowButtons = listOf("allow", "teruskan", "ok", "continue", "setuju")
                for (text in allowButtons) {
                    val nodes = rootNode.findAccessibilityNodeInfosByText(text)
                    if (nodes.isNotEmpty()) {
                        nodes[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        logToFloatingWidget("Clicked: $text")
                        break
                    }
                }
                
                // Check location permission popup
                val locationNodes = rootNode.findAccessibilityNodeInfosByText("location")
                if (locationNodes.isNotEmpty()) {
                    // Cari button Allow dalam dialog yang sama
                    val parent = locationNodes[0].parent
                    parent?.let {
                        val buttons = it.findAccessibilityNodeInfosByText("allow")
                        if (buttons.isNotEmpty()) {
                            buttons[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            logToFloatingWidget("Allowed location")
                        }
                    }
                }
                
                // Ulang check setiap 1 saat untuk 5 kali
                if (retryCount < 5) {
                    retryCount++
                    handler.postDelayed(this, 1000)
                }
            }
        })
    }

    /**
     * UTILITY FUNCTIONS (dari original code dengan penambahbaikan)
     */
    private fun isAppRunning(packageName: String): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val runningProcesses = activityManager.runningAppProcesses ?: return false
        return runningProcesses.any { it.processName == packageName }
    }

    private fun forceCloseApp(packageName: String) {
        val intent = Intent(Intent.ACTION_MAIN)
        intent.addCategory(Intent.CATEGORY_HOME)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        
        handler.postDelayed({
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            activityManager.killBackgroundProcesses(packageName)
            Log.i(TAG, "Force closed: $packageName")
        }, 300)
    }

    private fun clearAppCache(packageName: String) {
        try {
            val packageManager = packageManager
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            val clearcacheIntent = Intent(Intent.ACTION_DELETE)
            clearcacheIntent.data = android.net.Uri.parse("package:${applicationInfo.packageName}")
            clearcacheIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(clearcacheIntent)
            Log.i(TAG, "Cleared cache for: $packageName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear cache: ${e.message}")
        }
    }

    private fun toggleAirplaneMode(turnOn: Boolean) {
        try {
            val isAirplaneMode = Settings.Global.getInt(
                contentResolver,
                Settings.Global.AIRPLANE_MODE_ON
            ) != 0
            
            if (isAirplaneMode != turnOn) {
                Settings.Global.putInt(
                    contentResolver,
                    Settings.Global.AIRPLANE_MODE_ON,
                    if (turnOn) 1 else 0
                )
                
                // Broadcast perubahan
                val intent = Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED)
                intent.putExtra("state", turnOn)
                sendBroadcast(intent)
                Log.i(TAG, "Airplane mode ${if (turnOn) "ON" else "OFF"}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle airplane mode: ${e.message}")
        }
    }

    private fun launchApp(packageName: String) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(intent)
                Log.i(TAG, "Launched app: $packageName")
            } else {
                Log.e(TAG, "No launch intent for: $packageName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch app: ${e.message}")
        }
    }

    private fun getCurrentDnsModeFromEngine(): String? {
        // Method untuk dapatkan DNS mode dari CoreEngine
        // Boleh implement dengan SharedPreferences atau Broadcast
        return null // Temporary
    }

    private fun sendAutomationCompleteBroadcast() {
        val intent = Intent("AUTOMATION_COMPLETE")
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    /**
     * FLOATING DEBUG WIDGET (untuk monitor)
     */
    private fun showFloatingDebugWidget() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 50
                y = 100
            }

            val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
            floatingWindow = inflater.inflate(R.layout.floating_debug_widget, null) as FrameLayout
            logTextView = floatingWindow?.findViewById(R.id.debug_text)

            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            windowManager.addView(floatingWindow, layoutParams)
            logToFloatingWidget("Widget Ready")
        }
    }

    private fun logToFloatingWidget(message: String) {
        handler.post {
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val logMessage = "$timestamp: $message\n"
            logTextView?.append(logMessage)
            
            // Keep only last 10 lines
            val lines = logTextView?.text?.toString()?.lines()
            if (lines != null && lines.size > 10) {
                logTextView?.text = lines.takeLast(10).joinToString("\n")
            }
        }
    }

    /**
     * ACCESSIBILITY SERVICE OVERRIDES
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Untuk monitor events jika perlu
        event?.let {
            if (it.packageName == PACKAGE_PANDA && it.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                Log.d(TAG, "Panda window changed: ${it.className}")
            }
        }
    }

    override fun onInterrupt() {
        Log.i(TAG, "Service interrupted")
        logToFloatingWidget("Service Interrupted")
    }

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy")
        LocalBroadcastManager.getInstance(this).unregisterReceiver(broadcastReceiver)
        
        // Remove floating widget
        floatingWindow?.let {
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            windowManager.removeView(it)
        }
        
        handler.removeCallbacks(automationRunnable)
        super.onDestroy()
    }
}
