package com.example.cedokbooster

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var tvPublicIp: TextView
    private lateinit var tvCoreEngineStatus: TextView
    private lateinit var viewIndicator: View
    private lateinit var btnDoAllJob: Button
    private lateinit var btnOnA: Button
    private lateinit var btnOnAcs: Button
    private lateinit var btnOnB: Button
    private lateinit var btnOff: Button

    private var currentDns: String = "none"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        private const val TAG = "MainActivity"
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AppCoreEngService.CORE_ENGINE_STATUS_UPDATE -> {
                    val isRunning = intent.getBooleanExtra("isRunning", false)
                    val dns = intent.getStringExtra("dns") ?: "none"
                    val gpsStatus = intent.getStringExtra("gpsStatus") ?: "idle"
                    
                    updateUIStatus(isRunning, dns, gpsStatus)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupButtons()
        fetchPublicIp()
        requestOverlayPermission()

        val filter = IntentFilter(AppCoreEngService.CORE_ENGINE_STATUS_UPDATE)
        LocalBroadcastManager.getInstance(this).registerReceiver(statusReceiver, filter)
    }

    private fun initViews() {
        tvPublicIp = findViewById(R.id.tvPublicIp)
        tvCoreEngineStatus = findViewById(R.id.tvCoreEngineStatus)
        viewIndicator = findViewById(R.id.viewIndicator)
        btnDoAllJob = findViewById(R.id.btnDoAllJob)
        btnOnA = findViewById(R.id.btnOnA)
        btnOnAcs = findViewById(R.id.btnOnAcs)
        btnOnB = findViewById(R.id.btnOnB)
        btnOff = findViewById(R.id.btnOff)
    }

    private fun setupButtons() {
        btnOnAcs.setOnClickListener {
            Log.d(TAG, "ON ACS BUTTON CLICKED")
            openAccessibilitySettings()
        }

        btnOnA.setOnClickListener {
            Log.d(TAG, "ON A BUTTON CLICKED")
            if (!isAccessibilityEnabled()) {
                Toast.makeText(this, "Sila enable Accessibility Service dulu!", Toast.LENGTH_LONG).show()
                openAccessibilitySettings()
                return@setOnClickListener
            }
            startCoreEngine("A")
        }

        btnOnB.setOnClickListener {
            Log.d(TAG, "ON B BUTTON CLICKED")
            if (!isAccessibilityEnabled()) {
                Toast.makeText(this, "Sila enable Accessibility Service dulu!", Toast.LENGTH_LONG).show()
                openAccessibilitySettings()
                return@setOnClickListener
            }
            startCoreEngine("B")
        }

        btnDoAllJob.setOnClickListener {
            Log.d(TAG, "DO ALL JOB BUTTON CLICKED")
            if (!isAccessibilityEnabled()) {
                Toast.makeText(this, "Accessibility Service tak enabled!", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            
            if (currentDns == "none") {
                Toast.makeText(this, "CoreEngine tidak aktif! Tekan ON A atau ON B dulu.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            triggerDoAllJob()
        }

        btnOff.setOnClickListener {
            Log.d(TAG, "OFF BUTTON CLICKED")
            stopCoreEngine()
        }
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
        Toast.makeText(this, "Cari 'CedokBooster' dan enable", Toast.LENGTH_LONG).show()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expectedServiceName = "$packageName/${AccessibilityAutomationService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return enabledServices?.contains(expectedServiceName) == true
    }

    private fun startCoreEngine(dnsType: String) {
        currentDns = dnsType
        val intent = Intent(this, AppCoreEngService::class.java).apply {
            action = AppCoreEngService.ACTION_START_ENGINE
            putExtra("dnsType", dnsType)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        Toast.makeText(this, "CoreEngine starting dengan DNS $dnsType...", Toast.LENGTH_SHORT).show()
    }

    private fun stopCoreEngine() {
        val intent = Intent(this, AppCoreEngService::class.java).apply {
            action = AppCoreEngService.ACTION_STOP_ENGINE
        }
        startService(intent)
        
        currentDns = "none"
        updateUIStatus(false, "none", "idle")
        
        Toast.makeText(this, "CoreEngine stopped", Toast.LENGTH_SHORT).show()
    }

    private fun triggerDoAllJob() {
        val intent = Intent(AccessibilityAutomationService.DO_ALL_JOB_TRIGGER).apply {
            putExtra("currentDns", currentDns)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        Toast.makeText(this, "DO ALL JOB triggered!", Toast.LENGTH_SHORT).show()
    }

    private fun updateUIStatus(isRunning: Boolean, dns: String, gpsStatus: String) {
        runOnUiThread {
            tvCoreEngineStatus.text = if (isRunning) {
                "CoreEngine: Active (DNS: $dns) | GPS: $gpsStatus"
            } else {
                "CoreEngine: Disabled"
            }

            when {
                !isRunning -> {
                    viewIndicator.setBackgroundResource(R.drawable.red_circle)
                }
                gpsStatus == "stabilizing" -> {
                    viewIndicator.setBackgroundResource(R.drawable.yellow_circle)
                }
                gpsStatus == "locked" -> {
                    viewIndicator.setBackgroundResource(R.drawable.green_circle)
                }
                else -> {
                    viewIndicator.setBackgroundResource(R.drawable.yellow_circle)
                }
            }
        }
    }

    private fun fetchPublicIp() {
        CoroutineScope(Dispatchers.IO).launch {
            var ip: String? = null
            try {
                val url = URL("https://1.1.1.1/cdn-cgi/trace")
                val text = url.readText().trim()
                // Format: ip=123.123.123.123
                val ipLine = text.lines().find { it.startsWith("ip=") }
                ip = ipLine?.substringAfter("=")?.trim()
            } catch (e1: Exception) {
                // Fallback to ipify jika 1.1.1.1 gagal
                try {
                    ip = URL("https://api.ipify.org").readText().trim()
                } catch (e2: Exception) {
                    ip = null
                }
            }
            withContext(Dispatchers.Main) {
                textViewIP.text = if (ip.isNullOrEmpty()) "Public IP: —" else "Public IP: $ip"
            }
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
                Toast.makeText(this, "Sila enable overlay permission untuk floating widget", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver)
    }
}
