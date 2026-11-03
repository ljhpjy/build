package com.fuckds.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 前台服务：解压运行时、启动 Node、盯住进程并在崩溃后自动拉起。
 *
 * 和网页控制台的「重启」配合：控制台的 /admin/api/restart 在 Android 上会直接
 * process.exit(0)，这个服务看到进程退出后会立即重新拉起。
 */
class ServerService : Service() {
    companion object {
        private const val TAG = "fuckds"
        private const val CHANNEL_ID = "fuckds-server"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.fuckds.android.action.START"
        const val ACTION_STOP = "com.fuckds.android.action.STOP"
        const val ACTION_RESTART = "com.fuckds.android.action.RESTART"

        fun start(context: Context) {
            val intent = Intent(context, ServerService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun restart(context: Context) {
            val intent = Intent(context, ServerService::class.java).setAction(ACTION_RESTART)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ServerService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }

    private val worker = Executors.newSingleThreadExecutor()

    @Volatile
    private var nodeProcess: Process? = null

    @Volatile
    private var desiredRunning = false

    @Volatile
    private var restartAttempts = 0

    @Volatile
    private var startedAtElapsed = 0L

    private var wakeLock: PowerManager.WakeLock? = null
    private var logWriter: LogWriter? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                desiredRunning = false
                foreground("正在停止…")
                worker.execute { stopInternal(stopService = true) }
            }
            ACTION_RESTART -> {
                desiredRunning = true
                foreground("正在重启…")
                worker.execute { restartInternal() }
            }
            else -> {
                // null intent = START_STICKY 被系统重新拉起
                desiredRunning = true
                foreground("正在启动…")
                worker.execute { startInternal() }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        desiredRunning = false
        try {
            nodeProcess?.destroy()
        } catch (_: Throwable) {
            /* 进程可能已经退出 */
        }
        nodeProcess = null
        logWriter?.close()
        logWriter = null
        releaseWakeLock()
        worker.shutdownNow()
        super.onDestroy()
    }

    /* ------------------------------------------------------------------ */

    private fun startInternal() {
        if (!desiredRunning) return
        val running = nodeProcess
        if (running?.isAlive == true) {
            val port = RuntimeInstaller.currentPort(this)
            ServerState.update(ServerPhase.RUNNING, "服务运行中", port)
            foreground("运行中 · http://127.0.0.1:$port")
            return
        }

        try {
            ServerState.update(ServerPhase.PREPARING, "正在准备运行环境…")
            val installed = RuntimeInstaller.ensureInstalled(this) { message ->
                ServerState.update(ServerPhase.PREPARING, message)
                foreground(message)
            }
            if (!desiredRunning) return

            cleanupStaleNode()

            ServerState.update(ServerPhase.STARTING, "正在自检 Node 运行时…", installed.port)
            verifyRuntime(installed)
            if (!desiredRunning) return

            ServerState.update(ServerPhase.STARTING, "正在启动服务…", installed.port)
            foreground("正在启动服务…")
            val process = launchNode(installed)
            nodeProcess = process
            writeNodePid(process)
            startedAtElapsed = SystemClock.elapsedRealtime()
            acquireWakeLock()
            startLogPump(process, installed.logFile)
            watchProcess(process)
            val healthy = waitForHealthy(process, installed.port)
            if (!healthy) {
                stopProcess()
                return
            }

            if (nodeProcess !== process) return

            restartAttempts = 0
            ServerState.update(ServerPhase.RUNNING, "服务运行中", installed.port)
            foreground("运行中 · http://127.0.0.1:${installed.port}")
        } catch (t: Throwable) {
            Log.e(TAG, "启动失败", t)
            try {
                nodeProcess?.takeIf { it.isAlive }?.destroy()
            } catch (_: Throwable) {
                /* ignore */
            }
            nodeProcess = null
            logWriter?.close()
            logWriter = null
            releaseWakeLock()
            val message = t.message ?: t.javaClass.simpleName
            appendLog("启动失败：$message")
            ServerState.update(ServerPhase.ERROR, message, RuntimeInstaller.currentPort(this))
            foreground("启动失败：$message")
        }
    }

    private fun restartInternal() {
        desiredRunning = true
        restartAttempts = 0
        stopProcess()
        startInternal()
    }

    private fun stopInternal(stopService: Boolean) {
        desiredRunning = false
        stopProcess()
        releaseWakeLock()
        ServerState.update(ServerPhase.STOPPED, "服务已停止")
        if (stopService) {
            try {
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
            } catch (_: Throwable) {
                /* ignore */
            }
            stopSelf()
        }
    }

    private fun stopProcess() {
        val process = synchronized(this) {
            val current = nodeProcess
            nodeProcess = null
            current
        } ?: return
        try {
            process.destroy()
            if (!process.waitFor(6, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(3, TimeUnit.SECONDS)
            }
        } catch (_: Throwable) {
            /* process 已经没了 */
        }
        deleteNodePid()
        logWriter?.close()
        logWriter = null
    }

    private fun launchNode(installed: RuntimeInstaller.Installed): Process {
        val script = File(installed.appDir, "src/index.mjs")
        if (!script.isFile) throw IllegalStateException("缺少服务入口：${script.absolutePath}")

        val processBuilder = ProcessBuilder(installed.nodeExe.absolutePath, script.absolutePath)
        processBuilder.directory(installed.appDir)
        processBuilder.redirectErrorStream(true)
        processBuilder.environment().putAll(buildEnvironment(installed))

        appendLog("--- 启动 Node（${installed.nodeVersion} / ${BuildConfig.RUNTIME_ABI}）---")
        return processBuilder.start()
    }

    private fun buildEnvironment(installed: RuntimeInstaller.Installed): MutableMap<String, String> {
        val env = HashMap<String, String>()
        env["LD_LIBRARY_PATH"] = installed.libDir.absolutePath
        env["HOME"] = installed.homeDir.absolutePath
        env["TMPDIR"] = installed.tmpDir.absolutePath
        env["XDG_CACHE_HOME"] = installed.cacheDir.absolutePath
        env["FUCKDS_ANDROID"] = "1"
        env["POW_BIN"] = installed.powExe.absolutePath
        env["SSL_CERT_FILE"] = installed.certFile.absolutePath
        env["NODE_EXTRA_CA_CERTS"] = installed.certFile.absolutePath
        env["NO_COLOR"] = "1"
        env["PATH"] = "${installed.runtimeDir.absolutePath}/bin:/system/bin:/system/xbin"
        env["LANG"] = "C.UTF-8"
        env["LC_ALL"] = "C.UTF-8"
        env["TZ"] = java.util.TimeZone.getDefault().id
        // 不要设置 HOST / PORT / ENGINE：它们在 app/.env 里，控制台设置页可改
        return env
    }

    private fun verifyRuntime(installed: RuntimeInstaller.Installed) {
        val processBuilder = ProcessBuilder(installed.nodeExe.absolutePath, "--version")
        processBuilder.directory(installed.appDir)
        processBuilder.redirectErrorStream(true)
        processBuilder.environment().putAll(buildEnvironment(installed))
        val process = processBuilder.start()
        val finished = process.waitFor(20, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw IllegalStateException("Node 运行时自检超时")
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        if (process.exitValue() != 0) {
            throw IllegalStateException("Node 运行时无法执行（退出码 ${process.exitValue()}）：$output")
        }
        appendLog("Node 运行时自检通过：$output")
    }

    private fun waitForHealthy(process: Process, port: Int): Boolean {
        val deadline = SystemClock.elapsedRealtime() + 45_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!desiredRunning) return false
            if (!process.isAlive) {
                val code = try {
                    process.exitValue()
                } catch (_: Throwable) {
                    -1
                }
                throw IllegalStateException("Node 进程启动后立即退出（退出码 $code），请查看日志")
            }
            if (HealthClient.fetch(port) != null) return true
            Thread.sleep(350)
        }
        throw IllegalStateException("服务在 45 秒内没有响应 http://127.0.0.1:$port/healthz，请查看日志")
    }

    private fun watchProcess(process: Process) {
        Thread({
            val code = try {
                process.waitFor()
            } catch (_: Throwable) {
                -1
            }
            handleExit(process, code)
        }, "fuckds-watch").start()
    }

    private fun handleExit(process: Process, exitCode: Int) {
        synchronized(this) {
            if (nodeProcess !== process) return
            nodeProcess = null
        }

        deleteNodePid()
        logWriter?.close()
        logWriter = null
        releaseWakeLock()
        appendLog("--- Node 进程退出（code $exitCode）---")

        if (!desiredRunning) {
            ServerState.update(ServerPhase.STOPPED, "服务已停止")
            return
        }

        val upMs = SystemClock.elapsedRealtime() - startedAtElapsed
        if (upMs > 60_000) restartAttempts = 0
        restartAttempts += 1
        if (restartAttempts > 6) {
            desiredRunning = false
            ServerState.update(ServerPhase.ERROR, "Node 反复退出（退出码 $exitCode），请查看日志")
            foreground("服务异常退出，请查看日志")
            return
        }

        val delayMs = minOf(1000L * (1 shl (restartAttempts - 1)), 15_000L)
        ServerState.update(ServerPhase.STARTING, "进程退出（code $exitCode），${delayMs / 1000}s 后自动重启…")
        foreground("进程退出，准备自动重启…")

        Thread({
            try {
                Thread.sleep(delayMs)
                if (desiredRunning) worker.execute { startInternal() }
            } catch (_: Throwable) {
                /* 服务已经销毁 */
            }
        }, "fuckds-restart").start()
    }

    private fun startLogPump(process: Process, logFile: File) {
        val writer = LogWriter(logFile)
        logWriter = writer
        Thread({
            try {
                process.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        writer.write(line)
                        Log.i(TAG, line)
                    }
                }
            } catch (_: Throwable) {
                /* 进程结束时流会断开 */
            }
        }, "fuckds-log").start()
    }

    private fun writeNodePid(process: Process) {
        try {
            File(filesDir, "fuckds/node.pid").writeText("${process.pid()}\n")
        } catch (_: Throwable) {
            /* ignore */
        }
    }

    private fun deleteNodePid() {
        try {
            File(filesDir, "fuckds/node.pid").delete()
        } catch (_: Throwable) {
            /* ignore */
        }
    }

    /**
     * 应用进程被系统杀掉时，Node 子进程可能变成孤儿并继续占着端口。
     * 启动前按 pid 文件找到它（先确认 cmdline 是我们自己的 node），结束掉再启动。
     */
    private fun cleanupStaleNode() {
        val pidFile = File(filesDir, "fuckds/node.pid")
        if (!pidFile.isFile) return
        val pid = pidFile.readText().trim().toIntOrNull()
        pidFile.delete()
        if (pid == null || pid <= 0 || pid == android.os.Process.myPid()) return

        val cmdline = try {
            File("/proc/$pid/cmdline").readBytes().toString(Charsets.UTF_8).replace('\u0000', ' ')
        } catch (_: Throwable) {
            return
        }
        if (!cmdline.contains("/fuckds/runtime/bin/node")) return

        appendLog("发现上次残留的 Node 进程（pid $pid），先结束它")
        try {
            ProcessBuilder("/system/bin/sh", "-c", "kill -TERM $pid")
                .start()
                .waitFor(3, TimeUnit.SECONDS)
            Thread.sleep(700)
            if (File("/proc/$pid").exists()) {
                ProcessBuilder("/system/bin/sh", "-c", "kill -KILL $pid")
                    .start()
                    .waitFor(3, TimeUnit.SECONDS)
                Thread.sleep(300)
            }
        } catch (_: Throwable) {
            /* ignore */
        }
    }

    private fun appendLog(line: String) {
        try {
            val file = RuntimeInstaller.logFile(this)
            file.parentFile?.mkdirs()
            file.appendText(line + "\n")
        } catch (_: Throwable) {
            /* ignore */
        }
    }

    /* ------------------------------------------------------------------ */

    private fun foreground(text: String) {
        try {
            startForeground(NOTIFICATION_ID, buildNotification(text))
        } catch (t: Throwable) {
            Log.w(TAG, "更新前台通知失败", t)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val consoleIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, ConsoleActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, ServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_server)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.open_console), consoleIntent)
            .addAction(0, getString(R.string.stop_service), stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        if (!Prefs.keepAwake(this)) return
        releaseWakeLock()
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fuckds:server").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "申请 WakeLock 失败", t)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Throwable) {
            /* ignore */
        }
        wakeLock = null
    }

    /** 写入服务器日志（控制台的「日志」页读同一个文件），超过 8MB 自动轮转一次。 */
    private class LogWriter(private val file: File) {
        private var stream: FileOutputStream? = null
        private var bytes = 0L
        private var closed = false

        @Synchronized
        fun write(line: String) {
            if (closed) return
            try {
                if (stream == null) open()
                val data = (line + "\n").toByteArray(Charsets.UTF_8)
                stream?.write(data)
                stream?.flush()
                bytes += data.size
                if (bytes > 8L * 1024 * 1024) rotate()
            } catch (_: Throwable) {
                /* 日志写失败不影响服务 */
            }
        }

        private fun open() {
            file.parentFile?.mkdirs()
            stream = FileOutputStream(file, true)
            bytes = file.length()
        }

        private fun rotate() {
            closeStream()
            val backup = File(file.parentFile, file.name + ".1")
            backup.delete()
            file.renameTo(backup)
            open()
        }

        private fun closeStream() {
            try {
                stream?.close()
            } catch (_: Throwable) {
                /* ignore */
            }
            stream = null
        }

        @Synchronized
        fun close() {
            closed = true
            closeStream()
        }
    }
}
