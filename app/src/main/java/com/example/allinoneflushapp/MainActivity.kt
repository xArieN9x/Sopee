package com.example.allinoneflushapp

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var textViewIP: TextView
    private lateinit var textViewDNS: TextView
    private lateinit var networkIndicator: ImageView
    private lateinit var btnDoAllJob: Button
    private lateinit var btnStartVpn: Button

    private val pandaPackage = "com.logistics.rider.foodpanda"
    private val dnsList = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9", "9.9.9.10")

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(this, "Setting up VPN...", Toast.LENGTH_SHORT).show()
            startService(Intent(this, AppMonitorVPNService::class.java))
            AppMonitorVPNService.rotateDNS(dnsList)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGlobals.applicationContext = application
        setContentView(R.layout.activity_main)

        textViewIP = findViewById(R.id.textViewIP)
        textViewDNS = findViewById(R.id.textViewDNS)
        networkIndicator = findViewById(R.id.networkIndicator)
        btnDoAllJob = findViewById(R.id.btnDoAllJob)
        btnStartVpn = findViewById(R.id.btnStartVpn)

        updateIP()
        rotateDNS()
        startNetworkMonitor()

        btnDoAllJob.setOnClickListener { doAllJobSequence() }
        btnStartVpn.setOnClickListener { requestVpnPermission() }
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            Toast.makeText(this, "Setting up VPN...", Toast.LENGTH_SHORT).show()
            startService(Intent(this, AppMonitorVPNService::class.java))
            AppMonitorVPNService.rotateDNS(dnsList)
        }
    }

    private fun updateIP() {
        CoroutineScope(Dispatchers.IO).launch {
            val ip = try {
                URL("https://api.ipify.org").readText().trim()
            } catch (e: Exception) { null }
            withContext(Dispatchers.Main) {
                textViewIP.text = if (ip.isNullOrEmpty()) "Public IP: —" else "Public IP: $ip"
            }
        }
    }

    private fun rotateDNS() {
        val selectedDNS = dnsList.random()
        textViewDNS.text = "DNS: $selectedDNS"
        AppMonitorVPNService.rotateDNS(dnsList = listOf(selectedDNS))
    }

    private fun startNetworkMonitor() {
        CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                val connected = AppMonitorVPNService.isPandaActive()
                withContext(Dispatchers.Main) {
                    networkIndicator.setImageResource(
                        if (connected) R.drawable.green_circle else R.drawable.red_circle
                    )
                }
                delay(1500)
            }
        }
    }

    private fun doAllJobSequence() {
        // 1. Force stop Panda + clear cache
        AccessibilityAutomationService.requestClearAndForceStop(pandaPackage)

        CoroutineScope(Dispatchers.Main).launch {
            // ✅ Wait for force stop + clear cache complete (increase time)
            delay(7000)
            
            // 2. Toggle airplane
            AccessibilityAutomationService.requestToggleAirplane()
            
            // ✅ Wait airplane cycle (4s ON + 1s buffer)
            delay(5500)
            
            // ✅ Bring CB app to foreground
            bringAppToForeground()
            delay(500)
            
            // 3. Setup VPN
            Toast.makeText(this@MainActivity, "Setting up VPN tunnel...", Toast.LENGTH_SHORT).show()
            requestVpnPermission()
            
            // ✅ Wait VPN establish
            delay(2000)
            
            // 4. Rotate DNS & refresh IP
            rotateDNS()
            delay(500)
            updateIP()
            
            // 5. Launch Panda app
            delay(1000)
            launchPandaApp()
        }
    }

    private fun bringAppToForeground() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        startActivity(intent)
    }

    private fun launchPandaApp() {
        try {
            val intent = packageManager.getLaunchIntentForPackage(pandaPackage)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                Toast.makeText(this, "Panda launched!", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to launch Panda", Toast.LENGTH_SHORT).show()
        }
    }
}
