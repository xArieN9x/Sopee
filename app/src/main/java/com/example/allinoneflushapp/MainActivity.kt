// MainActivity.kt - VERSI DIPERBAIKI & LENGKAP
package com.example.allinoneflushapp

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

    // Broadcast Receiver untuk updates dari CoreEngine
    private val coreEngineReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "Broadcast received: ${intent.action}")
            when (intent.action) {
                "CORE_ENGINE_STATUS_UPDATE" -> {
                    isCoreEngineRunning = intent.getBooleanExtra("is_running", false)
                    currentDnsServer = intent.getStringExtra("dns_server")
                    updateCoreEngineStatusDisplay()
                    updateButtonStates()
                    updateGpsIndicator()
                    Log.d(TAG, "Status Update: Running=$isCoreEngineRunning, DNS=$currentDnsServer")
                }
                "CORE_ENGINE_DNS_UPDATE" -> {
                    val dns = intent.getStringExtra("dns") ?: "Unknown"
                    val success = intent.getBooleanExtra("success", false)
                    val statusText = if (success) "Active ($dns)" else "Failed"
                    textViewCoreStatus.text = "CoreEngine: $statusText"
                    Log.d(TAG, "DNS Update: $dns, Success=$success")
                }
                "CORE_ENGINE_GPS_UPDATE" -> {
                    gpsStatus = intent.getStringExtra("gps_status") ?: "inactive"
                    isGpsLocked = intent.getBooleanExtra("gps_locked", false)
                    updateGpsIndicator()
                    Log.d(TAG, "GPS Update: Status=$gpsStatus, Locked=$isGpsLocked")
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
    }

    private fun initializeViews() {
        textViewIP = findViewById(R.id.textViewIP)
        textViewCoreStatus = findViewById(R.id.textViewDNS)
        btnDoAllJob = findViewById(R.id.btnDoAllJob)
        btnOnA = findViewById(R.id.btnOnA)
        btnOnB = findViewById(R.id.btnOnB)
        btnOnACS = findViewById(R.id.btnAccessibilityOn)
        btnOff = findViewById(R.id.btnForceCloseAll)
        networkIndicator = findViewById(R.id.networkIndicator)

        textViewCoreStatus.text = "CoreEngine: Disabled"
        Log.d(TAG, "Views initialized")
    }

    private fun setupClickListeners() {
        // BUTTON ON ACS (Accessibility) - FIXED
        btnOnACS.setOnClickListener {
            Log.d(TAG, "════════════════════════════════")
            Log.d(TAG, "ON ACS BUTTON CLICKED")
            
            if (isAccessibilityServiceEnabled()) {
                showToast("Accessibility Service sudah ON")
                Log.d(TAG, "Accessibility already enabled")
            } else {
                Log.d(TAG, "Opening accessibility settings...")
                try {
                    // FIX: Gunakan intent yang betul untuk Android
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    showToast("Sila enable Accessibility untuk CedokBooster")
                } catch (e: Exception) {
                    Log.e(TAG, "Cannot open accessibility settings: ${e.message}")
                    showToast("Buka Settings > Accessibility secara manual")
                }
            }
        }

        // BUTTON ON A - FIXED
        btnOnA.setOnClickListener {
            Log.d(TAG, "════════════════════════════════")
            Log.d(TAG, "ON A BUTTON CLICKED")
            
            if (!isAccessibilityServiceEnabled()) {
                Log.d(TAG, "Accessibility NOT enabled, blocking ON A")
                showToast("Sila ON kan Accessibility Service (ON ACS) dahulu!")
                return@setOnClickListener
            }
            
            Log.d(TAG, "Starting CoreEngine with DNS A...")
            startCoreEngine(AppCoreEngService.DNS_SERVER_A)
            showToast("Starting CoreEngine with DNS A...")
        }

        // BUTTON ON B - FIXED
        btnOnB.setOnClickListener {
            Log.d(TAG, "════════════════════════════════")
            Log.d(TAG, "ON B BUTTON CLICKED")
            
            if (!isAccessibilityServiceEnabled()) {
                Log.d(TAG, "Accessibility NOT enabled, blocking ON B")
                showToast("Sila ON kan Accessibility Service (ON ACS) dahulu!")
                return@setOnClickListener
            }
            
            Log.d(TAG, "Starting CoreEngine with DNS B...")
            startCoreEngine(AppCoreEngService.DNS_SERVER_B)
            showToast("Starting CoreEngine with DNS B...")
        }

        // BUTTON OFF - FIXED
        btnOff.setOnClickListener {
            Log.d(TAG, "════════════════════════════════")
            Log.d(TAG, "OFF BUTTON CLICKED")
            
            stopCoreEngine()
            showToast("Stopping CoreEngine...")
        }

        // BUTTON DO ALL JOB - FIXED
        btnDoAllJob.setOnClickListener {
            Log.d(TAG, "════════════════════════════════")
            Log.d(TAG, "DO ALL JOB BUTTON CLICKED")
            Log.d(TAG, "Accessibility enabled: ${isAccessibilityServiceEnabled()}")
            Log.d(TAG, "CoreEngine running: $isCoreEngineRunning")
            Log.d(TAG, "GPS locked: $isGpsLocked")

            if (!isAccessibilityServiceEnabled()) {
                Log.d(TAG, "Accessibility not enabled, blocking DAJ")
                showToast("Sila ON kan Accessibility Service dahulu!")
                return@setOnClickListener
            }

            if (!isCoreEngineRunning) {
                Log.d(TAG, "CoreEngine not running, blocking DAJ")
                showToast("CoreEngine belum aktif. Pilih ON A atau ON B dulu.")
                return@setOnClickListener
            }

            val dnsToUse = currentDnsServer ?: AppCoreEngService.DNS_SERVER_A
            Log.d(TAG, "DO ALL JOB with DNS: $dnsToUse")
            showToast("Refreshing dengan DNS: $dnsToUse")
            
            // 1. Trigger automasi
            triggerAccessibilityAutomation()
            
            // 2. Restart CoreEngine dengan DNS yang sama
            restartCoreEngine(dnsToUse)
        }
    }

    private fun startCoreEngine(dnsServer: String) {
        Log.d(TAG, "startCoreEngine() called with DNS: $dnsServer")
        
        val intent = Intent(this, AppCoreEngService::class.java).apply {
            action = AppCoreEngService.ACTION_START_ENGINE
            putExtra(AppCoreEngService.EXTRA_DNS_SERVER, dnsServer)
        }
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
                Log.d(TAG, "Started as foreground service")
            } else {
                startService(intent)
                Log.d(TAG, "Started as normal service")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start CoreEngine: ${e.message}")
            showToast("Failed to start CoreEngine")
        }
    }

    private fun stopCoreEngine() {
        Log.d(TAG, "stopCoreEngine() called")
        
        val intent = Intent(this, AppCoreEngService::class.java).apply {
            action = AppCoreEngService.ACTION_STOP_ENGINE
        }
        
        try {
            startService(intent)
            Log.d(TAG, "Stop command sent to CoreEngine")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop CoreEngine: ${e.message}")
        }
    }

    private fun restartCoreEngine(dnsServer: String) {
        Log.d(TAG, "restartCoreEngine() called with DNS: $dnsServer")
        
        stopCoreEngine()
        
        Handler(Looper.getMainLooper()).postDelayed({
            startCoreEngine(dnsServer)
            Log.d(TAG, "CoreEngine restarted after delay")
        }, 1000)
    }

    private fun triggerAccessibilityAutomation() {
        Log.d(TAG, "triggerAccessibilityAutomation() called")
        
        try {
            val intent = Intent("DO_ALL_JOB_TRIGGER")
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
            Log.d(TAG, "DO_ALL_JOB_TRIGGER broadcast sent")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send broadcast: ${e.message}")
        }
    }

    private fun setupBroadcastReceivers() {
        Log.d(TAG, "Setting up broadcast receivers")
        
        val filter = IntentFilter().apply {
            addAction("CORE_ENGINE_STATUS_UPDATE")
            addAction("CORE_ENGINE_DNS_UPDATE")
            addAction("CORE_ENGINE_GPS_UPDATE")
        }
        
        try {
            LocalBroadcastManager.getInstance(this)
                .registerReceiver(coreEngineReceiver, filter)
            Log.d(TAG, "Broadcast receivers registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register receivers: ${e.message}")
        }
    }

    private fun updateCoreEngineStatusDisplay() {
        val statusText = when {
            !isCoreEngineRunning -> "CoreEngine: Disabled"
            currentDnsServer != null -> "CoreEngine: Active ($currentDnsServer)"
            else -> "CoreEngine: Active"
        }
        textViewCoreStatus.text = statusText
        Log.d(TAG, "Status display updated: $statusText")
    }

    private fun updateGpsIndicator() {
        Log.d(TAG, "updateGpsIndicator() - isGpsLocked: $isGpsLocked")
        
        // FIX: Gunakan resource yang ADA (yellow_circle mungkin tak wujud)
        val drawableRes = when {
            !isCoreEngineRunning -> {
                Log.d(TAG, "Setting indicator: RED (engine not running)")
                R.drawable.red_circle
            }
            isGpsLocked -> {
                Log.d(TAG, "Setting indicator: GREEN (GPS locked)")
                R.drawable.green_circle
            }
            else -> {
                Log.d(TAG, "Setting indicator: RED (GPS not locked)")
                R.drawable.red_circle  // Ganti sementara dari yellow_circle
            }
        }
        
        try {
            networkIndicator.setImageResource(drawableRes)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update indicator: ${e.message}")
        }
    }

    private fun updateButtonStates() {
        Log.d(TAG, "updateButtonStates() - Engine running: $isCoreEngineRunning")
        
        btnOnA.isEnabled = !isCoreEngineRunning
        btnOnB.isEnabled = !isCoreEngineRunning
        btnOff.isEnabled = isCoreEngineRunning
        btnDoAllJob.isEnabled = isCoreEngineRunning && isGpsLocked
        
        // FIX: Gunakan warna hardcode sementara
        val colorEnabled = ContextCompat.getColor(this, android.R.color.holo_green_dark)
        val colorDisabled = ContextCompat.getColor(this, android.R.color.darker_gray)
        
        btnOnA.setBackgroundColor(if (!isCoreEngineRunning) colorEnabled else colorDisabled)
        btnOnB.setBackgroundColor(if (!isCoreEngineRunning) colorEnabled else colorDisabled)
        btnOff.setBackgroundColor(if (isCoreEngineRunning) colorEnabled else colorDisabled)
        btnDoAllJob.setBackgroundColor(if (isCoreEngineRunning && isGpsLocked) colorEnabled else colorDisabled)
        
        Log.d(TAG, "Buttons updated: ON_A=${btnOnA.isEnabled}, ON_B=${btnOnB.isEnabled}, OFF=${btnOff.isEnabled}, DAJ=${btnDoAllJob.isEnabled}")
    }

    private fun updatePublicIpDisplay() {
        Log.d(TAG, "updatePublicIpDisplay() called")
        
        Thread {
            try {
                val ip = URL("https://api.ipify.org").readText()
                Log.d(TAG, "Public IP fetched: $ip")
                runOnUiThread {
                    textViewIP.text = "Public IP: $ip"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch IP: ${e.message}")
                runOnUiThread {
                    textViewIP.text = "Public IP: Unable to fetch"
                }
            }
        }.start()
    }

    private fun updateNetworkIndicator() {
        Log.d(TAG, "updateNetworkIndicator() called")
        
        try {
            val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val hasInternet = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            } else {
                true  // Assume ada internet untuk older versions
            }
            
            Log.d(TAG, "Internet connection: $hasInternet")
            networkIndicator.setImageResource(
                if (hasInternet) R.drawable.green_circle else R.drawable.red_circle
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update network indicator: ${e.message}")
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = "${packageName}/${AccessibilityAutomationService::class.java.canonicalName}"
        Log.d(TAG, "Checking accessibility service: $serviceName")
        
        return try {
            val enabled = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            
            val isEnabled = enabled.contains(serviceName)
            Log.d(TAG, "Accessibility service enabled: $isEnabled")
            isEnabled
        } catch (e: Exception) {
            Log.e(TAG, "Error checking accessibility: ${e.message}")
            false
        }
    }

    private fun checkAndRequestAccessibilityPermission() {
        Log.d(TAG, "checkAndRequestAccessibilityPermission() called")
        
        if (!isAccessibilityServiceEnabled()) {
            Log.d(TAG, "Accessibility not enabled, showing toast")
            showToast("Accessibility Service diperlukan. Sila tekan ON ACS.")
        } else {
            Log.d(TAG, "Accessibility already enabled")
        }
    }

    private fun showToast(message: String) {
        Log.d(TAG, "Showing toast: $message")
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "MainActivity onResume")
        
        updateNetworkIndicator()
        updateButtonStates()
        checkAndRequestAccessibilityPermission()
    }

    override fun onDestroy() {
        Log.d(TAG, "MainActivity onDestroy")
        
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(coreEngineReceiver)
            Log.d(TAG, "Broadcast receivers unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receivers: ${e.message}")
        }
        
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
