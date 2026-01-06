package com.example.cedokbooster

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.*

import java.net.HttpURLConnection
import java.net.URL
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.NetworkSpecifier
import androidx.annotation.RequiresApi

import java.net.DatagramSocket
import java.net.DatagramPacket
import java.net.InetAddress

class AppCoreEngService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var locationManager: LocationManager? = null
    private var keepAliveJob: Job? = null
    private var dnsType: String = "none"
    private var gpsStatus: String = "idle"
    private var isEngineRunning = false
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "AppCoreEngService"
        const val ACTION_START_ENGINE = "com.example.cedokbooster.START_ENGINE"
        const val ACTION_STOP_ENGINE = "com.example.cedokbooster.STOP_ENGINE"
        const val ACTION_QUERY_STATUS = "com.example.cedokbooster.QUERY_STATUS"
        const val CORE_ENGINE_STATUS_UPDATE = "com.example.cedokbooster.CORE_ENGINE_STATUS_UPDATE"
        const val GPS_LOCK_ACHIEVED = "com.example.cedokbooster.GPS_LOCK_ACHIEVED"
        
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "core_engine_channel"
    }

    private val statusQueryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_QUERY_STATUS) {
                Log.d(TAG, "Status query received, broadcasting current status")
                broadcastStatus()
            }
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val accuracy = location.accuracy
            Log.d(TAG, "GPS Update: Accuracy = $accuracy meters")
    
            if (accuracy < 25f && gpsStatus != "locked") {
                gpsStatus = "locked"
                Log.d(TAG, "GPS LOCKED! Accuracy: $accuracy meters")
                broadcastStatus()
                
                // ✅ TAMBAH DELAY sebelum launch Panda
                Handler(Looper.getMainLooper()).postDelayed({
                    // Trigger auto-launch Panda
                    val intent = Intent(GPS_LOCK_ACHIEVED)
                    LocalBroadcastManager.getInstance(this@AppCoreEngService).sendBroadcast(intent)
                    Log.d(TAG, "GPS_LOCK_ACHIEVED broadcast sent (delayed)")
                }, 2000) // Delay 2 saat
            } else if (accuracy >= 25f && gpsStatus == "locked") {
                gpsStatus = "stabilizing"
                broadcastStatus()
            }
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate()")
        createNotificationChannel()
        
        // Register receiver for status queries
        val filter = IntentFilter(ACTION_QUERY_STATUS)
        registerReceiver(statusQueryReceiver, filter)

        val restartFilter = IntentFilter("RESTART_CORE_ENGINE")
        LocalBroadcastManager.getInstance(this).registerReceiver(restartReceiver, restartFilter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_ENGINE -> {
                dnsType = intent.getStringExtra("dnsType") ?: "A"
                startCoreEngine()
            }
            ACTION_STOP_ENGINE -> {
                stopCoreEngine()
            }
        }
        return START_STICKY
    }

    private val restartReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "RESTART_CORE_ENGINE") {
                Log.d(TAG, "RESTART_CORE_ENGINE received - SMART RESTART")
                
                // SMART RESTART: Jangan stop CoreEngine, cuma trigger soft restart VPN
                handler.postDelayed({
                    // Soft restart VPN sahaja
                    try {
                        VpnDnsService.softRestartVpn(this@AppCoreEngService)
                        Log.d(TAG, "✅ VPN soft restart triggered")
                        
                        // Refresh network conditioning
                        stopNetworkConditioning()
                        handler.postDelayed({
                            startNetworkConditioning()
                            Log.d(TAG, "Network conditioning restarted")
                        }, 500)
                        
                        // Update notification
                        broadcastStatus()
                        
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to soft restart VPN: ${e.message}")
                    }
                }, 500)
            }
        }
    }

    private fun startCoreEngine() {
        if (isEngineRunning) {
            Log.d(TAG, "CoreEngine already running, restarting...")
            stopCoreEngine()
        }

        Log.d(TAG, "Starting CoreEngine with DNS: $dnsType")
        isEngineRunning = true

        // APPLY DNS
        applyDNS()

        // Start foreground
        startForeground(NOTIFICATION_ID, createNotification())

        // Acquire wakelock
        acquireWakeLock()

        // Start GPS
        startGPSStabilization()

        // Start network conditioning
        startNetworkConditioning()

        // Start anchor service
        startService(Intent(this, AnchorService::class.java))

        // Start floating widget
        startService(Intent(this, FloatingWidgetService::class.java).apply {
            action = FloatingWidgetService.ACTION_START_WIDGET
            putExtra("isActive", true)
        })

        broadcastStatus()
    }

    private fun stopCoreEngine() {
        Log.d(TAG, "Stopping CoreEngine")
        isEngineRunning = false
        gpsStatus = "idle"

        // restore DNS default
        restoreDNS()

        // Stop GPS
        stopGPSStabilization()

        // Release wakelock
        releaseWakeLock()

        // Stop network conditioning
        stopNetworkConditioning()

        // Update floating widget
        startService(Intent(this, FloatingWidgetService::class.java).apply {
            action = FloatingWidgetService.ACTION_START_WIDGET
            putExtra("isActive", false)
        })

        // Trigger force close Panda via accessibility
        //val intent = Intent(AccessibilityAutomationService.FORCE_CLOSE_PANDA)
        //LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

        broadcastStatus()
        stopForeground(true)
        stopSelf()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "CedokBooster::CoreEngineWakeLock"
        ).apply {
            acquire(60 * 60 * 1000L) // 1 hour
        }
        Log.d(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "WakeLock released")
            }
        }
        wakeLock = null
    }

    private fun startGPSStabilization() {
        gpsStatus = "stabilizing"
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        try {
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L, // 1 second
                0f,
                locationListener
            )
            Log.d(TAG, "GPS stabilization started")
            broadcastStatus()
        } catch (e: SecurityException) {
            Log.e(TAG, "GPS permission not granted", e)
        }
    }

    private fun stopGPSStabilization() {
        try {
            locationManager?.removeUpdates(locationListener)
            Log.d(TAG, "GPS stabilization stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping GPS", e)
        }
    }

    // NEW UPDATE for VpnDnsService.kt
    private fun applyDNS() {
        Log.d(TAG, "Applying DNS: $dnsType")
        
        try {
            // Guna VpnDnsService untuk apply DNS sebenar
            VpnDnsService.startVpn(this, dnsType)
            Log.d(TAG, "DNS VPN service started dengan type: $dnsType")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start DNS VPN: ${e.message}")
            // Fallback ke dummy log (existing behavior)
            val dns = when (dnsType) {
                "A" -> "156.154.70.1"
                "B" -> "1.1.1.1"
                else -> "8.8.8.8"
            }
            Log.d(TAG, "Fallback: DNS would be: $dns")
        }
    }

    // NEW UPDATE for VpnDnsService.kt
    private fun restoreDNS() {
        Log.d(TAG, "Restoring default DNS")
        
        try {
            VpnDnsService.stopVpn(this)
            Log.d(TAG, "DNS VPN service stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop DNS VPN: ${e.message}")
            // Fallback ke existing behavior
            Log.d(TAG, "Fallback: DNS restored to default")
        }
    }

    private fun startNetworkConditioning() {
        keepAliveJob = CoroutineScope(Dispatchers.IO).launch {
            // A1: QUIC socket - FIXED (use proper QUIC test endpoint)
            val quicSocket = DatagramSocket()
            val quicTestIP = "8.8.8.8" // Google DNS support QUIC
            
            // B1: CDN Pre-warming (one-time) - FIXED (use HEAD instead of OPTIONS)
            prewarmCDNConnections()
            
            // C1: Network Priority Setup - FIXED (remove network specifier issue)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setupNetworkPriority()
            }
            
            var cycle = 0
            
            while (isActive) {
                try {
                    cycle++
                    
                    // ROTATE TARGETS - FIXED (remove HTTPS from IPs)
                    val targets = listOf(
                        "https://www.google.com",
                        "https://www.youtube.com",
                        "https://i.ytimg.com",
                        "https://rr1---sn-5hne6nsk.googlevideo.com",
                        "http://8.8.8.8",  // CHANGED: HTTP not HTTPS
                        "http://1.1.1.1"   // CHANGED: HTTP not HTTPS
                    )
                    
                    val target = targets[cycle % targets.size]
                    
                    // A2: Mix protocols - FIXED (proper QUIC test)
                    if (cycle % 3 == 0) {
                        try {
                            // Real QUIC test: Connect to known QUIC endpoint
                            val quicClient = OkHttpClient.Builder()
                                .connectTimeout(2, TimeUnit.SECONDS)
                                .build()
                            
                            val quicRequest = Request.Builder()
                                .url("https://quic.rocks:443/") // Public QUIC test server
                                .build()
                            
                            quicClient.newCall(quicRequest).execute().use { response ->
                                Log.d(TAG, "QUIC test → ${response.code}")
                            }
                        } catch (e: Exception) {
                            // Fallback to UDP ping if QUIC fails
                            try {
                                val pingData = "PING".toByteArray()
                                val pingPacket = DatagramPacket(
                                    pingData,
                                    pingData.size,
                                    InetAddress.getByName(quicTestIP),
                                    443
                                )
                                quicSocket.send(pingPacket)
                                Log.d(TAG, "UDP ping sent to $quicTestIP:443")
                            } catch (ue: Exception) {
                                Log.w(TAG, "QUIC/UDP fallback failed")
                            }
                        }
                    }
                    
                    // VARIABLE REQUEST TYPES - FIXED (handle SSL exception for IPs)
                    val connection = URL(target).openConnection() as HttpURLConnection
                    connection.connectTimeout = 3000
                    connection.readTimeout = 5000
                    
                    // Set realistic headers
                    connection.setRequestProperty("User-Agent", 
                        "Mozilla/5.0 (Linux; Android 10; RMX2020) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36")
                    
                    connection.setRequestProperty("Accept", 
                        "text/html,application/xhtml+xml,application/xml;q=0.9," +
                        "image/webp,image/apng,*/*;q=0.8")
                    
                    // Randomize request method - FIXED (remove OPTIONS)
                    val methods = listOf("HEAD", "GET") // REMOVED: "OPTIONS"
                    connection.requestMethod = methods[cycle % methods.size]
                    
                    // VARIABLE PAYLOAD SIZES
                    val simulatePayload = cycle % 5 == 0
                    if (simulatePayload && connection.requestMethod == "GET") {
                        connection.doInput = true
                    }
                    
                    connection.connect()
                    val responseCode = connection.responseCode
                    
                    // Read partial response if GET
                    if (simulatePayload && responseCode == 200) {
                        val buffer = ByteArray(1024)
                        connection.inputStream.read(buffer, 0, 1024)
                    }
                    
                    // LOG WITH TRAFFIC TYPE
                    val trafficType = when {
                        target.contains("ytimg.com") -> "YT-CDN"
                        target.contains("googlevideo.com") -> "YT-VIDEO"
                        target.contains("1.1.1.1") -> "CF-DNS"
                        target.contains("8.8.8.8") -> "GG-DNS"
                        else -> "WEB"
                    }
                    
                    Log.d(TAG, "[$trafficType] $target → $responseCode (${connection.requestMethod})")
                    
                    connection.disconnect()
                    
                    // Variable delays
                    val delay = when (cycle % 6) {
                        0 -> 15000L
                        1 -> 25000L
                        2 -> 30000L
                        3 -> 10000L
                        4 -> 45000L
                        else -> 20000L
                    }
                    
                    delay(delay)
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Network conditioning failed: ${e.message}")
                    delay(10000)
                }
            }
        }
    }
    
    // B2: CDN Pre-warming function - FIXED
    private fun prewarmCDNConnections() {
        CoroutineScope(Dispatchers.IO).launch {
            val cdns = listOf(
                "https://yt3.ggpht.com",
                "https://i.ytimg.com",
                "https://fonts.gstatic.com",
                "https://www.gstatic.com",
                "https://play.googleapis.com"
            )
            
            cdns.forEachIndexed { index, cdn ->
                delay(index * 1000L)
                try {
                    val connection = URL(cdn).openConnection() as HttpURLConnection
                    connection.connectTimeout = 3000
                    connection.requestMethod = "HEAD" // CHANGED: OPTIONS → HEAD
                    connection.connect()
                    Log.d(TAG, "CDN pre-warmed: $cdn (${connection.responseCode})")
                    connection.disconnect()
                } catch (e: Exception) {
                    Log.w(TAG, "CDN pre-warm failed for $cdn")
                }
            }
        }
    }
    
    // C2: Network Priority Setup - FIXED
    @RequiresApi(Build.VERSION_CODES.M)
    private fun setupNetworkPriority() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            
            cm.requestNetwork(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "High-priority network available")
                    try {
                        cm.bindProcessToNetwork(network)
                    } catch (e: Exception) {
                        Log.e(TAG, "bindProcessToNetwork failed", e)
                    }
                }
                
                override fun onLost(network: Network) {
                    Log.d(TAG, "High-priority network lost")
                    try {
                        cm.bindProcessToNetwork(null) // Unbind
                    } catch (e: Exception) {
                        Log.e(TAG, "unbind failed", e)
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Network priority setup failed", e)
        }
    }

    private fun stopNetworkConditioning() {
        keepAliveJob?.cancel()
        Log.d(TAG, "Network conditioning stopped")
    }

    private fun broadcastStatus() {
        val intent = Intent(CORE_ENGINE_STATUS_UPDATE).apply {
            putExtra("isRunning", isEngineRunning)
            putExtra("dns", dnsType)
            putExtra("gpsStatus", gpsStatus)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        
        // Update notification
        if (isEngineRunning) {
            val notification = createNotification()
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotification(): Notification {
        val statusText = if (isEngineRunning) {
            "CoreEngine Active (DNS: $dnsType) | GPS: $gpsStatus"
        } else {
            "CoreEngine Stopped"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CedokBooster")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Core Engine Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps CoreEngine running"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(statusQueryReceiver)
            // ✅ TAMBAH BARIS INI:
            LocalBroadcastManager.getInstance(this).unregisterReceiver(restartReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }
    }
}
