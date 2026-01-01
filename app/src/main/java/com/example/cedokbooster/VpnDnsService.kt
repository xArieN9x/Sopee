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
import java.util.concurrent.atomic.AtomicBoolean

class VpnDnsService : VpnService() {
    
    companion object {
        private const val TAG = "VpnDnsService"
        private const val NOTIFICATION_ID = 999
        private const val CHANNEL_ID = "vpn_dns_channel"
        
        const val ACTION_START_VPN = "com.example.cedokbooster.START_VPN"
        const val ACTION_STOP_VPN = "com.example.cedokbooster.STOP_VPN"
        const val EXTRA_DNS_TYPE = "dns_type"
        
        private var isRunning = AtomicBoolean(false)
        private var currentDns = "1.0.0.1"
        
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
    
    private var vpnInterface: ParcelFileDescriptor? = null
    private var notificationManager: NotificationManager? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    
    /**
     * Get DNS servers berdasarkan type dengan failover
     */
    private fun getDnsServers(type: String): List<String> {
        return when (type.uppercase()) {
            "A" -> listOf("1.1.1.1", "1.0.0.1")      // Cloudflare dual
            "B" -> listOf("8.8.8.8", "8.8.4.4")      // Google dual
            else -> listOf("9.9.9.9", "149.112.112.112") // Quad9 dual
        }.also {
            currentDns = it.first()
        }
    }
    
    private fun setupVpn(dnsType: String): Boolean {
        return try {
            val dnsServers = getDnsServers(dnsType)
            LogUtil.d(TAG, "Setting up VPN dengan DNS: $dnsServers")
            
            vpnInterface?.close()
            
            // Dapatkan current gateway dari mobile network
            // Realme C3 guna ccmni0 dengan gateway 10.84.100.208
            val mobileGateway = "10.84.100.208"
            
            val builder = Builder()
                .setSession("CB-DNS")
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)           // Route semua IPv4
                .addDnsServer("1.1.1.1")
                .addDnsServer("1.0.0.1")
                .setMtu(1400)
                .setBlocking(false)               // ✅ False dulu
                .allowFamily(NetworkFamily.INET)  // IPv4 saja dulu
            
            // ✅ PENTING: Explicitly add route dengan gateway betul
            // builder.addRoute("0.0.0.0", 0, mobileGateway) // Tak support
            
            // Alternatif: Set interface metric tinggi supaya VPN priority
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false) // Consider unmetered
            }
            
            builder.establish()?.let { fd ->
                vpnInterface = fd
                
                // ✅ Force route update selepas establish
                updateRoutesManually()
                
                isRunning.set(true)
                LogUtil.d(TAG, "VPN established dengan DNS: $dnsServers")
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
     * Manual route update untuk Realme C3
     */
    private fun updateRoutesManually() {
        try {
            // Execute via shell (non-root masih boleh untuk VPN sendiri)
            Runtime.getRuntime().exec(arrayOf(
                "sh", "-c", 
                "ip route replace default dev tun1 metric 100 && " +
                "ip route add 10.84.100.208/32 dev ccmni0"
            ))
            LogUtil.d(TAG, "Manual route update executed")
        } catch (e: Exception) {
            LogUtil.e(TAG, "Route update failed: ${e.message}")
        }
    }
    
    private fun startVpnBackground(dnsType: String) {
        coroutineScope.launch {
            val dnsServers = getDnsServers(dnsType)
            
            if (isRunning.get() && currentDns == dnsServers.first()) {
                LogUtil.d(TAG, "VPN sudah running dengan DNS sama: $currentDns")
                return@launch
            }
            
            if (setupVpn(dnsType)) {
                showNotification(dnsServers.first())
                LogUtil.d(TAG, "VPN started dengan DNS: $dnsServers")
                
                sendBroadcast(Intent("VPN_DNS_STATUS").apply {
                    putExtra("status", "RUNNING")
                    putExtra("dns", dnsServers.first())
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
                currentDns = "1.0.0.1"
                
                vpnInterface?.close()
                vpnInterface = null
                
                notificationManager?.cancel(NOTIFICATION_ID)
                
                sendBroadcast(Intent("VPN_DNS_STATUS").apply {
                    putExtra("status", "STOPPED")
                    putExtra("dns", "1.0.0.1")
                })
                
                LogUtil.d(TAG, "VPN stopped, DNS restored")
            } catch (e: Exception) {
                LogUtil.e(TAG, "Error stopping VPN: ${e.message}")
            } finally {
                stopForeground(true)
                stopSelf()
            }
        }
    }
    
    /**
     * Show foreground notification
     */
    private fun showNotification(dnsServer: String) {
        notificationManager = getSystemService(NotificationManager::class.java)
        
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
        
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CedokBooster DNS Active")
            .setContentText("DNS: $dnsServer")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
        
        startForeground(NOTIFICATION_ID, notification)
        LogUtil.d(TAG, "Foreground notification shown")
    }
    
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
            
            else -> stopSelf()
        }
        
        return START_STICKY
    }
    
    override fun onDestroy() {
        LogUtil.d(TAG, "Service destroying...")
        stopVpn()
        super.onDestroy()
    }
    
    override fun onRevoke() {
        LogUtil.d(TAG, "VPN revoked by system/user")
        stopVpn()
        super.onRevoke()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    private object LogUtil {
        fun d(tag: String, message: String) = android.util.Log.d(tag, message)
        fun e(tag: String, message: String) = android.util.Log.e(tag, message)
    }
}
