package com.fleshout.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** AI 调用异常：携带错误码，便于前端展示。 */
class AiException(message: String, val code: String = "AI_ERROR") : Exception(message)

data class AiConfig(
    val provider: String,
    val apiKey: String,
    val baseUrl: String,
    val model: String,
    val temperature: Double,
    val maxTokens: Int,
    val timeoutSeconds: Int
) {
    companion object {
        const val PROVIDER_OLLAMA = "ollama"
        const val PROVIDER_CUSTOM = "custom"
        const val PROVIDER_DEEPSEEK = "deepseek"
        const val PROVIDER_SILICONFLOW = "siliconflow"

        const val DEEPSEEK_BASE = "https://api.deepseek.com"
        const val SILICONFLOW_BASE = "https://api.siliconflow.cn/v1"
        const val DEEPSEEK_FLASH = "deepseek-v4-flash"
        const val DEEPSEEK_PRO = "deepseek-v4-pro"
        const val SILICONFLOW_DEFAULT_MODEL = "deepseek-ai/DeepSeek-V3"

        fun defaultBaseUrl(provider: String): String = when (provider) {
            PROVIDER_OLLAMA -> "http://localhost:11434"
            PROVIDER_DEEPSEEK -> DEEPSEEK_BASE
            PROVIDER_SILICONFLOW -> SILICONFLOW_BASE
            else -> ""
        }

        fun defaultModel(provider: String): String = when (provider) {
            PROVIDER_DEEPSEEK -> DEEPSEEK_FLASH
            PROVIDER_SILICONFLOW -> SILICONFLOW_DEFAULT_MODEL
            else -> ""
        }

        fun fromJson(o: JSONObject): AiConfig {
            val provider = o.optString("provider", PROVIDER_CUSTOM).ifEmpty { PROVIDER_CUSTOM }
            var base = o.optString("baseUrl", "")
            if (base.isEmpty()) base = defaultBaseUrl(provider)
            var model = o.optString("model", "")
            if (model.isEmpty()) model = defaultModel(provider)
            return AiConfig(
                provider = provider,
                // 从输入框复制粘贴常带首尾空格/换行，直接拿去请求会 401
                apiKey = o.optString("apiKey", "").trim(),
                baseUrl = base,
                model = model,
                temperature = o.optDouble("temperature", 0.7),
                maxTokens = o.optInt("maxTokens", 4096),
                timeoutSeconds = o.optInt("timeout", 300).let { if (it <= 0) 300 else it }
            )
        }
    }

    val isOllama: Boolean get() = provider == PROVIDER_OLLAMA
    val isDeepSeek: Boolean get() = provider == PROVIDER_DEEPSEEK
}

data class AiMessage(
    val role: String,
    val content: String,
    val toolCalls: JSONArray? = null,
    val toolCallId: String? = null,
    val toolName: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("role", role)
        put("content", content)
        if (!toolCalls.isNullOrEmptyCompat()) put("tool_calls", toolCalls)
        if (!toolCallId.isNullOrEmpty()) put("tool_call_id", toolCallId)
        if (!toolName.isNullOrEmpty()) put("name", toolName)
    }
}

private fun JSONArray?.isNullOrEmptyCompat(): Boolean = this == null || length() == 0

data class AiResult(
    val content: String,
    val toolCalls: JSONArray,
    val finishReason: String
)

/**
 * 与 OpenAI 兼容接口 / Ollama 原生接口通信。
 */
class AiClient(private val cfg: AiConfig) {

    // ------------------------------------------------------------ 端点

    private fun chatEndpoint(): String {
        if (cfg.isOllama) return cfg.baseUrl.trimEnd('/') + "/api/chat"
        var base = cfg.baseUrl.trim()
        if (base.isEmpty()) throw AiException("Custom provider requires base_url", "CLIENT_ERROR")
        base = base.trimEnd('/')
        return when {
            base.endsWith("/chat/completions") -> base
            base.endsWith("/v1") -> "$base/chat/completions"
            else -> "$base/chat/completions"
        }
    }

    private fun modelsEndpoint(): String {
        if (cfg.isOllama) return cfg.baseUrl.trimEnd('/') + "/api/tags"
        var base = cfg.baseUrl.trim().trimEnd('/')
        if (base.endsWith("/chat/completions")) base = base.removeSuffix("/chat/completions")
        return when {
            base.endsWith("/v1") -> "$base/models"
            else -> "$base/models"
        }
    }

    // ------------------------------------------------------------ 请求体

    private fun buildBody(messages: List<AiMessage>, tools: JSONArray?, stream: Boolean): JSONObject {
        val arr = JSONArray()
        for (m in messages) arr.put(m.toJson())
        val body = JSONObject()
        body.put("model", cfg.model)
        body.put("messages", arr)
        body.put("stream", stream)
        if (cfg.isOllama) {
            val opts = JSONObject()
            opts.put("temperature", cfg.temperature)
            opts.put("num_predict", cfg.maxTokens)
            body.put("options", opts)
        } else {
            body.put("temperature", cfg.temperature)
            body.put("max_tokens", cfg.maxTokens)
            // DeepSeek 官方默认开思考，改写/总结这类任务用不上，关掉省 token
            if (cfg.isDeepSeek) body.put("thinking", JSONObject().put("type", "disabled"))
        }
        if (tools != null && tools.length() > 0) body.put("tools", tools)
        return body
    }

    private fun open(method: String, urlStr: String, body: JSONObject?): HttpURLConnection {
        val conn: HttpURLConnection
        try {
            conn = URL(urlStr).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw AiException("创建 HTTP 客户端失败: ${e.message}", "CLIENT_ERROR")
        }
        conn.requestMethod = method
        conn.connectTimeout = 30_000
        conn.readTimeout = cfg.timeoutSeconds * 1000
        conn.doInput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", if (cfg.isOllama) "application/json" else "application/json")
        if (cfg.apiKey.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
        if (body != null) {
            conn.doOutput = true
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            val out: OutputStream = conn.outputStream
            out.write(bytes)
            out.flush()
            out.close()
        }
        return conn
    }

    private fun readError(conn: HttpURLConnection): Nothing {
        val code = conn.responseCode
        var text = ""
        try {
            val es: InputStream? = conn.errorStream
            if (es != null) text = es.bufferedReader(Charsets.UTF_8).readText()
        } catch (_: Exception) {
        }
        val mapped = when (code) {
            401, 403 -> "INVALID_API_KEY"
            404 -> "MODEL_NOT_FOUND"
            429 -> "RATE_LIMIT"
            408 -> "TIMEOUT"
            in 500..599 -> "SERVICE_UNAVAILABLE"
            else -> "API_ERROR"
        }
        val detail = extractMessage(text).ifBlank { "HTTP $code" }
        throw AiException("API 返回错误 ($code): $detail", mapped)
    }

    /** 服务商返回的原始 JSON 很长，只取 message 字段给人看 */
    private fun extractMessage(raw: String): String {
        if (raw.isBlank()) return ""
        return try {
            val j = JSONObject(raw)
            val fromError = j.optJSONObject("error")?.optString("message").orEmpty()
            val msg = fromError.ifEmpty { j.optString("message", "") }
            if (msg.isNotEmpty()) msg else raw.take(200)
        } catch (e: Exception) {
            raw.take(200)
        }
    }

    // ------------------------------------------------------------ 非流式

    fun chat(messages: List<AiMessage>, tools: JSONArray? = null): AiResult {
        val conn = open("POST", chatEndpoint(), buildBody(messages, tools, false))
        try {
            val code = conn.responseCode
            if (code !in 200..299) readError(conn)
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            return parseChatResponse(text)
        } catch (e: SocketTimeoutException) {
            throw AiException("请求超时，请检查网络连接后重试", "TIMEOUT")
        } catch (e: UnknownHostException) {
            throw AiException("无法连接到 AI 服务，请检查网络连接", "CONNECTION_ERROR")
        } catch (e: AiException) {
            throw e
        } catch (e: Exception) {
            throw AiException("读取响应失败: ${e.message}", "RESPONSE_ERROR")
        } finally {
            conn.disconnect()
        }
    }

    private fun parseChatResponse(text: String): AiResult {
        val obj = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw AiException("解析响应失败: ${e.message}", "RESPONSE_ERROR")
        }
        if (cfg.isOllama) {
            val msg = obj.optJSONObject("message") ?: JSONObject()
            val calls = normalizeToolCalls(msg.optJSONArray("tool_calls"))
            return AiResult(msg.optString("content", ""), calls, if (obj.optBoolean("done", true)) "stop" else "")
        }
        val choices = obj.optJSONArray("choices")
            ?: throw AiException("解析响应失败: 缺少 choices", "RESPONSE_ERROR")
        if (choices.length() == 0) throw AiException("解析响应失败: choices 为空", "RESPONSE_ERROR")
        val choice = choices.getJSONObject(0)
        val msg = choice.optJSONObject("message") ?: JSONObject()
        val calls = normalizeToolCalls(msg.optJSONArray("tool_calls"))
        return AiResult(msg.optString("content", ""), calls, choice.optString("finish_reason", ""))
    }

    // ------------------------------------------------------------ 流式

    fun chatStream(messages: List<AiMessage>, tools: JSONArray? = null, onDelta: (String) -> Unit): AiResult {
        val conn = open("POST", chatEndpoint(), buildBody(messages, tools, true))
        try {
            val code = conn.responseCode
            if (code !in 200..299) readError(conn)
            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            val sb = StringBuilder()
            val toolCalls = JSONArray()
            val callBuffers = LinkedHashMap<Int, ToolCallBuf>()
            var finish = ""
            var line: String?
            while (true) {
                line = try {
                    reader.readLine()
                } catch (e: SocketTimeoutException) {
                    throw AiException("请求超时，请检查网络连接后重试", "TIMEOUT")
                } ?: break
                if (line.isBlank()) continue
                if (cfg.isOllama) {
                    if (line.trim() == "[DONE]") break
                    val obj = try {
                        JSONObject(line)
                    } catch (e: Exception) {
                        continue
                    }
                    val msg = obj.optJSONObject("message")
                    if (msg != null) {
                        val delta = msg.optString("content", "")
                        if (delta.isNotEmpty()) {
                            sb.append(delta)
                            onDelta(delta)
                        }
                        val calls = msg.optJSONArray("tool_calls")
                        if (calls != null) mergeToolCalls(callBuffers, calls, null)
                    }
                    if (obj.optBoolean("done", false)) {
                        finish = "stop"
                        break
                    }
                } else {
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val obj = try {
                        JSONObject(data)
                    } catch (e: Exception) {
                        continue
                    }
                    val choices = obj.optJSONArray("choices") ?: continue
                    if (choices.length() == 0) continue
                    val choice = choices.getJSONObject(0)
                    val delta = choice.optJSONObject("delta")
                    if (delta != null) {
                        val c = delta.optString("content", "")
                        if (c.isNotEmpty()) {
                            sb.append(c)
                            onDelta(c)
                        }
                        val calls = delta.optJSONArray("tool_calls")
                        if (calls != null) mergeToolCalls(callBuffers, calls, delta)
                    }
                    val fr = choice.optString("finish_reason", "")
                    if (fr.isNotEmpty()) finish = fr
                }
            }
            reader.close()
            for (buf in callBuffers.values) {
                toolCalls.put(buf.toJson())
            }
            return AiResult(sb.toString(), toolCalls, finish)
        } catch (e: AiException) {
            throw e
        } catch (e: UnknownHostException) {
            throw AiException("无法连接到 AI 服务，请检查网络连接", "CONNECTION_ERROR")
        } catch (e: Exception) {
            throw AiException("读取响应失败: ${e.message}", "RESPONSE_ERROR")
        } finally {
            conn.disconnect()
        }
    }

    private class ToolCallBuf(val index: Int) {
        var id: String = ""
        var name: String = ""
        val args = StringBuilder()

        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id.ifEmpty { "call_$index" })
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", name)
                put("arguments", args.toString().ifEmpty { "{}" })
            })
        }
    }

    private fun mergeToolCalls(buffers: LinkedHashMap<Int, ToolCallBuf>, calls: JSONArray, delta: JSONObject?) {
        for (i in 0 until calls.length()) {
            val c = calls.optJSONObject(i) ?: continue
            val idx = c.optInt("index", i)
            val buf = buffers.getOrPut(idx) { ToolCallBuf(idx) }
            val id = c.optString("id", "")
            if (id.isNotEmpty()) buf.id = id
            val fn = c.optJSONObject("function")
            if (fn != null) {
                val name = fn.optString("name", "")
                if (name.isNotEmpty()) buf.name = name
                val args = fn.opt("arguments")
                when (args) {
                    is String -> buf.args.append(args)
                    is JSONObject -> buf.args.append(args.toString())
                    else -> {}
                }
            }
        }
    }

    /** 统一工具调用结构：id / type / function{name,arguments(string)} */
    private fun normalizeToolCalls(calls: JSONArray?): JSONArray {
        val out = JSONArray()
        if (calls == null) return out
        for (i in 0 until calls.length()) {
            val c = calls.optJSONObject(i) ?: continue
            val id = c.optString("id", "call_$i")
            val fn = c.optJSONObject("function") ?: JSONObject()
            val argsObj = fn.opt("arguments")
            val argsStr = when (argsObj) {
                is String -> argsObj
                is JSONObject -> argsObj.toString()
                null -> "{}"
                else -> argsObj.toString()
            }
            out.put(JSONObject().apply {
                put("id", id)
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", fn.optString("name", ""))
                    put("arguments", argsStr)
                })
            })
        }
        return out
    }

    // ------------------------------------------------------------ 模型列表 / 连接测试

    fun listModels(): List<JSONObject> {
        val conn = open("GET", modelsEndpoint(), null)
        try {
            val code = conn.responseCode
            if (code !in 200..299) readError(conn)
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val obj = JSONObject(text)
            val out = ArrayList<JSONObject>()
            if (cfg.isOllama) {
                val models = obj.optJSONArray("models") ?: JSONArray()
                for (i in 0 until models.length()) {
                    val m = models.optJSONObject(i) ?: continue
                    val name = m.optString("name", m.optString("model", ""))
                    if (name.isEmpty()) continue
                    out.add(JSONObject().put("id", name).put("group", "Ollama"))
                }
            } else {
                val data = obj.optJSONArray("data") ?: JSONArray()
                for (i in 0 until data.length()) {
                    val m = data.optJSONObject(i) ?: continue
                    val id = m.optString("id", "")
                    if (id.isEmpty()) continue
                    out.add(JSONObject().put("id", id).put("group", JSONObject.NULL))
                }
            }
            return out
        } catch (e: AiException) {
            throw e
        } catch (e: Exception) {
            throw AiException("获取模型列表失败: ${e.message}", "REQUEST_ERROR")
        } finally {
            conn.disconnect()
        }
    }

    fun testConnection(): JSONObject {
        val start = System.currentTimeMillis()
        return try {
            // 最小化探针：无 system、单字输入、输出上限 16 token
            val probe = AiClient(cfg.copy(maxTokens = 16, temperature = 0.0))
            probe.chat(listOf(AiMessage("user", "hi")))
            JSONObject().put("success", true).put("latencyMs", System.currentTimeMillis() - start)
        } catch (e: AiException) {
            JSONObject().put("success", false)
                .put("message", e.message ?: "连接失败")
                .put("latencyMs", System.currentTimeMillis() - start)
        } catch (e: Exception) {
            JSONObject().put("success", false)
                .put("message", e.message ?: "连接失败")
                .put("latencyMs", System.currentTimeMillis() - start)
        }
    }
}
