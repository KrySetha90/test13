package com.hfr.screenrec

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.widget.Toast
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecorderService : Service() {

    companion object {
        const val ACTION_START = "com.hfr.screenrec.START"
        const val ACTION_STOP = "com.hfr.screenrec.STOP"
        private const val CHANNEL = "rec"
        private const val NOTIF_ID = 1

        @Volatile
        var running = false
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var cleaning = false
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (running) stopRecording()
        super.onDestroy()
    }

    private fun startRecording(intent: Intent) {
        if (running) return
        cleaning = false

        val prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val res = prefs.getInt("res", 1080)
        val fps = prefs.getInt("fps", 60)
        val orient = prefs.getInt("orient", 0)
        val codec = prefs.getString("codec", "avc") ?: "avc"
        val wantMic = prefs.getBoolean("mic", false) &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        // Must go foreground (with the right type) BEFORE getMediaProjection on Android 14+
        createChannel()
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (wantMic) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        startForeground(NOTIF_ID, buildNotification("Starting..."), type)

        var info = ""
        try {
            val code = intent.getIntExtra("code", Activity.RESULT_CANCELED)
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra("data", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra("data")
            }
            if (data == null) throw IllegalStateException("No screen capture permission")

            val mpm = getSystemService(MediaProjectionManager::class.java)
            val proj = mpm.getMediaProjection(code, data)
            projection = proj
            // Android 14+ requires a callback to be registered before creating the display
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopRecording()
                }
            }, main)

            val real = Util.realSize(this)
            val (w, h) = Util.targetSize(real[0], real[1], res, orient)
            val dpi = real[2]
            val hevc = codec == "hevc"
            val mime = Util.mime(codec)
            val limits = Util.limits(mime, w, h)
            info = "Encoder max at ${w}x$h: ${limits.maxFps ?: "unknown"} fps. Try a lower fps or the other codec."
            val bitrate = Util.bitrate(w, h, fps, hevc, limits.maxBitrate)

            val rec = if (Build.VERSION.SDK_INT >= 31) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            recorder = rec

            if (wantMic) rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setVideoEncoder(if (hevc) MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264)
            if (wantMic) {
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                rec.setAudioEncodingBitRate(128_000)
                rec.setAudioSamplingRate(44_100)
            }
            rec.setVideoSize(w, h)
            rec.setVideoFrameRate(fps)
            rec.setVideoEncodingBitRate(bitrate)

            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "HFR_$stamp.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/HFRRec")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val u = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Could not create the video file")
            uri = u
            val p = contentResolver.openFileDescriptor(u, "w")
                ?: throw IOException("Could not open the video file")
            pfd = p
            rec.setOutputFile(p.fileDescriptor)
            rec.prepare()

            display = proj.createVirtualDisplay(
                "HFRRec", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                rec.surface, null, null
            )
            rec.start()
            running = true
            notify("Recording ${w}x$h @ $fps fps")
        } catch (e: Exception) {
            Toast.makeText(this, "Could not start: ${e.message}\n$info", Toast.LENGTH_LONG).show()
            stopRecording()
        }
    }

    private fun stopRecording() {
        if (cleaning) return
        cleaning = true

        var ok = running
        try {
            if (running) recorder?.stop()
        } catch (e: RuntimeException) {
            ok = false // stopped too quickly, no valid data
        }
        try {
            recorder?.reset()
            recorder?.release()
        } catch (_: Exception) {
        }
        recorder = null

        try {
            display?.release()
        } catch (_: Exception) {
        }
        display = null

        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null

        try {
            pfd?.close()
        } catch (_: Exception) {
        }
        pfd = null

        uri?.let { u ->
            try {
                if (ok) {
                    val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                    contentResolver.update(u, done, null, null)
                    Toast.makeText(this, "Saved to Movies/HFRRec", Toast.LENGTH_LONG).show()
                } else {
                    contentResolver.delete(u, null, null)
                }
            } catch (_: Exception) {
            }
        }
        uri = null

        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Screen recording", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notify(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openPi = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "Stop",
            stopPi
        ).build()

        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("HFR ScreenRec")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(stopAction)
            .build()
    }
}
