package com.eni.albionradar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var btnStartVpn: Button
    private lateinit var btnStartOverlay: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvPlayerCount: TextView

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        } else {
            tvStatus.text = "Status: VPN permission denied"
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Продолжаем в любом случае, уведомления не критичны для работы
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStartVpn = findViewById(R.id.btnStartVpn)
        btnStartOverlay = findViewById(R.id.btnStartOverlay)
        btnStop = findViewById(R.id.btnStop)
        tvStatus = findViewById(R.id.tvStatus)
        tvPlayerCount = findViewById(R.id.tvPlayerCount)

        // Запрашиваем разрешение на уведомления (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        btnStartVpn.setOnClickListener {
            val intent = VpnService.prepare(this)
            if (intent != null) {
                vpnPermissionLauncher.launch(intent)
            } else {
                startVpnService()
            }
        }

        btnStartOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                startOverlayService()
            }
        }

        btnStop.setOnClickListener {
            stopService(Intent(this, RadarVpnService::class.java))
            stopService(Intent(this, OverlayService::class.java))
            tvStatus.text = "Status: Stopped"
            RadarEngine.clear()
        }

        Thread {
            while (true) {
                Thread.sleep(500)
                runOnUiThread {
                    tvPlayerCount.text = "Players: ${RadarEngine.getPlayerCount()} | PKT: ${PhotonParser.packetsParsed}"
                }
            }
        }.start()
    }

    private fun startVpnService() {
        try {
            val intent = Intent(this, RadarVpnService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            tvStatus.text = "Status: VPN Running"
        } catch (e: Exception) {
            tvStatus.text = "Status: Error - ${e.message}"
        }
    }

    private fun startOverlayService() {
        try {
            val intent = Intent(this, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            tvStatus.text = "Status: Overlay Active"
        } catch (e: Exception) {
            tvStatus.text = "Status: Error - ${e.message}"
        }
    }
}
