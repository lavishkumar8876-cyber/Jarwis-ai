package com.jarvis.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import android.content.pm.PackageManager
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit

class JarvisService : Service() {

    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var isRecording = false
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    companion object {
        var isRunning = false
        private const val CHANNEL_ID = "JarvisServiceChannel"
        private const val NOTIF_ID = 1
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val apiKey = intent?.getStringExtra("API_KEY") ?: return START_NOT_STICKY
        isRunning = true

        createNotificationChannel()
        val notification = createNotification("Jarvis is listening...")
        startForeground(NOTIF_ID, notification)

        startAudioPlayback()
        startWebSocketConnection(apiKey)
        startAudioRecording()

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Jarvis Live Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(message: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Jarvis Active")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun startWebSocketConnection(apiKey: String) {
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key=$apiKey"
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                val setupJson = """
                    {
                      "setup": {
                        "model": "models/gemini-2.0-flash-exp",
                        "generationConfig": {
                          "responseModalities": ["AUDIO"]
                        },
                        "systemInstruction": {
                          "parts": [{
                            "text": "Your name is Jarvis. Reply in the user's language (Hindi/Hinglish/English), short and natural."
                          }]
                        },
                        "tools": [{"googleSearch": {}}]
                      }
                    }
                """.trimIndent()
                ws.send(setupJson)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                if (text.contains("interrupted")) {
                    audioTrack?.flush()
                }
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                audioTrack?.write(bytes.toByteArray(), 0, bytes.size)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                // Reconnection or error handling
            }
        })
    }

    private fun startAudioRecording() {
        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )

        if (AcousticEchoCanceler.isAvailable()) {
            audioRecord?.audioSessionId?.let { AcousticEchoCanceler.create(it)?.enabled = true }
        }
        if (NoiseSuppressor.isAvailable()) {
            audioRecord?.audioSessionId?.let { NoiseSuppressor.create(it)?.enabled = true }
        }

        isRecording = true
        Thread {
            val buffer = ByteArray(3200) // ~100ms chunks
            audioRecord?.startRecording()
            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    val byteString = ByteString.of(buffer, 0, read)
                    val base64Data = byteString.base64()
                    val audioMessage = """
                        {
                          "realtimeInput": {
                            "mediaChunks": [{
                              "mimeType": "audio/pcm",
                              "data": "$base64Data"
                            }]
                          }
                        }
                    """.trimIndent()
                    webSocket?.send(audioMessage)
                }
            }
        }.start()
    }

    private fun startAudioPlayback() {
        val sampleRate = 24000
        val channelConfig = AudioFormat.CHANNEL_OUT_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(audioFormat)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferSize)
            .build()

        audioTrack?.play()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioTrack?.stop()
        audioTrack?.release()
        webSocket?.close(1000, "Service destroyed")
    }
}
