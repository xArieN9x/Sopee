package com.example.cedokbooster

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class VpnDnsService : VpnService() {
    
    companion object {
        private const val TAG = "VpnDnsService"
        private const val NOTIFICATION_ID = 999
        private const val CHANNEL_ID = "vpn_dns_channel"
        private const val DNS_PROXY_PORT = 5353
        private const val VPN_ADDRESS = "100.64.0.2"
        private const val VPN_PREFIX_LENGTH = 24
        
        fun startVpn(context: Context) {
            val intent = Intent(context, VpnDnsService::class.java).apply {
                action = ACTION_START
            }
            context.startService(intent)
        }
        
        fun stopVpn(context: Context) {
            val intent = Intent(context, VpnDnsService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
    
    // Actions
    private val ACTION_START = "${BuildConfig.APPLICATION_ID}.START_VPN"
    private val ACTION_STOP = "${BuildConfig.APPLICATION_ID}.STOP_VPN"
    
    // State
    private var isRunning = AtomicBoolean(false)
    private var vpnInterface: ParcelFileDescriptor? = null
    private var dnsProxyThread: Thread? = null
    private var dnsProxySocket: DatagramSocket? = null
    private val executor: ExecutorService = Executors.newFixedThreadPool(4)
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    
    // DNS Configuration
    private val primaryDns = "1.1.1.1"
    private val secondaryDns = "8.8.8.8"
    
    /**
     * SETUP DNS-ONLY VPN (Realme C3 Android 10 compatible)
     */
    private fun setupDnsOnlyVpn(): Boolean {
        return try {
            LogUtil.d(TAG, "Setting up DNS-only VPN for Realme C3")
            
            val builder = Builder()
                .setSession("CedokDNS")
                .addAddress(VPN_ADDRESS, VPN_PREFIX_LENGTH)
                .setMtu(1280)
                .setBlocking(true)
            
            // 🔥 REALME C3 TRICK: SYSTEM APPS BOLEH BYPASS DNS KITA
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    // SystemUI - biar guna DNS default (elak detection)
                    builder.addDisallowedApplication("com.android.systemui")
                    // Settings app
                    builder.addDisallowedApplication("com.android.settings")
                    // Phone/Dialer
                    builder.addDisallowedApplication("com.android.dialer")
                    LogUtil.d(TAG, "✅ System apps disallowed (Realme stealth mode)")
                } catch (e: Exception) {
                    LogUtil.w(TAG, "⚠️ Cannot disallow system apps: ${e.message}")
                }
            }
            
            // 🔥 TRICK 1: SET DNS SERVERS (Android akan forward ke VPN)
            builder.addDnsServer(primaryDns)
            builder.addDnsServer(secondaryDns)
            
            // 🔥 TRICK 2: ROUTE KE DNS SERVERS SAHAJA (Realme allow)
            // Route ke Cloudflare DNS
            builder.addRoute("1.1.1.1", 32)
            builder.addRoute("1.0.0.1", 32)
            // Route ke Google DNS
            builder.addRoute("8.8.8.8", 32)
            builder.addRoute("8.8.4.4", 32)
            // Route ke Telco DNS (untuk intercept)
            builder.addRoute("203.82.91.14", 32)
            builder.addRoute("203.82.91.30", 32)
            
            // 🔥 TRICK 3: ROUTE KE POPULAR SERVICES (optional)
            builder.addRoute("142.250.0.0", 16)  // Google
            builder.addRoute("13.0.0.0", 8)      // AWS
            builder.addRoute("34.0.0.0", 8)      // Google Cloud
            
            // 🔥 TRICK 4: NO DEFAULT ROUTE (Realme block)
            // JANGAN guna: builder.addRoute("0.0.0.0", 0)
            
            // Establish VPN
            val fd = builder.establish()
            if (fd != null) {
                vpnInterface = fd
                LogUtil.d(TAG, "✅ VPN established successfully")
                
                // 🔥 TRICK 5: START DNS PROXY (local port 5353)
                coroutineScope.launch {
                    startDnsProxy()
                }
                
                return true
            }
            
            LogUtil.e(TAG, "❌ VPN establishment failed")
            false
            
        } catch (e: Exception) {
            LogUtil.e(TAG, "💥 VPN setup error: ${e.message}")
            false
        }
    }
    
    /**
     * DNS PROXY - Handle DNS queries from VPN
     */
    private fun startDnsProxy() {
        LogUtil.d(TAG, "Starting DNS proxy on port $DNS_PROXY_PORT")
        
        try {
            // Close existing socket
            dnsProxySocket?.close()
            dnsProxyThread?.interrupt()
            
            // Create UDP socket
            dnsProxySocket = DatagramSocket(DNS_PROXY_PORT).apply {
                reuseAddress = true
                soTimeout = 5000
            }
            
            // Start proxy thread
            dnsProxyThread = Thread {
                var consecutiveErrors = 0
                val maxErrors = 5
                
                while (!Thread.currentThread().isInterrupted && isRunning.get()) {
                    try {
                        // Receive DNS query
                        val buffer = ByteArray(512)
                        val packet = DatagramPacket(buffer, buffer.size)
                        dnsProxySocket!!.receive(packet)
                        
                        consecutiveErrors = 0
                        
                        // Process query in background
                        executor.submit {
                            handleDnsQuery(packet)
                        }
                        
                    } catch (e: java.net.SocketTimeoutException) {
                        // Timeout is normal
                        continue
                    } catch (e: Exception) {
                        consecutiveErrors++
                        LogUtil.w(TAG, "DNS Proxy error #$consecutiveErrors: ${e.message}")
                        
                        if (consecutiveErrors >= maxErrors) {
                            LogUtil.e(TAG, "🚨 Too many errors, restarting proxy...")
                            restartDnsProxy()
                            break
                        }
                    }
                }
                
                LogUtil.d(TAG, "DNS Proxy thread stopped")
            }
            
            dnsProxyThread!!.priority = Thread.MAX_PRIORITY
            dnsProxyThread!!.start()
            
            LogUtil.d(TAG, "✅ DNS Proxy started successfully")
            
        } catch (e: Exception) {
            LogUtil.e(TAG, "💥 Failed to start DNS proxy: ${e.message}")
            
            // Retry after delay
            coroutineScope.launch {
                kotlinx.coroutines.delay(3000)
                if (isRunning.get()) {
                    startDnsProxy()
                }
            }
        }
    }
    
    /**
     * HANDLE DNS QUERY - Forward to 1.1.1.1 or 8.8.8.8
     */
    private fun handleDnsQuery(queryPacket: DatagramPacket) {
        var socket: DatagramSocket? = null
        
        try {
            // Extract query info
            val clientIp = queryPacket.address.hostAddress
            val clientPort = queryPacket.port
            val queryData = queryPacket.data.copyOf(queryPacket.length)
            
            // Try decode DNS ID for logging
            val queryId = if (queryData.size >= 2) {
                ((queryData[0].toInt() and 0xFF) shl 8) or (queryData[1].toInt() and 0xFF)
            } else 0
            
            LogUtil.d(TAG, "📡 DNS Query #$queryId from $clientIp:$clientPort")
            
            // 🔥 TRICK: Gunakan protect() supaya DNS query tak loop ke VPN
            val dnsSocket = DatagramSocket()
            protect(dnsSocket)
            
            // 🔥 DUAL DNS STRATEGY: Cubal 1.1.1.1 dulu, then 8.8.8.8
            var success = false
            val dnsServers = listOf(primaryDns, secondaryDns)
            
            for (dnsServer in dnsServers) {
                try {
                    // Send to DNS server
                    val forwardPacket = DatagramPacket(
                        queryData,
                        queryData.size,
                        InetAddress.getByName(dnsServer),
                        53
                    )
                    
                    dnsSocket.soTimeout = 3000
                    dnsSocket.send(forwardPacket)
                    
                    // Receive response
                    val responseBuffer = ByteArray(512)
                    val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
                    dnsSocket.receive(responsePacket)
                    
                    // Send back to client
                    val replyPacket = DatagramPacket(
                        responsePacket.data,
                        responsePacket.length,
                        queryPacket.address,
                        queryPacket.port
                    )
                    
                    dnsProxySocket!!.send(replyPacket)
                    
                    success = true
                    LogUtil.d(TAG, "✅ DNS resolved via $dnsServer for #$queryId")
                    break
                    
                } catch (e: java.net.SocketTimeoutException) {
                    LogUtil.w(TAG, "⚠️ $dnsServer timeout")
                    continue
                } catch (e: Exception) {
                    LogUtil.w(TAG, "❌ $dnsServer failed: ${e.message}")
                    continue
                }
            }
            
            if (!success) {
                LogUtil.e(TAG, "💥 All DNS servers failed for #$queryId")
            }
            
            dnsSocket.close()
            
        } catch (e: Exception) {
            LogUtil.e(TAG, "💥 DNS handling error: ${e.message}")
        } finally {
            socket?.close()
        }
    }
    
    /**
     * RESTART DNS PROXY jika crash
     */
    private fun restartDnsProxy() {
        coroutineScope.launch {
            LogUtil.d(TAG, "Restarting DNS proxy...")
            
            dnsProxyThread?.interrupt()
            dnsProxySocket?.close()
            
            kotlinx.coroutines.delay(1000)
            
            if (isRunning.get()) {
                startDnsProxy()
            }
        }
    }
    
    /**
     * SHOW NOTIFICATION (Foreground service required)
     */
    private fun showNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) 
            as NotificationManager
        
        // Create notification channel (Android 8.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DNS Protection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "DNS protection is active"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
        
        // Intent untuk buka app
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Stop action
        val stopIntent = Intent(this, VpnDnsService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Build notification
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🛡️ CedokDNS Active")
            .setContentText("DNS: 1.1.1.1 • 8.8.8.8")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopPendingIntent
            )
            .setAutoCancel(false)
            .build()
        
        startForeground(NOTIFICATION_ID, notification)
        LogUtil.d(TAG, "✅ Notification shown")
    }
    
    /**
     * START VPN SERVICE
     */
    private fun startVpnService() {
        if (isRunning.get()) {
            LogUtil.d(TAG, "VPN already running")
            return
        }
        
        LogUtil.d(TAG, "Starting VPN service...")
        
        // Request VPN permission jika belum
        val intent = VpnService.prepare(this)
        if (intent != null) {
            LogUtil.e(TAG, "VPN permission not granted")
            stopSelf()
            return
        }
        
        // Setup VPN
        val success = setupDnsOnlyVpn()
        if (success) {
            isRunning.set(true)
            showNotification()
            
            // Broadcast status
            sendBroadcast(Intent("DNS_VPN_STATUS").apply {
                putExtra("status", "ACTIVE")
                putExtra("primary_dns", primaryDns)
                putExtra("secondary_dns", secondaryDns)
            })
            
            LogUtil.d(TAG, "✅ VPN started successfully")
        } else {
            LogUtil.e(TAG, "❌ Failed to start VPN")
            stopSelf()
        }
    }
    
    /**
     * STOP VPN SERVICE
     */
    private fun stopVpnService() {
        if (!isRunning.get()) {
            LogUtil.d(TAG, "VPN already stopped")
            return
        }
        
        LogUtil.d(TAG, "Stopping VPN service...")
        
        // Signal stop
        isRunning.set(false)
        
        // Stop DNS proxy
        dnsProxyThread?.interrupt()
        dnsProxySocket?.close()
        executor.shutdownNow()
        
        // Close VPN interface
        vpnInterface?.close()
        vpnInterface = null
        
        // Stop foreground service
        stopForeground(true)
        
        // Broadcast status
        sendBroadcast(Intent("DNS_VPN_STATUS").apply {
            putExtra("status", "STOPPED")
        })
        
        LogUtil.d(TAG, "✅ VPN stopped successfully")
        stopSelf()
    }
    
    /**
     * SERVICE LIFECYCLE
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpnService()
            ACTION_STOP -> stopVpnService()
        }
        return START_STICKY
    }
    
    override fun onDestroy() {
        LogUtil.d(TAG, "Service destroying")
        stopVpnService()
        super.onDestroy()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    /**
     * LOGGING UTILITY
     */
    private object LogUtil {
        fun d(tag: String, message: String) = android.util.Log.d(tag, message)
        fun e(tag: String, message: String) = android.util.Log.e(tag, message)
        fun w(tag: String, message: String) = android.util.Log.w(tag, message)
    }
}
