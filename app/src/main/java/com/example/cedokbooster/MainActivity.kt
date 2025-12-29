package com.example.cedokbooster

import android.content.*
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import android.util.Log
import java.net.URL

class MainActivity : AppCompatActivity() {

    // UI Components
    private lateinit var textViewIP: TextView
    private lateinit var textViewCoreStatus: TextView
    private lateinit var btnDoAllJob: Button
    private lateinit var btnOnA: Button
    private lateinit var btnOnB: Button
    private lateinit var btnOnACS: Button
    private lateinit var btnOff: Button
    private lateinit var networkIndicator: ImageView

    // Status
    private var isCoreEngineRunning = false
    private var currentDnsServer: String? = null
    private var isGpsLocked = false
    private var gpsStatus: String = "inactive"

    // Broadcast Receiver untuk updates dari CoreEngine - IMPROVED
    private val coreEngineReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "════════════════════════════════")
            Log.d(TAG, "Broadcast received: ${intent.action}")
            
            when (intent.action) {
                "CORE_ENGINE_STATUS_UPDATE" -> {
                    isCoreEngineRunning = intent.getBooleanExtra("is_running", false)
                    currentDnsServer = intent.getStringExtra("dns_server")
                    Log.d(TAG, "Status Update: Running=$isCoreEngineRunning, DNS=$currentDnsServer")
                    
                    runOnUiThread {
                        updateCoreEngineStatusDisplay()
                        updateButtonStates()
                        updateGpsIndicator()
                        updateNetworkIndicator() // Also update network indicator
                    }
                }
                "CORE_ENGINE_DNS_UPDATE" -> {
                    val dns = intent.getStringExtra("dns") ?: "Unknown"
                    val success = intent.getBooleanExtra("success", false)
                    val statusText = if (success) "Active ($dns)" else "Failed"
                    Log.d(TAG, "DNS Update: $dns, Success=$success")
                    
                    runOnUiThread {
                        textViewCoreStatus.text = "CoreEngine: $statusText"
                    }
                }
                "CORE_ENGINE_GPS_UPDATE" -> {
                    gpsStatus = intent.getStringExtra("gps_status") ?: "inactive"
                    isGpsLocked = intent.getBooleanExtra("gps_locked", false)
                    Log.d(TAG, "GPS Update: Status=$gpsStatus, Locked=$isGpsLocked")
                    
                    runOnUiThread {
                        updateGpsIndicator()
                        updateButtonStates() // Enable/disable DAJ based on GPS lock
                    }
                }
                // NEW: Direct status request from FloatingWidgetService
                "CHECK_ENGINE_STATUS" -> {
                    // Send current status back
                    val statusIntent = Intent("CORE_ENGINE_STATUS_UPDATE").apply {
                        putExtra("is_running", isCoreEngineRunning)
                        putExtra("dns_server", currentDnsServer)
                    }
                    LocalBroadcastManager.getInstance(this@MainActivity).sendBroadcast(statusIntent)
                    Log.d(TAG, "Sent engine status to widget")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        Log.d(TAG, "════════════════════════════════")
        Log.d(TAG, "MainActivity onCreate")
        Log.d(TAG, "Package: $packageName")

        initializeViews()
        setupClickListeners()
        setupBroadcastReceivers()
        updatePublicIpDisplay()
        checkAndRequestAccessibilityPermission()
        updateNetworkIndicator()
        updateButtonStates()
        
        // Start FloatingWidgetService
        startFloatingWidgetService()
    }

    private fun startFloatingWidgetService() {
        Log.d(TAG, "Starting FloatingWidgetService...")
        try {
            val intent = Intent(this, FloatingWidgetService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Log.d(TAG, "FloatingWidgetService started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start FloatingWidgetService: ${e.message}")
        }
    }

    // REST OF THE CODE REMAINS THE SAME AS YOUR VERSION
    // Only changed the broadcast receiver part above
    
    // ... [ALL YOUR EXISTING CODE FOR initializeViews, setupClickListeners, etc.]
    // KEEP EVERYTHING ELSE EXACTLY AS YOU HAVE IT
    
    // Add this new method for better status sync
    private fun syncEngineStatus() {
        // Send current status to all components
        val statusIntent = Intent("CORE_ENGINE_STATUS_UPDATE").apply {
            putExtra("is_running", isCoreEngineRunning)
            putExtra("dns_server", currentDnsServer)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(statusIntent)
        Log.d(TAG, "Manual status sync sent")
    }
    
    override fun onResume() {
        super.onResume()
        Log.d(TAG, "MainActivity onResume")
        
        // Sync status when activity resumes (e.g., coming back from widget click)
        syncEngineStatus()
        
        updateNetworkIndicator()
        updateButtonStates()
        checkAndRequestAccessibilityPermission()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
