package com.jarvis.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var etApiKey: EditText
    private lateinit var btnToggle: Button
    private lateinit var tvStatus: TextView

    companion object {
        const val PREFS_NAME = "JarvisPrefs"
        const val KEY_API_KEY = "apiKey"
        private const val PERMISSION_REQUEST_CODE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etApiKey = findViewById(R.id.etApiKey)
        btnToggle = findViewById(R.id.btnToggle)
        tvStatus = findViewById(R.id.tvStatus)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedKey = prefs.getString(KEY_API_KEY, "")
        etApiKey.setText(savedKey)

        checkAndRequestPermissions()

        btnToggle.setOnClickListener {
            val apiKey = etApiKey.text.toString().trim()
            if (apiKey.isEmpty()) {
                etApiKey.error = "API key required"
                return@setOnClickListener
            }

            prefs.edit().putString(KEY_API_KEY, apiKey).apply()

            val serviceIntent = Intent(this, JarvisService::class.java).apply {
                putExtra("API_KEY", apiKey)
            }

            if (JarvisService.isRunning) {
                stopService(serviceIntent)
                btnToggle.text = "Start Jarvis"
                tvStatus.text = "Status: Disconnected"
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                btnToggle.text = "Stop Jarvis"
                tvStatus.text = "Status: Connecting..."
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            permissions.add(Manifest.permission.FOREGROUND_SERVICE_MICROPHONE)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }
}
