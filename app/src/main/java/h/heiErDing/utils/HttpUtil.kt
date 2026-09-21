package h.heiErDing.utils

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 极简 HTTP GET 工具。
 * 完全对译 F3 插件的 httpText / httpJson 实现，保证负一屏天气 / 一言 / 问候语网络行为一致。
 */
object HttpUtil {

    @JvmStatic
    fun getText(url: String, timeout: Int): String? {
        var c: HttpURLConnection? = null
        var input: InputStream? = null
        try {
            c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = timeout
            c.readTimeout = timeout
            c.instanceFollowRedirects = true
            c.requestMethod = "GET"
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) heiErDing/1.0")
            c.setRequestProperty("Accept", "application/json,text/plain,/")
            val code = c.responseCode
            if (code < 200 || code >= 300) return null
            input = c.inputStream
            val br = BufferedReader(InputStreamReader(input, "UTF-8"))
            val sb = StringBuilder()
            var line: String?
            while (br.readLine().also { line = it } != null) {
                sb.append(line)
                if (sb.length > 800000) break
            }
            return sb.toString()
        } catch (e: Throwable) {
            return null
        } finally {
            try { input?.close() } catch (ignored: Throwable) {}
            try { c?.disconnect() } catch (ignored: Throwable) {}
        }
    }

    @JvmStatic
    fun getJson(url: String, timeout: Int): JSONObject? {
        return try {
            val s = getText(url, timeout) ?: return null
            JSONObject(s)
        } catch (e: Throwable) {
            null
        }
    }
}
