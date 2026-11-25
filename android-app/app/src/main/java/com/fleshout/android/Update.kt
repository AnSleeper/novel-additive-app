package com.fleshout.android

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 自动更新检查：读 GitHub Releases 的最新版本，与本地 versionName 比较。
 *
 * 双通道：
 *  1) api.github.com/releases/latest —— 信息全（说明、APK 地址）
 *  2) github.com/.../releases/latest —— 网页会 302 到 /tag/vX.Y.Z，从最终 URL 取版本
 * 任一成功即可；两条都失败时把具体原因带回去。
 */
object Update {

    private const val REPO = "AnSleeper/novel-additive-app"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val WEB = "https://github.com/$REPO/releases/latest"

    fun currentVersion(): String = BuildConfig.VERSION_NAME

    fun check(): JSONObject {
        val current = currentVersion()
        val result = JSONObject()
            .put("current", current)
            .put("hasUpdate", false)

        if (REPO.startsWith("OWNER/")) {
            result.put("error", "未配置更新源")
            return result
        }

        val apiError = tryApi(result, current)
        if (apiError == null) return result

        // API 失败，回退网页通道
        val webError = tryWeb(result, current)
        result.put("error", "接口：$apiError；网页：$webError")
        return result
    }

    private fun open(urlStr: String, accept: String): HttpURLConnection {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept", accept)
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) FleshOut-Updater")
        return conn
    }

    /** API 通道成功返回 null，失败返回简短原因 */
    private fun tryApi(result: JSONObject, current: String): String? {
        var conn: HttpURLConnection? = null
        try {
            conn = open(API, "application/vnd.github+json")
            val code = conn.responseCode
            if (code !in 200..299) return "HTTP $code"

            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val obj = JSONObject(text)
            val tag = obj.optString("tag_name", "").trim().removePrefix("v").removePrefix("V")
            if (tag.isEmpty()) return "版本号缺失"

            result.put("latest", tag)
            result.put("pageUrl", obj.optString("html_url", WEB))
            result.put("notes", obj.optString("body", "").take(1200))
            val assets = obj.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    if (a.optString("name", "").endsWith(".apk", true)) {
                        result.put("apkUrl", a.optString("browser_download_url", ""))
                        result.put("size", a.optLong("size", 0))
                        break
                    }
                }
            }
            result.put("hasUpdate", isNewer(tag, current))
            result.remove("error")
            return null
        } catch (e: Exception) {
            return brief(e)
        } finally {
            conn?.disconnect()
        }
    }

    /** 网页通道：从 final URL 里抠出 /tag/vX.Y.Z */
    private fun tryWeb(result: JSONObject, current: String): String? {
        var conn: HttpURLConnection? = null
        try {
            conn = open(WEB, "text/html")
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            if (code !in 200..299) return "HTTP $code"

            val finalUrl = conn.url.toString()
            val m = Regex("/tag/v?([0-9][0-9A-Za-z.\\-_]*)").find(finalUrl)
                ?: return "未找到版本标记"
            val tag = m.groupValues[1].removePrefix("v").removePrefix("V")
            result.put("latest", tag)
            result.put("pageUrl", finalUrl)
            // 没有 notes，构造 APK 直链
            result.put("apkUrl", "https://github.com/$REPO/releases/download/v$tag/novel-additive-app-$tag.apk")
            result.put("hasUpdate", isNewer(tag, current))
            return null
        } catch (e: Exception) {
            return brief(e)
        } finally {
            conn?.disconnect()
        }
    }

    /** 把异常压成一句人话，避免把堆栈甩给用户 */
    private fun brief(e: Exception): String {
        val raw = e.message ?: e.javaClass.simpleName
        return when {
            raw.contains("Failed to connect", true) || raw.contains("ECONNREFUSED", true) -> "无法连接"
            raw.contains("timeout", true) || raw.contains("timed out", true) -> "超时"
            raw.contains("SSL", true) || raw.contains("certificate", true) -> "证书错误"
            raw.contains("ENOTFOUND", true) || raw.contains("Unable to resolve", true) -> "域名解析失败"
            else -> raw.take(80)
        }
    }

    fun isNewer(latest: String, current: String): Boolean {
        val a = parse(latest)
        val b = parse(current)
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun parse(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V")
            .split('.', '-', '+')
            .map { it.takeWhile { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
}
