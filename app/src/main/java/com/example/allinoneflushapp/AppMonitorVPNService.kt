package com.example.allinoneflushapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AppMonitorVPNService : VpnService() {
    companion object {
        private var pandaActive = false
        private var dnsIndex = 0
        private var instance: AppMonitorVPNService? = null
        fun isPandaActive() = pandaActive
        fun rotateDNS(dnsList: List<String>) {
            if (instance == null) return
            dnsIndex = (dnsIndex + 1) % dnsList.size
            val nextDNS = dnsList[dnsIndex]
            instance?.establishVPN(nextDNS)
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var forwardingActive = false
    private val tcpConnections = ConcurrentHashMap<Int, Socket>() // localPort -> Socket
    private val threadPool: ExecutorService = Executors.newCachedThreadPool()
    private val CHANNEL_ID = "panda_monitor_channel"
    private val NOTIF_ID = 1001

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        createNotificationChannel()
        startForeground(NOTIF_ID, createNotification("Panda Monitor running", connected = false))
        establishVPN("8.8.8.8")
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL_ID, "Panda Monitor", NotificationManager.IMPORTANCE_LOW)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String, connected: Boolean): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val smallIcon = if (connected) android.R.drawable.presence_online else android.R.drawable.presence_busy
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Panda Monitor")
            .setContentText(text)
            .setSmallIcon(smallIcon)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    fun establishVPN(dns: String) {
        try {
            forwardingActive = false
            // Tutup semua TCP connection lama
            tcpConnections.values.forEach { it.close() }
            tcpConnections.clear()
            vpnInterface?.close()
        } catch (_: Exception) {}

        val builder = Builder()
        builder.setSession("PandaMonitor")
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addAllowedApplication("com.logistics.rider.foodpanda")
            .addDnsServer(dns)

        vpnInterface = try {
            builder.establish()
        } catch (e: Exception) {
            null
        }

        try {
            startForeground(NOTIF_ID, createNotification("Panda Monitor (DNS: $dns)", connected = vpnInterface != null))
        } catch (_: Exception) {}

        if (vpnInterface != null) {
            forwardingActive = true
            startPacketForwarding()
        }
    }

    private fun startPacketForwarding() {
        // Thread: Baca dari VPN → hantar ke internet
        threadPool.execute {
            val buffer = ByteArray(2048)
            while (forwardingActive) {
                try {
                    val fd = vpnInterface?.fileDescriptor ?: break
                    val len = FileInputStream(fd).read(buffer)
                    if (len > 0) {
                        pandaActive = true
                        handleOutboundPacket(buffer.copyOfRange(0, len))
                    }
                } catch (e: Exception) {
                    pandaActive = false
                    Thread.sleep(100)
                }
            }
        }
    }

    private fun handleOutboundPacket(packet: ByteArray) {
        try {
            val ipHeaderLen = (packet[0].toInt() and 0x0F) * 4
            if (ipHeaderLen < 20 || packet.size < ipHeaderLen + 4) return

            val protocol = packet[9].toUByte().toInt()
            val destIp = "${packet[16].toUByte()}.${packet[17].toUByte()}.${packet[18].toUByte()}.${packet[19].toUByte()}"
            val srcPort = ((packet[ipHeaderLen].toUByte().toInt() shl 8) or packet[ipHeaderLen + 1].toUByte().toInt())
            val destPort = ((packet[ipHeaderLen + 2].toUByte().toInt() shl 8) or packet[ipHeaderLen + 3].toUByte().toInt())
            val payload = packet.copyOfRange(ipHeaderLen + (if (protocol == 6 || protocol == 17) 8 else 0), packet.size)

            when (protocol) {
                17 -> { // UDP
                    threadPool.execute {
                        try {
                            val sock = DatagramSocket()
                            sock.send(DatagramPacket(payload, payload.size, InetAddress.getByName(destIp), destPort))
                            // Terima balik (sekiranya ada)
                            val reply = ByteArray(2048)
                            val rp = DatagramPacket(reply, reply.size)
                            sock.receive(rp)
                            val fullReply = buildUdpPacket(rp.address.hostAddress!!, rp.port, "10.0.0.2", srcPort, rp.data, rp.length)
                            writePacketToVpn(fullReply)
                        } catch (_: Exception) {
                        } finally {
                            sock.close()
                        }
                    }
                }
                6 -> { // TCP
                    if (!tcpConnections.containsKey(srcPort)) {
                        threadPool.execute {
                            try {
                                val socket = Socket(InetAddress.getByName(destIp), destPort)
                                socket.tcpNoDelay = true
                                tcpConnections[srcPort] = socket

                                // Relay: Internet → App
                                threadPool.execute {
                                    val outStream = FileOutputStream(vpnInterface!!.fileDescriptor)
                                    val buf = ByteArray(2048)
                                    try {
                                        while (forwardingActive && socket.isConnected && !socket.isClosed) {
                                            val n = socket.getInputStream().read(buf)
                                            if (n <= 0) break
                                            val tcpReply = buildTcpPacket(destIp, destPort, "10.0.0.2", srcPort, buf, n)
                                            outStream.write(tcpReply)
                                            outStream.flush()
                                        }
                                    } catch (_: Exception) {}
                                    // Clean up
                                    tcpConnections.remove(srcPort)
                                    socket.close()
                                }

                                // Hantar payload pertama (jika ada)
                                if (payload.isNotEmpty()) {
                                    socket.getOutputStream().write(payload)
                                    socket.getOutputStream().flush()
                                }
                            } catch (_: Exception) {
                                tcpConnections.remove(srcPort)
                            }
                        }
                    } else {
                        // Hantar data tambahan ke sambungan sedia ada
                        tcpConnections[srcPort]?.getOutputStream()?.let {
                            it.write(payload)
                            it.flush()
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    // Tulis packet balik ke app melalui VPN
    private fun writePacketToVpn(packet: ByteArray) {
        try {
            val fd = vpnInterface?.fileDescriptor ?: return
            FileOutputStream(fd).write(packet)
        } catch (_: Exception) {}
    }

    // --- Pembina Packet Asas (Minimum untuk UDP/TCP) ---
    private fun buildUdpPacket(srcIp: String, srcPort: Int, destIp: String, destPort: Int, payload: ByteArray, len: Int): ByteArray {
        val totalLen = 28 + len
        val packet = ByteArray(totalLen)
        // IP Header (20 bytes)
        packet[0] = 0x45 // IPv4, header len 5 words
        packet[2] = (totalLen ushr 8).toByte()
        packet[3] = (totalLen and 0xFF).toByte()
        packet[9] = 17 // UDP
        val ipParts = srcIp.split(".")
        packet[12] = ipParts[0].toUByte().toByte()
        packet[13] = ipParts[1].toUByte().toByte()
        packet[14] = ipParts[2].toUByte().toByte()
        packet[15] = ipParts[3].toUByte().toByte()
        val destParts = destIp.split(".")
        packet[16] = destParts[0].toUByte().toByte()
        packet[17] = destParts[1].toUByte().toByte()
        packet[18] = destParts[2].toUByte().toByte()
        packet[19] = destParts[3].toUByte().toByte()
        // UDP Header (8 bytes)
        packet[20] = (srcPort ushr 8).toByte()
        packet[21] = (srcPort and 0xFF).toByte()
        packet[22] = (destPort ushr 8).toByte()
        packet[23] = (destPort and 0xFF).toByte()
        packet[24] = ((8 + len) ushr 8).toByte()
        packet[25] = ((8 + len) and 0xFF).toByte()
        // Payload
        System.arraycopy(payload, 0, packet, 28, len)
        return packet
    }

    private fun buildTcpPacket(srcIp: String, srcPort: Int, destIp: String, destPort: Int, payload: ByteArray, len: Int): ByteArray {
        val totalLen = 40 + len // IP(20) + TCP(20 min)
        val packet = ByteArray(totalLen)
        // IP Header
        packet[0] = 0x45
        packet[2] = (totalLen ushr 8).toByte()
        packet[3] = (totalLen and 0xFF).toByte()
        packet[9] = 6 // TCP
        val srcParts = srcIp.split(".")
        packet[12] = srcParts[0].toUByte().toByte()
        packet[13] = srcParts[1].toUByte().toByte()
        packet[14] = srcParts[2].toUByte().toByte()
        packet[15] = srcParts[3].toUByte().toByte()
        val destParts = destIp.split(".")
        packet[16] = destParts[0].toUByte().toByte()
        packet[17] = destParts[1].toUByte().toByte()
        packet[18] = destParts[2].toUByte().toByte()
        packet[19] = destParts[3].toUByte().toByte()
        // TCP Header (minimum 20 bytes, tanpa optional fields)
        packet[20] = (srcPort ushr 8).toByte()
        packet[21] = (srcPort and 0xFF).toByte()
        packet[22] = (destPort ushr 8).toByte()
        packet[23] = (destPort and 0xFF).toByte()
        // ... (skip sequence/ack, cukup untuk local tunnel)
        packet[32] = (len ushr 8).toByte() // Window size
        packet[33] = (len and 0xFF).toByte()
        // Payload
        System.arraycopy(payload, 0, packet, 40, len)
        return packet
    }

    override fun onDestroy() {
        forwardingActive = false
        tcpConnections.values.forEach { it.close() }
        tcpConnections.clear()
        threadPool.shutdownNow()
        try {
            vpnInterface?.close()
        } catch (_: Exception) {}
        pandaActive = false
        instance = null
        super.onDestroy()
    }
}
