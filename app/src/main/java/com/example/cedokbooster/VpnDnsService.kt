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
    
    // Method untuk cuba (urutan berdasarkan success rate)
    private enum class VpnMethod {
        REALME_HYBRID,      // Main: Private DNS + Proxy (paling success)
        DNS_ONLY_PROXY,     // Fallback 1: Local DNS proxy
        CGNAT_FULL,         // Fallback 2: CGNAT range
        ROUTE_HACK,         // Fallback 3: Route order hack
        LEGACY_VPN          // Fallback 4: Basic VPN
    }
    
    private var vpnInterface: ParcelFileDescriptor? = null
    private var notificationManager: NotificationManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var dnsProxySocket: DatagramSocket? = null
    private var dnsProxyThread: Thread? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private var currentMethod = VpnMethod.REALME_HYBRID
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
     * 🔥 HELPER: Set Private DNS (Android 9+)
     */
    private fun setPrivateDns(hostname: String = "one.one.one.one"): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Settings.Global.putString(contentResolver, "private_dns_mode", "hostname")
                Settings.Global.putString(contentResolver, "private_dns_specifier", hostname)
                LogUtil.d(TAG, "✅ Private DNS set to: $hostname")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            LogUtil.w(TAG, "⚠️ Private DNS failed: ${e.message}")
            false
        }
    }
    
    /**
     * 🔥 HELPER: Start Local DNS Proxy (Non-root compatible)
     */
    private fun startLocalDnsProxy(targetDns: String = "1.1.1.1"): Boolean {
        return try {
            // Stop existing
            dnsProxyThread?.interrupt()
            dnsProxySocket?.close()
            
            dnsProxyThread = Thread {
                try {
                    val server = DatagramSocket(5353)  // Port 5353 untuk non-root
                    dnsProxySocket = server
                    val buffer = ByteArray(512)
                    
                    while (!Thread.currentThread().isInterrupted && isRunning.get()) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        server.receive(packet)
                        
                        // Forward ke target DNS
                        val forward = DatagramSocket()
                        forward.connect(InetAddress.getByName(targetDns), 53)
                        forward.send(packet)
                        
                        val response = ByteArray(512)
                        val responsePacket = DatagramPacket(response, response.size)
                        forward.receive(responsePacket)
                        
                        // Send back
                        server.send(DatagramPacket(
                            response, 
                            responsePacket.length, 
                            packet.address, 
                            packet.port
                        ))
                        forward.close()
                    }
                    server.close()
                } catch (e: Exception) {
                    if (!Thread.currentThread().isInterrupted) {
                        LogUtil.w(TAG, "DNS Proxy error: ${e.message}")
                    }
                }
            }
            
            dnsProxyThread?.start()
            LogUtil.d(TAG, "✅ Local DNS Proxy started on port 5353")
            true
            
        } catch (e: Exception) {
            LogUtil.e(TAG, "❌ DNS Proxy failed: ${e.message}")
            false
        }
    }
    
    /**
     * 🔥 HELPER: Split gateway to network
     */
    private fun splitToNetwork(gateway: String): String {
        val parts = gateway.split(".")
        return if (parts.size == 4) "${parts[0]}.${parts[1]}.${parts[2]}.0" 
               else "10.66.191.0"
    }
    
    /**
     * 🔥 MAIN METHOD 1: REALME HYBRID (Private DNS + Proxy) - PALING SUCCESS!
     */
    private fun setupRealmHybrid(dnsServers: List<String>): Boolean {
        return try {
            LogUtil.d(TAG, "🚀 Trying REALME HYBRID method...")
            
            // Step 1: Set Private DNS kalau Android 9+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                setPrivateDns("one.one.one.one")
                Thread.sleep(300)
            }
            
            // Step 2: Start local DNS proxy
            startLocalDnsProxy(dnsServers.first())
            
            // Step 3: Setup VPN dengan config khusus
            mobileGateway = detectMobileGateway()
            vpnInterface?.close()
            
            val builder = Builder()
                .setSession("CB-REALME-HYBRID")
                .addAddress("100.64.0.2", 24)      // CGNAT range (kurang conflict)
                .addDnsServer("127.0.0.1")         // Point to local proxy
                .addDnsServer(dnsServers.first())  // Backup
                .setMtu(1280)
                .setBlocking(true)
            
            // 🔥 ROUTING ORDER PENTING UNTUK REALME:
            // 1. Default route DULU
            builder.addRoute("0.0.0.0", 0)
            
            // 2. VPN network
            builder.addRoute("100.64.0.0", 24)
            
            // 3. Mobile network
            builder.addRoute(splitToNetwork(mobileGateway), 24)
            
            // 4. DNS servers (force melalui VPN)
            builder.addRoute("1.1.1.1", 32)
            builder.addRoute("1.0.0.1", 32)
            
            // 5. Exclude local gateway
            builder.addRoute(mobileGateway, 32)
            
            // 🔥 REALME FIX: Set untuk semua networks
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                builder.setUnderlyingNetworks(null)  // All networks
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
                
                // Step 4: Apply system properties hack
                applySystemHacks()
                
                isRunning.set(true)
                currentMethod = VpnMethod.REALME_HYBRID
                LogUtil.d(TAG, "✅ REALME HYBRID SUCCESS")
                true
            } ?: run {
                LogUtil.e(TAG, "❌ REALME HYBRID failed to establish")
                false
            }
            
        } catch (e: Exception) {
            LogUtil.e(TAG, "REALME HYBRID error: ${e.message}")
            false
        }
    }
    
    /**
     * 🔥 METHOD 2: DNS ONLY PROXY (Fallback 1)
     */
    private fun setupDnsOnlyProxy(dnsServers: List<String>): Boolean {
        return try {
            LogUtil.d(TAG, "🔄 Trying DNS-ONLY PROXY method...")
            
            // Start proxy dulu
            if (!startLocalDnsProxy(dnsServers.first())) {
                return false
            }
            
            val builder = Builder()
                .setSession("CB-DNS-PROXY")
                .addAddress("169.254.1.2", 24)  // Link-local range
                .addDnsServer("127.0.0.1")      // Local proxy
                .addRoute("0.0.0.0", 0)         // Still claim all
                .setBlocking(true)
            
            builder.establish()?.let { fd ->
                vpnInterface = fd
                currentMethod = VpnMethod.DNS_ONLY_PROXY
                LogUtil.d(TAG, "✅ DNS-ONLY PROXY SUCCESS")
                true
            } ?: false
            
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 🔥 METHOD 3: CGNAT FULL (Fallback 2)
     */
    private fun setupCgnatFull(dnsServers: List<String>): Boolean {
        return try {
            LogUtil.d(TAG, "🔄 Trying CGNAT FULL method...")
            
            mobileGateway = detectMobileGateway()
            
            val builder = Builder()
                .setSession("CB-CGNAT")
                .addAddress("100.64.0.2", 24)
                .addDnsServer(dnsServers.first())
                .addRoute("0.0.0.0", 0)
                .addRoute("100.64.0.0", 10)
                .addRoute("1.1.1.1", 32)
                .addRoute("1.0.0.1", 32)
                .addRoute(splitToNetwork(mobileGateway), 24)
                .setBlocking(true)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                builder.setUnderlyingNetworks(null)
            }
            
            builder.establish()?.let { fd ->
                vpnInterface = fd
                currentMethod = VpnMethod.CGNAT_FULL
                LogUtil.d(TAG, "✅ CGNAT FULL SUCCESS")
                true
            } ?: false
            
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 🔥 METHOD 4: ROUTE HACK (Fallback 3)
     */
    private fun setupRouteHack(dnsServers: List<String>): Boolean {
        return try {
            LogUtil.d(TAG, "🔄 Trying ROUTE HACK method...")
            
            val builder = Builder()
                .setSession("CB-ROUTE-HACK")
                .addAddress("192.168.69.2", 24)
                .addDnsServer(dnsServers.first())
                // Route order hack untuk Realme
                .addRoute("0.0.0.0", 0)
                .addRoute("192.168.69.0", 24)
                .addRoute("1.1.1.1", 32)
                .addRoute("8.8.8.8", 32)
                .addRoute("10.66.191.1", 32)  // Exclude gateway
                .setBlocking(true)
            
            builder.establish()?.let { fd ->
                vpnInterface = fd
                currentMethod = VpnMethod.ROUTE_HACK
                LogUtil.d(TAG, "✅ ROUTE HACK SUCCESS")
                true
            } ?: false
            
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 🔥 METHOD 5: LEGACY VPN (Fallback 4)
     */
    private fun setupLegacyVpn(dnsServers: List<String>): Boolean {
        return try {
            LogUtil.d(TAG, "🔄 Trying LEGACY VPN method...")
            
            val builder = Builder()
                .setSession("CB-LEGACY")
                .addAddress("10.0.0.2", 24)
                .addDnsServer(dnsServers.first())
                .addRoute("0.0.0.0", 0)
                .setBlocking(true)
            
            builder.establish()?.let { fd ->
                vpnInterface = fd
                currentMethod = VpnMethod.LEGACY_VPN
                LogUtil.d(TAG, "✅ LEGACY VPN SUCCESS")
                true
            } ?: false
            
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 🔥 System hacks untuk Realme (Non-root compatible)
     */
    private fun applySystemHacks() {
        try {
            // Set system properties
            System.setProperty("net.dns1", "1.1.1.1")
            System.setProperty("net.dns2", "1.0.0.1")
            
            LogUtil.d(TAG, "✅ System hacks applied")
        } catch (e: Exception) {
            // Ignore
        }
    }
    
    /**
     * 🔥 Detect mobile gateway
     */
    private fun detectMobileGateway(): String {
        return try {
            // Try pelbagai mobile interfaces
            val interfaces = listOf("ccmni1", "ccmni0", "rmnet0", "rmnet1", "rmnet_data0")
            
            for (iface in interfaces) {
                try {
                    Runtime.getRuntime().exec("ip addr show $iface").inputStream
                        .bufferedReader().use { reader ->
                            val text = reader.readText()
                            if (text.contains("state UP") || text.contains("state UNKNOWN")) {
                                Regex("inet\\s+(\\d+\\.\\d+\\.\\d+\\.\\d+)").find(text)?.let { match ->
                                    val ip = match.groupValues[1]
                                    val parts = ip.split(".")
                                    if (parts.size == 4) {
                                        val gateway = "${parts[0]}.${parts[1]}.${parts[2]}.1"
                                        LogUtil.d(TAG, "Found $iface: $ip -> Gateway: $gateway")
                                        return gateway
                                    }
                                }
                            }
                        }
                } catch (e: Exception) {
                    continue
                }
            }
            
            // Default fallback
            "10.66.191.1"
            
        } catch (e: Exception) {
            "10.66.191.1"
        }
    }
    
    /**
     * 🔥 Auto fallback system
     */
    private fun setupVpnWithFallback(dnsType: String): Boolean {
        val dnsServers = getDnsServers(dnsType)
        
        // Urutan methods berdasarkan success rate
        val methods = listOf(
            { setupRealmHybrid(dnsServers) },     // Main method (paling success)
            { setupDnsOnlyProxy(dnsServers) },    // Fallback 1
            { setupCgnatFull(dnsServers) },       // Fallback 2
            { setupRouteHack(dnsServers) },       // Fallback 3
            { setupLegacyVpn(dnsServers) }        // Fallback 4
        )
        
        for ((index, method) in methods.withIndex()) {
            val methodName = VpnMethod.values()[index]
            LogUtil.d(TAG, "Attempt ${index + 1}/${methods.size}: $methodName")
            
            if (method()) {
                // Verify VPN actually works
                if (verifyVpnConnection()) {
                    LogUtil.d(TAG, "✅ Method $methodName VERIFIED")
                    return true
                } else {
                    LogUtil.w(TAG, "⚠️ Method $methodName failed verification")
                }
            }
            
            // Cleanup sebelum try next method
            vpnInterface?.close()
            vpnInterface = null
            Thread.sleep(800)
        }
        
        return false
    }
    
    /**
     * 🔥 Verify VPN connection
     */
    private fun verifyVpnConnection(): Boolean {
        return try {
            val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            val caps = connectivityManager.getNetworkCapabilities(network)
            
            val hasVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            
            LogUtil.d(TAG, "Verification: VPN=$hasVpn, Method=$currentMethod")
            
            // Untuk Realme, VPN=true sudah OK
            hasVpn
            
        } catch (e: Exception) {
            false
        }
    }
    
    private fun startVpnBackground(dnsType: String) {
        coroutineScope.launch {
            // Check overlay permission untuk Realme
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(this@VpnDnsService)) {
                    LogUtil.w(TAG, "⚠️ No overlay permission - Realme may kill service")
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
                    delay(1500)
                    if (!verifyVpnConnection()) {
                        LogUtil.w(TAG, "Verification failed, retrying...")
                        success = false
                    }
                }
                
                if (!success) {
                    delay(2000L * retryCount)
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
                LogUtil.d(TAG, "WakeLock acquired")
            }
        } catch (e: Exception) {
            LogUtil.e(TAG, "Failed to acquire WakeLock: ${e.message}")
        }
    }
    
    private fun stopVpn() {
        coroutineScope.launch {
            try {
                isRunning.set(false)
                currentDns = "1.0.0.1"
                
                // Stop DNS proxy
                dnsProxyThread?.interrupt()
                dnsProxySocket?.close()
                dnsProxyThread = null
                dnsProxySocket = null
                
                // Restore Private DNS kalau perlu
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try {
                        Settings.Global.putString(contentResolver, "private_dns_mode", "off")
                    } catch (e: Exception) {
                        // Ignore
                    }
                }
                
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
                
                LogUtil.d(TAG, "✅ VPN stopped completely")
                
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
        
        // Stop action button
        val stopIntent = Intent(this, VpnDnsService::class.java).apply {
            action = ACTION_STOP_VPN
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val statusText = when (currentMethod) {
            VpnMethod.REALME_HYBRID -> "Hybrid Mode ✓"
            VpnMethod.DNS_ONLY_PROXY -> "DNS Proxy ✓"
            VpnMethod.CGNAT_FULL -> "CGNAT Mode ✓"
            VpnMethod.ROUTE_HACK -> "Route Hack ✓"
            VpnMethod.LEGACY_VPN -> "Legacy Mode ✓"
        }
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🛡️ CedokBooster DNS Active")
            .setContentText("DNS: $dnsServer | $statusText")
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
        
        // Cleanup
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                LogUtil.d(TAG, "WakeLock released in onDestroy")
            }
        }
        
        dnsProxyThread?.interrupt()
        dnsProxySocket?.close()
        
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
