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
                Log.d(TAG, "RESTART_CORE_ENGINE received - Restarting CoreEngine")
                stopCoreEngine()  // Matikan dulu
                handler.postDelayed({
                    startCoreEngine() // Hidupkan semula
                }, 1000)
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
            while (isActive) {
                try {
                    val connection = java.net.URL("https://www.google.com").openConnection() as java.net.HttpURLConnection
                    connection.connectTimeout = 5000
                    connection.requestMethod = "HEAD"
                    connection.connect()
                    val responseCode = connection.responseCode
                    Log.d(TAG, "Keep-alive sent, response: $responseCode")
                    connection.disconnect()
                } catch (e: Exception) {
                    Log.e(TAG, "Keep-alive failed", e)
                }
                delay(30000) // Every 30 seconds
            }
        }
        Log.d(TAG, "Network conditioning started")
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
