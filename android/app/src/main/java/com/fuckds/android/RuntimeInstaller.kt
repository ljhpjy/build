package com.fuckds.android

import android.content.Context
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * 把 assets 里的 runtime.zip / fuckds.zip 解压到应用私有目录。
 *
 * 注意：应用 targetSdk 固定 28，Android 10+ 才不会禁止在私有目录里 exec；
 * 这也是 Termux 至今还在用 28 的原因。
 */
object RuntimeInstaller {
    data class Installed(
        val rootDir: File,
        val appDir: File,
        val runtimeDir: File,
        val nodeExe: File,
        val powExe: File,
        val libDir: File,
        val certFile: File,
        val homeDir: File,
        val tmpDir: File,
        val cacheDir: File,
        val dataDir: File,
        val logFile: File,
        val port: Int,
        val nodeVersion: String,
    )

    data class RuntimeInfo(
        val nodeVersion: String,
        val abi: String,
        val builtAt: String,
        val powType: String = "",
        val powMachine: String = "",
    )

    private const val INSTALL_PREFS = "fuckds-install"
    private const val KEY_VERSION = "versionCode"
    private const val KEY_ABI = "runtimeAbi"
    private const val DEFAULT_PORT = 8787
    private const val MIN_FREE_BYTES = 180L * 1024 * 1024

    @Synchronized
    fun ensureInstalled(context: Context, onProgress: (String) -> Unit = {}): Installed {
        val root = File(context.filesDir, "fuckds")
        val appDir = File(root, "app")
        val runtimeDir = File(root, "runtime")
        val dataDir = File(appDir, "data")
        val homeDir = File(root, "home")
        val tmpDir = File(root, "tmp")
        val cacheDir = File(root, "cache")

        root.mkdirs()
        appDir.mkdirs()
        dataDir.mkdirs()
        homeDir.mkdirs()
        tmpDir.mkdirs()
        cacheDir.mkdirs()

        val prefs = context.getSharedPreferences(INSTALL_PREFS, Context.MODE_PRIVATE)
        val installed =
            prefs.getInt(KEY_VERSION, -1) == BuildConfig.VERSION_CODE &&
                prefs.getString(KEY_ABI, null) == BuildConfig.RUNTIME_ABI &&
                File(runtimeDir, "bin/node").isFile &&
                File(runtimeDir, "bin/ds-pow").isFile &&
                File(appDir, "src/index.mjs").isFile

        if (!installed) {
            try {
                if (root.usableSpace in 1L until MIN_FREE_BYTES) {
                    throw IllegalStateException(
                        "内部存储空间不足：至少需要 180MB（当前可用 ${root.usableSpace / 1024 / 1024}MB）",
                    )
                }
            } catch (e: IllegalStateException) {
                throw e
            } catch (_: Throwable) {
                /* 拿不到剩余空间就不拦了 */
            }

            onProgress("正在解压 Node 运行环境（首次启动需要一点时间）…")
            runtimeDir.deleteRecursively()
            runtimeDir.mkdirs()
            unzipAsset(context, "runtime.zip", runtimeDir)

            onProgress("正在解压服务代码…")
            unzipAsset(context, "fuckds.zip", appDir)

            val envFile = File(appDir, ".env")
            if (!envFile.isFile) {
                context.assets.open("default.env").use { input ->
                    envFile.parentFile?.mkdirs()
                    FileOutputStream(envFile).use { output -> input.copyTo(output) }
                }
            }

            prefs.edit()
                .putInt(KEY_VERSION, BuildConfig.VERSION_CODE)
                .putString(KEY_ABI, BuildConfig.RUNTIME_ABI)
                .apply()
        }

        val nodeExe = File(runtimeDir, "bin/node")
        val powExe = File(runtimeDir, "bin/ds-pow")
        val libDir = File(runtimeDir, "lib")
        val certFile = File(runtimeDir, "etc/tls/cert.pem")

        if (!nodeExe.isFile) {
            throw IllegalStateException("APK 里缺少 ${BuildConfig.RUNTIME_ABI} 的 Node 运行时（assets/runtime.zip 损坏）")
        }
        if (!powExe.isFile) {
            throw IllegalStateException("APK 里缺少 PoW 求解器（assets/runtime.zip 损坏）")
        }
        if (!libDir.isDirectory) {
            throw IllegalStateException("Node 运行时缺少 lib/ 目录")
        }

        nodeExe.setExecutable(true, false)
        powExe.setExecutable(true, false)
        if (!nodeExe.canExecute()) {
            throw IllegalStateException("Node 运行时无法执行（targetSdk 必须 <= 28，或系统限制了应用私有目录 exec）")
        }

        val runtimeInfo = readRuntimeInfo(context)
        return Installed(
            rootDir = root,
            appDir = appDir,
            runtimeDir = runtimeDir,
            nodeExe = nodeExe,
            powExe = powExe,
            libDir = libDir,
            certFile = certFile,
            homeDir = homeDir,
            tmpDir = tmpDir,
            cacheDir = cacheDir,
            dataDir = dataDir,
            logFile = File(dataDir, "server.log"),
            port = currentPort(context),
            nodeVersion = runtimeInfo?.nodeVersion ?: "",
        )
    }

    fun appDir(context: Context): File = File(context.filesDir, "fuckds/app")

    fun logFile(context: Context): File = File(appDir(context), "data/server.log")

    fun currentPort(context: Context): Int =
        readPort(File(context.filesDir, "fuckds/app/.env"))

    /** 不改开其他配置，只把 .env 里的 PORT 改成指定值（没有就追加）。 */
    fun writePort(context: Context, port: Int): Boolean {
        val file = File(context.filesDir, "fuckds/app/.env")
        return try {
            file.parentFile?.mkdirs()
            val lines = if (file.isFile) file.readLines().toMutableList() else mutableListOf()
            val regex = Regex("^\\s*PORT\\s*=")
            var replaced = false
            for (index in lines.indices) {
                if (regex.containsMatchIn(lines[index])) {
                    lines[index] = "PORT=$port"
                    replaced = true
                }
            }
            if (!replaced) lines.add("PORT=$port")
            file.writeText(lines.joinToString("\n").trimEnd() + "\n")
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun readRuntimeInfo(context: Context): RuntimeInfo? {
        return try {
            val file = File(context.filesDir, "fuckds/runtime/runtime-info.json")
            if (!file.isFile) return null
            val json = JSONObject(file.readText())
            val pow = json.optJSONObject("pow")
            RuntimeInfo(
                nodeVersion = json.optString("nodeVersion"),
                abi = json.optString("abi"),
                builtAt = json.optString("builtAt"),
                powType = pow?.optString("type", "") ?: "",
                powMachine = pow?.optString("machine", "") ?: "",
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun readPort(file: File): Int {
        if (!file.isFile) return DEFAULT_PORT
        return try {
            val regex = Regex("^\\s*PORT\\s*=\\s*(\\d{1,5})\\s*$")
            for (line in file.readLines()) {
                if (line.trim().startsWith("#")) continue
                val match = regex.find(line) ?: continue
                val port = match.groupValues[1].toIntOrNull() ?: continue
                if (port in 1..65535) return port
            }
            DEFAULT_PORT
        } catch (_: Throwable) {
            DEFAULT_PORT
        }
    }

    private fun unzipAsset(context: Context, assetName: String, dest: File) {
        dest.mkdirs()
        val destPath = dest.canonicalPath
        context.assets.open(assetName).use { raw ->
            ZipInputStream(BufferedInputStream(raw, 64 * 1024)).use { zip ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (name.isNullOrEmpty()) {
                        zip.closeEntry()
                        continue
                    }
                    val outFile = File(dest, name)
                    val canonical = outFile.canonicalPath
                    if (canonical != destPath && !canonical.startsWith(destPath + File.separator)) {
                        zip.closeEntry()
                        continue
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { output ->
                            var read = zip.read(buffer)
                            while (read > 0) {
                                output.write(buffer, 0, read)
                                read = zip.read(buffer)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
    }
}
