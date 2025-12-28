package com.example.cedokbooster

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class AccessibilityAutomationService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val pandaPackage = "com.logistics.rider.foodpanda"

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
        
        val filter = IntentFilter().apply {
            addAction(DO_ALL_JOB_TRIGGER)
            addAction(FORCE_CLOSE_PANDA)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(jobReceiver, filter)

        val gpsFilter = IntentFilter(AppCoreEngService.GPS_LOCK_ACHIEVED)
        LocalBroadcastManager.getInstance(this).registerReceiver(gpsLockReceiver, gpsFilter)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    private fun executeDoAllJobSequence(currentDns: String) {
        Log.d(TAG, "Starting DO ALL JOB sequence...")

        // Step 1: Force close Panda
        forceClosePanda()
        
        handler.postDelayed({
            // Step 2: Wait for GPS
            Log.d(TAG, "Waiting for GPS stabilization...")
        }, 3000)

        handler.postDelayed({
            // Step 3: Launch Panda
            launchPanda()
        }, 5000)

        handler.postDelayed({
            // Step 4: Restart CoreEngine
            restartCoreEngine(currentDns)
        }, 7000)
    }

    private fun forceClosePanda() {
        try {
            Log.d(TAG, "Force closing Panda app")
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.killBackgroundProcesses(pandaPackage)
            Log.d(TAG, "Panda force closed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error force closing Panda", e)
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
