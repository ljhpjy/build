package com.fuckds.android

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 读取服务自己的 /healthz（公开接口，不需要 API Key）。 */
object HealthClient {
    data class Account(
        val index: Int,
        val available: Boolean,
        val cooldownMs: Long,
        val authFailed: Boolean,
        val requests: Int,
    )

    data class Health(
        val pid: Int,
        val uptimeSeconds: Int,
        val engine: String,
        val token: Boolean,
        val busy: Int,
        val queued: Int,
        val accounts: List<Account>,
    ) {
        val accountsUsable: Int
            get() = accounts.count { it.available && !it.authFailed }
    }

    fun fetch(port: Int): Health? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("http://127.0.0.1:$port/healthz").openConnection() as HttpURLConnection).apply {
                connectTimeout = 900
                readTimeout = 1500
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode !in 200..299) return null
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            val accountArray = json.optJSONArray("accounts")
            val accounts = ArrayList<Account>()
            if (accountArray != null) {
                for (index in 0 until accountArray.length()) {
                    val item = accountArray.optJSONObject(index) ?: continue
                    accounts.add(
                        Account(
                            index = item.optInt("index", index + 1),
                            available = item.optBoolean("available", false),
                            cooldownMs = item.optLong("cooldownMs", 0L),
                            authFailed = item.optBoolean("authFailed", false),
                            requests = item.optInt("requests", 0),
                        ),
                    )
                }
            }
            Health(
                pid = json.optInt("pid", 0),
                uptimeSeconds = json.optInt("uptime_s", 0),
                engine = json.optString("engine", "direct"),
                token = json.optBoolean("token", false),
                busy = json.optInt("busy", 0),
                queued = json.optInt("queued", 0),
                accounts = accounts,
            )
        } catch (_: Throwable) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
