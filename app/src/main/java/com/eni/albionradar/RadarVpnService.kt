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
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RadarVpnService : VpnService() {

    companion object {
        private const val TAG = "RadarVPN"
        private const val CHANNEL_ID = "albion_radar_vpn"
        private const val NOTIFICATION_ID = 1
        private var isRunning = false
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var captureThread: Thread? = null

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

            vpnInterface = builder.establish()

            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface")
                return
            }

            Log.i(TAG, "VPN interface established")
            startPacketCapture()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting VPN: ${e.message}")
        }
    }

    private fun startPacketCapture() {
        captureThread = Thread {
            val fd = vpnInterface?.fileDescriptor ?: return@Thread
            val input = FileInputStream(fd)
            val buffer = ByteBuffer.allocate(32767)
            buffer.order(ByteOrder.LITTLE_ENDIAN)

            Log.i(TAG, "Packet capture started")

            while (isRunning) {
                try {
                    buffer.clear()
                    val length = input.read(buffer.array())
                    if (length <= 0) continue

                    buffer.limit(length)
                    processPacket(buffer)
                } catch (e: Exception) {
                    if (isRunning) {
                        Log.e(TAG, "Capture error: ${e.message}")
                    }
                    break
                }
            }

            Log.i(TAG, "Packet capture stopped")
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

            if (protocol != 17) return

            val srcPort = ((buffer.get(ihl).toInt() and 0xFF) shl 8) or (buffer.get(ihl + 1).toInt() and 0xFF)
            val dstPort = ((buffer.get(ihl + 2).toInt() and 0xFF) shl 8) or (buffer.get(ihl + 3).toInt() and 0xFF)
            val udpLength = ((buffer.get(ihl + 4).toInt() and 0xFF) shl 8) or (buffer.get(ihl + 5).toInt() and 0xFF)

            val payloadOffset = ihl + 8
            val payloadLength = udpLength - 8

            if (payloadLength < 4) return
            if (payloadOffset + payloadLength > buffer.limit()) return

            val payload = ByteArray(payloadLength)
            buffer.position(payloadOffset)
            buffer.get(payload)

            PhotonParser.parse(payload, srcPort, dstPort)
        } catch (_: Exception) {}
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Albion Radar")
            .setContentText("Sniffing — ${RadarEngine.getPlayerCount()} players")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Albion Radar VPN",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        captureThread?.interrupt()
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
