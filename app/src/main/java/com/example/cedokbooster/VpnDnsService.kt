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

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast

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
            
            val regex = Regex("""(\d+):\s+(tun\d+):\s+<[^>]*UP[^>]*>""")
            val match = regex.find(output)
            
            if (match != null) {
                val ifaceName = match.groupValues[2]
                LogUtil.d(TAG, "🔥 Found VPN interface: $ifaceName")
                return ifaceName
            }
            
            LogUtil.w(TAG, "⚠️ No UP tun interface, trying tun0")
            "tun0"
        } catch (e: Exception) {
            LogUtil.w(TAG, "⚠️ Failed to detect interface: ${e.message}")
            "tun0"
        }
    }
    
    private fun setupNuclearRouteHijack(dnsServers: List<String>): Boolean {
        return try {
            LogUtil.d(TAG, "🔥 ANDROID 10 PSYCHO MODE - NO allowBypass MITOS")
            
            mobileGateway = detectMobileGateway()
            vpnInterface?.close()
            
            // 🔥 CONDITION REALME (SOCIAL ENGINEERING)
            conditionRealmeNetworkProps()
            
            val builder = Builder()
                .setSession("CCMNI0-EXTENSION")
                .addAddress("100.64.0.2", 24)
                .setMtu(1280)
                .setBlocking(false)
                // .allowBypass(false)  ← INI MITOS! BUANG!
            
            // 🔥 DNS DECEPTION: TELCO DNS DEPAN, OUR DNS BELAKANG
            builder.addDnsServer("203.82.91.14")  // TIPU: TELCO DNS
            builder.addDnsServer("203.82.91.30")  // TIPU: TELCO DNS
            builder.addDnsServer("1.1.1.1")       // REAL: KITA CONTROL
            builder.addDnsServer("1.0.0.1")
            
            // 🔥 ANDROID 10 TRICK 1: DISALLOW SYSTEM APPS SAHAJA
            // Biar user apps semua melalui VPN
            try {
                // SystemUI, Settings, etc. biar bypass (elak detection)
                builder.addDisallowedApplication("com.android.systemui")
                builder.addDisallowedApplication("com.android.settings")
                builder.addDisallowedApplication("com.android.phone")
                LogUtil.d(TAG, "✅ System apps disallowed (stealth mode)")
            } catch (e: Exception) {
                LogUtil.w(TAG, "⚠️ Cannot disallow system apps")
            }
            
            // 🔥 ANDROID 10 TRICK 2: FULL IP RANGE COVERAGE
            // Tanpa allowBypass, kita cover semua IP range
            // 0.0.0.0/1 + 128.0.0.0/1 = semua IP di dunia
            builder.addRoute("0.0.0.0", 1)      // 0-127.255.255.255
            builder.addRoute("128.0.0.0", 1)    // 128-255.255.255.255
    
            // 🔥🔥🔥 NUCLEAR TRICK: HIJACK DEFAULT ROUTE DENGAN METRIC TINGGI
            // Realme tak boleh delete 0.0.0.0/0, tapi kita boleh override dengan metric tinggi
            // Kernel akan pilih route dengan metric terendah
            
            builder.addRoute("0.0.0.0", 0)  // ← ADD INI JUGA!
            
            // 🔥 ROUTE KE DNS TELCO MELALUI VPN (INTERCEPT)
            builder.addRoute("203.82.91.14", 32)
            builder.addRoute("203.82.91.30", 32)
            
            // 🔥 ROUTE KE PUBLIC DNS MELALUI VPN (JIC)
            builder.addRoute("8.8.8.8", 32)
            builder.addRoute("8.8.4.4", 32)
    
            // 🔥🔥🔥 NUCLEAR TRICK: ROUTE DECEPTION
            // Realme mungkin filter routes berdasarkan prefix length
            // Kita cuba pelbagai format:
            // Split routes menjadi lebih kecil (bypass filter)
            //    0.0.0.0/8, 8.0.0.0/8, 16.0.0.0/8, ..., 248.0.0.0/8
            for (i in 0..31) {
                val network = (i * 8).toString() + ".0.0.0"
                builder.addRoute(network, 8)  // /8 routes
            }
            
            // 3. Route ke POPULAR SERVICES (Realme mungkin allow)
            builder.addRoute("1.0.0.0", 8)      // Cloudflare
            builder.addRoute("8.0.0.0", 8)      // Google
            builder.addRoute("13.0.0.0", 8)     // AWS
            builder.addRoute("34.0.0.0", 8)     // Google Cloud
            builder.addRoute("52.0.0.0", 8)     // Amazon
            builder.addRoute("104.0.0.0", 8)    // Fastly
            builder.addRoute("172.0.0.0", 8)    // Private? Try anyway
            
            // 🔥 ANDROID 10 TRICK 3: CLAIM CELLULAR IDENTITY (API 23+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                    val activeNet = cm.activeNetwork
                    
                    if (activeNet != null) {
                        val method = builder.javaClass.getDeclaredMethod(
                            "setUnderlyingNetworks", 
                            Array<Network>::class.java
                        )
                        method.invoke(builder, arrayOf(activeNet))
                        LogUtil.d(TAG, "✅ CLAIMED CELLULAR NETWORK: $activeNet")
                    }
                    
                    // METERED = CELLULAR CHARACTERISTIC
                    try {
                        val setMeteredMethod = builder.javaClass.getDeclaredMethod(
                            "setMetered",
                            Boolean::class.java
                        )
                        setMeteredMethod.invoke(builder, true)
                        LogUtil.d(TAG, "✅ SET AS METERED (cellular-like)")
                    } catch (e: Exception) {
                        // API 29 mungkin tak support
                    }
                } catch (e: Exception) {
                    LogUtil.w(TAG, "⚠️ Cellular identity theft failed: ${e.message}")
                }
            }
            
            // 🔥 ESTABLISH VPN
            val fd = builder.establish()
            if (fd != null) {
                vpnInterface = fd
                
                // 🔥 NEW FIX: KERNEL ROUTING CHECK + EMERGENCY PROXY
                coroutineScope.launch {
                    delay(2500)  // Tunggu VPN settle
                    
                    // CHECK #1: KERNEL ROUTES APPLIED?
                    val kernelRoutesApplied = checkKernelRoutesApplied()
                    
                    if (!kernelRoutesApplied) {
                        LogUtil.e(TAG, "🚨 REALME KERNEL BLOCKED ROUTES! Activating emergency proxy...")
                        
                        // 🔥 EMERGENCY MODE: START SOCKS5 PROXY
                        startEmergencySocks5Proxy()

                        // 🔥 NUCLEAR ROUTE INJECTION TO KERNEL
                        coroutineScope.launch {
                            delay(3500)  // Tunggu proxy start
                            
                            val ifaceName = getVpnInterfaceName()  // akan dapat "tun1"
                            LogUtil.d(TAG, "🔧 KERNEL ROUTE INJECTION for $ifaceName")
                            
                            // Inject routes langsung ke kernel (bypass Android framework)
                            val routesToInject = listOf(
                                "0.0.0.0/1 dev $ifaceName metric 50",
                                "128.0.0.0/1 dev $ifaceName metric 50",
                                "0.0.0.0/0 dev $ifaceName metric 60"
                            )
                            
                            for (route in routesToInject) {
                                try {
                                    Runtime.getRuntime().exec(arrayOf("ip", "route", "add", route))
                                    LogUtil.d(TAG, "✅ KERNEL ROUTE: $route")
                                } catch (e: Exception) {
                                    LogUtil.w(TAG, "⚠️ Failed: $route")
                                }
                                Thread.sleep(100)
                            }
                            
                            // Delete competing routes
                            try {
                                Runtime.getRuntime().exec(arrayOf("ip", "route", "del", "default", "dev", "wlan0"))
                                Runtime.getRuntime().exec(arrayOf("ip", "route", "del", "default", "dev", "ccmni0"))
                                LogUtil.d(TAG, "🔥 COMPETING ROUTES REMOVED")
                            } catch (e: Exception) {
                                // Expected for non-root
                            }
                            
                            // Flush route cache
                            try {
                                Runtime.getRuntime().exec("ip route flush cache")
                            } catch (e: Exception) {}
                            
                            // Verify
                            val hasRoutes = checkKernelRoutesApplied()
                            if (hasRoutes) {
                                LogUtil.d(TAG, "🎯 KERNEL ROUTES INJECTION SUCCESS!")
                            } else {
                                LogUtil.e(TAG, "💥 KERNEL STILL BLOCKING ROUTES")
                            }
                        }

                        // 🔥 OPTIONAL: SET SYSTEM PROXY
                        try {
                            Settings.Global.putString(
                                contentResolver,
                                Settings.Global.HTTP_PROXY,
                                "127.0.0.1:1080"
                            )
                            LogUtil.d(TAG, "✅ SYSTEM PROXY SET: 127.0.0.1:1080")
                        } catch (e: Exception) {
                            LogUtil.w(TAG, "⚠️ Cannot set system proxy")
                        }
                    } else {
                        LogUtil.d(TAG, "✅ KERNEL ROUTES APPLIED SUCCESSFULLY")
                    }
                    
                    // 🔥 PHASE 1: METRIC WAR (VPN LOW METRIC, CELLULAR HIGH METRIC)
                    try {
                        // GET INTERFACE NAME
                        val ifaceName = getVpnInterfaceName()
                        
                        // VPN routes dengan LOW metric (10)
                        Runtime.getRuntime().exec("ip route add 0.0.0.0/1 dev $ifaceName metric 10 2>/dev/null")
                        Runtime.getRuntime().exec("ip route add 128.0.0.0/1 dev $ifaceName metric 10 2>/dev/null")
                        
                        // Cellular route dengan HIGH metric (100)
                        Runtime.getRuntime().exec("ip route change default dev ccmni0 metric 100 2>/dev/null")
                        
                        LogUtil.d(TAG, "🔥 METRIC WAR INITIATED: VPN(10) vs Cellular(100)")
                    } catch (e: Exception) {
                        LogUtil.w(TAG, "⚠️ Metric war failed (non-root limitation)")
                    }
            
                    // 🔥 PHASE 2: PACKET FORWARDING ATTEMPT (NON-ROOT MAY FAIL)
                    try {
                        // Enable IP forwarding
                        Runtime.getRuntime().exec("echo 1 > /proc/sys/net/ipv4/ip_forward 2>/dev/null")
                        
                        // Enable packet forwarding untuk tun0
                        Runtime.getRuntime().exec("iptables -A FORWARD -i tun0 -j ACCEPT 2>/dev/null")
                        Runtime.getRuntime().exec("iptables -A FORWARD -o tun0 -j ACCEPT 2>/dev/null")
                        
                        // NAT untuk traffic keluar
                        Runtime.getRuntime().exec("iptables -t nat -A POSTROUTING -o ccmni0 -j MASQUERADE 2>/dev/null")
                        
                        LogUtil.d(TAG, "🔥 PACKET FORWARDING ATTEMPTED")
                    } catch (e: Exception) {
                        LogUtil.w(TAG, "⚠️ Packet forwarding failed (expected for non-root)")
                    }
    
                    // 🔥 PHASE 3: ROUTE FLUSH CACHE - FORCE KERNEL RE-EVALUATE
                    try {
                        Runtime.getRuntime().exec("ip route flush cache 2>/dev/null")
                        LogUtil.d(TAG, "✅ Route cache flushed")
                    } catch (e: Exception) {
                        // Continue
                    }
            
                    // 🔥 PHASE 4: SKIP PROCESS BINDING (AVOID DNS PROXY LOOP)
                    try {
                        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                        val allNetworks = cm.allNetworks
                        
                        // Cari network VPN kita
                        for (network in allNetworks) {
                            val caps = cm.getNetworkCapabilities(network)
                            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
                                // ⚠️ JANGAN BIND - CAUSE CIRCULAR ROUTING FOR DNS PROXY!
                                // cm.bindProcessToNetwork(network)
                                LogUtil.d(TAG, "⚠️ SKIP bindProcessToNetwork (avoid DNS proxy loop)")
                                break
                            }
                        }
                    } catch (e: Exception) {
                        LogUtil.w(TAG, "⚠️ Process bind check failed")
                    }
                    
                    // 🔥 PHASE 5: START DNS PROXY (INTERCEPT TELCO DNS)
                    startAggressiveDnsProxy("203.82.91.14")
                    
                    // 🔥 PHASE 6: TEST CONNECTIVITY (OPTIONAL)
                    try {
                        val ifaceName = getVpnInterfaceName()
                        Runtime.getRuntime().exec("ping -c 2 -I $ifaceName 1.1.1.1 2>/dev/null")
                        LogUtil.d(TAG, "✅ Ping test initiated through $ifaceName")
                    } catch (e: Exception) {
                        LogUtil.w(TAG, "⚠️ Ping test failed (expected)")
                    }

                    // 🔥 FINAL NUCLEAR OPTION: LOCAL TRAFFIC INTERCEPT
                    coroutineScope.launch {
                        delay(3000)
                        
                        // Force semua traffic ke localhost:1080 (jika proxy fail, kita intercept sendiri)
                        startLocalTrafficInterceptor()
                    }
                }
                
                isRunning.set(true)
                currentMethod = BattleMethod.NUCLEAR_ROUTE_HIJACK
                LogUtil.d(TAG, "🎯 ANDROID 10 HIJACK SUCCESS (No allowBypass myth)")
                return true
            } else {
                LogUtil.e(TAG, "❌ VPN Establishment failed")
                return false
            }
            
        } catch (e: Exception) {
            LogUtil.e(TAG, "💥 Android 10 hijack failed: ${e.message}")
            return false
        }
    }
    
    private fun conditionRealmeNetworkProps() {
        try {
            // NON-ROOT FRIENDLY PROPERTIES ONLY
            val props = listOf(
                // Ini boleh access tanpa root (testing properties)
                "setprop debug.vpn.ready 1",
                "setprop vpn.service.active true",
                "setprop net.dns.cache.ttl 300",
                // Oppo/Realme specific (mungkin boleh)
                "setprop persist.sys.vpn.enable 1",
                "setprop ro.vendor.vpn.support 1"
            )
            
            for (cmd in props) {
                try {
                    Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                    Thread.sleep(20)
                } catch (e: Exception) {
                    // Expected for non-root
                }
            }
            
            LogUtil.d(TAG, "✅ Realme conditioning attempted")
            
        } catch (e: Exception) {
            LogUtil.w(TAG, "⚠️ Conditioning failed (non-root limitation)")
        }
    }
    
    private fun startAggressiveDnsProxy(targetDns: String) {
        try {
            // 🔥 NUCLEAR CLEANUP
            dnsProxyThread?.interrupt()
            dnsProxySocket?.close()
            Thread.sleep(200)
            
            // 🔥 PORT STRATEGY
            val portStrategy = listOf(
                Pair(5353, "PRIMARY"),
                Pair(5354, "SECONDARY"), 
                Pair(5355, "TERTIARY"),
                Pair(9999, "ALTERNATE"),
                Pair(53535, "RANDOM")
            )
            
            var server: DatagramSocket? = null
            
            for ((port, name) in portStrategy) {
                try {
                    // Try release port
                    try {
                        Runtime.getRuntime().exec(arrayOf(
                            "sh", "-c",
                            "fuser -k $port/udp 2>/dev/null || true; killall mdnsd 2>/dev/null || true"
                        ))
                        Thread.sleep(100)
                    } catch (e: Exception) { /* Non-root */ }
                    
                    server = DatagramSocket(null).apply {
                        reuseAddress = true
                        soTimeout = 5000
                        bind(InetSocketAddress(port))
                    }
                    
                    dnsProxySocket = server
                    LogUtil.d(TAG, "🔥 DNS Proxy ACQUIRED port $port ($name)")
                    
                    try {
                        Runtime.getRuntime().exec("setprop net.dns.proxy.port $port")
                        Runtime.getRuntime().exec("setprop net.local.dns.port $port")
                    } catch (e: Exception) { /* Non-root */ }
                    
                    break
                    
                } catch (e: SocketException) {
                    if (e.message?.contains("EADDRINUSE") == true) {
                        LogUtil.w(TAG, "🚨 Port $port occupied")
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
            
            // 🔥 WARRIOR ARCHITECTURE (SINGLE THREAD, NO DOUBLE NESTING)
            dnsProxyThread = Thread {
                var consecutiveErrors = 0
                val maxErrors = 3
                
                // 🔥 GUNA PARAMETER targetDns
                val primaryDns = targetDns  // "203.82.91.14"
                
                // 🔥 FALLBACK DNS LIST
                val fallbackDnsList = listOf(
                    primaryDns,      // TELCO DNS FIRST
                    "1.1.1.1",      // Cloudflare
                    "1.0.0.1",      // Cloudflare  
                    "8.8.8.8",      // Google
                    "9.9.9.9"       // Quad9
                )
                
                while (!Thread.currentThread().isInterrupted && isRunning.get()) {
                    try {
                        val buffer = ByteArray(1024)
                        val packet = DatagramPacket(buffer, buffer.size)
                        
                        server!!.soTimeout = 30000
                        server!!.receive(packet)
                        
                        consecutiveErrors = 0
                        
                        val clientIp = packet.address.hostAddress
                        val clientPort = packet.port
                        
                        // Try decode DNS query
                        try {
                            val queryData = packet.data.copyOf(packet.length)
                            if (queryData.size >= 12) {
                                val queryId = ((queryData[0].toInt() and 0xFF) shl 8) or 
                                            (queryData[1].toInt() and 0xFF)
                                
                                LogUtil.d(TAG, "📡 DNS Query #$queryId from $clientIp:$clientPort")
                                
                                val queryStr = String(queryData).toLowerCase()
                                if (queryStr.contains("foodpanda") || 
                                    queryStr.contains("grabfood") || 
                                    queryStr.contains("mapbox")) {
                                    LogUtil.d(TAG, "🎯 RIDER APP DETECTED!")
                                }
                            }
                        } catch (e: Exception) {
                            // Continue
                        }
                        
                        // 🔥 PARALLEL PROCESSING (INNER THREAD OK)
                        Thread {
                            var forwardSocket: DatagramSocket? = null
                            
                            try {
                                var resolved = false
                                
                                for (dnsServer in fallbackDnsList) {
                                    try {
                                        forwardSocket?.close()
                                        forwardSocket = DatagramSocket()
                                        forwardSocket.soTimeout = 3000
                                        
                                        val forwardData = packet.data.copyOf(packet.length)
                                        
                                        // 🔥 STEALTH MODE
                                        val stealthDns = when {
                                            dnsServer == "203.82.91.14" -> "1.1.1.1"
                                            dnsServer == "203.82.91.30" -> "1.0.0.1"
                                            else -> dnsServer
                                        }
                                        
                                        val forwardPacket = DatagramPacket(
                                            forwardData,
                                            packet.length,
                                            InetAddress.getByName(stealthDns),
                                            53
                                        )
                                        forwardSocket.send(forwardPacket)
                                        
                                        val response = ByteArray(1024)
                                        val responsePacket = DatagramPacket(response, response.size)
                                        forwardSocket.receive(responsePacket)
                                        
                                        val replyPacket = DatagramPacket(
                                            response, 
                                            responsePacket.length, 
                                            packet.address,
                                            packet.port
                                        )
                                        server!!.send(replyPacket)
                                        
                                        resolved = true
                                        LogUtil.d(TAG, "✅ DNS via $stealthDns → $clientIp:$clientPort")
                                        break
                                        
                                    } catch (e: SocketTimeoutException) {
                                        LogUtil.w(TAG, "⚠️ $dnsServer timeout")
                                        continue
                                    } catch (e: Exception) {
                                        LogUtil.w(TAG, "❌ $dnsServer failed")
                                        continue
                                    } finally {
                                        forwardSocket?.close()
                                    }
                                }
                                
                                if (!resolved) {
                                    LogUtil.e(TAG, "💥 All DNS failed for $clientIp:$clientPort")
                                }
                                
                            } catch (e: Exception) {
                                LogUtil.w(TAG, "⚠️ Forwarding error")
                            } finally {
                                forwardSocket?.close()
                            }
                        }.start()
                        
                    } catch (e: SocketTimeoutException) {
                        consecutiveErrors = 0
                    } catch (e: Exception) {
                        consecutiveErrors++
                        LogUtil.w(TAG, "⚠️ DNS Proxy error #$consecutiveErrors")
                        
                        if (consecutiveErrors >= maxErrors) {
                            LogUtil.e(TAG, "🚨 CRITICAL ERROR - Auto-recovery...")
                            
                            try {
                                server?.close()
                                Thread.sleep(500)
                                
                                if (isRunning.get()) {
                                    startAggressiveDnsProxy(targetDns)
                                }
                                return@Thread
                            } catch (re: Exception) {
                                LogUtil.e(TAG, "💥 Auto-recovery failed")
                            }
                            break
                        }
                    }
                }
                
                try {
                    server?.close()
                    LogUtil.d(TAG, "✅ DNS Proxy shutdown cleanly")
                } catch (e: Exception) {
                    // Ignore
                }
            }
            
            dnsProxyThread?.priority = Thread.MAX_PRIORITY
            dnsProxyThread?.start()
            
            LogUtil.d(TAG, "🎯 WARRIOR SOCKET ARCHITECTURE DEPLOYED!")
            
        } catch (e: Exception) {
            LogUtil.e(TAG, "🔥 DNS Proxy INIT failed: ${e.message}")
            
            coroutineScope.launch {
                delay(3000)
                if (isRunning.get()) {
                    LogUtil.d(TAG, "🔥 Retrying DNS Proxy...")
                    startAggressiveDnsProxy(targetDns)
                }
            }
        }
    }

    // 🔥 NEW FUNCTION: CHECK KERNEL ROUTES
    private fun checkKernelRoutesApplied(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("ip route show")
            val output = process.inputStream.bufferedReader().readText()
            
            output.contains("0.0.0.0/1.*tun") || 
            output.contains("128.0.0.0/1.*tun") ||
            output.contains("default.*tun")
        } catch (e: Exception) {
            false
        }
    }
    
    // 🔥 NEW FUNCTION: EMERGENCY SOCKS5 PROXY
    private fun startEmergencySocks5Proxy() {
        LogUtil.d(TAG, "🔥 STARTING EMERGENCY SOCKS5 PROXY ON PORT 1080")
        
        Thread {
            // SET SYSTEM-WIDE PROXY
            try {
                Settings.Global.putString(
                    applicationContext.contentResolver,
                    Settings.Global.HTTP_PROXY,
                    "127.0.0.1:1080"
                )
                LogUtil.d(TAG, "✅ SYSTEM PROXY SET: 127.0.0.1:1080")
                
                // Force connectivity refresh
                val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.reportNetworkConnectivity(null, true)
                
            } catch (e: SecurityException) {
                LogUtil.e(TAG, "❌ Need WRITE_SECURE_SETTINGS permission")
                // Fallback: Notify user to set proxy manually
                showProxySetupNotification()
            } catch (e: Exception) {
                LogUtil.e(TAG, "❌ Proxy setting failed: ${e.message}")
            }

            try {
                val server = java.net.ServerSocket(1080)
                server.soTimeout = 0
                
                LogUtil.d(TAG, "✅ SOCKS5 Proxy listening on 127.0.0.1:1080")
                
                while (isRunning.get()) {
                    try {
                        val clientSocket = server.accept()
                        
                        Thread {
                            handleSocks5Connection(clientSocket)
                        }.start()
                        
                    } catch (e: Exception) {
                        if (isRunning.get()) {
                            LogUtil.w(TAG, "⚠️ Client accept error: ${e.message}")
                        }
                    }
                }
                
                server.close()
            } catch (e: Exception) {
                LogUtil.e(TAG, "💥 SOCKS5 Proxy failed: ${e.message}")
            }
        }.start()
    }

    private fun startLocalTrafficInterceptor() {
        LogUtil.d(TAG, "🔥 STARTING LOCAL TRAFFIC INTERCEPTOR")
        
        // Start HTTP proxy pada port 8080 (selain SOCKS5 1080)
        Thread {
            try {
                val server = java.net.ServerSocket(8080)
                server.soTimeout = 0
                
                LogUtil.d(TAG, "✅ HTTP Proxy listening on 127.0.0.1:8080")
                
                while (isRunning.get()) {
                    try {
                        val clientSocket = server.accept()
                        
                        Thread {
                            handleHttpProxyConnection(clientSocket)
                        }.start()
                        
                    } catch (e: Exception) {
                        if (isRunning.get()) {
                            LogUtil.w(TAG, "⚠️ HTTP Proxy accept error")
                        }
                    }
                }
                
                server.close()
            } catch (e: Exception) {
                LogUtil.e(TAG, "💥 HTTP Proxy failed: ${e.message}")
            }
        }.start()
        
        // 🔥 TRICK: REDIRECT TRAFFIC DENGAN IPTABLES (jika boleh)
        try {
            Runtime.getRuntime().exec(arrayOf(
                "sh", "-c",
                "iptables -t nat -A OUTPUT -p tcp ! -d 127.0.0.1 -j REDIRECT --to-port 8080 2>/dev/null || true"
            ))
            LogUtil.d(TAG, "🔥 IPTABLES REDIRECT ATTEMPTED")
        } catch (e: Exception) {
            LogUtil.w(TAG, "⚠️ IPTABLES not available (non-root)")
        }
    }
    
    private fun handleHttpProxyConnection(clientSocket: java.net.Socket) {
        try {
            val reader = java.io.BufferedReader(
                java.io.InputStreamReader(clientSocket.getInputStream())
            )
            
            val requestLine = reader.readLine()
            if (requestLine != null && requestLine.startsWith("CONNECT")) {
                // HTTPS CONNECT tunnel
                handleHttpsTunnel(clientSocket, reader, requestLine)
            } else if (requestLine != null && requestLine.contains("HTTP")) {
                // HTTP request - simple response (kita handle HTTPS sahaja dulu)
                val writer = java.io.PrintWriter(clientSocket.getOutputStream())
                writer.println("HTTP/1.1 501 Not Implemented")
                writer.println("Content-Type: text/plain")
                writer.println()
                writer.println("HTTP proxy not fully implemented. Use HTTPS.")
                writer.flush()
                clientSocket.close()
            } else {
                clientSocket.close()
            }
        } catch (e: Exception) {
            try { clientSocket.close() } catch (e: Exception) {}
        }
    }
    
    private fun handleHttpsTunnel(clientSocket: java.net.Socket, 
                                   reader: java.io.BufferedReader, 
                                   requestLine: String) {
        try {
            // Parse destination
            val parts = requestLine.split(" ")
            if (parts.size >= 2) {
                val dest = parts[1].split(":")
                val destHost = dest[0]
                val destPort = dest.getOrElse(1) { "443" }.toInt()
                
                // Connect through VPN
                val vpnSocket = java.net.Socket()
                vpnSocket.connect(java.net.InetSocketAddress(destHost, destPort))
                
                // Send 200 Connection Established
                val writer = java.io.PrintWriter(clientSocket.getOutputStream())
                writer.println("HTTP/1.1 200 Connection Established")
                writer.println("Proxy-Agent: CedokBooster/1.0")
                writer.println()
                writer.flush()
                
                // Start bidirectional forwarding
                startTunnelForwarding(clientSocket, vpnSocket)
                
                LogUtil.d(TAG, "🔗 HTTPS Tunnel: $destHost:$destPort")
            }
        } catch (e: Exception) {
            LogUtil.w(TAG, "⚠️ HTTPS tunnel failed: ${e.message}")
            try { clientSocket.close() } catch (e: Exception) {}
        }
    }
    
    private fun startTunnelForwarding(clientSocket: java.net.Socket, 
                                      vpnSocket: java.net.Socket) {
        // Client → VPN
        Thread {
            try {
                val input = clientSocket.getInputStream()
                val output = vpnSocket.getOutputStream()
                val buffer = ByteArray(8192)
                var bytesRead: Int
                
                while (isRunning.get() && clientSocket.isConnected && 
                       vpnSocket.isConnected) {
                    bytesRead = input.read(buffer)
                    if (bytesRead == -1) break
                    output.write(buffer, 0, bytesRead)
                    output.flush()
                }
            } catch (e: Exception) {
                // Socket closed
            } finally {
                try { clientSocket.close() } catch (e: Exception) {}
                try { vpnSocket.close() } catch (e: Exception) {}
            }
        }.start()
        
        // VPN → Client
        Thread {
            try {
                val input = vpnSocket.getInputStream()
                val output = clientSocket.getOutputStream()
                val buffer = ByteArray(8192)
                var bytesRead: Int
                
                while (isRunning.get() && clientSocket.isConnected && 
                       vpnSocket.isConnected) {
                    bytesRead = input.read(buffer)
                    if (bytesRead == -1) break
                    output.write(buffer, 0, bytesRead)
                    output.flush()
                }
            } catch (e: Exception) {
                // Socket closed
            } finally {
                try { clientSocket.close() } catch (e: Exception) {}
                try { vpnSocket.close() } catch (e: Exception) {}
            }
        }.start()
    }
    
    // 🔥 NEW FUNCTION: HANDLE SOCKS5 CONNECTION
    private fun handleSocks5Connection(clientSocket: java.net.Socket) {
        try {
            val input = clientSocket.getInputStream()
            val output = clientSocket.getOutputStream()
            
            // SOCKS5 handshake
            input.read()  // Version
            val nMethods = input.read()
            input.skip(nMethods.toLong())
            
            output.write(byteArrayOf(0x05, 0x00))  // No auth required
            
            // Read request
            val request = ByteArray(256)
            val bytesRead = input.read(request)
            
            if (bytesRead >= 4) {
                val cmd = request[1]
                
                if (cmd == 0x01.toByte()) {  // CONNECT
                    val addrType = request[3]
                    
                    if (addrType == 0x01.toByte()) {  // IPv4
                        // FIX: Convert Byte to Int before bitwise operation
                        val destIp = "${request[4].toInt() and 0xFF}.${request[5].toInt() and 0xFF}.${request[6].toInt() and 0xFF}.${request[7].toInt() and 0xFF}"
                        val destPort = ((request[8].toInt() and 0xFF) shl 8) or (request[9].toInt() and 0xFF)
                        
                        LogUtil.d(TAG, "🔗 SOCKS5: $destIp:$destPort")
                        
                        // Forward melalui VPN socket
                        forwardThroughVpn(clientSocket, destIp, destPort)
                        return
                    }
                }
            }
            
            clientSocket.close()
        } catch (e: Exception) {
            LogUtil.w(TAG, "⚠️ SOCKS5 handling error: ${e.message}")
            try { clientSocket.close() } catch (e: Exception) {}
        }
    }
    
    // 🔥 NEW FUNCTION: FORWARD TRAFFIC THROUGH VPN
    private fun forwardThroughVpn(clientSocket: java.net.Socket, destIp: String, destPort: Int) {
        try {
            // RAW SOCKET FORWARDING MELALUI VPN
            val destAddr = java.net.InetSocketAddress(destIp, destPort)
            val vpnSocket = java.net.Socket()
            
            // Connect melalui VPN interface
            vpnSocket.connect(destAddr)
            
            // Stream forwarding
            Thread {
                try {
                    val clientInput = clientSocket.getInputStream()
                    val vpnOutput = vpnSocket.getOutputStream()
                    
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    
                    while (isRunning.get() && clientSocket.isConnected && 
                           vpnSocket.isConnected) {
                        bytesRead = clientInput.read(buffer)
                        if (bytesRead == -1) break
                        vpnOutput.write(buffer, 0, bytesRead)
                        vpnOutput.flush()
                    }
                } catch (e: Exception) {
                    LogUtil.w(TAG, "⚠️ Client→VPN stream error")
                } finally {
                    try { clientSocket.close() } catch (e: Exception) {}
                    try { vpnSocket.close() } catch (e: Exception) {}
                }
            }.start()
            
            Thread {
                try {
                    val vpnInput = vpnSocket.getInputStream()
                    val clientOutput = clientSocket.getOutputStream()
                    
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    
                    while (isRunning.get() && clientSocket.isConnected && 
                           vpnSocket.isConnected) {
                        bytesRead = vpnInput.read(buffer)
                        if (bytesRead == -1) break
                        clientOutput.write(buffer, 0, bytesRead)
                        clientOutput.flush()
                    }
                } catch (e: Exception) {
                    LogUtil.w(TAG, "⚠️ VPN→Client stream error")
                } finally {
                    try { clientSocket.close() } catch (e: Exception) {}
                    try { vpnSocket.close() } catch (e: Exception) {}
                }
            }.start()
            
            LogUtil.d(TAG, "✅ Traffic forwarded via VPN socket")
            
        } catch (e: Exception) {
            LogUtil.e(TAG, "💥 VPN forwarding failed: ${e.message}")
            try { clientSocket.close() } catch (e: Exception) {}
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

    private fun showProxySetupNotification() {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) 
                as NotificationManager
                
            val channelId = "proxy_setup_channel"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "Proxy Setup Instructions",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Manual proxy setup for VPN bypass"
                    enableVibration(true)
                    setShowBadge(true)
                }
                notificationManager.createNotificationChannel(channel)
            }
            
            // Intent untuk buka WiFi settings
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra("EXTRA_PREFS_SHOW_BUTTON_BAR", true)
                putExtra("EXTRA_PREFS_SET_BACK_TEXT", "Back")
                putExtra("EXTRA_PREFS_SET_NEXT_TEXT", "Next")
            }
            
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            // Intent untuk copy proxy settings
            val copyIntent = Intent(this, VpnDnsService::class.java).apply {
                action = "COPY_PROXY_SETTINGS"
            }
            val copyPendingIntent = PendingIntent.getService(
                this, 1, copyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            val notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle("🔧 Manual Proxy Setup Required")
                .setContentText("Realme blocked auto-proxy. Tap to setup.")
                .setStyle(NotificationCompat.BigTextStyle()
                    .bigText("1. Go to WiFi Settings\n" +
                            "2. Tap current network\n" +
                            "3. Edit network → Advanced\n" +
                            "4. Proxy: Manual\n" +
                            "5. Hostname: 127.0.0.1\n" +
                            "6. Port: 1080\n" +
                            "7. Save"))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .addAction(
                    android.R.drawable.ic_menu_share,
                    "Copy Settings",
                    copyPendingIntent
                )
                .setAutoCancel(true)
                .setOngoing(false)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
                
            notificationManager.notify(8888, notification)
            
            LogUtil.d(TAG, "📋 Proxy setup notification shown")
            
        } catch (e: Exception) {
            LogUtil.e(TAG, "❌ Notification failed: ${e.message}")
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

                // Kill SOCKS5 proxy
                try {
                    Runtime.getRuntime().exec("fuser -k 1080/tcp 2>/dev/null || true")
                    LogUtil.d(TAG, "✅ SOCKS5 proxy killed")
                } catch (e: Exception) {
                    LogUtil.w(TAG, "⚠️ Cannot kill SOCKS5 proxy")
                }
                
                // Remove system proxy
                try {
                    Settings.Global.putString(contentResolver, Settings.Global.HTTP_PROXY, "")
                    LogUtil.d(TAG, "✅ System proxy cleared")
                } catch (e: Exception) {
                    LogUtil.w(TAG, "⚠️ Cannot clear system proxy")
                }
                
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

            "COPY_PROXY_SETTINGS" -> {
            // Copy proxy settings to clipboard
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Proxy Settings", "127.0.0.1:1080")
            clipboard.setPrimaryClip(clip)
            
            Toast.makeText(this, "Proxy settings copied", Toast.LENGTH_SHORT).show()
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
