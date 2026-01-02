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
    
    // Battle methods
    private enum class BattleMethod {
        NUCLEAR_ROUTE_HIJACK,
        REALME_PROPS_OVERRIDE,
        DNS_PROXY_AGGRESSIVE,
        INTERFACE_SPOOFING,
        LAST_RESORT_REFLECTION
    }
    
    private var vpnInterface: ParcelFileDescriptor? = null
    private var notificationManager: NotificationManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var dnsProxySocket: DatagramSocket? = null
    private var dnsProxyThread: Thread? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private var currentMethod = BattleMethod.NUCLEAR_ROUTE_HIJACK
    private var retryCount = 0
    private var mobileGateway = "10.66.191.1"
    
    private fun getDnsServers(type: String): List<String> {
        return when (type.uppercase()) {
            "A" -> listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001")
            "B" -> listOf("8.8.8.8", "8.8.4.4", "2001:4860:4860::8888", "2001:4860:4860::8844")
            else -> listOf("9.9.9.9", "149.112.112.112", "2620:fe::fe", "2620:fe::9")
        }.also {
            currentDns = it.first()
        }
    }
    
    private fun setupNuclearRouteHijack(dnsServers: List<String>): Boolean {
        return try {
            LogUtil.d(TAG, "NUCLEAR ROUTE HIJACK ACTIVATED")
            
            mobileGateway = detectMobileGateway()
            vpnInterface?.close()
            
            overrideRealmeNetworkProps()
            
            val builder = Builder()
                .setSession("CB-NUCLEAR-HIJACK")
                .addAddress("100.64.0.2", 24)
                .setMtu(1280)
                .setBlocking(true)
            
            dnsServers.forEach { dns ->
                builder.addDnsServer(dns)
            }
            
            repeat(3) {
                builder.addRoute("0.0.0.0", 0)
            }
            
            builder.addRoute("10.66.191.0", 24)
            builder.addRoute(mobileGateway, 32)
            builder.addRoute("10.0.0.0", 8)
            builder.addRoute("1.1.1.1", 32)
            builder.addRoute("1.0.0.1", 32)
            builder.addRoute("8.8.8.8", 32)
            builder.addRoute("100.64.0.0", 24)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val method = builder.javaClass.getDeclaredMethod(
                        "setUnderlyingNetworks", 
                        Array<Network>::class.java
                    )
                    method.invoke(builder, null as Array<Network>?)
                    LogUtil.d(TAG, "Bound to ALL networks")
                } catch (e: Exception) {
                    // Continue
                }
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val method = builder.javaClass.getDeclaredMethod(
                        "setConfigureIntent", 
                        PendingIntent::class.java
                    )
                    method.invoke(builder, null)
                } catch (e: Exception) {
                    // Continue
                }
            }
            
            builder.establish()?.let { fd ->
                vpnInterface = fd
                
                coroutineScope.launch {
                    delay(1000)
                    
                    try {
                        Runtime.getRuntime().exec(arrayOf(
                            "sh", "-c",
                            "ip route replace default dev tun0 metric 50 2>/dev/null || true;" +
                            "ip route flush cache 2>/dev/null || true;" +
                            "echo 0 > /proc/sys/net/ipv4/conf/ccmni1/rp_filter 2>/dev/null || true"
                        ))
                    } catch (e: Exception) {
                        // Non-root limitation
                    }
                    
                    startAggressiveDnsProxy(dnsServers.first())
                }
                
                isRunning.set(true)
                currentMethod = BattleMethod.NUCLEAR_ROUTE_HIJACK
                LogUtil.d(TAG, "NUCLEAR HIJACK SUCCESS")
                true
            } ?: run {
                LogUtil.e(TAG, "Nuclear hijack failed")
                false
            }
            
        } catch (e: Exception) {
            LogUtil.e(TAG, "Nuclear error: ${e.message}")
            false
        }
    }
    
    private fun overrideRealmeNetworkProps() {
        try {
            val props = listOf(
                "setprop oppo.service.datafree.enable 1",
                "setprop persist.oppo.network.operator 0",
                "setprop net.tethering.noprovisioning true",
                "setprop net.dns1 1.1.1.1",
                "setprop net.dns2 1.0.0.1",
                "setprop net.rmnet0.dns1 1.1.1.1",
                "setprop net.rmnet0.dns2 1.0.0.1"
            )
            
            for (cmd in props) {
                try {
                    Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                    Thread.sleep(50)
                } catch (e: Exception) {
                    // Continue
                }
            }
            
            LogUtil.d(TAG, "Realme properties overridden")
            
        } catch (e: Exception) {
            LogUtil.w(TAG, "Props override failed")
        }
    }
    
    private fun startAggressiveDnsProxy(targetDns: String) {
        try {
            dnsProxyThread?.interrupt()
            dnsProxySocket?.close()
            
            dnsProxyThread = Thread {
                try {
                    val server = DatagramSocket(5353)
                    dnsProxySocket = server
                    val buffer = ByteArray(512)
                    
                    LogUtil.d(TAG, "DNS Proxy active on port 5353")
                    
                    while (!Thread.currentThread().isInterrupted && isRunning.get()) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        server.receive(packet)
                        
                        val forward = DatagramSocket()
                        forward.connect(InetAddress.getByName(targetDns), 53)
                        forward.send(packet)
                        
                        val response = ByteArray(512)
                        val responsePacket = DatagramPacket(response, response.size)
                        forward.receive(responsePacket)
                        
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
                        LogUtil.w(TAG, "DNS Proxy stopped: ${e.message}")
                    }
                }
            }
            
            dnsProxyThread?.start()
            
        } catch (e: Exception) {
            LogUtil.e(TAG, "DNS Proxy failed: ${e.message}")
        }
    }
    
    private fun setupInterfaceSpoofing(dnsServers: List<String>): Boolean {
        return try {
            LogUtil.d(TAG, "Trying INTERFACE SPOOFING")
            
            val builder = Builder()
                .setSession("CB-INTERFACE-SPOOF")
                .addAddress("192.168.200.2", 24)
                .addDnsServer(dnsServers.first())
                .addRoute("0.0.0.0", 0)
                .setBlocking(true)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val method = builder.javaClass.getDeclaredMethod(
                        "setInterface", 
                        String::class.java
                    )
                    method.invoke(builder, "dns0")
                    LogUtil.d(TAG, "Interface name changed to dns0")
                } catch (e: Exception) {
                    // Continue
                }
            }
            
            builder.establish()?.let { fd ->
                vpnInterface = fd
                currentMethod = BattleMethod.INTERFACE_SPOOFING
                true
            } ?: false
            
        } catch (e: Exception) {
            false
        }
    }
    
    private fun setupReflectionNuclear(dnsServers: List<String>): Boolean {
        return try {
            LogUtil.d(TAG, "ACTIVATING REFLECTION NUCLEAR")
            
            val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) 
                as ConnectivityManager
            
            try {
                val method = connectivityManager.javaClass.getDeclaredMethod(
                    "setVpnDefaultRoute", 
                    Boolean::class.java
                )
                method.isAccessible = true
                method.invoke(connectivityManager, true)
                LogUtil.d(TAG, "Reflection: VPN set as default route")
            } catch (e: Exception) {
                // Method not found
            }
            
            try {
                val netCapClass = Class.forName("android.net.NetworkCapabilities")
                val removeCapMethod = netCapClass.getDeclaredMethod(
                    "removeCapability", 
                    Int::class.javaPrimitiveType
                )
                removeCapMethod.isAccessible = true
                removeCapMethod.invoke(null, 15)
                LogUtil.d(TAG, "Reflection: NOT_VPN capability removed")
            } catch (e: Exception) {
                // Continue
            }
            
            val builder = Builder()
                .setSession("CB-REFLECTION-NUKE")
                .addAddress("10.1.1.2", 24)
                .addDnsServer(dnsServers.first())
                .addRoute("0.0.0.0", 0)
                .setBlocking(true)
            
            builder.establish()?.let { fd ->
                vpnInterface = fd
                currentMethod = BattleMethod.LAST_RESORT_REFLECTION
                true
            } ?: false
            
        } catch (e: Exception) {
            false
        }
    }
    
    private fun launchNuclearBattle(dnsType: String): Boolean {
        val dnsServers = getDnsServers(dnsType)
        
        val battleMethods = listOf(
            { setupNuclearRouteHijack(dnsServers) },
            { setupInterfaceSpoofing(dnsServers) },
            { setupReflectionNuclear(dnsServers) }
        )
        
        for ((index, method) in battleMethods.withIndex()) {
            val methodName = BattleMethod.values()[index]
            LogUtil.d(TAG, "BATTLE PHASE ${index + 1}: $methodName")
            
            if (method()) {
                if (verifyNuclearVictory()) {
                    LogUtil.d(TAG, "VICTORY with $methodName")
                    logBattleMetrics()
                    return true
                } else {
                    LogUtil.w(TAG, "$methodName passed but verification failed")
                }
            }
            
            vpnInterface?.close()
            vpnInterface = null
            Thread.sleep(1000)
        }
        
        return false
    }
    
    private fun verifyNuclearVictory(): Boolean {
        return try {
            val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) 
                as ConnectivityManager
            
            val network = connectivityManager.activeNetwork
            val caps = connectivityManager.getNetworkCapabilities(network)
            
            val hasVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            
            if (hasVpn) {
                testDnsResolution()
            }
            
            LogUtil.d(TAG, "Verification: VPN=$hasVpn, Method=$currentMethod")
            hasVpn
            
        } catch (e: Exception) {
            false
        }
    }
    
    private fun testDnsResolution() {
        coroutineScope.launch {
            try {
                val addresses = InetAddress.getAllByName("google.com")
                LogUtil.d(TAG, "DNS Test: ${addresses.size} addresses resolved")
                
                val dnsCheck = Runtime.getRuntime().exec("getprop net.dns1")
                val dns = dnsCheck.inputStream.bufferedReader().readText().trim()
                LogUtil.d(TAG, "Current DNS: $dns")
                
                if (dns.contains("1.1.1.1") || dns.contains("8.8.8.8")) {
                    LogUtil.d(TAG, "DNS HIJACK SUCCESSFUL")
                }
                
            } catch (e: Exception) {
                LogUtil.w(TAG, "DNS Test failed")
            }
        }
    }
    
    private fun logBattleMetrics() {
        try {
            Runtime.getRuntime().exec("ip route show").inputStream
                .bufferedReader().use { reader ->
                    val routes = reader.readText()
                    LogUtil.d(TAG, "FINAL ROUTES:\n$routes")
                }
            
            Runtime.getRuntime().exec("getprop | grep dns").inputStream
                .bufferedReader().use { reader ->
                    val dnsProps = reader.readText()
                    LogUtil.d(TAG, "DNS PROPERTIES:\n$dnsProps")
                }
                
        } catch (e: Exception) {
            // Ignore errors
        }
    }
    
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
    
    private fun startNuclearBattle(dnsType: String) {
        coroutineScope.launch {
            if (isRunning.get()) {
                LogUtil.d(TAG, "Battle already in progress")
                return@launch
            }
            
            LogUtil.d(TAG, "INITIATING NUCLEAR BATTLE SEQUENCE")
            
            var victory = false
            retryCount = 0
            
            while (!victory && retryCount < 2) {
                retryCount++
                LogUtil.d(TAG, "BATTLE ATTEMPT $retryCount")
                
                victory = launchNuclearBattle(dnsType)
                
                if (victory) {
                    delay(2000)
                    if (!verifyNuclearVictory()) {
                        LogUtil.w(TAG, "Victory verification failed, re-engaging")
                        victory = false
                    }
                }
                
                if (!victory) {
                    delay(3000L * retryCount)
                }
            }
            
            if (victory) {
                acquireWakeLock()
                showVictoryNotification()
                
                LogUtil.d(TAG, "NUCLEAR BATTLE VICTORY Method: $currentMethod")
                
                sendBroadcast(Intent("VPN_BATTLE_STATUS").apply {
                    putExtra("status", "VICTORY")
                    putExtra("method", currentMethod.name)
                    putExtra("dns", currentDns)
                })
            } else {
                LogUtil.e(TAG, "ALL BATTLE METHODS FAILED")
                stopSelf()
            }
        }
    }
    
    private fun showVictoryNotification() {
        notificationManager = getSystemService(NotificationManager::class.java)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DNS Battle Victory",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "CedokBooster Nuclear Victory"
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableVibration(true)
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
        
        val battleStatus = when (currentMethod) {
            BattleMethod.NUCLEAR_ROUTE_HIJACK -> "Route Hijack ✓"
            BattleMethod.REALME_PROPS_OVERRIDE -> "Props Override ✓"
            BattleMethod.DNS_PROXY_AGGRESSIVE -> "DNS Proxy ✓"
            BattleMethod.INTERFACE_SPOOFING -> "Interface Spoof ✓"
            BattleMethod.LAST_RESORT_REFLECTION -> "Reflection Nuke ✓"
        }
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CedokBooster VICTORY")
            .setContentText("DNS: $currentDns | $battleStatus")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Ceasefire",
                stopPendingIntent
            )
            .setColor(Color.parseColor("#FF6B6B"))
            .setAutoCancel(false)
            .build()
        
        startForeground(NOTIFICATION_ID, notification)
        LogUtil.d(TAG, "Victory notification displayed")
    }
    
    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "CedokBooster:NuclearBattle"
            ).apply {
                setReferenceCounted(false)
                acquire(60 * 60 * 1000L)
                LogUtil.d(TAG, "WakeLock acquired for battle")
            }
        } catch (e: Exception) {
            LogUtil.e(TAG, "WakeLock failed: ${e.message}")
        }
    }
    
    private fun stopNuclearBattle() {
        coroutineScope.launch {
            try {
                isRunning.set(false)
                currentDns = "1.0.0.1"
                
                dnsProxyThread?.interrupt()
                dnsProxySocket?.close()
                dnsProxyThread = null
                dnsProxySocket = null
                
                vpnInterface?.close()
                vpnInterface = null
                
                try {
                    Runtime.getRuntime().exec("setprop oppo.service.datafree.enable 0")
                } catch (e: Exception) {
                    // Ignore errors
                }
                
                wakeLock?.let {
                    if (it.isHeld) {
                        it.release()
                        LogUtil.d(TAG, "WakeLock released")
                    }
                }
                
                notificationManager?.cancel(NOTIFICATION_ID)
                
                sendBroadcast(Intent("VPN_BATTLE_STATUS").apply {
                    putExtra("status", "CEASEFIRE")
                })
                
                LogUtil.d(TAG, "Nuclear battle ceased")
                
            } catch (e: Exception) {
                LogUtil.e(TAG, "Ceasefire error: ${e.message}")
            } finally {
                stopForeground(true)
                stopSelf()
            }
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LogUtil.d(TAG, "onStartCommand: ${intent?.action}")
        
        when (intent?.action) {
            ACTION_START_VPN -> {
                val dnsType = intent.getStringExtra(EXTRA_DNS_TYPE) ?: "A"
                LogUtil.d(TAG, "Launching nuclear battle with DNS: $dnsType")
                startNuclearBattle(dnsType)
            }
            
            ACTION_STOP_VPN -> {
                LogUtil.d(TAG, "Ceasefire requested")
                stopNuclearBattle()
            }
            
            else -> stopSelf()
        }
        
        return START_STICKY
    }
    
    override fun onDestroy() {
        LogUtil.d(TAG, "Service destroying")
        wakeLock?.let { if (it.isHeld) it.release() }
        dnsProxyThread?.interrupt()
        dnsProxySocket?.close()
        stopNuclearBattle()
        super.onDestroy()
    }
    
    override fun onRevoke() {
        LogUtil.d(TAG, "VPN revoked by system")
        stopNuclearBattle()
        super.onRevoke()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    private object LogUtil {
        fun d(tag: String, message: String) = android.util.Log.d(tag, message)
        fun e(tag: String, message: String) = android.util.Log.e(tag, message)
        fun w(tag: String, message: String) = android.util.Log.w(tag, message)
    }
}
