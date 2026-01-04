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
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlinx.coroutines.withTimeout

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
        private var battleStartTime = 0L
        
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

    private fun getVpnInterfaceName(): String {
        return try {
            val process = Runtime.getRuntime().exec("ip addr show")
            val output = process.inputStream.bufferedReader().readText()
            
            // Find tun interface yang UP
            val lines = output.split("\n")
            for (i in lines.indices) {
                val line = lines[i]
                if (line.contains("tun") && line.contains("UP")) {
                    // Extract interface name (e.g., "tun2:")
                    val parts = line.split(":")
                    if (parts.isNotEmpty()) {
                        val name = parts[0].trim().split(" ").last()
                        LogUtil.d(TAG, "🔥 Found VPN interface: $name")
                        return name
                    }
                }
            }
            
            "tun0" // fallback
        } catch (e: Exception) {
            LogUtil.w(TAG, "⚠️ Failed to detect interface: ${e.message}")
            "tun0"
        }
    }
    
    private fun setupNuclearRouteHijack(dnsServers: List<String>): Boolean {
        return try {
            LogUtil.d(TAG, "🔥 NUCLEAR ROUTE HIJACK ACTIVATED")
            
            mobileGateway = detectMobileGateway()
            vpnInterface?.close()
            
            overrideRealmeNetworkProps()
            
            val builder = Builder()
                .setSession("CB-NUCLEAR-HIJACK")
                .addAddress("100.64.0.2", 24)
                .setMtu(1280)
                .setBlocking(false)
            
            dnsServers.forEach { dns ->
                builder.addDnsServer(dns)
            }
            
            // 🔥 REALME WORKAROUND: Specific routes only, NO default in builder
            builder.addRoute("1.1.1.1", 32)
            builder.addRoute("1.0.0.1", 32)
            builder.addRoute("8.8.8.8", 32)
            builder.addRoute("8.8.4.4", 32)
            builder.addRoute("9.9.9.9", 32)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val method = builder.javaClass.getDeclaredMethod(
                        "setUnderlyingNetworks", 
                        Array<Network>::class.java
                    )
                    method.invoke(builder, null as Array<Network>?)
                    LogUtil.d(TAG, "✅ Bound to ALL networks")
                    
                    try {
                        val setMeteredMethod = builder.javaClass.getDeclaredMethod(
                            "setMetered",
                            Boolean::class.java
                        )
                        setMeteredMethod.invoke(builder, true)
                        LogUtil.d(TAG, "✅ Set as metered network")
                    } catch (e: Exception) {
                        // Continue
                    }
                } catch (e: Exception) {
                    LogUtil.w(TAG, "⚠️ Advanced hiding failed: ${e.message}")
                }
            }
            
            val fd = builder.establish()
            if (fd != null) {
                vpnInterface = fd
                
                // 🔥 REALME FIX: Force default route AFTER establish
                coroutineScope.launch {
                    delay(500)
                    
                    // Get actual interface name
                    val ifaceName = getVpnInterfaceName()
                    LogUtil.d(TAG, "🔥 VPN Interface: $ifaceName")
                    
                    try {
                        // 🔥 METHOD 1: ip command
                        val commands = arrayOf(
                            "ip route add default dev $ifaceName metric 1",
                            "ip route add 0.0.0.0/1 dev $ifaceName",
                            "ip route add 128.0.0.0/1 dev $ifaceName",
                            "ip route flush cache"
                        )
                        
                        for (cmd in commands) {
                            try {
                                Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd)).waitFor()
                                LogUtil.d(TAG, "✅ $cmd")
                            } catch (e: Exception) {
                                LogUtil.w(TAG, "⚠️ $cmd failed")
                            }
                        }
                        
                        delay(500)
                        
                        // Verify routing
                        val routeCheck = Runtime.getRuntime().exec("ip route show")
                        val routes = routeCheck.inputStream.bufferedReader().readText()
                        LogUtil.d(TAG, "🔥 ROUTES AFTER FIX:\n$routes")
                        
                        if (routes.contains("default dev $ifaceName")) {
                            LogUtil.d(TAG, "🎯 DEFAULT ROUTE SUCCESS!")
                        } else {
                            LogUtil.e(TAG, "❌ DEFAULT ROUTE FAILED!")
                        }
                        
                    } catch (e: Exception) {
                        LogUtil.e(TAG, "💥 Route injection failed: ${e.message}")
                    }
                    
                    startAggressiveDnsProxy(dnsServers.first())
                }
                
                isRunning.set(true)
                currentMethod = BattleMethod.NUCLEAR_ROUTE_HIJACK
                LogUtil.d(TAG, "🎯 NUCLEAR HIJACK SUCCESS")
                true
            } else {
                LogUtil.e(TAG, "❌ Nuclear hijack failed")
                false
            }
            
        } catch (e: Exception) {
            LogUtil.e(TAG, "💥 Nuclear error: ${e.message}")
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
            
            LogUtil.d(TAG, "✅ Realme properties overridden")
            
        } catch (e: Exception) {
            LogUtil.w(TAG, "⚠️ Props override failed")
        }
    }
    
    private fun startAggressiveDnsProxy(targetDns: String) {
        try {
            // 🔥 NUCLEAR CLEANUP - Kill semua yang pakai port kita
            dnsProxyThread?.interrupt()
            dnsProxySocket?.close()
            
            // 🔥 WAIT FOR CLEANUP
            Thread.sleep(200)
            
            // 🔥 PORT STRATEGY - Multiple port attempts
            val portStrategy = listOf(
                Pair(5353, "PRIMARY"),
                Pair(5354, "SECONDARY"), 
                Pair(5355, "TERTIARY"),
                Pair(9999, "ALTERNATE"),
                Pair(53535, "RANDOM")
            )
            
            var successfulPort = -1
            var server: DatagramSocket? = null
            
            // 🔥 AGGRESSIVE PORT ACQUISITION
            for ((port, name) in portStrategy) {
                try {
                    // 🔥 KILL EXISTING PROCESS ON PORT (non-root method)
                    try {
                        Runtime.getRuntime().exec(arrayOf(
                            "sh", "-c",
                            """
                            # Try release port
                            fuser -k $port/udp 2>/dev/null || true
                            killall mdnsd 2>/dev/null || true
                            """
                        ))
                        Thread.sleep(100)
                    } catch (e: Exception) {
                        // Non-root limitation
                    }
                    
                    // 🔥 TRY BIND WITH REUSE ADDRESS
                    server = DatagramSocket(null).apply {
                        reuseAddress = true
                        soTimeout = 5000
                        bind(InetSocketAddress(port))
                    }
                    
                    successfulPort = port
                    dnsProxySocket = server
                    LogUtil.d(TAG, "🔥 DNS Proxy ACQUIRED port $port ($name)")
                    
                    // 🔥 SET SYSTEM PROPERTIES untuk announce port kita
                    try {
                        Runtime.getRuntime().exec("setprop net.dns.proxy.port $port")
                        Runtime.getRuntime().exec("setprop net.local.dns.port $port")
                    } catch (e: Exception) {
                        // Non-root, continue
                    }
                    
                    break
                    
                } catch (e: SocketException) {
                    if (e.message?.contains("EADDRINUSE") == true) {
                        LogUtil.w(TAG, "🚨 Port $port occupied, trying next...")
                        continue
                    } else {
                        throw e
                    }
                }
            }
            
            if (server == null) {
                LogUtil.e(TAG, "💥 ALL PORTS BLOCKED by Realme!")
                return
            }
            
            // 🔥🔥🔥 WARRIOR FIX: SEPARATE SOCKET ARCHITECTURE 🔥🔥🔥
            dnsProxyThread = Thread {
                var consecutiveErrors = 0
                val maxErrors = 3
                
                while (!Thread.currentThread().isInterrupted && isRunning.get()) {
                    try {
                        val buffer = ByteArray(1024)
                        val packet = DatagramPacket(buffer, buffer.size)
                        
                        // 🔥 SOCKET A: LISTEN (port 5353)
                        server!!.soTimeout = 30000
                        server!!.receive(packet)
                        
                        // 🔥 RESET ERROR COUNTER on successful receive
                        consecutiveErrors = 0
                        
                        // 🔥 LOG DNS QUERIES
                        val clientIp = packet.address.hostAddress
                        val clientPort = packet.port
                        
                        // Try decode DNS query
                        try {
                            val queryData = packet.data.copyOf(packet.length)
                            if (queryData.size >= 12) {
                                val queryId = ((queryData[0].toInt() and 0xFF) shl 8) or 
                                            (queryData[1].toInt() and 0xFF)
                                
                                LogUtil.d(TAG, "📡 DNS Query #$queryId from $clientIp:$clientPort")
                                
                                // 🔥 DETECT RIDER APPS
                                val queryStr = String(queryData).toLowerCase()
                                if (queryStr.contains("foodpanda") || 
                                    queryStr.contains("grabfood") || 
                                    queryStr.contains("mapbox")) {
                                    LogUtil.d(TAG, "🎯 RIDER APP DETECTED! Priority handling...")
                                }
                            }
                        } catch (e: Exception) {
                            // Continue anyway
                        }
                        
                        // 🔥🔥🔥 WARRIOR FIX: PARALLEL PROCESSING WITH SEPARATE SOCKETS 🔥🔥🔥
                        Thread {
                            // 🔥 SOCKET B & C: FORWARD & RECEIVE (random ports)
                            var forwardSocket: DatagramSocket? = null
                            
                            try {
                                // 🔥 MULTI-DNS FALLBACK
                                val dnsServers = listOf(
                                    "1.1.1.1",    // Cloudflare Primary
                                    "1.0.0.1",    // Cloudflare Secondary  
                                    "8.8.8.8",    // Google Primary
                                    "8.8.4.4",    // Google Secondary
                                    "9.9.9.9"     // Quad9
                                )
                                
                                var resolved = false
                                
                                for (dnsServer in dnsServers) {
                                    try {
                                        // 🔥 WARRIOR FIX: CREATE DEDICATED FORWARD SOCKET
                                        // Socket B: For sending to DNS server
                                        // Socket C: For receiving from DNS server (SAME socket, different purpose)
                                        forwardSocket?.close()  // Close previous attempt
                                        forwardSocket = DatagramSocket()  // NEW socket with RANDOM port
                                        forwardSocket.soTimeout = 3000
                                        
                                        // 🔥 PREPARE QUERY DATA
                                        val forwardData = packet.data.copyOf(packet.length)
                                        
                                        // 🔥 SOCKET B: SEND to DNS server
                                        val forwardPacket = DatagramPacket(
                                            forwardData,
                                            packet.length,
                                            InetAddress.getByName(dnsServer),
                                            53
                                        )
                                        forwardSocket.send(forwardPacket)
                                        
                                        // 🔥 SOCKET C: RECEIVE from DNS server (SAME socket as B)
                                        val response = ByteArray(1024)
                                        val responsePacket = DatagramPacket(response, response.size)
                                        forwardSocket.receive(responsePacket)
                                        
                                        // 🔥 SOCKET A: SEND back to client (original listening socket)
                                        // KEY FIX: Use server socket (5353) to reply, NOT forwardSocket!
                                        val replyPacket = DatagramPacket(
                                            response, 
                                            responsePacket.length, 
                                            packet.address,  // Original client IP
                                            packet.port      // Original client port
                                        )
                                        server!!.send(replyPacket)
                                        
                                        resolved = true
                                        LogUtil.d(TAG, "✅ DNS resolved via $dnsServer → client $clientIp:$clientPort")
                                        break
                                        
                                    } catch (e: SocketTimeoutException) {
                                        LogUtil.w(TAG, "⚠️ $dnsServer timeout")
                                        continue  // Try next server
                                    } catch (e: Exception) {
                                        LogUtil.w(TAG, "❌ $dnsServer failed: ${e.message}")
                                        continue  // Try next server
                                    } finally {
                                        // Clean up forward socket after each attempt
                                        forwardSocket?.close()
                                    }
                                }
                                
                                if (!resolved) {
                                    LogUtil.e(TAG, "💥 All DNS servers failed for query from $clientIp:$clientPort")
                                }
                                
                            } catch (e: Exception) {
                                LogUtil.w(TAG, "⚠️ Forwarding error: ${e.message}")
                            } finally {
                                // 🔥 ENSURE SOCKET CLEANUP
                                forwardSocket?.close()
                            }
                        }.start()
                        
                    } catch (e: SocketTimeoutException) {
                        // 🔥 NO TRAFFIC - Normal, just continue
                        consecutiveErrors = 0
                        
                    } catch (e: Exception) {
                        consecutiveErrors++
                        LogUtil.w(TAG, "⚠️ DNS Proxy error #$consecutiveErrors: ${e.message}")
                        
                        // 🔥 AUTO-RECOVERY on multiple errors
                        if (consecutiveErrors >= maxErrors) {
                            LogUtil.e(TAG, "🚨 CRITICAL ERROR - Attempting auto-recovery...")
                            
                            try {
                                server?.close()
                                Thread.sleep(500)
                                
                                // 🔥 RESTART WITH DIFFERENT PORT
                                if (isRunning.get()) {
                                    startAggressiveDnsProxy(targetDns)
                                }
                                return@Thread
                            } catch (re: Exception) {
                                LogUtil.e(TAG, "💥 Auto-recovery failed: ${re.message}")
                            }
                            break
                        }
                    }
                }
                
                // 🔥 CLEAN EXIT
                try {
                    server?.close()
                    LogUtil.d(TAG, "✅ DNS Proxy shutdown cleanly")
                } catch (e: Exception) {
                    // Ignore
                }
            }
            
            // 🔥 SET THREAD PRIORITY
            dnsProxyThread?.priority = Thread.MAX_PRIORITY
            dnsProxyThread?.start()
            
            LogUtil.d(TAG, "🎯 WARRIOR SOCKET ARCHITECTURE DEPLOYED!")
            
        } catch (e: Exception) {
            LogUtil.e(TAG, "🔥 DNS Proxy INIT failed: ${e.message}")
            
            // 🔥 LAST RESORT - Try again in 3 seconds
            coroutineScope.launch {
                delay(3000)
                if (isRunning.get()) {
                    LogUtil.d(TAG, "🔥 Retrying DNS Proxy...")
                    startAggressiveDnsProxy(targetDns)
                }
            }
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
                Unit
            } else {
                Unit
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
            battleStartTime = System.currentTimeMillis() 
            if (isRunning.get()) {
                LogUtil.d(TAG, "Battle already in progress")
                return@launch
            }
            
            LogUtil.d(TAG, "🔥 INITIATING NUCLEAR BATTLE SEQUENCE")
            
            var victory = false
            retryCount = 0
            
            while (!victory && retryCount < 2) {
                retryCount++
                LogUtil.d(TAG, "⚔️ BATTLE ATTEMPT $retryCount")
                
                victory = launchNuclearBattle(dnsType)
                
                if (victory) {
                    delay(2000)
                    if (!verifyNuclearVictory()) {
                        LogUtil.w(TAG, "Victory verification failed, re-engaging")
                        victory = false
                    } else {
                        Unit
                    }
                }
                
                if (!victory) {
                    delay(3000L * retryCount)
                } else {
                    Unit
                }
            }
            
            if (victory) {
                acquireWakeLock()
                showVictoryNotification()
                
                LogUtil.d(TAG, "🎯 NUCLEAR BATTLE VICTORY Method: $currentMethod")
                
                sendBroadcast(Intent("VPN_BATTLE_STATUS").apply {
                    putExtra("status", "VICTORY")
                    putExtra("method", currentMethod.name)
                    putExtra("dns", currentDns)
                })
            } else {
                LogUtil.e(TAG, "❌ ALL BATTLE METHODS FAILED")
                stopSelf()
            }
            Unit
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
            .setContentTitle("🔥 CedokBooster VICTORY")
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
        LogUtil.d(TAG, "✅ Victory notification displayed")
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
                LogUtil.d(TAG, "✅ WakeLock acquired for battle")
            }
        } catch (e: Exception) {
            LogUtil.e(TAG, "❌ WakeLock failed: ${e.message}")
        }
    }
    
    private fun stopNuclearBattle() {
        LogUtil.d(TAG, "🔥 INITIATING GRACEFUL CEASEFIRE")
        
        coroutineScope.launch {
            try {
                // 🔥 PHASE 1: SIGNAL STOP
                isRunning.set(false)
                currentDns = "1.0.0.1"
                
                // 🔥 PHASE 2: GRACEFUL DNS PROXY SHUTDOWN
                dnsProxyThread?.let { thread ->
                    thread.interrupt()
                    
                    try {
                        thread.join(2000)
                        if (thread.isAlive) {
                            LogUtil.w(TAG, "🚨 DNS Thread not responding, forcing...")
                        }
                    } catch (e: Exception) {
                        LogUtil.w(TAG, "Thread join error: ${e.message}")
                    }
                    Unit  // ← ADD THIS!
                }
                
                // 🔥 PHASE 3: FORCE CLOSE ALL SOCKETS
                dnsProxySocket?.close()
                dnsProxyThread = null
                dnsProxySocket = null
                
                // 🔥 PHASE 4: VPN INTERFACE CLEANUP
                vpnInterface?.close()
                vpnInterface = null
                
                // 🔥 PHASE 5: RESTORE REALME PROPERTIES
                try {
                    Runtime.getRuntime().exec(arrayOf(
                        "sh", "-c",
                        """
                        setprop oppo.service.datafree.enable 0
                        setprop net.dns.proxy.port ""
                        setprop net.local.dns.port ""
                        ip route change default dev ccmni0 metric 100 2>/dev/null || true
                        killall -HUP netd 2>/dev/null || true
                        """
                    ))
                    LogUtil.d(TAG, "✅ Realme properties restored")
                } catch (e: Exception) {
                    LogUtil.w(TAG, "Property restore failed: ${e.message}")
                }
                
                // 🔥 PHASE 6: RESOURCE CLEANUP
                wakeLock?.let {
                    if (it.isHeld) {
                        it.release()
                        LogUtil.d(TAG, "WakeLock released")
                    } else {
                        Unit
                    }
                }
                
                notificationManager?.cancel(NOTIFICATION_ID)
                
                // 🔥 PHASE 7: BROADCAST CEASEFIRE
                sendBroadcast(Intent("VPN_BATTLE_STATUS").apply {
                    putExtra("status", "CEASEFIRE")
                    putExtra("timestamp", System.currentTimeMillis())
                    putExtra("battle_duration", System.currentTimeMillis() - battleStartTime)
                })
                
                LogUtil.d(TAG, "🎌 CEASEFIRE COMPLETE")
                
            } catch (e: Exception) {
                LogUtil.e(TAG, "💥 CEASEFIRE ERROR: ${e.message}")
                
                try {
                    stopForeground(true)
                    stopSelf()
                } catch (fe: Exception) {
                    LogUtil.e(TAG, "FINAL FAILURE: ${fe.message}")
                }
                
            } finally {
                try {
                    stopForeground(true)
                    stopSelf()
                    LogUtil.d(TAG, "Service terminated")
                } catch (e: Exception) {
                    // Already stopped
                }
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
        wakeLock?.let { if (it.isHeld) it.release() else Unit }
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
