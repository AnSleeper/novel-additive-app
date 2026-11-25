package com.fleshout.android

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.zip.ZipFile

/**
 * 前端命令分发层。
 *
 * 前端 tauri-shim.js 调用 `FleshOutNative.call(id, cmd, payloadJson)`，
 * 本类在后台线程执行命令后，通过 `window.__FO_RESOLVE(id, json)` 回填结果。
 *
 * 返回约定：`{ok:true,data:{success:true,data:...}}` 或
 * `{ok:true,data:{success:false,error:{message,code}}}`。
 * 与前端 useApiCall 的 `a.success / a.data / a.error.message` 读取方式一致。
 */
class Ipc(private val activity: Activity) {

    companion object {
        private const val REQ_OPEN = 0x1001
        private const val REQ_SAVE = 0x1002
    }

    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newCachedThreadPool()

    /** requestCode -> 等待文件选择结果的回调 */
    private val waiters = ConcurrentHashMap<Int, (Uri?) -> Unit>()
    private var seq = REQ_OPEN

    private val repo: Repo get() = App.repo

    // ------------------------------------------------------------------ 入口

    @JavascriptInterface
    fun call(id: String, cmd: String, payloadJson: String) {
        val debug = BuildConfig.DEBUG
        if (debug) android.util.Log.d("FleshOutIpc", "→ $cmd ($id)")
        val payload = try {
            if (payloadJson.isBlank() || payloadJson == "null") JSONObject() else JSONObject(payloadJson)
        } catch (e: Exception) {
            JSONObject()
        }

        if (cmd == "dialog:open" || cmd == "dialog:save") {
            pickFile(id, cmd, payload)
            return
        }

        pool.execute {
            val out = try {
                JSONObject().put("ok", true).put("data", route(cmd, payload))
            } catch (e: Exception) {
                JSONObject().put("ok", true).put("data", fail(e))
            }
            if (debug) android.util.Log.d("FleshOutIpc", "← $cmd " + mask(out.toString()).take(160))
            reply(id, out)
        }
    }

    /** 日志脱敏：任何 sk- 开头的密钥都不落日志 */
    private fun mask(s: String): String = s.replace(Regex("sk-[A-Za-z0-9_\\-]{8,}"), "sk-***")

    // ------------------------------------------------------------------ 命令路由

    /** 供自检直接调用命令（与前端走同一套实现） */
    fun exec(cmd: String, payload: JSONObject): JSONObject = route(cmd, payload)

    private fun route(cmdRaw: String, p: JSONObject): JSONObject = try {
        // tauri-shim 给业务命令统一加了 "cmd:" 前缀
        val cmd = cmdRaw.removePrefix("cmd:")
        when (cmd) {
            // ---------------- 窗口 / 系统插件（桌面端能力，安卓端降级处理）
            "plugin:window|is_maximized" -> ok(false)
            "plugin:window|is_fullscreen" -> ok(false)
            "plugin:window|is_focused" -> ok(true)
            "plugin:window|scale_factor" -> ok(1.0)
            "plugin:window|inner_size" -> ok(JSONObject().put("width", 0).put("height", 0))
            "plugin:app|version" -> ok("0.2.0")
            "plugin:app|name" -> ok("FleshOut")
            "plugin:path|resolve_directory" -> ok(FileStore.root(App.ctx).absolutePath)

            // ---------------- 项目
            "list_projects" -> ok(JSONObject().put("projects", repo.listProjects()))

            "get_project" -> {
                val id = p.str("id")
                val project = repo.getProjectRow(id) ?: throw IllegalStateException("项目不存在")
                ok(
                    JSONObject()
                        .put("project", project)
                        .put("chapters", filterIgnored(repo.listChapters(id, false), false))
                )
            }

            "list_chapters" -> {
                val pid = p.str("projectId")
                val includeIgnored = p.optBoolean("includeIgnored", false)
                ok(JSONObject().put("chapters", filterIgnored(repo.listChapters(pid, false), includeIgnored)))
            }

            "create_project" -> {
                val data = p.optJSONObject("data") ?: JSONObject()
                val prepared = data.apply {
                    if (optString("outputPath").isEmpty()) {
                        put("outputPath", FileStore.defaultOutputDir(App.ctx, optString("name", "未命名项目")).absolutePath)
                    }
                    if (optString("bookPath").isNotEmpty()) {
                        put("bookPath", importBook(optString("bookPath")))
                    }
                }
                ok(repo.createProject(prepared))
            }

            "parse_project_book" -> {
                val pid = p.str("projectId")
                val project = repo.getProjectRow(pid) ?: throw IllegalStateException("项目不存在")
                val count = parseIntoProject(pid, project)
                ok(count)
            }

            "update_project" -> {
                val id = p.str("id")
                val data = p.optJSONObject("data") ?: JSONObject()
                if (data.optBoolean("clearErrorInfo", false)) repo.clearProjectError(id)
                ok(repo.updateProject(id, data) ?: throw IllegalStateException("项目不存在"))
            }

            "delete_project" -> {
                repo.deleteProject(p.str("id"))
                ok(true)
            }

            "get_txt_split_rules" -> ok(repo.getTxtSplitRules())

            "save_txt_split_rules" -> ok(repo.saveTxtSplitRules(p.optJSONObject("rules") ?: JSONObject()))

            "preview_txt_chapters" -> {
                val file = FileStore.importUri(App.ctx, p.str("filePath"))
                if (!file.exists()) throw IllegalStateException("文件不存在")
                val rules = p.optJSONObject("rules") ?: repo.getTxtSplitRules()
                val max = if (p.isNull("maxChapters")) 0 else p.optInt("maxChapters", 0)
                val list = BookParser.splitTxt(BookParser.readTextAuto(file), rules)
                val arr = JSONArray()
                val limit = if (max > 0) minOf(max, list.size) else list.size
                for (i in 0 until limit) {
                    val c = list[i]
                    arr.put(
                        JSONObject()
                            .put("index", c.index)
                            .put("title", c.title)
                            .put("lineNumber", c.lineNumber)
                            .put("wordCount", c.wordCount)
                    )
                }
                ok(JSONObject().put("chapters", arr))
            }

            "parse_book" -> {
                val rawPath = p.optString("filePath", "")
                if (rawPath.isEmpty()) throw IllegalStateException("请先选择书籍文件")
                val file = FileStore.importUri(App.ctx, rawPath)
                if (!file.exists()) throw IllegalStateException("书籍文件不存在")
                val format = formatOf(file.name)
                val parsed = BookParser.parse(file, format)
                val arr = JSONArray()
                for (c in parsed.chapters) {
                    arr.put(
                        JSONObject()
                            .put("index", c.index)
                            .put("title", c.title)
                            .put("wordCount", c.wordCount)
                    )
                }
                ok(
                    JSONObject()
                        .put("title", parsed.title)
                        .put("author", parsed.author)
                        .put("publisher", parsed.publisher)
                        .put("language", parsed.language)
                        .put("description", parsed.description)
                        .put("coverImage", parsed.coverImage ?: JSONObject.NULL)
                        .put("fileSize", file.length())
                        .put("chapters", arr)
                )
            }

            // ---------------- AI 模型
            "list_ai_models" -> ok(JSONObject().put("models", repo.listAiModels()))

            "get_config" -> {
                val ai = JSONObject().put("defaultModelId", repo.getDefaultModelId() ?: "")
                val config = JSONObject().put("ai", ai)
                val raw = repo.getConfigValue("ai_settings")
                if (!raw.isNullOrBlank()) {
                    try {
                        JSONObject(raw).keys().forEach { k ->
                            if (!ai.has(k)) ai.put(k, JSONObject(raw).opt(k))
                        }
                    } catch (_: Exception) {
                    }
                }
                ok(JSONObject().put("config", config))
            }

            "create_ai_model" -> ok(repo.createAiModel(p.optJSONObject("data") ?: JSONObject()))

            "update_ai_model" -> {
                val id = p.str("id")
                ok(repo.updateAiModel(id, p.optJSONObject("data") ?: JSONObject()) ?: throw IllegalStateException("模型不存在"))
            }

            "delete_ai_model" -> {
                repo.deleteAiModel(p.str("id"))
                ok(true)
            }

            "set_default_ai_model" -> {
                val id = p.str("id")
                repo.setDefaultModelId(id)
                ok(true)
            }

            "get_ai_models" -> {
                val cfg = AiConfig.fromJson(p.optJSONObject("config") ?: JSONObject())
                val arr = JSONArray()
                for (m in AiClient(cfg).listModels()) arr.put(m)
                ok(arr)
            }

            "test_ai_connection" -> {
                val cfg = AiConfig.fromJson(p.optJSONObject("config") ?: JSONObject())
                ok(AiClient(cfg).testConnection())
            }

            // ---------------- 流水线
            "start_pipeline" -> {
                val pid = p.optJSONObject("request")?.str("projectId")?.takeIf { it.isNotEmpty() }
                    ?: p.str("projectId")
                PipelineEngine.start(pid)
                // 前端监听器可能晚于命令返回才挂上，延迟补一次状态，避免界面不刷新
                App.main.postDelayed({
                    val row = repo.getProjectRow(pid)
                    App.emit(
                        "pipeline-project-status",
                        JSONObject()
                            .put("projectId", pid)
                            .put("status", "running")
                            .put("currentStage", row?.optInt("currentStage") ?: 1)
                    )
                }, 400)
                ok(true)
            }

            "cancel_pipeline" -> {
                PipelineEngine.cancel(p.str("projectId"))
                ok(true)
            }

            "goto_stage" -> {
                val pid = p.str("projectId")
                val target = p.optInt("targetStage", 1)
                if (p.optBoolean("cleanupAfter", false)) PipelineEngine.cleanupAfterStage(pid, target)
                repo.setCurrentStage(pid, target)
                repo.setProjectStatus(pid, "pending", target)
                PipelineEngine.start(pid)
                ok(true)
            }

            "cleanup_after_stage" -> {
                PipelineEngine.cleanupAfterStage(p.str("projectId"), p.optInt("targetStage", 1))
                ok(true)
            }

            "cleanup_current_stage" -> {
                PipelineEngine.cleanupCurrentStage(p.str("projectId"), p.optInt("stage", 1))
                ok(true)
            }

            "start_rewrite_stage" -> {
                val pid = p.str("projectId")
                repo.setCurrentStage(pid, PipelineEngine.STAGE_REWRITE)
                PipelineEngine.regenerateRewrite(pid, emptyList())
                ok(true)
            }

            "regenerate_summary" -> {
                PipelineEngine.regenerateSummary(p.str("projectId"), p.stringList("chapterIds"))
                ok(true)
            }

            "regenerate_rewrite" -> {
                PipelineEngine.regenerateRewrite(p.str("projectId"), p.stringList("chapterIds"))
                ok(true)
            }

            "retry_failed" -> {
                val pid = p.str("projectId")
                val summaryOnly = p.optBoolean("summaryOnly", false)
                PipelineEngine.retryFailed(pid, summaryOnly, !summaryOnly)
                ok(true)
            }

            // ---------------- 手动模式
            "create_manual_project" -> {
                val req = p.optJSONObject("request") ?: p
                val name = req.optString("name", "未命名项目")
                val bookPath = importBook(req.optString("bookPath"))
                val modelId = req.optString("modelId", "")
                val format = formatOf(bookPath)
                val project = repo.createProject(
                    JSONObject()
                        .put("name", name)
                        .put("bookPath", bookPath)
                        .put("outputPath", FileStore.defaultOutputDir(App.ctx, name).absolutePath)
                        .put("sourceFormat", format)
                        .put("modelId", modelId)
                        .put("defaultMode", "manual")
                )
                val pid = project.str("id")
                parseIntoProject(pid, repo.getProjectRow(pid) ?: project)
                ok(JSONObject().put("projectId", pid))
            }

            "list_manual_chapters" -> ok(repo.listManualChapters(p.str("projectId")))

            "get_manual_project_info" -> ok(
                repo.manualProjectInfo(p.str("projectId"), p.optInt("currentChapterIndex", 1))
            )

            "get_chapter_context" -> {
                val chapterId = p.str("chapterId")
                ok(repo.getChapter(chapterId, includeContent = true) ?: throw IllegalStateException("章节不存在"))
            }

            "save_manual_content" -> {
                repo.saveChapterContent(p.str("chapterId"), p.optString("content", ""))
                ok(true)
            }

            "export_manual_project" -> {
                val path = repo.exportProject(p.str("projectId"))
                val out = File(path)
                if (out.exists()) FileStore.exportToDownloads(App.ctx, out)
                ok(path)
            }

            "get_chapter_original_content" -> {
                val content = repo.getOriginalContent(p.str("id"))
                if (content.isEmpty()) {
                    // 与桌面端一致：章节文件不存在时给出可识别的提示语
                    throw IllegalStateException("章节文件不存在")
                }
                ok(JSONObject().put("content", content))
            }

            "update_chapter_stage_status" -> {
                val id = p.str("id")
                val stage = p.optInt("stage", 1)
                repo.setStageIgnored(id, stage, p.optBoolean("ignored", false))
                ok(repo.getStageStatus(id, stage))
            }

            "get_chapter_stage_status" -> ok(
                repo.getStageStatus(p.str("id"), p.optInt("stage", 1))
            )

            "update_chapter" -> {
                val id = p.str("id")
                val data = p.optJSONObject("data") ?: JSONObject()
                if (data.has("needsRewrite")) {
                    repo.setNeedsRewrite(id, data.optBoolean("needsRewrite", false))
                }
                ok(repo.getChapter(id) ?: throw IllegalStateException("章节不存在"))
            }

            "save_rewritten_content" -> {
                val id = p.str("id")
                val data = p.optJSONObject("data") ?: JSONObject()
                repo.saveRewrittenContent(id, data.optString("content", ""))
                ok(true)
            }

            "restore_chapter_original" -> {
                repo.restoreOriginal(p.str("chapterId"))
                ok(true)
            }

            "get_chapter" -> ok(repo.getChapter(p.str("id")) ?: throw IllegalStateException("章节不存在"))

            "get_category_config" -> {
                val pid = p.str("projectId")
                val project = repo.getProjectRow(pid) ?: JSONObject()
                val custom = repo.getCustomPrompts(project.optString("customPromptsId").ifEmpty { null })
                val identify = custom?.optString("identifyPrompt").orEmpty()
                val source = if (identify.isNotBlank()) "project" else "default"
                val cfg = if (identify.isNotBlank()) {
                    Util.jsonObjectOrEmpty(identify)
                } else {
                    JSONObject().put("categories", JSONArray())
                }
                if (!cfg.has("categories")) cfg.put("categories", JSONArray())
                cfg.put("promptSource", source)
                cfg.put("hasProjectPrompt", identify.isNotBlank())
                ok(JSONObject().put("categoryConfig", cfg))
            }

            "export_chapter_summary" -> ok(
                JSONObject().put("markdown", repo.exportChapterSummary(p.str("projectId")))
            )

            // ---------------- 会话
            "list_conversations" -> ok(repo.listConversations(p.str("projectId")))

            "load_conversation_history" -> {
                val convId = p.str("conversationId")
                val limit = if (p.isNull("limit")) null else p.optInt("limit", 0).takeIf { it > 0 }
                ok(repo.listMessages(convId, limit))
            }

            "create_conversation" -> ok(
                repo.createConversation(p.str("projectId"), p.optString("name", "新会话"))
            )

            "delete_conversation" -> {
                repo.deleteConversation(p.str("conversationId"))
                ok(true)
            }

            "send_manual_message_stream" -> {
                val req = p.optJSONObject("request") ?: p
                Manual.send(req)
                ok(true)
            }

            // ---------------- 提示词模板
            "list_prompt_templates" -> ok(JSONObject().put("templates", repo.listPromptTemplates()))

            "get_prompt_template" -> {
                val t = repo.getPromptTemplate(p.str("id")) ?: throw IllegalStateException("模板不存在")
                ok(JSONObject().put("template", t))
            }

            "create_prompt_template" -> {
                val data = p.optJSONObject("data") ?: JSONObject()
                ok(JSONObject().put("template", repo.createPromptTemplate(data)))
            }

            "update_prompt_template" -> {
                val data = p.optJSONObject("data") ?: JSONObject()
                ok(JSONObject().put("template", repo.updatePromptTemplate(p.str("id"), data) ?: throw IllegalStateException("模板不存在")))
            }

            "delete_prompt_template" -> {
                repo.deletePromptTemplate(p.str("id"))
                ok(true)
            }

            "export_prompt_template" -> {
                val id = p.str("id")
                val t = repo.getPromptTemplate(id) ?: throw IllegalStateException("模板不存在")
                ok(JSONObject().put("json", t.toString(2)))
            }

            "import_prompt_template" -> importTemplate(p)

            // ---------------- 文件 / 系统
            "open_path" -> {
                openPath(p.str("path"))
                ok(true)
            }

            "show_in_folder" -> {
                revealPath(p.str("path"))
                ok(true)
            }

            // ---------------- 更新
            "check_update" -> ok(Update.check())

            "download_update" -> {
                val url = p.str("url")
                val dir = File(FileStore.root(App.ctx), "updates").apply { mkdirs() }
                val out = File(dir, "app-update.apk")
                if (out.exists()) out.delete()
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 20_000
                conn.readTimeout = 180_000
                conn.instanceFollowRedirects = true
                try {
                    val code = conn.responseCode
                    if (code !in 200..299) throw IllegalStateException("下载失败 (HTTP $code)")
                    val total = conn.contentLengthLong
                    conn.inputStream.use { input ->
                        out.outputStream().use { output ->
                            val buf = ByteArray(16 * 1024)
                            var read: Int
                            var done = 0L
                            var lastPct = -1
                            while (input.read(buf).also { read = it } > 0) {
                                output.write(buf, 0, read)
                                done += read
                                if (total > 0) {
                                    val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                                    if (pct != lastPct && (pct % 5 == 0 || pct == 100)) {
                                        lastPct = pct
                                        App.emit("update-download-progress", JSONObject().put("percent", pct))
                                    }
                                } else {
                                    // 无 Content-Length 时按每 256KB 报一次已下载字节数
                                    if (done / (256 * 1024) != lastPct.toLong()) {
                                        lastPct = (done / (256 * 1024)).toInt()
                                        App.emit("update-download-progress", JSONObject().put("bytes", done))
                                    }
                                }
                            }
                        }
                    }
                    App.emit("update-download-progress", JSONObject().put("percent", 100))
                } finally {
                    conn.disconnect()
                }
                if (out.length() < 100_000) {
                    out.delete()
                    throw IllegalStateException("下载的文件不完整")
                }
                ok(JSONObject().put("path", out.absolutePath).put("size", out.length()))
            }

            "install_update" -> {
                val f = File(p.str("path"))
                if (!f.exists()) throw IllegalStateException("安装包不存在")
                App.main.post {
                    try {
                        val uri = FileProvider.getUriForFile(
                            App.ctx,
                            "${App.ctx.packageName}.fileprovider",
                            f
                        )
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        activity.startActivity(intent)
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(
                            activity,
                            "无法调起安装器：${e.message}",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
                ok(true)
            }

            "fs:read_text_file" -> ok(FileStore.readText(App.ctx, p.str("path")))
            "fs:write_text_file" -> {
                FileStore.writeText(App.ctx, p.str("path"), p.optString("content", ""))
                ok(true)
            }

            "get_settings" -> ok(
                JSONObject()
                    .put("outputSettings", Util.jsonObjectOrEmpty(repo.getConfigValue("output_settings")))
                    .put("uiSettings", Util.jsonObjectOrEmpty(repo.getConfigValue("ui_settings")))
            )

            "save_settings" -> {
                p.optJSONObject("outputSettings")?.let { repo.setConfigValue("output_settings", it.toString()) }
                p.optJSONObject("uiSettings")?.let { repo.setConfigValue("ui_settings", it.toString()) }
                ok(true)
            }

            // 其余桌面端插件命令一律宽松放行，避免前端报错
            else -> if (cmd.startsWith("plugin:")) ok(null)
            else fail(IllegalArgumentException("未实现的后端命令: $cmd"), "NOT_IMPLEMENTED")
        }
    } catch (e: Exception) {
        fail(e)
    }

    // ------------------------------------------------------------------ 项目解析

    /** 把电子书拆分进项目工作区，返回章节数 */
    private fun parseIntoProject(projectId: String, project: JSONObject): Int {
        val bookPath = importBook(project.optString("bookPath"))
        if (bookPath.isEmpty()) throw IllegalStateException("书籍文件不存在")
        val file = File(bookPath)
        if (!file.exists()) throw IllegalStateException("书籍文件不存在")
        val format = project.optString("sourceFormat", "").ifEmpty { formatOf(file.name) }

        val parsed = BookParser.parse(file, format)
        if (parsed.chapters.isEmpty()) throw IllegalStateException("书籍文件中没有章节")

        val ws = repo.workspace(project.optString("outputPath"))
        val chapters = ArrayList<JSONObject>()
        for (ch in parsed.chapters) {
            Util.writeText(File(ws, "chapters/${ch.index}.txt"), ch.content)
            chapters.add(
                JSONObject()
                    .put("id", Util.uuid())
                    .put("index", ch.index)
                    .put("title", ch.title)
                    .put("originalHref", ch.href)
                    .put("wordCount", ch.wordCount)
            )
        }
        repo.replaceChapters(projectId, chapters)

        val totalWords = chapters.sumOf { it.optInt("wordCount") }
        repo.saveBookMetadata(
            projectId,
            JSONObject()
                .put("title", if (parsed.title.isBlank()) project.optString("name") else parsed.title)
                .put("author", parsed.author)
                .put("publisher", parsed.publisher)
                .put("language", parsed.language)
                .put("description", parsed.description)
                .put("totalChapters", chapters.size)
                .put("totalWords", totalWords)
                .put("fileSize", file.length())
                .put("coverImage", parsed.coverImage ?: "")
        )
        return chapters.size
    }

    /** content:// 选择结果落到应用私有目录，返回可用路径 */
    private fun importBook(raw: String): String {
        if (raw.isEmpty()) return ""
        if (!FileStore.isContentUri(raw)) return raw
        return FileStore.importUri(App.ctx, raw).absolutePath
    }

    private fun formatOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "txt" -> "txt"
            "mobi" -> "mobi"
            "azw3" -> "azw3"
            else -> "epub"
        }
    }

    private fun filterIgnored(arr: JSONArray, includeIgnored: Boolean): JSONArray {
        if (includeIgnored) return arr
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optBoolean("ignored")) continue
            out.put(o)
        }
        return out
    }

    // ------------------------------------------------------------------ 提示词模板导入

    private fun importTemplate(p: JSONObject): JSONObject {
        val file = FileStore.importUri(App.ctx, p.str("filePath"))
        if (!file.exists()) throw IllegalStateException("文件不存在")
        val strategy = p.optString("strategy", "rename")

        // 压缩包批量导入：只取包内的 .json，用纯文件名落盘避免路径穿越
        if (file.name.endsWith(".zip", true)) {
            val dir = File(App.ctx.cacheDir, "tpl-import-${System.currentTimeMillis()}").apply { mkdirs() }
            val extracted = ArrayList<File>()
            try {
                ZipFile(file).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val e = entries.nextElement()
                        if (e.isDirectory || !e.name.endsWith(".json", true)) continue
                        val safe = File(e.name).name
                        if (safe.isBlank()) continue
                        val out = File(dir, safe)
                        zip.getInputStream(e).use { input ->
                            out.outputStream().use { output -> input.copyTo(output) }
                        }
                        extracted.add(out)
                    }
                }
            } catch (e: Exception) {
                throw IllegalStateException("压缩包无法读取：${e.message}")
            }
            if (extracted.isEmpty()) throw IllegalStateException("压缩包里没有 JSON 模板")

            var imported = 0
            var skipped = 0
            var failed = 0
            for (f in extracted) {
                try {
                    if (importOneTemplate(f, strategy) == null) skipped++ else imported++
                } catch (e: Exception) {
                    failed++
                }
            }
            return ok(
                JSONObject()
                    .put("imported", imported)
                    .put("skipped", skipped)
                    .put("failed", failed)
                    .put("total", extracted.size)
            )
        }

        val created = importOneTemplate(file, strategy)
            ?: return JSONObject()
                .put("success", false)
                .put("error", JSONObject().put("message", "已存在同名模板").put("code", "PROMPT_TEMPLATE_IMPORT_SKIPPED"))
        return ok(JSONObject().put("template", created))
    }

    private fun importOneTemplate(file: File, strategy: String): JSONObject? {
        val text = file.readText(Charsets.UTF_8)
        val obj = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IllegalStateException("模板文件格式不正确：${file.name}")
        }
        val name = obj.optString("name", file.nameWithoutExtension)
        val existing = repo.findTemplateByName(name)
        if (existing != null && strategy == "skip") return null
        val finalName = if (existing != null && strategy == "rename") "$name (导入)" else name
        return repo.createPromptTemplate(
            JSONObject()
                .put("name", finalName)
                .put("summaryTemplate", obj.optString("summaryTemplate", ""))
                .put("rewriteTemplate", obj.optString("rewriteTemplate", ""))
                .put("breakthroughTemplate", obj.optString("breakthroughTemplate", ""))
                .put("identifyTemplate", obj.optString("identifyTemplate", ""))
        )
    }

    // ------------------------------------------------------------------ 文件打开 / 定位

    private fun openPath(path: String) {
        if (path.isEmpty()) throw IllegalStateException("路径为空")
        if (FileStore.isContentUri(path)) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(path), FileStore.mimeFor(path))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
            return
        }
        val f = File(path)
        if (!f.exists()) throw IllegalStateException("文件不存在: $path")
        FileStore.openFile(activity, f)
    }

    /** 安卓无「在文件夹中显示」，退化为复制路径并提示保存位置 */
    private fun revealPath(path: String) {
        val dir = if (path.isEmpty()) "" else {
            val f = File(path)
            if (f.isDirectory) f.absolutePath else f.parent.orEmpty()
        }
        main.post {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("路径", dir.ifEmpty { path }))
            Toast.makeText(activity, "路径已复制：$dir", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------ 系统文件选择器

    private fun pickFile(id: String, cmd: String, options: JSONObject) {
        main.post {
            try {
                // 安卓 SAF 的目录授权拿不到可写的真实路径，统一使用应用工作目录
                if (cmd == "dialog:open" && options.optBoolean("directory", false)) {
                    val stamp = java.text.SimpleDateFormat("MMdd-HHmmss", java.util.Locale.US)
                        .format(java.util.Date())
                    val dir = FileStore.defaultOutputDir(App.ctx, "workspace-$stamp")
                    Toast.makeText(activity, "工作目录：${dir.absolutePath}", Toast.LENGTH_LONG).show()
                    reply(id, JSONObject().put("ok", true).put("data", dir.absolutePath))
                    return@post
                }

                val code = ++seq
                waiters[code] = { uri -> resolvePick(id, cmd, uri) }

                val intent = if (cmd == "dialog:save") {
                    Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        val suggested = options.optString("defaultPath", "")
                        if (suggested.isNotEmpty()) {
                            type = FileStore.mimeFor(suggested)
                            putExtra(Intent.EXTRA_TITLE, File(suggested).name)
                        } else {
                            type = "*/*"
                        }
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                } else {
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = mimeFromFilters(options.optJSONArray("filters"))
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, options.optBoolean("multiple", false))
                        // 默认落在 Download，避免 Recent 里找不到刚放进去的文件
                        try {
                            putExtra(
                                DocumentsContract.EXTRA_INITIAL_URI,
                                Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload")
                            )
                        } catch (e: Exception) {
                            // 某些厂商文件管理器不支持，忽略即可
                        }
                    }
                }
                activity.startActivityForResult(intent, code)
            } catch (e: Exception) {
                waiters.remove(seq)
                reply(id, JSONObject().put("ok", true).put("data", fail(e)))
            }
        }
    }

    private fun mimeFromFilters(filters: JSONArray?): String {
        val first = filters?.optJSONObject(0) ?: return "*/*"
        val exts = first.optJSONArray("extensions") ?: return "*/*"
        if (exts.length() != 1) return "*/*"
        return FileStore.mimeFor("x." + exts.optString(0, ""))
    }

    private fun resolvePick(id: String, cmd: String, uri: Uri?) {
        android.util.Log.d("FleshOutIpc", "resolvePick cmd=$cmd uri=$uri")
        if (uri == null) {
            reply(id, JSONObject().put("ok", true).put("data", JSONObject.NULL))
            return
        }
        try {
            if (cmd == "dialog:save") {
                // 交给 fs:write_text_file 用 contentResolver 写入
                reply(id, JSONObject().put("ok", true).put("data", uri.toString()))
            } else {
                val name = FileStore.displayName(App.ctx, uri)
                val dir = File(App.ctx.cacheDir, "picked").apply { mkdirs() }
                var target = File(dir, Util.safeName(name))
                val srcSize = FileStore.sizeOf(App.ctx, uri)
                if (target.exists() && srcSize > 0 && target.length() == srcSize) {
                    reply(id, JSONObject().put("ok", true).put("data", target.absolutePath))
                    return
                }
                if (target.exists()) {
                    val base = target.nameWithoutExtension
                    val ext = target.extension
                    var i = 2
                    while (target.exists()) {
                        target = File(dir, if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext")
                        i++
                    }
                }
                App.ctx.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { out -> input.copyTo(out) }
                } ?: throw IllegalStateException("无法读取所选文件")
                reply(id, JSONObject().put("ok", true).put("data", target.absolutePath))
            }
        } catch (e: Exception) {
            reply(id, JSONObject().put("ok", true).put("data", fail(e)))
        }
    }

    /** MainActivity.onActivityResult 转发到这里 */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        android.util.Log.d(
            "FleshOutIpc",
            "onActivityResult req=$requestCode result=$resultCode uri=${data?.data} clip=${data?.clipData?.itemCount}"
        )
        val cb = waiters.remove(requestCode)
        if (cb == null) {
            android.util.Log.w("FleshOutIpc", "no waiter for requestCode $requestCode")
            return
        }
        if (resultCode != Activity.RESULT_OK) {
            cb(null)
            return
        }
        val uri = data?.data
        if (uri != null) {
            cb(uri)
            return
        }
        val clip = data?.clipData
        if (clip != null && clip.itemCount > 0) {
            cb(clip.getItemAt(0).uri)
            return
        }
        cb(null)
    }

    // ------------------------------------------------------------------ 回复

    private fun reply(id: String, body: JSONObject) {
        val json = body.toString()
        main.post {
            val js = "window.__FO_RESOLVE(" + JSONObject.quote(id) + "," + JSONObject.quote(json) + ");"
            try {
                App.webView?.evaluateJavascript(js, null)
            } catch (_: Throwable) {
            }
        }
    }

    private fun ok(data: Any?): JSONObject = JSONObject().put("success", true).put("data", data ?: JSONObject.NULL)

    private fun fail(e: Exception, code: String? = null): JSONObject {
        val c = code ?: when (e) {
            is AiException -> e.code
            is IllegalArgumentException -> "INVALID_ARGUMENT"
            else -> "UNKNOWN"
        }
        return JSONObject()
            .put("success", false)
            .put("error", JSONObject().put("message", e.message ?: "操作失败").put("code", c))
    }

    // ------------------------------------------------------------------ JSON 辅助

    private fun JSONObject.str(key: String): String {
        val v = optString(key, "")
        if (v.isEmpty()) throw IllegalArgumentException("缺少参数: $key")
        return v
    }

    private fun JSONObject.stringList(key: String): List<String> {
        val arr = optJSONArray(key) ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) out.add(arr.optString(i, ""))
        return out.filter { it.isNotEmpty() }
    }
}
