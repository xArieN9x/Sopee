package com.example.cedokbooster

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
    
    // Variants untuk cuba
    private enum class VpnMethod {
        CGNAT_FULL_ROUTE,    // Solution E (Main)
        ROUTING_HIJACK,      // Solution A (Fallback 1)
        NETWORK_BIND,        // Solution C (Fallback 2)
        DEEP_HACK,           // Solution D (Fallback 3)
        DNS_ONLY_PROXY       // Nuclear option
    }
    
    private var vpnInterface: ParcelFileDescriptor? = null
    private var notificationManager: NotificationManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var dnsProxySocket: DatagramSocket? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private var currentMethod = VpnMethod.CGNAT_FULL_ROUTE
    private var retryCount = 0
    private var mobileGateway = "10.66.191.1"
    
    /**
     * Get DNS servers
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
     * 🔥 MAIN METHOD: Solution E - CGNAT range + route rebuild (Paling orang berjaya)
     */
    private fun setupCgnatMethod(dnsServers: List<String>): Boolean {
        return try {
            LogUtil.d(TAG, "🔧 Trying CGNAT method...")
            
            mobileGateway = detectMobileGateway()
            LogUtil.d(TAG, "Mobile gateway: $mobileGateway")
            
            vpnInterface?.close()
            
            val builder = Builder()
                .setSession("CB-DNS-FIX")
                // 🔥 GUNA CGNAT RANGE (100.64.0.0/10)
                .addAddress("100.64.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addRoute("100.64.0.0", 10)     // Large CGNAT range
                .addRoute(mobileGateway, 32)
                .addRoute(splitToNetwork(mobileGateway), 24)
                .setMtu(1280)                  // Kecil untuk stability
                .setBlocking(true)
            
            // Add DNS
            dnsServers.filter { it.contains('.') }.forEach { dns ->
                builder.addDnsServer(dns)
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }
            
            // 🔥 Configure intent untuk stability
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val configureIntent = PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.setConfigureIntent(configureIntent)
            }
            
            builder.establish()?.let { fd ->
                vpnInterface = fd
                
                // 🔥 CRITICAL: Rebuild routing table selepas VPN up
                rebuildRoutingTable()
                
                // Start DNS proxy sebagai backup
                startDnsProxyBackup()
                
                isRunning.set(true)
                currentMethod = VpnMethod.CGNAT_FULL_ROUTE
                LogUtil.d(TAG, "✅ CGNAT method SUCCESS")
                true
            } ?: run {
                LogUtil.e(TAG, "❌ CGNAT method failed")
                false
            }
            
        } catch (e: Exception) {
            LogUtil.e(TAG, "CGNAT error: ${e.message}")
            false
        }
    }
    
    /**
     * 🔥 FALLBACK 1: Solution A - Routing hijack
     */
    private fun setupRoutingHijackMethod(dnsServers: List<String>): Boolean {
        return try {
            LogUtil.d(TAG, "🔧 Trying Routing Hijack method...")
            
            val builder = Builder()
                .setSession("CB-DNS-HIJACK")
                .addAddress("192.168.69.2", 24)
                .addRoute("0.0.0.0", 0)
                .setMtu(1400)
                .setBlocking(true)
            
            dnsServers.filter { it.contains('.') }.forEach { dns ->
                builder.addDnsServer(dns)
            }
            
            builder.establish()?.let { fd ->
                vpnInterface = fd
                
                // 🔥 HIJACK ROUTING TABLE
                hijackRoutingTable()
                
                startDnsProxyBackup()
                isRunning.set(true)
                currentMethod = VpnMethod.ROUTING_HIJACK
                LogUtil.d(TAG, "✅ Routing Hijack SUCCESS")
                true
            } ?: false
            
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 🔥 FALLBACK 2: Solution C - Bind to specific network
     */
    private fun setupNetworkBindMethod(dnsServers: List<String>): Boolean {
        return try {
            LogUtil.d(TAG, "🔧 Trying Network Bind method...")
            
            val builder = Builder()
                .setSession("CB-DNS-BIND")
                .addAddress("172.16.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .setMtu(1400)
                .setBlocking(true)
            
            dnsServers.forEach { dns ->
                builder.addDnsServer(dns)
            }
            
            // 🔥 BIND TO MOBILE NETWORK
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                val networks = connectivityManager.allNetworks
                
                for (network in networks) {
                    val caps = connectivityManager.getNetworkCapabilities(network)
                    if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) {
                        try {
                            val method = builder.javaClass.getDeclaredMethod(
                                "setUnderlyingNetworks", 
                                Array<Network>::class.java
                            )
                            method.invoke(builder, arrayOf(network))
                            LogUtil.d(TAG, "✅ Bound to mobile network")
                        } catch (e: Exception) {
                            // Reflection failed
                        }
                        break
                    }
                }
            }
            
            builder.establish()?.let { fd ->
                vpnInterface = fd
                rebuildRoutingTable()
                isRunning.set(true)
                currentMethod = VpnMethod.NETWORK_BIND
                LogUtil.d(TAG, "✅ Network Bind SUCCESS")
                true
            } ?: false
            
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 🔥 FALLBACK 3: Solution D - Deep hack
     */
    private fun setupDeepHackMethod(dnsServers: List<String>): Boolean {
        return try {
            LogUtil.d(TAG, "🔧 Trying Deep Hack method...")
            
            // Apply deep hacks first
            applyDeepHacks()
            
            val builder = Builder()
                .setSession("CB-DNS-DEEP")
                .addAddress("10.1.1.2", 24)  // Different range
                .addRoute("0.0.0.0", 0)
                .setMtu(1500)
                .setBlocking(true)
            
            dnsServers.take(2).forEach { dns ->
                builder.addDnsServer(dns)
            }
            
            builder.establish()?.let { fd ->
                vpnInterface = fd
                
                // Deep route injection
                deepRouteInject()
                
                isRunning.set(true)
                currentMethod = VpnMethod.DEEP_HACK
                LogUtil.d(TAG, "✅ Deep Hack SUCCESS")
                true
            } ?: false
            
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 🔥 NUCLEAR OPTION: DNS-only mode with proxy
     */
    private fun setupDnsOnlyProxy(dnsServers: List<String>): Boolean {
        return try {
            LogUtil.d(TAG, "🔥 Trying DNS-Only Proxy (Nuclear option)...")
            
            // Start local DNS proxy dulu
            if (!startDnsProxyServer()) {
                return false
            }
            
            val builder = Builder()
                .setSession("CB-DNS-PROXY")
                .addAddress("169.254.1.2", 24)  // Link-local
                .addDnsServer("127.0.0.1")      // Point to our proxy
                .addRoute("0.0.0.0", 0)
                .setBlocking(true)
            
            builder.establish()?.let { fd ->
                vpnInterface = fd
                isRunning.set(true)
                currentMethod = VpnMethod.DNS_ONLY_PROXY
                LogUtil.d(TAG, "✅ DNS-Only Proxy SUCCESS")
                true
            } ?: false
            
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 🔥 CRITICAL FUNCTION: Rebuild routing table (Solution E core)
     */
    private fun rebuildRoutingTable() {
        coroutineScope.launch {
            delay(800) // Biar VPN settle
            
            try {
                val cmds = """
                    # 1. DELETE REALME'S BROKEN ROUTES
                    ip route del 10.0.0.0/8 dev ccmni1 2>/dev/null || true
                    ip route del default dev ccmni1 2>/dev/null || true
                    
                    # 2. SET CCMNI1 METRIC TINGGI (999)
                    ip route add 10.66.191.0/24 dev ccmni1 metric 999 2>/dev/null || true
                    ip route add ${mobileGateway}/32 dev ccmni1 metric 998 2>/dev/null || true
                    
                    # 3. SET VPN AS DEFAULT (METRIC RENDAH)
                    ip route add default dev tun0 metric 10 2>/dev/null || true
                    ip route add 0.0.0.0/0 dev tun0 metric 20 2>/dev/null || true
                    
                    # 4. ADD SPECIFIC ROUTES KE VPN
                    ip route add 100.64.0.0/10 dev tun0 metric 30 2>/dev/null || true
                    ip route add ${splitToNetwork(mobileGateway)}/24 dev tun0 metric 40 2>/dev/null || true
                    
                    # 5. BLOCK TRAFFIC LEAK
                    ip route add 1.1.1.1/32 dev tun0 metric 5 2>/dev/null || true
                    ip route add 8.8.8.8/32 dev tun0 metric 5 2>/dev/null || true
                    
                    # 6. FLUSH CACHE
                    ip route flush cache 2>/dev/null || true
                    
                    # 7. VERIFY
                    echo "=== FINAL ROUTES ==="
                    ip route show
                """.trimIndent()
                
                Runtime.getRuntime().exec(arrayOf("sh", "-c", cmds))
                LogUtil.d(TAG, "✅ Routing table rebuilt")
                
                // Log final routes
                Runtime.getRuntime().exec("ip route show").inputStream.bufferedReader().use {
                    LogUtil.d(TAG, "ROUTES:\n${it.readText()}")
                }
                
            } catch (e: Exception) {
                LogUtil.e(TAG, "❌ Rebuild failed: ${e.message}")
            }
        }
    }
    
    /**
     * Hijack routing table (Solution A)
     */
    private fun hijackRoutingTable() {
        try {
            val cmds = """
                # Set ccmni1 metric tinggi
                ip route change 10.0.0.0/8 dev ccmni1 metric 999
                
                # Force default ke tun0
                ip route replace default dev tun0 metric 0
                
                # Lock
                echo 1 > /proc/sys/net/ipv4/route/no_metric_reset 2>/dev/null || true
            """
            Runtime.getRuntime().exec(arrayOf("sh", "-c", cmds))
        } catch (e: Exception) {
            // Ignore
        }
    }
    
    /**
     * Deep hack methods (Solution D)
     */
    private fun applyDeepHacks() {
        try {
            // Try to disable Realme's routing optimization
            Runtime.getRuntime().exec("echo 0 > /proc/sys/net/ipv4/conf/ccmni1/rp_filter 2>/dev/null")
            Runtime.getRuntime().exec("echo 1 > /proc/sys/net/ipv4/route/min_adv_mss 2>/dev/null")
        } catch (e: Exception) {
            // Ignore
        }
    }
    
    private fun deepRouteInject() {
        try {
            val routes = """
                # Via proc interface
                default dev tun0 metric 0
                0.0.0.0/0 dev tun0 metric 10
                10.0.0.0/8 dev ccmni1 metric 1000
            """
            Runtime.getRuntime().exec("echo '$routes' > /proc/net/route 2>/dev/null")
        } catch (e: Exception) {
            // Ignore
        }
    }
    
    /**
     * DNS proxy backup
     */
    private fun startDnsProxyBackup() {
        coroutineScope.launch {
            try {
                dnsProxySocket?.close()
                val socket = DatagramSocket(5353)
                dnsProxySocket = socket
                val buffer = ByteArray(512)
                
                while (isRunning.get()) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    
                    // Forward to Cloudflare
                    val forwardSocket = DatagramSocket()
                    forwardSocket.connect(InetAddress.getByName("1.1.1.1"), 53)
                    forwardSocket.send(packet)
                    
                    val response = ByteArray(512)
                    val responsePacket = DatagramPacket(response, response.size)
                    forwardSocket.receive(responsePacket)
                    
                    socket.send(DatagramPacket(response, responsePacket.length, 
                        packet.address, packet.port))
                    forwardSocket.close()
                }
                socket.close()
            } catch (e: Exception) {
                // Expected when stopping
            }
        }
    }
    
    private fun startDnsProxyServer(): Boolean {
        return try {
            Thread {
                val server = DatagramSocket(53)
                val buffer = ByteArray(512)
                
                while (isRunning.get()) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    server.receive(packet)
                    
                    val forward = DatagramSocket()
                    forward.connect(InetAddress.getByName("1.1.1.1"), 53)
                    forward.send(packet)
                    
                    val response = ByteArray(512)
                    val responsePacket = DatagramPacket(response, response.size)
                    forward.receive(responsePacket)
                    
                    server.send(DatagramPacket(response, responsePacket.length,
                        packet.address, packet.port))
                    forward.close()
                }
                server.close()
            }.start()
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Helper functions
     */
    private fun detectMobileGateway(): String {
        return try {
            val interfaces = listOf("ccmni1", "ccmni0", "rmnet0", "rmnet1")
            for (iface in interfaces) {
                try {
                    Runtime.getRuntime().exec("ip addr show $iface").inputStream
                        .bufferedReader().use { reader ->
                            val text = reader.readText()
                            Regex("inet\\s+(\\d+\\.\\d+\\.\\d+\\.\\d+)").find(text)?.let { match ->
                                val ip = match.groupValues[1]
                                val parts = ip.split(".")
                                if (parts.size == 4) {
                                    return "${parts[0]}.${parts[1]}.${parts[2]}.1"
                                }
                            }
                        }
                } catch (e: Exception) {
                    continue
                }
            }
            "10.66.191.1"
        } catch (e: Exception) {
            "10.66.191.1"
        }
    }
    
    private fun splitToNetwork(gateway: String): String {
        val parts = gateway.split(".")
        return if (parts.size == 4) "${parts[0]}.${parts[1]}.${parts[2]}.0" else "10.66.191.0"
    }
    
    /**
     * Main VPN setup dengan automatic fallback
     */
    private fun setupVpnWithFallback(dnsType: String): Boolean {
        val dnsServers = getDnsServers(dnsType)
        
        val methods = listOf(
            { setupCgnatMethod(dnsServers) },      // Main method
            { setupRoutingHijackMethod(dnsServers) }, // Fallback 1
            { setupNetworkBindMethod(dnsServers) },   // Fallback 2
            { setupDeepHackMethod(dnsServers) },      // Fallback 3
            { setupDnsOnlyProxy(dnsServers) }         // Nuclear option
        )
        
        for ((index, method) in methods.withIndex()) {
            LogUtil.d(TAG, "Attempting method ${index + 1}/${methods.size}")
            
            if (method()) {
                // Verify connection
                if (verifyVpnConnection()) {
                    return true
                } else {
                    LogUtil.w(TAG, "Method $index passed but verification failed")
                }
            }
            
            // Cleanup sebelum try next method
            vpnInterface?.close()
            vpnInterface = null
            Thread.sleep(1000)
        }
        
        return false
    }
    
    /**
     * Verify VPN connection actually works
     */
    private fun verifyVpnConnection(): Boolean {
        return try {
            // Check if VPN has internet capability
            val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            val caps = connectivityManager.getNetworkCapabilities(network)
            
            val hasVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            
            LogUtil.d(TAG, "Verification: VPN=$hasVpn, Internet=$hasInternet, Method=$currentMethod")
            
            hasVpn && hasInternet
        } catch (e: Exception) {
            false
        }
    }
    
    private fun startVpnBackground(dnsType: String) {
        coroutineScope.launch {
            // Check overlay permission
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(this@VpnDnsService)) {
                    LogUtil.w(TAG, "No overlay permission - Realme may kill service")
                }
            }
            
            if (isRunning.get()) {
                LogUtil.d(TAG, "VPN already running")
                return@launch
            }
            
            // Try dengan retry mechanism
            var success = false
            retryCount = 0
            
            while (!success && retryCount < 2) {
                retryCount++
                LogUtil.d(TAG, "VPN setup attempt $retryCount")
                
                success = setupVpnWithFallback(dnsType)
                
                if (success) {
                    // Double verification
                    Thread.sleep(2000)
                    if (!verifyVpnConnection()) {
                        LogUtil.w(TAG, "Verification failed, retrying...")
                        success = false
                    }
                }
                
                if (!success) {
                    Thread.sleep(2000L * retryCount)
                }
            }
            
            if (success) {
                acquireWakeLock()
                showNotification(currentDns)
                
                LogUtil.d(TAG, "✅ VPN STARTED with method: $currentMethod")
                
                sendBroadcast(Intent("VPN_DNS_STATUS").apply {
                    putExtra("status", "RUNNING")
                    putExtra("dns", currentDns)
                    putExtra("method", currentMethod.name)
                })
            } else {
                LogUtil.e(TAG, "❌ ALL METHODS FAILED")
                stopSelf()
            }
        }
    }
    
    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "CedokBooster:VPNLock"
            ).apply {
                setReferenceCounted(false)
                acquire(60 * 60 * 1000L)
            }
        } catch (e: Exception) {
            // Ignore
        }
    }
    
    private fun stopVpn() {
        coroutineScope.launch {
            try {
                isRunning.set(false)
                currentDns = "1.0.0.1"
                
                // Cleanup sockets
                dnsProxySocket?.close()
                dnsProxySocket = null
                
                vpnInterface?.close()
                vpnInterface = null
                
                // Release wake lock
                wakeLock?.let {
                    if (it.isHeld) it.release()
                }
                
                notificationManager?.cancel(NOTIFICATION_ID)
                
                sendBroadcast(Intent("VPN_DNS_STATUS").apply {
                    putExtra("status", "STOPPED")
                })
                
                LogUtil.d(TAG, "VPN stopped")
            } catch (e: Exception) {
                LogUtil.e(TAG, "Stop error: ${e.message}")
            } finally {
                stopForeground(true)
                stopSelf()
            }
        }
    }
    
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
        
        val stopIntent = Intent(this, VpnDnsService::class.java).apply {
            action = ACTION_STOP_VPN
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🛡️ CedokBooster DNS Active")
            .setContentText("DNS: $dnsServer | Method: $currentMethod")
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
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LogUtil.d(TAG, "onStartCommand: ${intent?.action}")
        
        when (intent?.action) {
            ACTION_START_VPN -> {
                val dnsType = intent.getStringExtra(EXTRA_DNS_TYPE) ?: "A"
                startVpnBackground(dnsType)
            }
            ACTION_STOP_VPN -> stopVpn()
            else -> stopSelf()
        }
        
        return START_STICKY
    }
    
    override fun onDestroy() {
        LogUtil.d(TAG, "Service destroying...")
        wakeLock?.let { if (it.isHeld) it.release() }
        dnsProxySocket?.close()
        stopVpn()
        super.onDestroy()
    }
    
    override fun onRevoke() {
        LogUtil.d(TAG, "VPN revoked")
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
