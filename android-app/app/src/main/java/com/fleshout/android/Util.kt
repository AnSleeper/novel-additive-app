package com.fleshout.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

object Util {

    fun uuid(): String = UUID.randomUUID().toString()

    /** 与前端 ge() 完全一致的字数统计规则 */
    fun wordCount(text: String?): Int {
        if (text.isNullOrEmpty()) return 0
        var count = 0
        var inLatin = false
        for (ch in text) {
            when {
                ch.isWhitespace() -> {
                    if (inLatin) {
                        count++
                        inLatin = false
                    }
                }
                ch in 'a'..'z' || ch in 'A'..'Z' -> inLatin = true
                ch.code in 0x21..0x7E -> {
                    // ASCII 标点/符号：不计入
                }
                else -> {
                    count++
                    inLatin = false
                }
            }
        }
        if (inLatin) count++
        return count
    }

    fun nowSql(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        return sdf.format(java.util.Date())
    }

    fun nowEpochSeconds(): Long = System.currentTimeMillis() / 1000L

    fun extForFormat(format: String?): String = when (format) {
        "mobi", "azw3" -> "epub"
        "txt" -> "txt"
        else -> "epub"
    }

    fun readText(file: File): String = file.readText(Charsets.UTF_8)

    fun writeText(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text, Charsets.UTF_8)
    }

    fun safeName(name: String): String =
        name.replace(Regex("[<>:\"/\\\\|?*]"), "_").trim().ifEmpty { "未命名" }

    fun jsonObjectOrEmpty(text: String?): JSONObject {
        if (text.isNullOrBlank()) return JSONObject()
        return try {
            JSONObject(text)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    fun jsonArrayOrEmpty(text: String?): JSONArray {
        if (text.isNullOrBlank()) return JSONArray()
        return try {
            JSONArray(text)
        } catch (e: Exception) {
            JSONArray()
        }
    }

    fun nullIfEmpty(s: String?): String? = if (s.isNullOrEmpty()) null else s
}
