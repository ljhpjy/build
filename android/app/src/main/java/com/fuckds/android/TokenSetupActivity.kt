package com.fuckds.android

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.fuckds.android.databinding.ActivityTokenSetupBinding
import java.util.concurrent.Executors

/**
 * 原生首启向导：环境准备完成、服务健康但还没有 token 时自动打开。
 *
 * 保存 token 到 data/auth.json（与 Node 侧 saveTokens 同格式）→ 重启前台服务 →
 * 轮询 /healthz 确认新的 Node 进程已经加载登录态。
 */
class TokenSetupActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTokenSetupBinding
    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private var saving = false
    private var configured = false
    private var pollAttempts = 0
    private var normalStatusColor = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTokenSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        TokenWizardState.shownThisProcess = true
        normalStatusColor = binding.statusText.currentTextColor

        binding.btnPaste.setOnClickListener { pasteFromClipboard() }
        binding.btnSave.setOnClickListener { saveAndRestart() }
        binding.btnSkip.setOnClickListener { finish() }

        updateExistingHint()
        binding.inputEdit.postDelayed({ focusInput() }, 350)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        io.shutdownNow()
        super.onDestroy()
    }

    /* ------------------------------------------------------------------ */

    private fun focusInput() {
        if (isFinishing || isDestroyed) return
        binding.inputEdit.requestFocus()
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.inputEdit, InputMethodManager.SHOW_IMPLICIT)
        } catch (_: Throwable) {
            /* 键盘弹不出来也不影响 */
        }
    }

    private fun updateExistingHint() {
        val count = TokenUtils.existingTokens(this).size
        if (count > 0) {
            binding.existingHint.text = getString(R.string.token_existing_hint, count)
            binding.existingHint.visibility = View.VISIBLE
        } else {
            binding.existingHint.visibility = View.GONE
        }
    }

    private fun pasteFromClipboard() {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = manager.primaryClip
        if (clip == null || clip.itemCount == 0) {
            toast(getString(R.string.token_clipboard_empty))
            return
        }
        val text = clip.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            toast(getString(R.string.token_clipboard_empty))
            return
        }
        binding.inputEdit.setText(text)
        binding.inputEdit.setSelection(text.length)
        binding.inputLayout.error = null
    }

    private fun saveAndRestart() {
        if (saving) return

        val raw = binding.inputEdit.text?.toString().orEmpty()
        val token = try {
            TokenUtils.normalize(raw)
        } catch (e: IllegalArgumentException) {
            binding.inputLayout.error = e.message
            return
        }
        binding.inputLayout.error = null

        setBusy(true, getString(R.string.token_saving))
        io.execute {
            try {
                TokenUtils.saveNormalized(this, token)
                handler.post {
                    if (isFinishing || isDestroyed) return@post
                    setBusy(true, getString(R.string.token_restarting))
                    try {
                        ServerService.restart(this)
                        pollAttempts = 0
                        pollService()
                    } catch (t: Throwable) {
                        fail(getString(R.string.token_restart_failed, t.message ?: t.javaClass.simpleName))
                    }
                }
            } catch (t: Throwable) {
                handler.post {
                    if (!isFinishing && !isDestroyed) {
                        fail(getString(R.string.token_save_failed, t.message ?: t.javaClass.simpleName))
                    }
                }
            }
        }
    }

    private fun pollService() {
        if (isFinishing || isDestroyed) return
        val port = RuntimeInstaller.currentPort(this)
        try {
            io.execute {
                val health = HealthClient.fetch(port)
                handler.post {
                    if (isFinishing || isDestroyed) return@post
                    if (health != null && health.token) {
                        onConfigured()
                        return@post
                    }
                    pollAttempts += 1
                    if (pollAttempts > 75) {
                        fail(getString(R.string.token_timeout))
                    } else {
                        if (pollAttempts == 12) {
                            setBusy(true, getString(R.string.token_waiting))
                        }
                        handler.postDelayed({ pollService() }, 800)
                    }
                }
            }
        } catch (_: Throwable) {
            /* executor 已关闭 */
        }
    }

    private fun onConfigured() {
        if (configured) return
        configured = true
        setBusy(false, getString(R.string.token_success))
        binding.inputEdit.isEnabled = false
        binding.btnPaste.isEnabled = false
        binding.btnSkip.visibility = View.GONE
        binding.btnSave.text = getString(R.string.token_done)
        binding.btnSave.isEnabled = true
        binding.btnSave.setOnClickListener { finish() }
        toast(getString(R.string.token_success))
        handler.postDelayed({
            if (!isFinishing && !isDestroyed) finish()
        }, 1000)
    }

    private fun fail(message: String) {
        if (isFinishing || isDestroyed) return
        setBusy(false, message, error = true)
        toast(message)
    }

    private fun setBusy(busy: Boolean, message: String, error: Boolean = false) {
        saving = busy
        binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.inputEdit.isEnabled = !busy
        binding.btnPaste.isEnabled = !busy
        binding.btnSave.isEnabled = !busy
        binding.btnSkip.isEnabled = !busy
        setStatus(message, error)
    }

    private fun setStatus(message: String, error: Boolean) {
        binding.statusText.text = message
        binding.statusText.setTextColor(
            if (error) ContextCompat.getColor(this, R.color.status_error) else normalStatusColor,
        )
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
