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

    // Broadcast Receiver
    private val coreEngineReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
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
                    }
                }
                "CORE_ENGINE_DNS_UPDATE" -> {
                    val dns = intent.getStringExtra("dns") ?: "Unknown"
                    val success = intent.getBooleanExtra("success", false)
                    val statusText = if (success) "Active ($dns)" else "Failed"
                    
                    runOnUiThread {
                        textViewCoreStatus.text = "CoreEngine: $statusText"
                    }
                    Log.d(TAG, "DNS Update: $dns, Success=$success")
                }
                "CORE_ENGINE_GPS_UPDATE" -> {
                    gpsStatus = intent.getStringExtra("gps_status") ?: "inactive"
                    isGpsLocked = intent.getBooleanExtra("gps_locked", false)
                    
                    runOnUiThread {
                        updateGpsIndicator()
                        updateButtonStates()
                    }
                    Log.d(TAG, "GPS Update: Status=$gpsStatus, Locked=$isGpsLocked")
                }
                "CHECK_ENGINE_STATUS" -> {
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
        
        Log.d(TAG, "MainActivity onCreate")
        Log.d(TAG, "Package: $packageName")

        // Initialize views
        textViewIP = findViewById(R.id.textViewIP)
        textViewCoreStatus = findViewById(R.id.textViewDNS)
        btnDoAllJob = findViewById(R.id.btnDoAllJob)
        btnOnA = findViewById(R.id.btnOnA)
        btnOnB = findViewById(R.id.btnOnB)
        btnOnACS = findViewById(R.id.btnAccessibilityOn)
        btnOff = findViewById(R.id.btnForceCloseAll)
        networkIndicator = findViewById(R.id.networkIndicator)

        textViewCoreStatus.text = "CoreEngine: Disabled"
        
        // Setup click listeners
        setupClickListeners()
        
        // Setup broadcast receivers
        val filter = IntentFilter().apply {
            addAction("CORE_ENGINE_STATUS_UPDATE")
            addAction("CORE_ENGINE_DNS_UPDATE")
            addAction("CORE_ENGINE_GPS_UPDATE")
            addAction("CHECK_ENGINE_STATUS")
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(coreEngineReceiver, filter)
        
        // Initial updates
        updatePublicIpDisplay()
        updateNetworkIndicator()
        updateButtonStates()
        
        // Start FloatingWidgetService
        startFloatingWidgetService()
    }

    private fun setupClickListeners() {
        btnOnACS.setOnClickListener {
            Log.d(TAG, "ON ACS BUTTON CLICKED")
            
            if (isAccessibilityServiceEnabled()) {
                showToast("Accessibility Service sudah ON")
            } else {
                try {
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

        btnOnA.setOnClickListener {
            Log.d(TAG, "ON A BUTTON CLICKED")
            
            if (!isAccessibilityServiceEnabled()) {
                showToast("Sila ON kan Accessibility Service (ON ACS) dahulu!")
                return@setOnClickListener
            }
            
            startCoreEngine(AppCoreEngService.DNS_SERVER_A)
            showToast("Starting CoreEngine with DNS A...")
        }

        btnOnB.setOnClickListener {
            Log.d(TAG, "ON B BUTTON CLICKED")
            
            if (!isAccessibilityServiceEnabled()) {
                showToast("Sila ON kan Accessibility Service (ON ACS) dahulu!")
                return@setOnClickListener
            }
            
            startCoreEngine(AppCoreEngService.DNS_SERVER_B)
            showToast("Starting CoreEngine with DNS B...")
        }

        btnOff.setOnClickListener {
            Log.d(TAG, "OFF BUTTON CLICKED")
            stopCoreEngine()
            showToast("Stopping CoreEngine...")
        }

        btnDoAllJob.setOnClickListener {
            Log.d(TAG, "DO ALL JOB BUTTON CLICKED")
            
            if (!isAccessibilityServiceEnabled()) {
                showToast("Sila ON kan Accessibility Service dahulu!")
                return@setOnClickListener
            }

            if (!isCoreEngineRunning) {
                showToast("CoreEngine belum aktif. Pilih ON A atau ON B dulu.")
                return@setOnClickListener
            }

            val dnsToUse = currentDnsServer ?: AppCoreEngService.DNS_SERVER_A
            showToast("Refreshing dengan DNS: $dnsToUse")
            
            // Trigger automation
            val intent = Intent("DO_ALL_JOB_TRIGGER")
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
            Log.d(TAG, "DO_ALL_JOB_TRIGGER broadcast sent")
            
            // Restart CoreEngine
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
            } else {
                startService(intent)
            }
            Log.d(TAG, "CoreEngine start command sent")
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
            Log.d(TAG, "CoreEngine stop command sent")
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
        val drawableRes = when {
            !isCoreEngineRunning -> R.drawable.red_circle
            isGpsLocked -> R.drawable.green_circle
            else -> R.drawable.red_circle
        }
        
        try {
            networkIndicator.setImageResource(drawableRes)
            Log.d(TAG, "GPS indicator updated: ${if (isGpsLocked) "GREEN" else "RED"}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update indicator: ${e.message}")
        }
    }

    private fun updateButtonStates() {
        btnOnA.isEnabled = !isCoreEngineRunning
        btnOnB.isEnabled = !isCoreEngineRunning
        btnOff.isEnabled = isCoreEngineRunning
        btnDoAllJob.isEnabled = isCoreEngineRunning && isGpsLocked
        
        val colorEnabled = ContextCompat.getColor(this, android.R.color.holo_green_dark)
        val colorDisabled = ContextCompat.getColor(this, android.R.color.darker_gray)
        
        btnOnA.setBackgroundColor(if (!isCoreEngineRunning) colorEnabled else colorDisabled)
        btnOnB.setBackgroundColor(if (!isCoreEngineRunning) colorEnabled else colorDisabled)
        btnOff.setBackgroundColor(if (isCoreEngineRunning) colorEnabled else colorDisabled)
        btnDoAllJob.setBackgroundColor(if (isCoreEngineRunning && isGpsLocked) colorEnabled else colorDisabled)
        
        Log.d(TAG, "Buttons updated: ON_A=${btnOnA.isEnabled}, ON_B=${btnOnB.isEnabled}, OFF=${btnOff.isEnabled}, DAJ=${btnDoAllJob.isEnabled}")
    }

    private fun updatePublicIpDisplay() {
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
        try {
            val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val hasInternet = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            } else {
                true
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
        if (!isAccessibilityServiceEnabled()) {
            showToast("Accessibility Service diperlukan. Sila tekan ON ACS.")
        }
    }

    private fun showToast(message: String) {
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
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver: ${e.message}")
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
