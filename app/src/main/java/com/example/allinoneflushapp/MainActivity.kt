// MainActivity.kt
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
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class MainActivity : AppCompatActivity() {

    // UI Components
    private lateinit var textViewIP: TextView
    private lateinit var textViewCoreStatus: TextView
    private lateinit var btnDoAllJob: Button
    private lateinit var btnOnA: Button
    private lateinit var btnOnB: Button
    private lateinit var btnOnACS: Button
    private lateinit var btnOff: Button

    // Status
    private var isCoreEngineRunning = false
    private var currentDnsServer: String? = null
    private var isGpsLocked = false
    private var gpsStatus: String = "inactive"

    // Broadcast Receiver untuk updates dari CoreEngine
    private val coreEngineReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                "CORE_ENGINE_STATUS_UPDATE" -> {
                    isCoreEngineRunning = intent.getBooleanExtra("is_running", false)
                    currentDnsServer = intent.getStringExtra("dns_server")
                    updateCoreEngineStatusDisplay()
                    updateButtonStates()
                    Log.d(TAG, "Status Update: Running=$isCoreEngineRunning, DNS=$currentDnsServer")
                }
                "CORE_ENGINE_DNS_UPDATE" -> {
                    val dns = intent.getStringExtra("dns") ?: "Unknown"
                    val success = intent.getBooleanExtra("success", false)
                    textViewCoreStatus.text = "CoreEngine: ${if (success) "Active ($dns)" else "Failed"}"
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

        initializeViews()
        setupClickListeners()
        setupBroadcastReceivers()
        updatePublicIpDisplay()
        checkAndRequestAccessibilityPermission()
        updateNetworkIndicator()
    }

    private fun initializeViews() {
        textViewIP = findViewById(R.id.textViewIP)
        textViewCoreStatus = findViewById(R.id.textViewDNS) // ID masih textViewDNS dari XML
        btnDoAllJob = findViewById(R.id.btnDoAllJob)
        btnOnA = findViewById(R.id.btnOnA)
        btnOnB = findViewById(R.id.btnOnB)
        btnOnACS = findViewById(R.id.btnAccessibilityOn) // ID asal
        btnOff = findViewById(R.id.btnForceCloseAll) // ID asal

        // Set text awal
        textViewCoreStatus.text = "CoreEngine: Disabled"
    }

    private fun setupClickListeners() {
        // BUTTON ON A
        btnOnA.setOnClickListener {
            if (!isAccessibilityServiceEnabled()) {
                showToast("Sila ON kan Accessibility Service (ON ACS) dahulu!")
                return@setOnClickListener
            }
            startCoreEngine(AppCoreEngService.DNS_SERVER_A)
            showToast("Starting CoreEngine with DNS A...")
        }

        // BUTTON ON B
        btnOnB.setOnClickListener {
            if (!isAccessibilityServiceEnabled()) {
                showToast("Sila ON kan Accessibility Service (ON ACS) dahulu!")
                return@setOnClickListener
            }
            startCoreEngine(AppCoreEngService.DNS_SERVER_B)
            showToast("Starting CoreEngine with DNS B...")
        }

        // BUTTON ON ACS (Accessibility)
        btnOnACS.setOnClickListener {
            if (isAccessibilityServiceEnabled()) {
                showToast("Accessibility Service sudah ON")
            } else {
                openAccessibilitySettings()
                showToast("Sila enable Accessibility Service untuk CedokBooster")
            }
        }

        // BUTTON OFF
        btnOff.setOnClickListener {
            stopCoreEngine()
            showToast("Stopping CoreEngine...")
        }

        // BUTTON DO ALL JOB
        btnDoAllJob.setOnClickListener {
            if (!isAccessibilityServiceEnabled()) {
                showToast("Sila ON kan Accessibility Service dahulu!")
                return@setOnClickListener
            }

            if (!isCoreEngineRunning) {
                showToast("CoreEngine belum aktif. Pilih ON A atau ON B dulu.")
                return@setOnClickListener
            }

            // DO ALL JOB: Ulangi proses dengan DNS yang sama
            val dnsToUse = currentDnsServer ?: AppCoreEngService.DNS_SERVER_A
            showToast("Refreshing dengan DNS: $dnsToUse")
            
            // 1. Hantar signal ke Accessibility Service untuk buat automasi
            triggerAccessibilityAutomation()
            
            // 2. Restart CoreEngine dengan DNS yang sama (refresh sockets)
            restartCoreEngine(dnsToUse)
        }
    }

    private fun startCoreEngine(dnsServer: String) {
        val intent = Intent(this, AppCoreEngService::class.java).apply {
            action = AppCoreEngService.ACTION_START_ENGINE
            putExtra(AppCoreEngService.EXTRA_DNS_SERVER, dnsServer)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopCoreEngine() {
        val intent = Intent(this, AppCoreEngService::class.java).apply {
            action = AppCoreEngService.ACTION_STOP_ENGINE
        }
        startService(intent)
    }

    private fun restartCoreEngine(dnsServer: String) {
        // Hentikan dulu, kemudian start semula
        stopCoreEngine()
        
        Handler(Looper.getMainLooper()).postDelayed({
            startCoreEngine(dnsServer)
        }, 1000) // Delay 1 saat
    }

    private fun triggerAccessibilityAutomation() {
        // Hantar broadcast ke AccessibilityAutomationService
        val intent = Intent("DO_ALL_JOB_TRIGGER")
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        Log.d(TAG, "Sent DO_ALL_JOB_TRIGGER to Accessibility Service")
    }

    private fun setupBroadcastReceivers() {
        val filter = IntentFilter().apply {
            addAction("CORE_ENGINE_STATUS_UPDATE")
            addAction("CORE_ENGINE_DNS_UPDATE")
            addAction("CORE_ENGINE_GPS_UPDATE")
        }
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(coreEngineReceiver, filter)
    }

    private fun updateCoreEngineStatusDisplay() {
        val statusText = when {
            !isCoreEngineRunning -> "CoreEngine: Disabled"
            currentDnsServer != null -> "CoreEngine: Active ($currentDnsServer)"
            else -> "CoreEngine: Active"
        }
        textViewCoreStatus.text = statusText
    }

    private fun updateGpsIndicator() {
        // Update UI indicator berdasarkan GPS status
        val indicator = findViewById<android.widget.ImageView>(R.id.networkIndicator)
        when {
            !isCoreEngineRunning -> indicator.setImageResource(R.drawable.red_circle)
            isGpsLocked -> indicator.setImageResource(R.drawable.green_circle)
            else -> indicator.setImageResource(R.drawable.yellow_circle) // GPS sedang stabilizing
        }
    }

    private fun updateButtonStates() {
        // Enable/disable buttons berdasarkan state
        val engineRunning = isCoreEngineRunning
        
        btnOnA.isEnabled = !engineRunning
        btnOnB.isEnabled = !engineRunning
        btnOff.isEnabled = engineRunning
        btnDoAllJob.isEnabled = engineRunning && isGpsLocked
        
        // Update button colors
        val colorEnabled = ContextCompat.getColor(this, R.color.button_enabled)
        val colorDisabled = ContextCompat.getColor(this, R.color.button_disabled)
        
        btnOnA.setBackgroundColor(if (!engineRunning) colorEnabled else colorDisabled)
        btnOnB.setBackgroundColor(if (!engineRunning) colorEnabled else colorDisabled)
        btnOff.setBackgroundColor(if (engineRunning) colorEnabled else colorDisabled)
    }

    private fun updatePublicIpDisplay() {
        // Fetch public IP dalam background thread
        Thread {
            try {
                val ip = java.net.URL("https://api.ipify.org").readText()
                runOnUiThread {
                    textViewIP.text = "Public IP: $ip"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    textViewIP.text = "Public IP: Unable to fetch"
                }
            }
        }.start()
    }

    private fun updateNetworkIndicator() {
        val indicator = findViewById<android.widget.ImageView>(R.id.networkIndicator)
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            
            runOnUiThread {
                indicator.setImageResource(
                    if (hasInternet) R.drawable.green_circle else R.drawable.red_circle
                )
            }
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = packageName + "/" + AccessibilityAutomationService::class.java.canonicalName
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return enabled?.contains(service) == true
    }

    private fun checkAndRequestAccessibilityPermission() {
        if (!isAccessibilityServiceEnabled()) {
            showToast("Accessibility Service diperlukan. Sila tekan ON ACS.")
        }
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        updateNetworkIndicator()
        updateButtonStates()
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(coreEngineReceiver)
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
