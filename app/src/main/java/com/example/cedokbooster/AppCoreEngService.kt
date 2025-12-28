package com.example.cedokbooster

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

class AppCoreEngService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var locationManager: LocationManager? = null
    private var keepAliveJob: Job? = null
    private var dnsType: String = "none"
    private var gpsStatus: String = "idle"
    private var isEngineRunning = false

    companion object {
        private const val TAG = "AppCoreEngService"
        const val ACTION_START_ENGINE = "com.example.cedokbooster.START_ENGINE"
        const val ACTION_STOP_ENGINE = "com.example.cedokbooster.STOP_ENGINE"
        const val CORE_ENGINE_STATUS_UPDATE = "com.example.cedokbooster.CORE_ENGINE_STATUS_UPDATE"
        const val GPS_LOCK_ACHIEVED = "com.example.cedokbooster.GPS_LOCK_ACHIEVED"
        
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "core_engine_channel"
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val accuracy = location.accuracy
            Log.d(TAG, "GPS Update: Accuracy = $accuracy meters")

            if (accuracy < 25f && gpsStatus != "locked") {
                gpsStatus = "locked"
                Log.d(TAG, "GPS LOCKED! Accuracy: $accuracy meters")
                broadcastStatus()
                
                // Trigger auto-launch Panda
                val intent = Intent(GPS_LOCK_ACHIEVED)
                LocalBroadcastManager.getInstance(this@AppCoreEngService).sendBroadcast(intent)
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

    private fun startCoreEngine() {
        if (isEngineRunning) {
            Log.d(TAG, "CoreEngine already running, restarting...")
            stopCoreEngine()
        }

        Log.d(TAG, "Starting CoreEngine with DNS: $dnsType")
        isEngineRunning = true

        // Start foreground
        startForeground(NOTIFICATION_ID, createNotification())

        // Apply DNS
        applyDNS()

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

        // Stop GPS
        stopGPSStabilization()

        // Release wakelock
        releaseWakeLock()

        // Stop network conditioning
        stopNetworkConditioning()

        // Restore DNS
        restoreDNS()

        // Update floating widget
        startService(Intent(this, FloatingWidgetService::class.java).apply {
            action = FloatingWidgetService.ACTION_START_WIDGET
            putExtra("isActive", false)
        })

        // Trigger force close Panda via accessibility
        val intent = Intent(AccessibilityAutomationService.FORCE_CLOSE_PANDA)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

        broadcastStatus()
        stopForeground(true)
        stopSelf()
    }

    private fun applyDNS() {
        val dns = when (dnsType) {
            "A" -> "156.154.70.1"
            "B" -> "1.1.1.1"
            else -> "8.8.8.8"
        }

        try {
            Log.d(TAG, "Applying DNS: $dns")
            // Note: DNS change without root is very limited on Android 10+
            // This is a placeholder for system-level DNS change
            // In real implementation, this would require VPN-based DNS or root access
            
            // For testing purposes, we'll just log it
            val dnsResolved = InetAddress.getByName("google.com")
            Log.d(TAG, "DNS resolution test: ${dnsResolved.hostAddress}")
            
        } catch (e: Exception) {
            Log.e(TAG, "DNS application failed", e)
        }
    }

    private fun restoreDNS() {
        Log.d(TAG, "Restoring default DNS")
        // Placeholder for DNS restoration
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

    private fun startNetworkConditioning() {
        keepAliveJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    // Send keep-alive packet
                    val connection = URL("https://www.google.com").openConnection() as HttpURLConnection
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
}
