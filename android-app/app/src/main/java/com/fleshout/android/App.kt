package com.fleshout.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject

/**
 * 全局单例：保存上下文、数据库仓储与 WebView 引用，负责向前端派发事件。
 */
object App {
    lateinit var ctx: Context
    val main = Handler(Looper.getMainLooper())
    var webView: WebView? = null

    val repo: Repo by lazy { Repo(ctx) }

    private fun toJsonText(payload: Any?): String = when (payload) {
        null -> "null"
        is JSONObject -> payload.toString()
        is JSONArray -> payload.toString()
        is String -> JSONObject.quote(payload)
        is Boolean, is Number -> payload.toString()
        else -> JSONObject.quote(payload.toString())
    }

    /** 向前端派发 Tauri 风格事件 */
    fun emit(event: String, payload: Any?) {
        val js = "window.__FO_EVENT(" + JSONObject.quote(event) + "," + toJsonText(payload) + ");"
        runJs(js)
    }

    fun runJs(js: String) {
        main.post {
            try {
                webView?.evaluateJavascript(js, null)
            } catch (_: Throwable) {
            }
        }
    }
}
