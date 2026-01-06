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
import android.location.LocationProvider
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

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

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
        // Check runtime permission
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "ACCESS_FINE_LOCATION permission not granted")
            return
        }
    
        // Check background location for Android 10+ (API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "BACKGROUND_LOCATION not granted - may restrict in background")
            }
        }
    
        gpsStatus = "stabilizing"
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    
        // Check if GPS provider is enabled
        if (locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) != true) {
            Log.e(TAG, "GPS provider not enabled")
            gpsStatus = "disabled"
            broadcastStatus()
            return
        }
    
        try {
            // Create and store listener reference for cleanup
            locationListener = createHybridLocationListener()
            
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L, // 1 second (stability)
                0f,
                locationListener
            )
            Log.d(TAG, "GPS HYBRID MODE: 1000ms + Smart Monitoring")
            broadcastStatus()
        } catch (e: SecurityException) {
            Log.e(TAG, "GPS permission not granted", e)
        }
    }
    
    private fun stopGPSStabilization() {
        try {
            if (::locationListener.isInitialized) {
                locationManager?.removeUpdates(locationListener)
                Log.d(TAG, "GPS stabilization stopped")
            } else {
                Log.w(TAG, "locationListener not initialized, skipping removeUpdates")
            }
            gpsStatus = "stopped"
            broadcastStatus()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop GPS: ${e.message}")
        }
    }
    
    private fun createHybridLocationListener(): LocationListener {
        return object : LocationListener {
            private var locationCount = 0
            private var lastAccuracy = 0f
            private var lastBroadcastTime = 0L
            private val BROADCAST_INTERVAL = 2000L // Broadcast setiap 2 saat sahaja
            
            override fun onLocationChanged(location: Location) {
                locationCount++
                val accuracy = location.accuracy
                val currentTime = System.currentTimeMillis()
                
                // Accuracy logging & monitoring
                Log.d(TAG, "GPS Update #$locationCount: ${String.format("%.1f", accuracy)}m")
                
                // Broadcast dengan rate limiting
                if (currentTime - lastBroadcastTime >= BROADCAST_INTERVAL) {
                    broadcastLocation(location)
                    lastBroadcastTime = currentTime
                    
                    // Accuracy trend monitoring
                    if (lastAccuracy > 0) {
                        val accuracyDiff = accuracy - lastAccuracy
                        if (accuracyDiff < -2.0) {
                            Log.d(TAG, "Accuracy improving: ${String.format("%.1f", accuracyDiff)}m")
                        }
                    }
                    lastAccuracy = accuracy
                }
                
                // Auto-detect high accuracy mode
                if (accuracy < 5.0f && accuracy > 0) {
                    Log.d(TAG, "HIGH ACCURACY MODE: ${String.format("%.1f", accuracy)}m")
                }
            }
    
            override fun onProviderEnabled(provider: String) {
                Log.d(TAG, "GPS provider enabled: $provider")
                gpsStatus = "active"
                broadcastStatus()
            }
            
            override fun onProviderDisabled(provider: String) {
                Log.d(TAG, "GPS provider disabled: $provider")
                gpsStatus = "disabled"
                broadcastStatus()
            }
            
            @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
                // For backward compatibility (API < 29)
                val statusText = when (status) {
                    LocationProvider.AVAILABLE -> "AVAILABLE"
                    LocationProvider.OUT_OF_SERVICE -> "OUT_OF_SERVICE"
                    LocationProvider.TEMPORARILY_UNAVAILABLE -> "TEMPORARILY_UNAVAILABLE"
                    else -> "UNKNOWN"
                }
                Log.d(TAG, "GPS status: $statusText")
                gpsStatus = statusText.lowercase()
                broadcastStatus()
            }
        }
    }

    // Store listener reference for cleanup
    private lateinit var locationListener: LocationListener

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
            // A1: Multi-protocol socket initialization
            val udpSocket = DatagramSocket()
            val quicTestIP = "8.8.8.8"
            var consecutiveFailures = 0
            val maxFailures = 3
            
            // Set global redirects setting
            HttpURLConnection.setFollowRedirects(true)
            
            // B1: CDN Pre-warming (one-time)
            prewarmCDNConnections()
            
            // C1: Network Priority Setup
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setupNetworkPriority()
            }
            
            var cycle = 0
            
            while (isActive) {
                try {
                    cycle++
                    
                    // ENHANCED: Rotate targets dengan fallback
                    val targets = listOf(
                        "https://www.google.com",
                        "https://www.youtube.com",
                        "https://i.ytimg.com",
                        "https://yt3.ggpht.com",
                        "https://rr1---sn-5hne6nsk.googlevideo.com",
                        "https://dns.google",
                        "https://one.one.one.one",
                        "https://www.gstatic.com"
                    )
                    
                    val target = targets[cycle % targets.size]
                    
                    // A2: UDP Keep-alive (every 3rd cycle)
                    if (cycle % 3 == 0) {
                        try {
                            val timestamp = System.currentTimeMillis()
                            val pingData = "PING/$timestamp".toByteArray()
                            val pingPacket = DatagramPacket(
                                pingData,
                                pingData.size,
                                InetAddress.getByName(quicTestIP),
                                53  // DNS port (lebih reliable dari 443)
                            )
                            udpSocket.send(pingPacket)
                            Log.d(TAG, "UDP keep-alive -> $quicTestIP:53")
                        } catch (e: Exception) {
                            Log.w(TAG, "UDP keep-alive skipped: ${e.message}")
                        }
                    }
                    
                    // ENHANCED: HTTP request dengan retry logic
                    var connection: HttpURLConnection? = null
                    var success = false
                    var attempts = 0
                    val maxAttempts = 2
                    
                    while (!success && attempts < maxAttempts) {
                        try {
                            attempts++
                            connection = URL(target).openConnection() as HttpURLConnection
                            connection.connectTimeout = 4000  // Slightly higher
                            connection.readTimeout = 6000
                            
                            // ENHANCED: Realistic browser headers (without Accept-Encoding untuk simplicity)
                            connection.setRequestProperty("User-Agent", 
                                "Mozilla/5.0 (Linux; Android 10; RMX2020) AppleWebKit/537.36 " +
                                "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36")
                            
                            connection.setRequestProperty("Accept", 
                                "text/html,application/xhtml+xml,application/xml;q=0.9," +
                                "image/webp,image/apng,*/*;q=0.8")
                            
                            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                            connection.setRequestProperty("Connection", "keep-alive")
                            
                            // ENHANCED: 3 request methods dengan smart rotation
                            val methods = listOf("HEAD", "GET", "OPTIONS")
                            val method = when {
                                target.contains("googlevideo.com") -> "HEAD"  // Video CDN: HEAD only
                                cycle % 5 == 0 -> "GET"  // Simulate real download
                                else -> methods[cycle % methods.size]
                            }
                            connection.requestMethod = method
                            
                            // ENHANCED: Variable payload simulation
                            val simulatePayload = cycle % 5 == 0
                            if (simulatePayload && method == "GET") {
                                connection.doInput = true
                            }
                            
                            connection.connect()
                            val responseCode = connection.responseCode
                            
                            // ENHANCED: Adaptive payload reading (non-blocking approach)
                            if (simulatePayload && responseCode == 200 && method == "GET") {
                                try {
                                    val inputStream = connection.inputStream
                                    val buffer = ByteArray(1024)  // Smaller 1KB buffer
                                    var totalRead = 0
                                    var bytesRead: Int
                                    
                                    // Read up to 2KB max atau sampai habis
                                    while (totalRead < 2048) {
                                        bytesRead = inputStream.read(buffer)
                                        if (bytesRead == -1) break
                                        totalRead += bytesRead
                                    }
                                    
                                    if (totalRead > 0) {
                                        Log.d(TAG, "Payload read: $totalRead bytes")
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Payload read skipped: ${e.message}")
                                }
                            }
                            
                            // ENHANCED: Traffic classification
                            val trafficType = when {
                                target.contains("ytimg.com") || target.contains("yt3.ggpht.com") -> "YT-CDN"
                                target.contains("googlevideo.com") -> "YT-VIDEO"
                                target.contains("one.one.one.one") -> "CF-DNS"
                                target.contains("dns.google") -> "GG-DNS"
                                target.contains("gstatic.com") -> "STATIC"
                                else -> "WEB"
                            }
                            
                            Log.d(TAG, "[$trafficType] $target -> $responseCode ($method) [Attempt: $attempts]")
                            
                            success = true
                            consecutiveFailures = 0  // Reset failure counter
                            
                        } catch (e: Exception) {
                            Log.w(TAG, "Request failed (attempt $attempts): ${e.message}")
                            if (attempts >= maxAttempts) {
                                consecutiveFailures++
                            }
                        } finally {
                            connection?.disconnect()
                        }
                    }
                    
                    // ENHANCED: Adaptive delays based on success/failure
                    val delay = when {
                        consecutiveFailures >= maxFailures -> 60000L  // Back off on repeated failures
                        !success -> 15000L  // Shorter delay after failure
                        cycle % 6 == 0 -> 15000L
                        cycle % 6 == 1 -> 25000L
                        cycle % 6 == 2 -> 30000L
                        cycle % 6 == 3 -> 10000L
                        cycle % 6 == 4 -> 45000L
                        else -> 20000L
                    }
                    
                    delay(delay)
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Network conditioning cycle failed: ${e.message}")
                    consecutiveFailures++
                    delay(if (consecutiveFailures >= maxFailures) 60000L else 10000L)
                }
            }
            
            // Cleanup
            udpSocket.close()
        }
        
        Log.d(TAG, "ENHANCED Network conditioning started (FIXED VERSION)")
    }
    
    // B2: ENHANCED CDN Pre-warming dengan retry
    private fun prewarmCDNConnections() {
        CoroutineScope(Dispatchers.IO).launch {
            val cdns = listOf(
                "https://www.google.com",
                "https://yt3.ggpht.com",
                "https://i.ytimg.com",
                "https://fonts.gstatic.com",
                "https://www.gstatic.com",
                "https://play.googleapis.com",
                "https://dns.google",
                "https://one.one.one.one"
            )
            
            cdns.forEachIndexed { index, cdn ->
                delay(index * 800L)  // Stagger dengan 800ms
                
                var attempts = 0
                var success = false
                
                while (!success && attempts < 2) {
                    attempts++
                    try {
                        val connection = URL(cdn).openConnection() as HttpURLConnection
                        connection.connectTimeout = 4000
                        connection.readTimeout = 4000
                        connection.requestMethod = "HEAD"
                        
                        connection.setRequestProperty("User-Agent", 
                            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
                        
                        connection.connect()
                        val code = connection.responseCode
                        Log.d(TAG, "CDN pre-warmed: $cdn ($code) [Attempt: $attempts]")
                        connection.disconnect()
                        success = true
                        
                    } catch (e: Exception) {
                        Log.w(TAG, "CDN pre-warm retry $cdn (attempt $attempts)")
                        if (attempts < 2) delay(1000L)
                    }
                }
            }
        }
    }
    
    // C2: ENHANCED Network Priority dengan proper cleanup
    @RequiresApi(Build.VERSION_CODES.M)
    private fun setupNetworkPriority() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "Priority network available: ${network}")
                    try {
                        cm.bindProcessToNetwork(network)
                    } catch (e: Exception) {
                        Log.e(TAG, "Bind failed: ${e.message}")
                    }
                }
                
                override fun onLost(network: Network) {
                    Log.d(TAG, "Priority network lost: ${network}")
                    try {
                        cm.bindProcessToNetwork(null)
                    } catch (e: Exception) {
                        Log.w(TAG, "Unbind failed: ${e.message}")
                    }
                }
                
                override fun onUnavailable() {
                    Log.w(TAG, "Priority network unavailable")
                }
            }
            
            cm.requestNetwork(request, callback)
            
        } catch (e: Exception) {
            Log.e(TAG, "Network priority setup failed: ${e.message}")
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
