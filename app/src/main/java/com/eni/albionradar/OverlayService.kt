package com.eni.albionradar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager

class OverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "albion_radar_overlay"
        private const val NOTIFICATION_ID = 2
    }

    private var windowManager: WindowManager? = null
    private var radarView: RadarView? = null
    private val handler = Handler(Looper.getMainLooper())

    private val updateRunnable = object : Runnable {
        override fun run() {
            radarView?.invalidate()
            handler.postDelayed(this, 50)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        radarView = RadarView(this)
        radarView?.let { rv ->
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START

            try {
                windowManager?.addView(rv, params)
                handler.post(updateRunnable)
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateRunnable)
        radarView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        radarView = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Albion Radar Overlay")
            .setContentText("Radar active — ${RadarEngine.getPlayerCount()} players")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Albion Radar Overlay",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private class RadarView(context: Context) : View(context) {

        private val paintPlayer = Paint().apply {
            color = Color.rgb(255, 60, 60)
            isAntiAlias = true
            style = Paint.Style.FILL
        }

        private val paintPlayerText = Paint().apply {
            color = Color.WHITE
            isAntiAlias = true
            textSize = 24f
            setShadowLayer(3f, 1f, 1f, Color.BLACK)
        }

        private val paintLocal = Paint().apply {
            color = Color.rgb(60, 220, 100)
            isAntiAlias = true
            style = Paint.Style.FILL
        }

        private val paintCircle = Paint().apply {
            color = Color.argb(30, 120, 80, 220)
            isAntiAlias = true
            style = Paint.Style.FILL
        }

        private val paintCircleStroke = Paint().apply {
            color = Color.argb(80, 160, 120, 255)
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }

        private val paintLine = Paint().apply {
            color = Color.argb(60, 255, 255, 255)
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }

        private val scale = 0.5f
        private val radarRadius = 1500f

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val cx = width / 2f
            val cy = height / 2f

            val localPos = RadarEngine.getLocalPosition()
            val localX = localPos[0]
            val localZ = localPos[2]

            canvas.drawCircle(cx, cy, radarRadius * scale, paintCircle)
            canvas.drawCircle(cx, cy, radarRadius * scale, paintCircleStroke)

            canvas.drawLine(cx - 20, cy, cx + 20, cy, paintLine)
            canvas.drawLine(cx, cy - 20, cx, cy + 20, paintLine)

            val players = RadarEngine.getPlayers()
            val localPlayerId = RadarEngine.getLocalPlayer()?.id

            for ((_, player) in players) {
                if (player.id == localPlayerId) continue

                val relX = (player.x - localX) * scale
                val relZ = (player.z - localZ) * scale

                val screenX = cx + relX
                val screenY = cy + relZ

                val dist = Math.sqrt((relX * relX + relZ * relZ).toDouble()).toFloat()
                if (dist > radarRadius * scale) continue

                canvas.drawCircle(screenX, screenY, 5f, paintPlayer)

                if (dist < 500) {
                    canvas.drawText(player.name, screenX + 8, screenY + 4, paintPlayerText)
                }

                canvas.drawLine(cx, cy, screenX, screenY, paintLine)
            }

            canvas.drawCircle(cx, cy, 8f, paintLocal)
        }
    }
}
