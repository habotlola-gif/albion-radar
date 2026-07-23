package com.eni.albionradar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

class RadarVpnService : VpnService() {

    companion object {
        private const val TAG = "RadarVPN"
        private const val CHANNEL_ID = "albion_radar_vpn"
        private const val NOTIFICATION_ID = 1
        private var isRunning = false
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var captureThread: Thread? = null
    private val udpSockets = ConcurrentHashMap<String, DatagramSocket>()
    private var outputStream: FileOutputStream? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        if (!isRunning) {
            isRunning = true
            startVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        try {
            val builder = Builder()
                .setSession("Albion Radar")
                .addAddress("10.0.0.2", 24)
                .addDnsServer("8.8.8.8")
                .addRoute("0.0.0.0", 0)
                .setMtu(1500)

            // Пропускаем только Альбион через VPN
            val packages = listOf("com.albiononline.mobile", "com.sandboxinteractive.albiononline")
            for (pkg in packages) {
                try {
                    builder.addAllowedApplication(pkg)
                    Log.i(TAG, "Routing $pkg")
                    break
                } catch (_: Exception) {}
            }

            vpnInterface = builder.establish()
            if (vpnInterface == null) return
            outputStream = FileOutputStream(vpnInterface!!.fileDescriptor)
            startPacketCapture()
        } catch (e: Exception) {
            Log.e(TAG, "VPN error: ${e.message}")
        }
    }

    private fun startPacketCapture() {
        captureThread = Thread {
            val input = FileInputStream(vpnInterface!!.fileDescriptor)
            val buffer = ByteBuffer.allocate(32767)
            while (isRunning) {
                try {
                    buffer.clear()
                    val length = input.read(buffer.array())
                    if (length <= 0) continue
                    buffer.limit(length)
                    processPacket(buffer)
                } catch (e: Exception) {
                    if (isRunning) Log.e(TAG, "Capture: ${e.message}")
                    break
                }
            }
            try { input.close() } catch (_: Exception) {}
        }
        captureThread?.start()
    }

    private fun processPacket(buffer: ByteBuffer) {
        try {
            if (buffer.limit() < 28) return
            val version = (buffer.get(0).toInt() shr 4) and 0x0F
            if (version != 4) return
            val ihl = (buffer.get(0).toInt() and 0x0F) * 4
            val protocol = buffer.get(9).toInt() and 0xFF
            if (protocol != 17) return // Только UDP

            val srcIp = readIp(buffer, 12)
            val dstIp = readIp(buffer, 16)
            val srcPort = readPort(buffer, ihl)
            val dstPort = readPort(buffer, ihl + 2)
            val udpLen = readShort(buffer, ihl + 4)
            val payloadLen = udpLen - 8
            if (payloadLen <= 0 || ihl + 8 + payloadLen > buffer.limit()) return

            val payload = ByteArray(payloadLen)
            buffer.position(ihl + 8)
            buffer.get(payload)

            PhotonParser.parse(payload, srcPort, dstPort)
            forwardUdp(srcIp, srcPort, dstIp, dstPort, payload)
        } catch (_: Exception) {}
    }

    private fun forwardUdp(srcIp: String, srcPort: Int, dstIp: String, dstPort: Int, payload: ByteArray) {
        val key = "$srcPort-$dstIp-$dstPort"
        if (!udpSockets.containsKey(key)) {
            val socket = DatagramSocket()
            protect(socket) // Защита от петли
            udpSockets[key] = socket

            // Поток приёма ответов от сервера
            Thread {
                val recv = ByteArray(32767)
                while (isRunning && !socket.isClosed) {
                    try {
                        socket.soTimeout = 30000
                        val pkt = DatagramPacket(recv, recv.size)
                        socket.receive(pkt)

                        val resp = ByteArray(pkt.length)
                        System.arraycopy(pkt.data, 0, resp, 0, pkt.length)
                        PhotonParser.parse(resp, pkt.port, srcPort)

                        val respSrcIp = pkt.address.hostAddress ?: dstIp
                        val packet = buildUdpPacket(respSrcIp, srcIp, pkt.port, srcPort, resp)
                        outputStream?.let { stream ->
                            synchronized(stream) { stream.write(packet) }
                        }
                    } catch (_: Exception) { break }
                }
                udpSockets.remove(key)
                try { socket.close() } catch (_: Exception) {}
            }.start()
        }

        try {
            udpSockets[key]?.send(DatagramPacket(payload, payload.size, InetAddress.getByName(dstIp), dstPort))
        } catch (_: Exception) {}
    }

    private fun buildUdpPacket(srcIp: String, dstIp: String, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val total = 20 + 8 + payload.size
        val pkt = ByteArray(total)
        pkt[0] = 0x45
        pkt[2] = ((total shr 8) and 0xFF).toByte()
        pkt[3] = (total and 0xFF).toByte()
        pkt[6] = 0x40; pkt[8] = 64; pkt[9] = 17
        val s = srcIp.split(".")
        pkt[12] = s[0].toInt().toByte(); pkt[13] = s[1].toInt().toByte()
        pkt[14] = s[2].toInt().toByte(); pkt[15] = s[3].toInt().toByte()
        val d = dstIp.split(".")
        pkt[16] = d[0].toInt().toByte(); pkt[17] = d[1].toInt().toByte()
        pkt[18] = d[2].toInt().toByte(); pkt[19] = d[3].toInt().toByte()

        var sum = 0L
        for (i in 0 until 20 step 2) sum += ((pkt[i].toInt() and 0xFF) shl 8) or (pkt[i+1].toInt() and 0xFF)
        while (sum > 0xFFFF) sum = (sum and 0xFFFF) + (sum shr 16)
        sum = sum xor 0xFFFF
        pkt[10] = ((sum shr 8) and 0xFF).toByte(); pkt[11] = (sum and 0xFF).toByte()

        pkt[20] = ((srcPort shr 8) and 0xFF).toByte(); pkt[21] = (srcPort and 0xFF).toByte()
        pkt[22] = ((dstPort shr 8) and 0xFF).toByte(); pkt[23] = (dstPort and 0xFF).toByte()
        val ulen = 8 + payload.size
        pkt[24] = ((ulen shr 8) and 0xFF).toByte(); pkt[25] = (ulen and 0xFF).toByte()
        System.arraycopy(payload, 0, pkt, 28, payload.size)
        return pkt
    }

    private fun readIp(b: ByteBuffer, o: Int) = "${b.get(o).toInt() and 0xFF}.${b.get(o+1).toInt() and 0xFF}.${b.get(o+2).toInt() and 0xFF}.${b.get(o+3).toInt() and 0xFF}"
    private fun readPort(b: ByteBuffer, o: Int) = ((b.get(o).toInt() and 0xFF) shl 8) or (b.get(o+1).toInt() and 0xFF)
    private fun readShort(b: ByteBuffer, o: Int) = ((b.get(o).toInt() and 0xFF) shl 8) or (b.get(o+1).toInt() and 0xFF)

    private fun createNotification() = Notification.Builder(this, CHANNEL_ID)
        .setContentTitle("Albion Radar")
        .setContentText("Sniffing — ${RadarEngine.getPlayerCount()} players")
        .setSmallIcon(android.R.drawable.ic_menu_compass)
        .setOngoing(true).build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Albion Radar VPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        captureThread?.interrupt()
        for ((_, s) in udpSockets) { try { s.close() } catch (_: Exception) {} }
        udpSockets.clear()
        try { outputStream?.close() } catch (_: Exception) {}
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
    }

    override fun onRevoke() {
        super.onRevoke()
        isRunning = false
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        stopSelf()
    }
}
