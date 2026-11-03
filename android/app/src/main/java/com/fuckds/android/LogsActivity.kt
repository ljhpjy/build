package com.fuckds.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.fuckds.android.databinding.ActivityLogsBinding
import java.io.File
import java.io.RandomAccessFile

/** 直接读 data/server.log 的尾巴；服务起不来、控制台打不开时靠它排障。 */
class LogsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLogsBinding
    private val handler = Handler(Looper.getMainLooper())
    private var refreshing = false

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (!refreshing) return
            refresh(forceBottom = false)
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnRefresh.setOnClickListener { refresh(forceBottom = true) }
        binding.btnCopy.setOnClickListener { copyLog() }
        binding.btnShare.setOnClickListener { shareLog() }

        refresh(forceBottom = true)
    }

    override fun onResume() {
        super.onResume()
        refreshing = true
        handler.removeCallbacks(refreshRunnable)
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        refreshing = false
        handler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshRunnable)
        super.onDestroy()
    }

    /* ------------------------------------------------------------------ */

    private fun refresh(forceBottom: Boolean) {
        val wasAtBottom = !binding.scroll.canScrollVertically(1)
        val text = readTail(RuntimeInstaller.logFile(this), 500)
        binding.logText.text = text.ifEmpty { getString(R.string.logs_empty) }
        if (forceBottom || wasAtBottom) {
            binding.scroll.post { binding.scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun readTail(file: File, maxLines: Int): String {
        if (!file.isFile) return ""
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val length = raf.length()
                val maxBytes = 512L * 1024
                val start = (length - maxBytes).coerceAtLeast(0)
                val size = (length - start).toInt()
                if (size <= 0) return ""
                val buffer = ByteArray(size)
                raf.seek(start)
                raf.readFully(buffer)
                String(buffer, Charsets.UTF_8)
                    .split('\n')
                    .takeLast(maxLines)
                    .joinToString("\n")
            }
        } catch (_: Throwable) {
            ""
        }
    }

    private fun copyLog() {
        val text = binding.logText.text.toString()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("fuckds-log", text))
        Toast.makeText(this, getString(R.string.log_copied), Toast.LENGTH_SHORT).show()
    }

    private fun shareLog() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, binding.logText.text.toString())
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share)))
    }
}
