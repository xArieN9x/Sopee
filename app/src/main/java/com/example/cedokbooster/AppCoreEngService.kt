package com.example.allinoneflushapp

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.location.*
import android.net.*
import android.os.*
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.lang.reflect.Method
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.net.InetAddress

class AppCoreEngService : Service() {

    companion object {
        const val TAG = "AppCoreEngService"
        const val NOTIFICATION_CHANNEL_ID = "core_engine_channel"
        const val NOTIFICATION_ID = 101
        const val ACTION_START_ENGINE = "START_ENGINE"
        const val ACTION_STOP_ENGINE = "STOP_ENGINE"
        const val EXTRA_DNS_SERVER = "DNS_SERVER"
        const val DNS_SERVER_A = "156.154.70.1"
        const val DNS_SERVER_B = "1.1.1.1"
        private const val GPS_UPDATE_INTERVAL_MS = 1000L // 1 saat untuk GPS cepat
        private const val GPS_MIN_DISTANCE_M = 0f
        private const val KEEP_ALIVE_INTERVAL_SEC = 30L // Keep-alive setiap 30 saat
    }

    // Status & Resources
    private var isEngineRunning = false
    private var currentDnsServer: String? = null
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var locationManager: LocationManager

    // GPS
    private var gpsLocationListener: LocationListener? = null
    private var isGpsLocked = false
    private var lastStableLocation: Location? = null
    private var gpsStatusStartTime: Long = 0

    // Keep-alive
    private val scheduler = Executors.newScheduledThreadPool(1)
    private var keepAliveTask: ScheduledFuture<*>? = null

    // DNS Changer (Reflection untuk Android 10+)
    private var originalDns: String? = null
    private var setDnsMethod: Method? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        
        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        
        createNotificationChannel()
        acquirePartialWakeLock()
        initializeDnsChanger()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand, action: ${intent?.action}")

        when (intent?.action) {
            ACTION_START_ENGINE -> {
                val dnsServer = intent.getStringExtra(EXTRA_DNS_SERVER) ?: DNS_SERVER_A
                startCoreEngine(dnsServer)
            }
            ACTION_STOP_ENGINE -> {
                stopCoreEngine()
                stopSelf()
            }
            else -> {
                if (isEngineRunning) {
                    updateNotification()
                }
            }
        }
        return START_STICKY
    }

    private fun startCoreEngine(dnsServer: String) {
        if (isEngineRunning) {
            Log.i(TAG, "Engine sedang running. Restarting...")
            stopNetworkConditioning()
            stopGpsStabilization()
        }

        Log.i(TAG, "Starting CoreEngine dengan DNS: $dnsServer")
        currentDnsServer = dnsServer
        gpsStatusStartTime = System.currentTimeMillis()

        // 1. Apply DNS
        if (!setCustomDns(dnsServer)) {
            Log.e(TAG, "Gagal set DNS. Engine tidak di-start.")
            showToast("Gagal set DNS. Check permission/network.")
            return
        }

        // 2. Start Network Conditioning
        startNetworkConditioning()

        // 3. Start GPS Stabilization
        startGpsStabilization()

        isEngineRunning = true

        // 4. Update UI
        updateNotification()
        broadcastEngineStatus(true, dnsServer)

        Log.i(TAG, "CoreEngine started.")
    }

    private fun stopCoreEngine() {
        Log.i(TAG, "Stopping CoreEngine")

        stopGpsStabilization()
        stopNetworkConditioning()
        restoreDefaultDns()

        isEngineRunning = false
        currentDnsServer = null
        isGpsLocked = false

        updateNotification()
        broadcastEngineStatus(false, null)
        stopForeground(true)

        Log.i(TAG, "CoreEngine stopped.")
    }

    /** =================================================
     * REAL DNS CHANGER IMPLEMENTATION (Android 10+)
     * =================================================*/
    private fun initializeDnsChanger() {
        try {
            // Reflection untuk access private API: ConnectivityManager#setDefaultDns
            val connMgrClass = ConnectivityManager::class.java
            setDnsMethod = connMgrClass.getDeclaredMethod(
                "setDefaultDns", 
                Network::class.java, 
                MutableList::class.java
            )
            setDnsMethod?.isAccessible = true
            Log.d(TAG, "DNS changer method initialized via reflection.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize DNS changer. Method not found.", e)
            setDnsMethod = null
        }
    }

    private fun setCustomDns(dnsServer: String): Boolean {
        if (setDnsMethod == null) {
            Log.e(TAG, "DNS changer method not available.")
            return false
        }

        return try {
            val network = connectivityManager.activeNetwork
            if (network == null) {
                Log.e(TAG, "No active network.")
                return false
            }

            // Simpan original DNS dulu (optional, untuk restore)
            val linkProperties = connectivityManager.getLinkProperties(network)
            originalDns = linkProperties?.dnsServers?.joinToString(",")

            // Set new DNS
            val dnsList = mutableListOf<InetAddress>(
                InetAddress.getByName(dnsServer)
            )
            setDnsMethod?.invoke(connectivityManager, network, dnsList)

            Log.i(TAG, "[DNS] Set to $dnsServer (original: $originalDns)")
            broadcastDnsUpdate(dnsServer, true)
            true
        } catch (e: Exception) {
            Log.e(TAG, "[DNS] Failed to set DNS: $dnsServer", e)
            broadcastDnsUpdate("Failed", false)
            false
        }
    }

    private fun restoreDefaultDns() {
        if (originalDns.isNullOrEmpty()) return
        
        try {
            val network = connectivityManager.activeNetwork
            if (network != null && setDnsMethod != null) {
                // Restore ke DNS original atau default (Google DNS sebagai fallback)
                val defaultDnsList = mutableListOf<InetAddress>(
                    InetAddress.getByName("8.8.8.8"),
                    InetAddress.getByName("8.8.4.4")
                )
                setDnsMethod?.invoke(connectivityManager, network, defaultDnsList)
                Log.i(TAG, "[DNS] Restored to default.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[DNS] Failed to restore DNS", e)
        }
        broadcastDnsUpdate("Default", true)
    }

    /** =================================================
     * REAL GPS LAYER IMPLEMENTATION
     * =================================================*/
    private fun startGpsStabilization() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "[GPS] Fine location permission not granted.")
            broadcastGpsUpdate("permission_required", false)
            return
        }

        // Check jika GPS sudah ON
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            // Prompt user untuk turn on GPS
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            showToast("Sila ON kan GPS dahulu.")
        }

        gpsLocationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val accuracy = location.accuracy
                val isAccurate = accuracy > 0 && accuracy < 25.0f // 25 meter sebagai threshold

                Log.d(TAG, "[GPS] Location: ${location.latitude}, ${location.longitude}, Accuracy: $accuracy meters")

                if (isAccurate && !isGpsLocked) {
                    // GPS LOCKED: Signal sudah stabil dan accurate
                    isGpsLocked = true
                    lastStableLocation = location
                    val elapsedTime = (System.currentTimeMillis() - gpsStatusStartTime) / 1000
                    Log.i(TAG, "[GPS] LOCKED in ${elapsedTime}s with accuracy ${accuracy}m")

                    // Hantar broadcast untuk update UI
                    broadcastGpsUpdate("active", true)
                    
                    // Notify Accessibility Service untuk teruskan automasi (jika perlu)
                    val gpsLockIntent = Intent("GPS_LOCK_ACHIEVED")
                    LocalBroadcastManager.getInstance(this@AppCoreEngService)
                        .sendBroadcast(gpsLockIntent)
                }

                updateNotification()
            }

            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
                val statusText = when (status) {
                    LocationProvider.AVAILABLE -> "AVAILABLE"
                    LocationProvider.OUT_OF_SERVICE -> "OUT_OF_SERVICE"
                    LocationProvider.TEMPORARILY_UNAVAILABLE -> "TEMPORARILY_UNAVAILABLE"
                    else -> "UNKNOWN"
                }
                Log.d(TAG, "[GPS] Status: $statusText")
                broadcastGpsUpdate(statusText.lowercase(), false)
            }

            override fun onProviderEnabled(provider: String) {
                Log.d(TAG, "[GPS] Provider enabled: $provider")
            }

            override fun onProviderDisabled(provider: String) {
                Log.d(TAG, "[GPS] Provider disabled: $provider")
                isGpsLocked = false
                broadcastGpsUpdate("provider_disabled", false)
            }
        }

        try {
            // Request high-frequency, high-accuracy location updates
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                GPS_UPDATE_INTERVAL_MS,
                GPS_MIN_DISTANCE_M,
                gpsLocationListener as LocationListener
            )
            Log.i(TAG, "[GPS] High-accuracy updates requested.")
            broadcastGpsUpdate("stabilizing", false)
        } catch (e: SecurityException) {
            Log.e(TAG, "[GPS] SecurityException: ${e.message}")
            broadcastGpsUpdate("security_error", false)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "[GPS] IllegalArgumentException: ${e.message}")
            broadcastGpsUpdate("illegal_argument", false)
        }
    }

    private fun stopGpsStabilization() {
        gpsLocationListener?.let {
            locationManager.removeUpdates(it)
            gpsLocationListener = null
        }
        isGpsLocked = false
        lastStableLocation = null
        Log.i(TAG, "[GPS] Stabilization stopped.")
        broadcastGpsUpdate("inactive", false)
    }

    /** =================================================
     * NETWORK CONDITIONING (Keep-alive)
     * =================================================*/
    private fun acquirePartialWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "CedokBooster::CoreEngineWakeLock"
        ).apply {
            setReferenceCounted(false)
        }
    }

    private fun startNetworkConditioning() {
        Log.d(TAG, "[NetworkConditioning] Starting")

        // 1. Acquire WakeLock
        if (!wakeLock.isHeld) {
            wakeLock.acquire(TimeUnit.HOURS.toMillis(1)) // Auto-release selepas 1 jam
            Log.d(TAG, "[NetworkConditioning] WakeLock acquired for 1 hour")
        }

        // 2. Start periodic keep-alive requests
        keepAliveTask = scheduler.scheduleAtFixedRate({
            runKeepAliveRequest()
        }, 0, KEEP_ALIVE_INTERVAL_SEC, TimeUnit.SECONDS)

        Log.d(TAG, "[NetworkConditioning] Keep-alive scheduled every ${KEEP_ALIVE_INTERVAL_SEC}s")
    }

    private fun runKeepAliveRequest() {
        // Buat request kecil ke server yang reliable untuk keep connection alive
        // Contoh: Google DNS (8.8.8.8) port 53, atau simple HTTP GET
        try {
            // Ini adalah contoh: Connect ke Google DNS (UDP 53) untuk trigger network activity
            val socket = java.net.DatagramSocket()
            val address = InetAddress.getByName("8.8.8.8")
            val emptyData = ByteArray(1)
            val packet = java.net.DatagramPacket(emptyData, emptyData.size, address, 53)
            socket.send(packet)
            socket.close()
            Log.v(TAG, "[KeepAlive] Packet sent to 8.8.8.8:53")
        } catch (e: Exception) {
            Log.w(TAG, "[KeepAlive] Failed: ${e.message}")
        }
    }

    private fun stopNetworkConditioning() {
        Log.d(TAG, "[NetworkConditioning] Stopping")

        // Cancel keep-alive task
        keepAliveTask?.let {
            if (!it.isCancelled) {
                it.cancel(true)
                Log.d(TAG, "[NetworkConditioning] Keep-alive task cancelled")
            }
        }
        keepAliveTask = null

        // Release WakeLock
        if (wakeLock.isHeld) {
            wakeLock.release()
            Log.d(TAG, "[NetworkConditioning] WakeLock released")
        }
    }

    /** =================================================
     * NOTIFICATION & BROADCAST
     * =================================================*/
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "CoreEngine Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows CoreEngine status and GPS information."
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification() {
        // Build status text
        val dnsText = currentDnsServer ?: "Default"
        val gpsText = when {
            !isEngineRunning -> "GPS: Off"
            isGpsLocked -> "GPS: LOCKED"
            else -> "GPS: stabilizing..."
        }
        val statusText = "DNS: $dnsText | $gpsText"

        // Pilih icon berdasarkan status GPS
        val (iconRes, colorRes) = when {
            !isEngineRunning -> Pair(android.R.drawable.ic_dialog_info, android.R.color.darker_gray)
            isGpsLocked -> Pair(android.R.drawable.presence_online, android.R.color.holo_green_dark)
            else -> Pair(android.R.drawable.presence_busy, android.R.color.holo_red_dark)
        }

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("CedokBooster CoreEngine")
            .setContentText(statusText)
            .setSmallIcon(iconRes)
            .setColor(ContextCompat.getColor(this, colorRes))
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun broadcastEngineStatus(isRunning: Boolean, dnsServer: String?) {
        val intent = Intent("CORE_ENGINE_STATUS_UPDATE").apply {
            putExtra("is_running", isRunning)
            putExtra("dns_server", dnsServer)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastDnsUpdate(dns: String, success: Boolean) {
        val intent = Intent("CORE_ENGINE_DNS_UPDATE").apply {
            putExtra("dns", dns)
            putExtra("success", success)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastGpsUpdate(status: String, locked: Boolean) {
        val intent = Intent("CORE_ENGINE_GPS_UPDATE").apply {
            putExtra("gps_status", status)
            putExtra("gps_locked", locked)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun showToast(message: String) {
        // Gunakan Handler untuk post toast dari background thread
        Handler(Looper.getMainLooper()).post {
            android.widget.Toast.makeText(
                applicationContext, 
                message, 
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** =================================================
     * SERVICE LIFECYCLE
     * =================================================*/
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy")
        if (isEngineRunning) stopCoreEngine()
        scheduler.shutdown()
        super.onDestroy()
    }
}
