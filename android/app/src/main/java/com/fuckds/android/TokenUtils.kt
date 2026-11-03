package com.fuckds.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 原生向导用的 token 处理：
 * - 和 src/auth.mjs 的 normalizeUserToken 保持一致（裸 token / Bearer / localStorage JSON / 引号）；
 * - 写出的 data/auth.json 与 Node 侧 saveTokens 完全同格式，重启后 Node 直接能读。
 */
object TokenUtils {
    fun authFile(context: Context): File = File(context.filesDir, "fuckds/app/data/auth.json")

    fun normalize(raw: String): String {
        var value = raw.trim()
        if (value.isEmpty()) throw IllegalArgumentException("token 不能为空")

        val bearer = Regex("^bearer\\s+", RegexOption.IGNORE_CASE).find(value)
        if (bearer != null) value = value.substring(bearer.value.length).trim()

        if (value.startsWith("{")) {
            try {
                val parsed = JSONObject(value)
                val inner = parsed.optString("value", "")
                if (inner.isNotBlank()) value = inner.trim()
            } catch (_: Throwable) {
                /* 不是合法 JSON 就按裸 token 处理 */
            }
        }

        if (
            value.length >= 2 &&
            ((value.startsWith("\"") && value.endsWith("\"")) ||
                (value.startsWith("'") && value.endsWith("'")))
        ) {
            value = value.substring(1, value.length - 1).trim()
        }

        if (value.length < 16) {
            throw IllegalArgumentException("token 太短了（${value.length} 字符），请确认复制完整")
        }
        if (value.any { it.isWhitespace() }) {
            throw IllegalArgumentException("token 里不应该有空格或换行")
        }
        return value
    }

    fun existingTokens(context: Context): List<String> {
        val file = authFile(context)
        if (!file.isFile) return emptyList()
        return try {
            val json = JSONObject(file.readText())
            val result = LinkedHashSet<String>()
            val array = json.optJSONArray("tokens")
            if (array != null) {
                for (index in 0 until array.length()) {
                    val token = array.optString(index, "").trim()
                    if (token.isNotEmpty()) result.add(token)
                }
            }
            val single = json.optString("token", "").trim()
            if (single.isNotEmpty()) result.add(single)
            result.toList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** token 必须是 normalize() 后的结果。 */
    fun saveNormalized(context: Context, token: String): List<String> {
        val tokens = LinkedHashSet(existingTokens(context))
        tokens.add(token)

        val file = authFile(context)
        file.parentFile?.mkdirs()

        val savedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

        val tokenArray = JSONArray()
        for (item in tokens) tokenArray.put(item)

        val payload = JSONObject()
            .put("token", tokens.first())
            .put("tokens", tokenArray)
            .put("savedAt", savedAt)

        file.writeText(payload.toString(2) + "\n")
        file.setReadable(true, true)
        file.setWritable(true, true)
        file.setExecutable(false, false)
        return tokens.toList()
    }
}

/** 同一个 App 进程里自动弹一次向导，避免旋转/回到主页时反复打断。手动入口不受影响。 */
object TokenWizardState {
    @Volatile
    var shownThisProcess: Boolean = false
}
