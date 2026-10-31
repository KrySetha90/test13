package com.hfr.screenrec

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        private const val REQ_PERM = 1
        private const val REQ_CAPTURE = 2
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var info: TextView
    private lateinit var btn: Button
    private val handler = Handler(Looper.getMainLooper())

    private val resOptions = listOf(720, 1080)
    private val fpsOptions = listOf(30, 60, 90, 120, 144)
    private val orientOptions = listOf("Match phone now", "Portrait", "Landscape (games)")
    private val codecOptions = listOf("avc" to "H.264 (most compatible)", "hevc" to "H.265 / HEVC (smaller files)")

    private val tick = object : Runnable {
        override fun run() {
            refreshButton()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(20), dp(20), dp(32))

        col.addView(tv("HFR ScreenRec", 26f))
        info = tv("", 13f)
        col.addView(info)

        col.addView(label("Resolution"))
        col.addView(
            spinner(resOptions.map { "${it}p" }, resOptions.indexOf(prefs.getInt("res", 1080)).coerceAtLeast(0)) {
                prefs.edit().putInt("res", resOptions[it]).apply()
                refreshInfo()
            }
        )

        col.addView(label("Frame rate"))
        col.addView(
            spinner(fpsOptions.map { "$it fps" }, fpsOptions.indexOf(prefs.getInt("fps", 60)).coerceAtLeast(0)) {
                prefs.edit().putInt("fps", fpsOptions[it]).apply()
            }
        )

        col.addView(label("Orientation"))
        col.addView(
            spinner(orientOptions, prefs.getInt("orient", 0).coerceIn(0, 2)) {
                prefs.edit().putInt("orient", it).apply()
                refreshInfo()
            }
        )

        col.addView(label("Video codec"))
        col.addView(
            spinner(
                codecOptions.map { it.second },
                codecOptions.indexOfFirst { it.first == prefs.getString("codec", "avc") }.coerceAtLeast(0)
            ) {
                prefs.edit().putString("codec", codecOptions[it].first).apply()
                refreshInfo()
            }
        )

        val mic = Switch(this)
        mic.text = "Record microphone"
        mic.isChecked = prefs.getBoolean("mic", false)
        mic.setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("mic", checked).apply() }
        mic.setPadding(0, dp(16), 0, dp(16))
        col.addView(mic)

        btn = Button(this)
        btn.textSize = 18f
        btn.setOnClickListener { onToggle() }
        col.addView(btn)

        val scroll = ScrollView(this)
        scroll.addView(col)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refreshInfo()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun onToggle() {
        if (RecorderService.running) {
            startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
            return
        }
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (prefs.getBoolean("mic", false) &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            need.add(Manifest.permission.RECORD_AUDIO)
        }
        if (need.isNotEmpty()) {
            requestPermissions(need.toTypedArray(), REQ_PERM)
        } else {
            requestCapture()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERM) requestCapture() // the service skips the mic if it was denied
    }

    private fun requestCapture() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode == RESULT_OK && data != null) {
            val i = Intent(this, RecorderService::class.java)
                .setAction(RecorderService.ACTION_START)
                .putExtra("code", resultCode)
                .putExtra("data", data)
            startForegroundService(i)
            // Let the service go foreground first, then get out of the way so you can record other apps
            handler.postDelayed({ moveTaskToBack(true) }, 800)
        } else {
            Toast.makeText(this, "Screen capture permission was not granted", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshButton() {
        btn.text = if (RecorderService.running) "STOP RECORDING" else "START RECORDING"
    }

    private fun refreshInfo() {
        refreshButton()
        val d = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val hzNow = Math.round(d.refreshRate)
        val hzMax = Math.round(d.supportedModes.maxOf { it.refreshRate })

        val real = Util.realSize(this)
        val (w, h) = Util.targetSize(real[0], real[1], prefs.getInt("res", 1080), prefs.getInt("orient", 0))
        val mime = Util.mime(prefs.getString("codec", "avc") ?: "avc")
        val lim = Util.limits(mime, w, h)

        info.text = "Screen: $hzNow Hz now, up to $hzMax Hz\n" +
            "Output size: ${w}x$h\n" +
            "Encoder limit at this size: ${lim.maxFps ?: "unknown"} fps\n\n" +
            "A video can't hold more real frames than your screen draws. For 120/144 fps, set your " +
            "phone's refresh rate to its highest (Settings > Display) and record an app or game that runs that fast."
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun tv(s: String, size: Float): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = size
        t.setPadding(0, dp(6), 0, dp(6))
        return t
    }

    private fun label(s: String): TextView {
        val t = tv(s, 14f)
        t.setPadding(0, dp(14), 0, 0)
        return t
    }

    private fun spinner(items: List<String>, selected: Int, onPick: (Int) -> Unit): Spinner {
        val s = Spinner(this)
        s.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        s.setSelection(selected)
        s.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                onPick(pos)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        return s
    }
}
