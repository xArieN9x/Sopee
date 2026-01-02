package com.example.cedokbooster

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    private var wakeLock: PowerManager.WakeLock? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    
    /**
     * Get DNS servers dengan IPv6 support
     */
    private fun getDnsServers(type: String): List<String> {
        return when (type.uppercase()) {
            "A" -> listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001") // Cloudflare dual-stack
            "B" -> listOf("8.8.8.8", "8.8.4.4", "2001:4860:4860::8888", "2001:4860:4860::8844") // Google dual-stack
            else -> listOf("9.9.9.9", "149.112.112.112", "2620:fe::fe", "2620:fe::9") // Quad9 dual-stack
        }.also {
            currentDns = it.first()
        }
    }
    
    /**
     * Detect mobile gateway dari current network
     */
    private fun detectMobileGateway(): String {
        return try {
            // Try semua mobile interfaces
            val interfaces = listOf("ccmni1", "ccmni0", "rmnet0", "rmnet1")
            
            for (iface in interfaces) {
                try {
                    Runtime.getRuntime().exec("ip addr show $iface")
                        .inputStream.bufferedReader().use { reader ->
                            val lines = reader.readText()
                            if (lines.contains("state UP") || lines.contains("state UNKNOWN")) {
                                Regex("inet\\s+(\\d+\\.\\d+\\.\\d+\\.\\d+)").find(lines)?.groupValues?.get(1)?.let { ip ->
                                    val parts = ip.split(".")
                                    if (parts.size == 4) {
                                        val gateway = "${parts[0]}.${parts[1]}.${parts[2]}.1"
                                        LogUtil.d(TAG, "Found interface $iface with IP: $ip -> Gateway: $gateway")
                                        return gateway
                                    }
                                }
                            }
                        }
                } catch (e: Exception) {
                    // Interface tak wujud, try next
                }
            }
            
            // Fallback: Hardcode based on common Realme patterns
            "10.66.191.1"
            
        } catch (e: Exception) {
            "10.66.191.1"
        }
    }
    
    private fun setupVpn(dnsType: String): Boolean {
        return try {
            val dnsServers = getDnsServers(dnsType)
            LogUtil.d(TAG, "Setting up VPN dengan DNS: $dnsServers")
            
            vpnInterface?.close()
            
            // ✅ DETECT CURRENT MOBILE NETWORK GATEWAY
            val mobileGateway = detectMobileGateway()
            LogUtil.d(TAG, "Detected mobile gateway: $mobileGateway")
            
            val builder = Builder()
                .setSession("CB-DNS-BYPASS")
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)           // IPv4 semua
                .setMtu(1400)
            
            // ✅ EXPERIMENT 1: SET BLOCKING MODE BASED ON REALME VERSION
            val realmeModel = Build.MODEL.lowercase()
            val isRealmeC3 = realmeModel.contains("c3") || realmeModel.contains("rmx")
            
            if (isRealmeC3) {
                builder.setBlocking(true)
                LogUtil.d(TAG, "Realme C3 detected - Enabling blocking mode")
            } else {
                builder.setBlocking(false)
            }
            
            // Add DNS (IPv4 saja dulu untuk compatibility Realme)
            dnsServers.filter { it.contains('.') }.forEach { dns ->
                builder.addDnsServer(dns)
            }
            
            // ✅ EXPERIMENT 2: ADD IPv6 ROUTING (FIX AMBIGUOUS OVERLOAD)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    // Gunakan InetAddress untuk elak ambiguous overload
                    val ipv6Default = java.net.InetAddress.getByName("::")
                    builder.addRoute(ipv6Default, 0)
                    LogUtil.d(TAG, "Added IPv6 route")
                }
            } catch (e: Exception) {
                LogUtil.d(TAG, "IPv6 routing not supported: ${e.message}")
            }
            
            // ✅ EXPERIMENT 3: EXCLUDE SYSTEM APPS UNTUK STABILITY
            try {
                val excludedPackages = listOf(
                    "com.google.android.gms",
                    "com.android.systemui",
                    "com.google.android.googlequicksearchbox"
                )
                
                excludedPackages.forEach { pkg ->
                    try {
                        packageManager.getPackageInfo(pkg, 0)
                        builder.addDisallowedApplication(pkg)
                        LogUtil.d(TAG, "Excluded package: $pkg")
                    } catch (e: Exception) {
                        // Package tidak wujud, ignore
                    }
                }
            } catch (e: Exception) {
                LogUtil.e(TAG, "Failed to exclude apps: ${e.message}")
            }
            
            // ✅ CRITICAL: Add NETWORK ROUTE
            if (mobileGateway.isNotEmpty()) {
                val gatewayParts = mobileGateway.split(".")
                if (gatewayParts.size == 4) {
                    builder.addRoute(mobileGateway, 32)
                    LogUtil.d(TAG, "Added route to mobile gateway: $mobileGateway/32")
                    
                    val network = "${gatewayParts[0]}.${gatewayParts[1]}.${gatewayParts[2]}.0"
                    builder.addRoute(network, 24)
                    LogUtil.d(TAG, "Added network route: $network/24")
                }
            }
            
            // Route local VPN network
            builder.addRoute("10.0.0.0", 8)
            
            // Metered setting untuk Android Q+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }
            
            builder.establish()?.let { fd ->
                vpnInterface = fd
                forceRouteUpdate(mobileGateway)
                isRunning.set(true)
                LogUtil.d(TAG, "✅ VPN established dengan DNS: $dnsServers | Gateway: $mobileGateway | Blocking: ${isRealmeC3}")
                true
            } ?: run {
                LogUtil.e(TAG, "❌ Failed to establish VPN")
                false
            }
        } catch (e: Exception) {
            LogUtil.e(TAG, "Error setup VPN: ${e.message}")
            false
        }
    }
    
    /**
     * Force route update selepas VPN established
     */
    private fun forceRouteUpdate(gateway: String) {
        coroutineScope.launch {
            delay(1000)
            
            try {
                val parts = gateway.split(".")
                if (parts.size != 4) {
                    LogUtil.e(TAG, "Invalid gateway format: $gateway")
                    return@launch
                }
                
                val network = "${parts[0]}.${parts[1]}.${parts[2]}.0"
                
                var vpnInterfaceName = "tun0"
                var mobileInterface = "ccmni1"
                
                try {
                    Runtime.getRuntime().exec("ip link show")
                        .inputStream.bufferedReader().use { reader ->
                            val lines = reader.readText()
                            if (lines.contains("tun1")) vpnInterfaceName = "tun1"
                            if (lines.contains("ccmni0") && lines.contains("state UNKNOWN")) mobileInterface = "ccmni0"
                        }
                } catch (e: Exception) {
                    // Use defaults
                }
                
                val commands = arrayOf(
                    "sh", "-c",
                    """
                    # Delete broken routes
                    ip route del default dev $vpnInterfaceName 2>/dev/null || true
                    ip route del default via 0.0.0.0 2>/dev/null || true
                    
                    # Add default route dengan gateway
                    ip route add default via $gateway dev $vpnInterfaceName metric 10 2>/dev/null || true
                    
                    # Alternative route
                    ip route add default dev $vpnInterfaceName metric 20 2>/dev/null || true
                    
                    # Mobile network routes
                    ip route add $network/24 dev $mobileInterface metric 100 2>/dev/null || true
                    ip route add $gateway/32 dev $mobileInterface metric 50 2>/dev/null || true
                    
                    # VPN local route
                    ip route add 10.0.0.0/8 dev $vpnInterfaceName 2>/dev/null || true
                    
                    # Flush cache
                    ip route flush cache 2>/dev/null || true
                    """
                )
                
                Runtime.getRuntime().exec(commands)
                LogUtil.d(TAG, "✅ Enhanced route update executed")
                
                Runtime.getRuntime().exec("ip route show")
                    .inputStream.bufferedReader().use { reader ->
                        val routes = reader.readText()
                        LogUtil.d(TAG, "Final routing table:\n$routes")
                    }
                    
            } catch (e: Exception) {
                LogUtil.e(TAG, "❌ Route update failed: ${e.message}")
            }
        }
    }
    
    private fun startVpnBackground(dnsType: String) {
        coroutineScope.launch {
            // ✅ Check OVERLAY permission untuk Realme
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!android.provider.Settings.canDrawOverlays(this@VpnDnsService)) {
                    LogUtil.e(TAG, "No OVERLAY permission - Realme may kill service")
                }
            }
            
            val dnsServers = getDnsServers(dnsType)
            
            if (isRunning.get() && currentDns == dnsServers.first()) {
                LogUtil.d(TAG, "VPN sudah running dengan DNS sama: $currentDns")
                return@launch
            }
            
            // ✅ Multiple retry untuk Realme
            var success = false
            var retryCount = 0
            
            while (!success && retryCount < 3) {
                success = setupVpn(dnsType)
                if (!success) {
                    delay(1000L * (retryCount + 1))
                    retryCount++
                    LogUtil.d(TAG, "Retry VPN setup attempt $retryCount")
                }
            }
            
            if (success) {
                // ✅ Acquire wake lock sebelum show notification
                acquireWakeLock()
                showNotification(dnsServers.first())
                
                LogUtil.d(TAG, "VPN started dengan DNS: $dnsServers")
                
                sendBroadcast(Intent("VPN_DNS_STATUS").apply {
                    putExtra("status", "RUNNING")
                    putExtra("dns", dnsServers.first())
                })
            } else {
                LogUtil.e(TAG, "Failed to start VPN after $retryCount attempts")
                stopSelf()
            }
        }
    }
    
    /**
     * Acquire wake lock untuk prevent Realme kill service
     */
    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "CedokBooster:VPNLock"
            ).apply {
                setReferenceCounted(false)
                acquire(60 * 60 * 1000L) // 1 hour
                LogUtil.d(TAG, "WakeLock acquired")
            }
        } catch (e: Exception) {
            LogUtil.e(TAG, "Failed to acquire WakeLock: ${e.message}")
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
                
                // ✅ Release wake lock
                wakeLock?.let {
                    if (it.isHeld) {
                        it.release()
                        LogUtil.d(TAG, "WakeLock released")
                    }
                }
                
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
     * Show foreground notification dengan HIGH priority
     */
    private fun showNotification(dnsServer: String) {
        notificationManager = getSystemService(NotificationManager::class.java)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DNS VPN Service",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "CedokBooster DNS VPN Service"
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null)
                enableVibration(false)
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
        
        // ✅ Stop action button
        val stopIntent = Intent(this, VpnDnsService::class.java).apply {
            action = ACTION_STOP_VPN
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🛡️ CedokBooster DNS Active")
            .setContentText("DNS: $dnsServer | Tap to open")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop VPN",
                stopPendingIntent
            )
            .setColor(Color.parseColor("#2196F3"))
            .setAutoCancel(false)
            .build()
        
        startForeground(NOTIFICATION_ID, notification)
        LogUtil.d(TAG, "Foreground notification shown with MAX priority")
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
        
        // ✅ Cleanup wake lock
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                LogUtil.d(TAG, "WakeLock released in onDestroy")
            }
        }
        
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
