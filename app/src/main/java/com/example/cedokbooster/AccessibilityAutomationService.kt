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
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class AccessibilityAutomationService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val pandaPackage = "com.foodpanda.android"

    companion object {
        private const val TAG = "AccessibilityAutomation"
        const val DO_ALL_JOB_TRIGGER = "com.example.cedokbooster.DO_ALL_JOB"
        const val FORCE_CLOSE_PANDA = "com.example.cedokbooster.FORCE_CLOSE_PANDA"
    }

    private val jobReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                DO_ALL_JOB_TRIGGER -> {
                    val currentDns = intent.getStringExtra("currentDns") ?: "A"
                    Log.d(TAG, "DO_ALL_JOB_TRIGGER received, DNS: $currentDns")
                    executeDoAllJobSequence(currentDns)
                }
                FORCE_CLOSE_PANDA -> {
                    Log.d(TAG, "FORCE_CLOSE_PANDA received")
                    forceClosePanda()
                }
            }
        }
    }

    private val gpsLockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AppCoreEngService.GPS_LOCK_ACHIEVED -> {
                    Log.d(TAG, "GPS_LOCK_ACHIEVED received, launching Panda")
                    handler.postDelayed({
                        launchPanda()
                    }, 2000)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "AccessibilityAutomationService created")
        
        // Register receivers
        val filter = IntentFilter().apply {
            addAction(DO_ALL_JOB_TRIGGER)
            addAction(FORCE_CLOSE_PANDA)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(jobReceiver, filter)

        val gpsFilter = IntentFilter(AppCoreEngService.GPS_LOCK_ACHIEVED)
        LocalBroadcastManager.getInstance(this).registerReceiver(gpsLockReceiver, gpsFilter)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Can be used for monitoring if needed
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    private fun executeDoAllJobSequence(currentDns: String) {
        Log.d(TAG, "Starting DO ALL JOB sequence...")

        // Step 1: Force close Panda
        forceClosePanda()
        
        handler.postDelayed({
            // Step 2: Clear cache
            clearPandaCache()
        }, 1000)

        handler.postDelayed({
            // Step 3: Toggle airplane mode
            toggleAirplaneMode()
        }, 2000)

        handler.postDelayed({
            // Step 4: Wait for network/GPS
            Log.d(TAG, "Waiting for network and GPS stabilization...")
        }, 5000)

        handler.postDelayed({
            // Step 5: Launch Panda
            launchPanda()
        }, 8000)

        handler.postDelayed({
            // Step 6: Restart CoreEngine with same DNS
            restartCoreEngine(currentDns)
        }, 10000)
    }

    private fun forceClosePanda() {
        try {
            Log.d(TAG, "Force closing Panda app")
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.killBackgroundProcesses(pandaPackage)
            
            // Alternative method using shell command (requires FORCE_STOP permission)
            // This won't work without root, but we try anyway
            try {
                Runtime.getRuntime().exec("am force-stop $pandaPackage")
            } catch (e: Exception) {
                Log.e(TAG, "Shell force-stop failed (expected on non-root)", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error force closing Panda", e)
        }
    }

    private fun clearPandaCache() {
        try {
            Log.d(TAG, "Clearing Panda cache")
            // This requires root or specific permissions
            // Without root, we can only clear our own app's cache
            // This is a placeholder
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:$pandaPackage")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // We don't actually start this as it would interrupt the flow
            // Instead, we just log it
            Log.d(TAG, "Cache clear simulated (requires manual action or root)")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing cache", e)
        }
    }

    private fun toggleAirplaneMode() {
        try {
            Log.d(TAG, "Toggling airplane mode")
            
            // Note: Changing airplane mode programmatically is restricted since Android 4.2
            // This requires WRITE_SETTINGS permission and may not work on all devices
            
            val isAirplaneModeOn = Settings.Global.getInt(
                contentResolver,
                Settings.Global.AIRPLANE_MODE_ON, 0
            ) != 0

            // Toggle it
            Settings.Global.putInt(
                contentResolver,
                Settings.Global.AIRPLANE_MODE_ON,
                if (isAirplaneModeOn) 0 else 1
            )

            // Broadcast the change
            val intent = Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            intent.putExtra("state", !isAirplaneModeOn)
            sendBroadcast(intent)

            // Toggle back after 2 seconds
            handler.postDelayed({
                Settings.Global.putInt(
                    contentResolver,
                    Settings.Global.AIRPLANE_MODE_ON,
                    if (isAirplaneModeOn) 1 else 0
                )
                val intent2 = Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED)
                intent2.putExtra("state", isAirplaneModeOn)
                sendBroadcast(intent2)
                Log.d(TAG, "Airplane mode toggled back")
            }, 2000)

        } catch (e: Exception) {
            Log.e(TAG, "Airplane mode toggle failed (requires permission)", e)
        }
    }

    private fun launchPanda() {
        try {
            Log.d(TAG, "Launching Panda app")
            val launchIntent = packageManager.getLaunchIntentForPackage(pandaPackage)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                Log.d(TAG, "Panda launched successfully")
            } else {
                Log.e(TAG, "Launch intent for Panda not found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error launching Panda", e)
        }
    }

    private fun restartCoreEngine(dnsType: String) {
        Log.d(TAG, "Restarting CoreEngine with DNS: $dnsType")
        
        // Stop current engine
        val stopIntent = Intent(this, AppCoreEngService::class.java).apply {
            action = AppCoreEngService.ACTION_STOP_ENGINE
        }
        startService(stopIntent)

        // Start new engine after 1 second
        handler.postDelayed({
            val startIntent = Intent(this, AppCoreEngService::class.java).apply {
                action = AppCoreEngService.ACTION_START_ENGINE
                putExtra("dnsType", dnsType)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(startIntent)
            } else {
                startService(startIntent)
            }
            Log.d(TAG, "CoreEngine restarted")
        }, 1000)
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(jobReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(gpsLockReceiver)
        Log.d(TAG, "AccessibilityAutomationService destroyed")
    }
}
