package com.fuckds.android

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.fuckds.android.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastHealth: HealthClient.Health? = null

    private var polling = false
    private var currentPort = 8787
    private var currentSnapshot = ServerState.snapshot
    private var displayPhase = ServerPhase.STOPPED
    private var pendingAutoStart = false
    private var autoWizardScheduled = false

    private val stateListener: (ServerSnapshot) -> Unit = { snapshot ->
        currentSnapshot = snapshot
        handler.post {
            if (!isFinishing && !isDestroyed) render()
        }
    }

    private val pollRunnable: Runnable = object : Runnable {
        override fun run() {
            if (!polling) return
            val port = RuntimeInstaller.currentPort(this@MainActivity)
            try {
                io.execute {
                    val health = HealthClient.fetch(port)
                    handler.post {
                        if (!polling || isFinishing || isDestroyed) return@post
                        lastHealth = health
                        currentPort = port
                        render()
                        maybeShowTokenWizard(health)
                        handler.postDelayed(pollRunnable, 1000)
                    }
                }
            } catch (_: Throwable) {
                /* Activity 正在销毁 */
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        currentPort = RuntimeInstaller.currentPort(this)
        setupListeners()
        requestNotificationPermission()
        render()
        pendingAutoStart = savedInstanceState == null
    }

    override fun onResume() {
        super.onResume()
        currentPort = RuntimeInstaller.currentPort(this)
        currentSnapshot = ServerState.snapshot
        ServerState.addListener(stateListener)
        polling = true
        handler.removeCallbacks(pollRunnable)
        handler.post(pollRunnable)

        // 放在 onResume 里启动：这时 Activity 一定可见，Android 12+ 的前台服务启动限制不会拦。
        if (pendingAutoStart) {
            pendingAutoStart = false
            runCatching { ServerService.start(this) }
                .onFailure { toast(it.message ?: "启动服务失败") }
        }
    }

    override fun onPause() {
        polling = false
        handler.removeCallbacks(pollRunnable)
        ServerState.removeListener(stateListener)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        io.shutdownNow()
        super.onDestroy()
    }

    /* ------------------------------------------------------------------ */

    private fun setupListeners() {
        binding.btnToggle.setOnClickListener {
            when (displayPhase) {
                ServerPhase.RUNNING, ServerPhase.STARTING, ServerPhase.PREPARING ->
                    runCatching { ServerService.stop(this) }
                        .onFailure { toast(it.message ?: "停止服务失败") }
                else ->
                    runCatching { ServerService.start(this) }
                        .onFailure { toast(it.message ?: "启动服务失败") }
            }
        }
        binding.btnRestart.setOnClickListener {
            runCatching { ServerService.restart(this) }
                .onFailure { toast(it.message ?: "重启失败") }
        }
        binding.btnConsole.setOnClickListener { openConsole() }
        binding.btnToken.setOnClickListener { openTokenWizard() }
        binding.btnLogs.setOnClickListener { openLogs() }
        binding.btnCopy.setOnClickListener { copyEndpoint() }
    }

    private fun render() {
        val health = lastHealth
        val snapshot = currentSnapshot
        val phase = if (health != null) ServerPhase.RUNNING else snapshot.phase
        displayPhase = phase

        val colorRes = when (phase) {
            ServerPhase.RUNNING -> R.color.status_running
            ServerPhase.PREPARING, ServerPhase.STARTING -> R.color.status_busy
            ServerPhase.ERROR -> R.color.status_error
            else -> R.color.status_stopped
        }
        binding.statusDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
        binding.statusTitle.setText(
            when (phase) {
                ServerPhase.RUNNING -> R.string.status_running
                ServerPhase.PREPARING -> R.string.status_preparing
                ServerPhase.STARTING -> R.string.status_starting
                ServerPhase.ERROR -> R.string.status_error
                else -> R.string.status_stopped
            },
        )
        binding.statusPhase.text = "v${BuildConfig.VERSION_NAME}"
        binding.statusDetail.text = snapshot.message
        binding.endpoint.text = "http://127.0.0.1:$currentPort/v1"
        binding.uptime.text = health?.let { "已运行 ${formatUptime(it.uptimeSeconds)}" } ?: ""

        binding.infoEngine.text = getString(
            R.string.info_engine,
            health?.engine ?: "—",
            health?.busy ?: 0,
            health?.queued ?: 0,
        )
        binding.infoToken.setText(if (health?.token == true) R.string.info_token_ok else R.string.info_token_missing)

        val accounts = health?.accounts ?: emptyList()
        binding.infoAccounts.text = if (accounts.isEmpty()) {
            getString(R.string.info_accounts_empty)
        } else {
            getString(R.string.info_accounts, accounts.size, health?.accountsUsable ?: 0)
        }

        val runtimeInfo = RuntimeInstaller.readRuntimeInfo(this)
        binding.infoRuntime.text = if (runtimeInfo != null) {
            getString(
                R.string.info_runtime,
                runtimeInfo.nodeVersion.ifBlank { "?" },
                BuildConfig.RUNTIME_ABI,
                BuildConfig.VERSION_NAME,
            )
        } else {
            getString(R.string.info_runtime_missing)
        }

        binding.tokenCard.visibility = if (health != null && !health.token) View.VISIBLE else View.GONE
        if (phase == ServerPhase.ERROR && health == null) {
            binding.errorText.visibility = View.VISIBLE
            binding.errorText.text = snapshot.message
        } else {
            binding.errorText.visibility = View.GONE
        }

        val busy = phase == ServerPhase.PREPARING || phase == ServerPhase.STARTING
        binding.btnToggle.isEnabled = true
        binding.btnToggle.text = getString(
            if (phase == ServerPhase.RUNNING || busy) R.string.stop_service else R.string.start_service,
        )
        binding.btnRestart.isEnabled = phase == ServerPhase.RUNNING
        binding.btnConsole.isEnabled = phase == ServerPhase.RUNNING || health != null
    }

    /* ------------------------------------------------------------------ */

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_token -> {
                openTokenWizard()
                true
            }
            R.id.action_logs -> {
                openLogs()
                true
            }
            R.id.action_settings -> {
                showSettings()
                true
            }
            R.id.action_help -> {
                showHelp()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun openConsole() {
        if (displayPhase != ServerPhase.RUNNING && lastHealth == null) {
            toast("服务还在启动，稍等一下再打开控制台")
            return
        }
        startActivity(Intent(this, ConsoleActivity::class.java))
    }

    private fun openTokenWizard() {
        TokenWizardState.shownThisProcess = true
        startActivity(Intent(this, TokenSetupActivity::class.java))
    }

    /** 环境准备完成（/healthz 可达）且还没 token 时，自动引导一次。 */
    private fun maybeShowTokenWizard(health: HealthClient.Health?) {
        if (health == null || health.token) return
        if (TokenWizardState.shownThisProcess || autoWizardScheduled) return
        if (!polling || isFinishing || isDestroyed) return
        autoWizardScheduled = true
        handler.postDelayed({
            autoWizardScheduled = false
            if (!polling || isFinishing || isDestroyed) return@postDelayed
            if (lastHealth?.token != false) return@postDelayed
            if (TokenWizardState.shownThisProcess) return@postDelayed
            TokenWizardState.shownThisProcess = true
            startActivity(Intent(this, TokenSetupActivity::class.java))
        }, 450)
    }

    private fun openLogs() {
        startActivity(Intent(this, LogsActivity::class.java))
    }

    private fun copyEndpoint() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("fuckds", "http://127.0.0.1:$currentPort"))
        toast(getString(R.string.copied))
    }

    private fun showSettings() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val autoStart = CheckBox(this).apply {
            text = getString(R.string.setting_auto_start)
            isChecked = Prefs.autoStart(this@MainActivity)
        }
        val keepAwake = CheckBox(this).apply {
            text = getString(R.string.setting_keep_awake)
            isChecked = Prefs.keepAwake(this@MainActivity)
        }
        val portInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(currentPort.toString())
            hint = getString(R.string.setting_port)
        }
        val batteryButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.setting_battery)
            setOnClickListener { requestIgnoreBatteryOptimizations() }
        }
        container.addView(autoStart)
        container.addView(keepAwake)
        container.addView(portInput)
        container.addView(batteryButton)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                Prefs.setAutoStart(this, autoStart.isChecked)
                val keepAwakeChanged = Prefs.keepAwake(this) != keepAwake.isChecked
                Prefs.setKeepAwake(this, keepAwake.isChecked)

                val newPort = portInput.text.toString().trim().toIntOrNull()
                if (newPort == null || newPort !in 1..65535) {
                    toast(getString(R.string.port_invalid))
                    return@setPositiveButton
                }
                var portChanged = newPort != currentPort
                if (portChanged) {
                    if (RuntimeInstaller.writePort(this, newPort)) {
                        currentPort = newPort
                    } else {
                        portChanged = false
                        toast("写入 .env 失败")
                    }
                }
                val needRestart =
                    (keepAwakeChanged && displayPhase == ServerPhase.RUNNING) ||
                        (portChanged && (displayPhase == ServerPhase.RUNNING || displayPhase == ServerPhase.ERROR))
                if (needRestart) {
                    runCatching { ServerService.restart(this) }
                        .onFailure { toast(it.message ?: "重启失败") }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.help)
            .setMessage(R.string.help_text)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun requestIgnoreBatteryOptimizations() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            toast(getString(R.string.battery_already))
            return
        }
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                ),
            )
        } catch (_: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Throwable) {
                toast(getString(R.string.battery_unavailable))
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
    }

    private fun formatUptime(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${secs}s"
            else -> "${secs}s"
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
