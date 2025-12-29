package com.example.cedokbooster

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * VPN Service untuk ubah DNS system-wide tanpa root
 * Simplified version dari DNSVpnService.java (frostnerd)
 * Khusus untuk projek CedokBooster
 */
class VpnDnsService : VpnService() {
    
    companion object {
        private const val TAG = "VpnDnsService"
        private const val NOTIFICATION_ID = 999
        private const val CHANNEL_ID = "vpn_dns_channel"
        
        // DNS Servers untuk projek Tuan
        private const val DNS_A = "156.154.70.1"
        private const val DNS_B = "1.1.1.1"
        private const val DNS_DEFAULT = "8.8.8.8"
        
        // Actions untuk control service
        const val ACTION_START_VPN = "com.example.cedokbooster.START_VPN"
        const val ACTION_STOP_VPN = "com.example.cedokbooster.STOP_VPN"
        const val EXTRA_DNS_TYPE = "dns_type" // "A", "B", atau "DEFAULT"
        
        // Status flags
        private var isRunning = AtomicBoolean(false)
        private var currentDns = DNS_DEFAULT
        
        // Helper functions untuk panggil dari service lain
        fun startVpn(context: android.content.Context, dnsType: String) {
            val intent = Intent(context, VpnDnsService::class.java).apply {
                action = ACTION_START_VPN
                putExtra(EXTRA_DNS_TYPE, dnsType)
            }
            context.startService(intent)
        }
        
        fun stopVpn(context: android.content.Context) {
            val intent = Intent(context, VpnDnsService::class.java).apply {
                action = ACTION_STOP_VPN
            }
            context.startService(intent)
        }
        
        fun isVpnRunning(): Boolean = isRunning.get()
        fun getCurrentDns(): String = currentDns
    }
    
    // VPN variables
    private var vpnInterface: ParcelFileDescriptor? = null
    private var notificationManager: NotificationManager? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    
    /**
     * Get DNS IP berdasarkan type
     */
    private fun getDnsIpFromType(type: String): String {
        return when (type.uppercase()) {
            "A" -> DNS_A
            "B" -> DNS_B
            else -> DNS_DEFAULT
        }.also {
            currentDns = it
        }
    }
    
    /**
     * Setup VPN dengan DNS specific
     */
    private fun setupVpn(dnsServer: String): Boolean {
        return try {
            LogUtil.d(TAG, "Setting up VPN dengan DNS: $dnsServer")
            
            // Stop existing VPN jika ada
            vpnInterface?.close()
            
            // Build VPN configuration
            val builder = Builder()
                .setSession("CedokBooster DNS")
                .setMtu(1500)
                .addAddress("10.0.0.2", 32)      // VPN internal IP
                .addRoute("0.0.0.0", 0)          // Route semua IPv4 traffic
                .addDnsServer(dnsServer)         // DNS server yang kita nak
                .setBlocking(false)              // Non-blocking
            
            // Apply untuk semua apps (system-wide)
            builder.establish()?.let { fd ->
                vpnInterface = fd
                isRunning.set(true)
                LogUtil.d(TAG, "VPN established dengan DNS: $dnsServer")
                true
            } ?: run {
                LogUtil.e(TAG, "Failed to establish VPN")
                false
            }
        } catch (e: Exception) {
            LogUtil.e(TAG, "Error setup VPN: ${e.message}")
            false
        }
    }
    
    /**
     * Start VPN dalam background thread
     */
    private fun startVpnBackground(dnsType: String) {
        coroutineScope.launch {
            val dnsServer = getDnsIpFromType(dnsType)
            
            // Check jika VPN dah running dengan DNS yang sama
            if (isRunning.get() && currentDns == dnsServer) {
                LogUtil.d(TAG, "VPN sudah running dengan DNS sama: $currentDns")
                return@launch
            }
            
            // Setup VPN
            if (setupVpn(dnsServer)) {
                showNotification(dnsServer)
                LogUtil.d(TAG, "VPN started dengan DNS: $dnsServer")
                
                // Broadcast status update
                sendBroadcast(Intent("VPN_DNS_STATUS").apply {
                    putExtra("status", "RUNNING")
                    putExtra("dns", dnsServer)
                })
            } else {
                LogUtil.e(TAG, "Failed to start VPN")
                stopSelf()
            }
        }
    }
    
    /**
     * Stop VPN dan cleanup
     */
    private fun stopVpn() {
        coroutineScope.launch {
            try {
                isRunning.set(false)
                currentDns = DNS_DEFAULT
                
                // Close VPN interface
                vpnInterface?.close()
                vpnInterface = null
                
                // Hide notification
                notificationManager?.cancel(NOTIFICATION_ID)
                
                // Broadcast status update
                sendBroadcast(Intent("VPN_DNS_STATUS").apply {
                    putExtra("status", "STOPPED")
                    putExtra("dns", DNS_DEFAULT)
                })
                
                LogUtil.d(TAG, "VPN stopped, DNS restored to default")
            } catch (e: Exception) {
                LogUtil.e(TAG, "Error stopping VPN: ${e.message}")
            } finally {
                stopForeground(true)
                stopSelf()
            }
        }
    }
    
    /**
     * Show foreground notification (required for VPN service)
     */
    private fun showNotification(dnsServer: String) {
        notificationManager = getSystemService(NotificationManager::class.java)
        
        // Create notification channel untuk Android O+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DNS VPN Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "CedokBooster DNS VPN Service"
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
        
        // Intent untuk buka app kalau notification diklik
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Build notification
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CedokBooster DNS Active")
            .setContentText("DNS: $dnsServer")
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Guna icon yang sama dengan app
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
        
        // Start foreground service dengan notification
        startForeground(NOTIFICATION_ID, notification)
        LogUtil.d(TAG, "Foreground notification shown for DNS: $dnsServer")
    }
    
    /**
     * Handle start command dari AppCoreEngService
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LogUtil.d(TAG, "onStartCommand: ${intent?.action}")
        
        when (intent?.action) {
            ACTION_START_VPN -> {
                val dnsType = intent.getStringExtra(EXTRA_DNS_TYPE) ?: "A"
                LogUtil.d(TAG, "Starting VPN dengan DNS type: $dnsType")
                startVpnBackground(dnsType)
            }
            
            ACTION_STOP_VPN -> {
                LogUtil.d(TAG, "Stopping VPN")
                stopVpn()
            }
            
            else -> {
                // Default action: stop service kalau takde command
                stopSelf()
            }
        }
        
        return START_STICKY
    }
    
    /**
     * Cleanup bila service destroyed
     */
    override fun onDestroy() {
        LogUtil.d(TAG, "Service destroying...")
        stopVpn() // Ensure VPN stopped
        super.onDestroy()
    }
    
    /**
     * Handle VPN revocation (user manually stop VPN dari system)
     */
    override fun onRevoke() {
        LogUtil.d(TAG, "VPN revoked by system/user")
        stopVpn()
        super.onRevoke()
    }
    
    /**
     * Binder tak perlu untuk case ni (simple start/stop je)
     */
    override fun onBind(intent: Intent?): IBinder? = null
    
    /**
     * Simple LogUtil untuk consistency
     */
    private object LogUtil {
        fun d(tag: String, message: String) {
            android.util.Log.d(tag, message)
        }
        
        fun e(tag: String, message: String) {
            android.util.Log.e(tag, message)
        }
    }
}
