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
import android.provider.Settings
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.lang.reflect.Method
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
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
        
        fun startVpn(context: Context, dnsType: String) {
            val intent = Intent(context, VpnDnsService::class.java).apply {
                action = ACTION_START_VPN
                putExtra(EXTRA_DNS_TYPE, dnsType)
            }
            context.startService(intent)
        }
        
        fun stopVpn(context: Context) {
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
    private var dnsHijackSocket: DatagramSocket? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    
    // 🔥 NEW: Realme bypass flag
    private var realmeBypassApplied = false
    
    /**
     * Get DNS servers dengan IPv6 support
     */
    private fun getDnsServers(type: String): List<String> {
        return when (type.uppercase()) {
            "A" -> listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001")
            "B" -> listOf("8.8.8.8", "8.8.4.4", "2001:4860:4860::8888", "2001:4860:4860::8844")
            else -> listOf("9.9.9.9", "149.112.112.112", "2620:fe::fe", "2620:fe::9")
        }.also {
            currentDns = it.first()
        }
    }
    
    /**
     * 🔥 NEW: Apply Realme VPN bypass hacks
     */
    private fun applyRealmeBypassHacks() {
        try {
            // LAPIS 1: Write secure settings untuk full tunnel
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Settings.Global.putString(
                        contentResolver,
                        "vpn_default_profile",
                        "{\"dns\":[\"1.1.1.1\"],\"full_tunnel\":true}"
                    )
                    LogUtil.d(TAG, "✅ Secure settings injected")
                }
            } catch (e: SecurityException) {
                LogUtil.e(TAG, "❌ Need WRITE_SECURE_SETTINGS permission")
            }
            
            // LAPIS 2: Reflection hack remove Realme restriction
            try {
                val networkCapabilities = Class.forName("android.net.NetworkCapabilities")
                val removeCapMethod: Method = networkCapabilities.getDeclaredMethod(
                    "removeCapability", 
                    Int::class.javaPrimitiveType
                )
                
                // Remove VPN transport restriction flag (0x00008000)
                removeCapMethod.invoke(null, 0x00008000)
                LogUtil.d(TAG, "✅ Reflection hack applied")
            } catch (e: Exception) {
                LogUtil.w(TAG, "⚠️ Reflection failed: ${e.message}")
            }
            
            // LAPIS 3: Force system properties untuk DNS
            try {
                System.setProperty("net.dns1", "1.1.1.1")
                System.setProperty("net.dns2", "1.0.0.1")
                
                Runtime.getRuntime().exec(arrayOf("setprop", "net.dns1", "1.1.1.1"))
                Runtime.getRuntime().exec(arrayOf("setprop", "net.dns2", "1.0.0.1"))
                LogUtil.d(TAG, "✅ System properties set")
            } catch (e: Exception) {
                LogUtil.w(TAG, "⚠️ Setprop failed")
            }
            
            realmeBypassApplied = true
            
        } catch (e: Exception) {
            LogUtil.e(TAG, "❌ Realme bypass failed: ${e.message}")
        }
    }
    
    /**
     * Detect mobile gateway dari current network
     */
    private fun detectMobileGateway(): String {
        return try {
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
            
            "10.66.191.1"
            
        } catch (e: Exception) {
            "10.66.191.1"
        }
    }
    
    /**
     * 🔥 MODIFIED: VPN setup dengan Realme bypass
     */
    private fun setupVpn(dnsType: String): Boolean {
        return try {
            val dnsServers = getDnsServers(dnsType)
            LogUtil.d(TAG, "Setting up VPN dengan DNS: $dnsServers")
            
            // 🔥 Apply bypass sebelum setup VPN
            if (!realmeBypassApplied) {
                applyRealmeBypassHacks()
                coroutineScope.launch {
                    delay(500) // Biar bypass settle
                }.join() // Tunggu sampai selesai
            }
            
            vpnInterface?.close()
            
            val mobileGateway = detectMobileGateway()
            LogUtil.d(TAG, "Detected mobile gateway: $mobileGateway")
            
            val builder = Builder()
                .setSession("CB-DNS")
                // 🔥 GANTI IP RANGE (Realme mungkin block 10.0.0.0/8)
                .addAddress("192.168.69.2", 24)  // Guna range lain
                .addRoute("0.0.0.0", 0)
                .addRoute("192.168.69.0", 24)    // Route untuk VPN network
                .setMtu(1400)
                .setBlocking(true)
            
            // Add DNS
            dnsServers.filter { it.contains('.') }.forEach { dns ->
                builder.addDnsServer(dns)
            }
            
            // 🔥 TAMBAH: Allow IPv6 DNS jgak
            dnsServers.filter { it.contains(':') }.forEach { dns ->
                builder.addDnsServer(dns)
            }
            
            // Add proper routes
            if (mobileGateway.isNotEmpty()) {
                val gatewayParts = mobileGateway.split(".")
                if (gatewayParts.size == 4) {
                    builder.addRoute(mobileGateway, 32)
                    val network = "${gatewayParts[0]}.${gatewayParts[1]}.${gatewayParts[2]}.0"
                    builder.addRoute(network, 24)
                }
            }
            
            // 🔥 TAMBAH: Route untuk common services
            builder.addRoute("1.1.1.1", 32)
            builder.addRoute("8.8.8.8", 32)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }
            
            // 🔥 TAMBAH: Set configure intent untuk stability
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val configureIntent = PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
                builder.setConfigureIntent(configureIntent)
            }
            
            builder.establish()?.let { fd ->
                vpnInterface = fd
                
                // 🔥 TAMBAH: Start DNS hijack sebagai backup
                startDnsHijackBackup()
                
                // Simple route update
                coroutineScope.launch {
                    delay(1000)
                    try {
                        Runtime.getRuntime().exec(arrayOf(
                            "sh", "-c",
                            "ip route replace default dev tun0 && ip route flush cache"
                        ))
                        LogUtil.d(TAG, "Simple route update done")
                    } catch (e: Exception) {
                        LogUtil.e(TAG, "Route update skipped")
                    }
                }
                
                isRunning.set(true)
                LogUtil.d(TAG, "VPN ESTABLISHED with Realme bypass")
                true
            } ?: run {
                LogUtil.e(TAG, "Failed to establish VPN")
                false
            }
        } catch (e: Exception) {
            LogUtil.e(TAG, "Error: ${e.message}")
            false
        }
    }
    
    /**
     * 🔥 NEW: DNS hijack backup untuk Realme
     */
    private fun startDnsHijackBackup() {
        coroutineScope.launch {
            try {
                // Stop existing socket
                dnsHijackSocket?.close()
                
                val socket = DatagramSocket(5353)
                dnsHijackSocket = socket
                val buffer = ByteArray(512)
                
                while (isRunning.get()) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        
                        // Forward ke Cloudflare
                        val forwardSocket = DatagramSocket()
                        forwardSocket.connect(InetAddress.getByName("1.1.1.1"), 53)
                        forwardSocket.send(packet)
                        
                        val response = ByteArray(512)
                        val responsePacket = DatagramPacket(response, response.size)
                        forwardSocket.receive(responsePacket)
                        
                        // Send back
                        socket.send(
                            DatagramPacket(
                                response, 
                                response.size, 
                                packet.address, 
                                packet.port
                            )
                        )
                        forwardSocket.close()
                        
                    } catch (e: Exception) {
                        // Continue loop
                    }
                }
            } catch (e: Exception) {
                LogUtil.w(TAG, "DNS hijack failed: ${e.message}")
            }
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
                    ip route add 192.168.69.0/24 dev $vpnInterfaceName 2>/dev/null || true
                    
                    # DNS server routes
                    ip route add 1.1.1.1/32 dev $vpnInterfaceName 2>/dev/null || true
                    ip route add 8.8.8.8/32 dev $vpnInterfaceName 2>/dev/null || true
                    
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
    
    /**
     * 🔥 NEW: Check VPN traffic flow
     */
    private fun checkVpnTrafficFlow(): Boolean {
        return try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val network = connectivityManager.activeNetwork
            val caps = connectivityManager.getNetworkCapabilities(network)
            
            val hasVpn = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
            val hasInternet = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            
            LogUtil.d(TAG, "VPN Check: VPN=$hasVpn, Internet=$hasInternet")
            
            hasVpn && hasInternet
        } catch (e: Exception) {
            false
        }
    }
    
    private fun startVpnBackground(dnsType: String) {
        coroutineScope.launch {
            // Check OVERLAY permission untuk Realme
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(this@VpnDnsService)) {
                    LogUtil.e(TAG, "No OVERLAY permission - Realme may kill service")
                }
            }
            
            val dnsServers = getDnsServers(dnsType)
            
            if (isRunning.get() && currentDns == dnsServers.first()) {
                LogUtil.d(TAG, "VPN sudah running dengan DNS sama: $currentDns")
                return@launch
            }
            
            // Multiple retry untuk Realme
            var success = false
            var retryCount = 0
            
            while (!success && retryCount < 3) {
                LogUtil.d(TAG, "VPN setup attempt ${retryCount + 1}")
                success = setupVpn(dnsType)
                
                if (success) {
                    // 🔥 CHECK traffic flow
                    delay(2000)
                    val trafficOk = checkVpnTrafficFlow()
                    
                    if (!trafficOk) {
                        LogUtil.w(TAG, "VPN up but traffic not flowing, retrying...")
                        success = false
                    }
                }
                
                if (!success) {
                    delay(1000L * (retryCount + 1))
                    retryCount++
                }
            }
            
            if (success) {
                // Acquire wake lock sebelum show notification
                acquireWakeLock()
                showNotification(dnsServers.first())
                
                LogUtil.d(TAG, "✅ VPN started dengan DNS: $dnsServers")
                
                sendBroadcast(Intent("VPN_DNS_STATUS").apply {
                    putExtra("status", "RUNNING")
                    putExtra("dns", dnsServers.first())
                    putExtra("bypass", realmeBypassApplied)
                })
            } else {
                LogUtil.e(TAG, "❌ Failed to start VPN after $retryCount attempts")
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
                realmeBypassApplied = false
                
                // Stop DNS hijack
                dnsHijackSocket?.close()
                dnsHijackSocket = null
                
                vpnInterface?.close()
                vpnInterface = null
                
                // Release wake lock
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
        
        // Stop action button
        val stopIntent = Intent(this, VpnDnsService::class.java).apply {
            action = ACTION_STOP_VPN
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val bypassStatus = if (realmeBypassApplied) "✅ Bypass ON" else "⚠️ Bypass OFF"
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🛡️ CedokBooster DNS Active")
            .setContentText("DNS: $dnsServer | $bypassStatus")
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
        
        // Cleanup wake lock
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                LogUtil.d(TAG, "WakeLock released in onDestroy")
            }
        }
        
        // Cleanup DNS hijack
        dnsHijackSocket?.close()
        
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
        fun w(tag: String, message: String) = android.util.Log.w(tag, message)
    }
}
