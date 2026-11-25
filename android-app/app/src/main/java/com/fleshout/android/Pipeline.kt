package com.fleshout.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 流水线引擎：与原桌面端一致的五阶段流程。
 * 阶段编号：1 拆分 / 2 总结 / 3 识别 / 4 改写 / 5 合并。
 */
object PipelineEngine {

    const val STAGE_COMPLETED = 0
    const val STAGE_EXTRACT = 1
    const val STAGE_SUMMARIZE = 2
    const val STAGE_IDENTIFY = 3
    const val STAGE_REWRITE = 4
    const val STAGE_MERGE = 5

    private val jobs = ConcurrentHashMap<String, Job>()

    class Job {
        @Volatile
        var cancelled = false
    }

    fun isRunning(projectId: String): Boolean = jobs.containsKey(projectId)

    fun cancel(projectId: String) {
        jobs[projectId]?.cancelled = true
        val p = App.repo.getProjectRow(projectId)
        App.repo.setProjectStatus(projectId, "pending", null)
        App.emit("pipeline-cancelled", JSONObject().put("projectId", projectId)
            .put("stage", p?.optInt("currentStage") ?: 0))
    }

    // ---------------------------------------------------------------- 启动

    fun start(projectId: String) {
        if (isRunning(projectId)) throw IllegalStateException("项目正在运行中")
        val job = Job()
        jobs[projectId] = job
        Thread {
            try {
                runAll(projectId, job)
            } catch (e: Exception) {
                fail(projectId, e)
            } finally {
                jobs.remove(projectId)
            }
        }.start()
    }

    private fun runAll(projectId: String, job: Job) {
        val project = App.repo.getProjectRow(projectId) ?: throw IllegalStateException("项目不存在")
        var stage = project.optInt("currentStage", STAGE_EXTRACT)
        if (stage <= STAGE_COMPLETED) stage = STAGE_EXTRACT
        App.repo.setProjectStatus(projectId, "running", stage)

        while (stage <= STAGE_MERGE) {
            if (job.cancelled) return
            App.repo.setCurrentStage(projectId, stage)
            App.emit("pipeline-stage-start", JSONObject().put("projectId", projectId).put("stage", stage))
            when (stage) {
                STAGE_EXTRACT -> runExtract(projectId, job)
                STAGE_SUMMARIZE -> runSummarize(projectId, job, null)
                STAGE_IDENTIFY -> runIdentify(projectId, job, null)
                STAGE_REWRITE -> runRewrite(projectId, job, null)
                STAGE_MERGE -> runMerge(projectId)
            }
            if (job.cancelled) return
            App.emit("pipeline-stage-complete", JSONObject().put("projectId", projectId).put("stage", stage))
            stage++
        }

        App.repo.setProjectStatus(projectId, "completed", STAGE_COMPLETED)
        App.emit("pipeline-project-status", JSONObject().put("projectId", projectId)
            .put("status", "completed").put("currentStage", STAGE_COMPLETED))
        App.emit("pipeline-complete", JSONObject().put("projectId", projectId))

        // 前端在跳转/挂载过程中可能错过这次状态，延迟补发，避免进度页卡在旧状态
        App.main.postDelayed({
            App.emit("pipeline-project-status", JSONObject().put("projectId", projectId)
                .put("status", "completed").put("currentStage", STAGE_COMPLETED))
            App.emit("pipeline-progress", JSONObject()
                .put("projectId", projectId)
                .put("stage", STAGE_COMPLETED)
                .put("status", "completed"))
        }, 800)
    }

    // ---------------------------------------------------------------- 阶段 1：拆分

    fun runExtract(projectId: String, job: Job) {
        val project = App.repo.getProjectRow(projectId) ?: throw IllegalStateException("项目不存在")
        val bookPath = project.optString("bookPath")
        if (bookPath.isEmpty()) throw IllegalStateException("书籍文件不存在")
        val format = project.optString("sourceFormat", "epub")
        val file = resolveBookFile(bookPath)
        if (!file.exists()) throw IllegalStateException("书籍文件不存在")

        val parsed = BookParser.parse(file, format)
        val ws = App.repo.workspace(project.optString("outputPath"))
        val chapters = ArrayList<JSONObject>()
        for (ch in parsed.chapters) {
            val id = Util.uuid()
            Util.writeText(File(ws, "chapters/${ch.index}.txt"), ch.content)
            chapters.add(JSONObject().apply {
                put("id", id)
                put("index", ch.index)
                put("title", ch.title)
                put("originalHref", ch.href)
                put("wordCount", ch.wordCount)
            })
        }
        if (chapters.isEmpty()) throw IllegalStateException("书籍文件中没有章节")
        App.repo.replaceChapters(projectId, chapters)

        val totalWords = chapters.sumOf { it.optInt("wordCount") }
        App.repo.saveBookMetadata(projectId, JSONObject().apply {
            put("title", if (parsed.title.isBlank()) project.optString("name") else parsed.title)
            put("author", parsed.author)
            put("publisher", parsed.publisher)
            put("language", parsed.language)
            put("description", parsed.description)
            put("totalChapters", chapters.size)
            put("totalWords", totalWords)
            put("fileSize", file.length())
            put("coverImage", parsed.coverImage ?: "")
        })
    }

    private fun resolveBookFile(path: String): File {
        if (FileStore.isContentUri(path)) return FileStore.importUri(App.ctx, path)
        return File(path)
    }

    // ---------------------------------------------------------------- 阶段 2：总结

    fun runSummarize(projectId: String, job: Job, chapterIds: List<String>?) {
        val cfg = aiConfig(projectId)
        val chapters = App.repo.listChapters(projectId, false)
        val targets = ArrayList<JSONObject>()
        for (i in 0 until chapters.length()) {
            val c = chapters.optJSONObject(i) ?: continue
            if (c.optBoolean("ignored")) continue
            if (chapterIds != null && !chapterIds.contains(c.optString("id"))) continue
            if (chapterIds == null && App.repo.getStageStatus(c.optString("id"), STAGE_SUMMARIZE)
                    .optString("status") == "completed") continue
            targets.add(c)
        }
        if (targets.isEmpty()) return

        val summaryPrompt = App.repo.resolveSummaryPrompt(projectId)
        val concurrency = projectConcurrency(projectId)
        val pool = Executors.newFixedThreadPool(concurrency)
        val counter = AtomicInteger(0)
        try {
            for (c in targets) {
                pool.submit {
                    if (job.cancelled) return@submit
                    val cid = c.optString("id")
                    try {
                        App.repo.setStageStatus(cid, STAGE_SUMMARIZE, "running")
                        emitProgress(projectId, c, STAGE_SUMMARIZE, "running")
                        val index = c.optInt("index")
                        val content = readChapterFile(projectId, index)
                        val title = c.optString("title")
                        val user = Prompts.render(summaryPrompt, mapOf(
                            "chapter_title" to title,
                            "chapter_content" to content
                        ))
                        val messages = listOf(
                            AiMessage("system", Prompts.SUMMARY_SYSTEM + Prompts.JSON_WARN),
                            AiMessage("user", user)
                        )
                        val result = AiClient(cfg).chat(messages)
                        val json = Prompts.extractJson(result.content)
                            ?: throw IllegalStateException("JSON 解析失败")
                        val text = Prompts.summaryToText(json)
                        val ws = App.repo.workspace(projectPath(projectId))
                        Util.writeText(File(ws, "summaries/$index.json"), json.toString())
                        App.repo.setSummary(cid, text, false)
                        App.repo.setStageStatus(cid, STAGE_SUMMARIZE, "completed")
                        emitProgress(projectId, c, STAGE_SUMMARIZE, "completed")
                        counter.incrementAndGet()
                    } catch (e: Exception) {
                        App.repo.setStageStatus(cid, STAGE_SUMMARIZE, "error", e.message)
                        emitProgress(projectId, c, STAGE_SUMMARIZE, "error")
                        throw e
                    }
                }
            }
            pool.shutdown()
            pool.awaitTermination(12, TimeUnit.HOURS)
        } finally {
            pool.shutdownNow()
        }
    }

    // ---------------------------------------------------------------- 阶段 3：识别

    fun runIdentify(projectId: String, job: Job, chapterIds: List<String>?) {
        val cfg = aiConfig(projectId)
        val identifyPrompt = App.repo.resolveIdentifyPrompt(projectId)
        val categories = Prompts.parseCategories(identifyPrompt)
        val categoriesText = Prompts.categoriesText(categories)
        val chapters = App.repo.listChapters(projectId, false)
        val targets = ArrayList<JSONObject>()
        for (i in 0 until chapters.length()) {
            val c = chapters.optJSONObject(i) ?: continue
            if (c.optBoolean("ignored")) continue
            if (chapterIds != null && !chapterIds.contains(c.optString("id"))) continue
            if (chapterIds == null && App.repo.getStageStatus(c.optString("id"), STAGE_IDENTIFY)
                    .optString("status") == "completed") continue
            targets.add(c)
        }
        if (targets.isEmpty()) return

        val concurrency = projectConcurrency(projectId)
        val pool = Executors.newFixedThreadPool(concurrency)
        try {
            for (c in targets) {
                pool.submit {
                    if (job.cancelled) return@submit
                    val cid = c.optString("id")
                    try {
                        App.repo.setStageStatus(cid, STAGE_IDENTIFY, "running")
                        emitProgress(projectId, c, STAGE_IDENTIFY, "running")
                        val index = c.optInt("index")
                        val title = c.optString("title")
                        val summary = c.optString("summary")
                        val content = if (summary.isNotBlank()) summary else readChapterFile(projectId, index)
                        val user = Prompts.render(Prompts.IDENTIFY_USER, mapOf(
                            "rewrite_categories" to categoriesText,
                            "chapter_title" to title,
                            "chapter_content" to content
                        ))
                        val messages = listOf(
                            AiMessage("system", Prompts.JSON_WARN),
                            AiMessage("user", user)
                        )
                        val result = AiClient(cfg).chat(messages)
                        val json = Prompts.extractJson(result.content)
                            ?: throw IllegalStateException("JSON 解析失败")
                        val analysis = json.optJSONObject("analysis")
                        val hasTarget = analysis?.optBoolean("hasTargetContent", false) ?: false
                        val markers = analysis?.optJSONArray("markers") ?: JSONArray()
                        val ws = App.repo.workspace(projectPath(projectId))
                        Util.writeText(File(ws, "identify/$index.json"), json.toString())
                        App.repo.setSummary(cid, summary, hasTarget)
                        if (hasTarget) {
                            val first = markers.optJSONObject(0)
                            if (first != null) {
                                App.repo.saveRewriteReason(
                                    cid,
                                    first.optString("categoryId", ""),
                                    first.optString("expandDescription", "")
                                )
                            }
                        } else {
                            App.repo.clearRewriteReason(cid)
                        }
                        App.repo.setStageStatus(cid, STAGE_IDENTIFY, "completed")
                        emitProgress(projectId, c, STAGE_IDENTIFY, "completed")
                    } catch (e: Exception) {
                        App.repo.setStageStatus(cid, STAGE_IDENTIFY, "error", e.message)
                        emitProgress(projectId, c, STAGE_IDENTIFY, "error")
                        throw e
                    }
                }
            }
            pool.shutdown()
            pool.awaitTermination(12, TimeUnit.HOURS)
        } finally {
            pool.shutdownNow()
        }
    }

    // ---------------------------------------------------------------- 阶段 4：改写

    fun runRewrite(projectId: String, job: Job, chapterIds: List<String>?) {
        val cfg = aiConfig(projectId)
        val project = App.repo.getProjectRow(projectId) ?: throw IllegalStateException("项目不存在")
        val rewritePrompt = App.repo.resolveRewritePrompt(projectId)
        val breakthrough = App.repo.resolveBreakthroughPrompt(projectId)
        val expandWordCount = project.optInt("expandWordCount", 4000)
        val chapters = App.repo.listChapters(projectId, false)
        val targets = ArrayList<JSONObject>()
        for (i in 0 until chapters.length()) {
            val c = chapters.optJSONObject(i) ?: continue
            if (c.optBoolean("ignored")) continue
            if (!c.optBoolean("needsRewrite")) continue
            if (chapterIds != null && !chapterIds.contains(c.optString("id"))) continue
            targets.add(c)
        }
        if (targets.isEmpty()) return

        val concurrency = projectConcurrency(projectId)
        val pool = Executors.newFixedThreadPool(concurrency)
        try {
            for (c in targets) {
                pool.submit {
                    if (job.cancelled) return@submit
                    val cid = c.optString("id")
                    try {
                        App.repo.setStageStatus(cid, STAGE_REWRITE, "running")
                        emitProgress(projectId, c, STAGE_REWRITE, "running")
                        val index = c.optInt("index")
                        val original = readChapterFile(projectId, index)
                        if (original.isBlank()) throw IllegalStateException("原始内容不存在")
                        val ws = App.repo.workspace(projectPath(projectId))
                        val identifyFile = File(ws, "identify/$index.json")
                        val markers = if (identifyFile.exists()) {
                            Prompts.extractJson(identifyFile.readText(Charsets.UTF_8))
                                ?.optJSONObject("analysis")?.optJSONArray("markers") ?: JSONArray()
                        } else JSONArray()

                        val system = Prompts.buildRewriteSystem(rewritePrompt, breakthrough, markers, expandWordCount)
                        val user = Prompts.buildRewriteUser(original)
                        val messages = listOf(AiMessage("system", system), AiMessage("user", user))
                        var rewritten = applyRewrite(AiClient(cfg), messages, original)
                        if (rewritten.isBlank()) {
                            throw IllegalStateException("AI 返回内容为空")
                        }
                        Util.writeText(File(ws, "rewritten/$index.txt"), rewritten)
                        App.repo.setStageStatus(cid, STAGE_REWRITE, "completed")
                        emitProgress(projectId, c, STAGE_REWRITE, "completed")
                    } catch (e: Exception) {
                        App.repo.setStageStatus(cid, STAGE_REWRITE, "error", e.message)
                        emitProgress(projectId, c, STAGE_REWRITE, "error")
                        throw e
                    }
                }
            }
            pool.shutdown()
            pool.awaitTermination(12, TimeUnit.HOURS)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun applyRewrite(client: AiClient, messages: List<AiMessage>, original: String): String {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                val result = client.chat(messages)
                if (Prompts.isRefusal(result.content)) {
                    throw IllegalStateException("AI 拒绝执行请求")
                }
                val json = Prompts.extractJson(result.content)
                    ?: throw IllegalStateException("ParseFailed")
                val anchor = json.optString("anchor", "")
                val expanded = json.optString("expanded", "")
                if (anchor.isEmpty()) throw IllegalStateException("EmptyAnchor")
                if (expanded.isEmpty()) throw IllegalStateException("EmptyExpanded")
                val idx = original.indexOf(anchor)
                if (idx < 0) throw IllegalStateException("AnchorNotFound")
                return original.substring(0, idx) + expanded + original.substring(idx + anchor.length)
            } catch (e: Exception) {
                lastError = e
                if (e is AiException) throw e
            }
        }
        throw lastError ?: IllegalStateException("改写失败")
    }

    // ---------------------------------------------------------------- 阶段 5：合并

    fun runMerge(projectId: String) {
        val path = App.repo.exportProject(projectId)
        val project = App.repo.getProjectRow(projectId)
        // 成品自动落到公共 Download 目录
        val out = File(path)
        if (out.exists()) {
            val saved = FileStore.exportToDownloads(App.ctx, out)
            App.emit("pipeline-export", JSONObject().put("projectId", projectId).put("downloadPath", saved))
        }
        val chapters = App.repo.listChapters(projectId, false)
        for (i in 0 until chapters.length()) {
            val c = chapters.optJSONObject(i) ?: continue
            App.repo.setStageStatus(c.optString("id"), STAGE_MERGE, "completed")
            emitProgress(projectId, c, STAGE_MERGE, "completed")
        }
        App.emit("pipeline-stage-complete", JSONObject().put("projectId", projectId)
            .put("stage", STAGE_MERGE).put("output", path)
            .put("projectName", project?.optString("name") ?: ""))
    }

    // ---------------------------------------------------------------- 重试 / 单章

    fun regenerateSummary(projectId: String, chapterIds: List<String>) {
        val job = Job()
        jobs[projectId] = job
        Thread {
            try {
                App.repo.setProjectStatus(projectId, "running", STAGE_SUMMARIZE)
                runSummarize(projectId, job, chapterIds)
                runIdentify(projectId, job, chapterIds)
                App.emit("pipeline-stage-complete", JSONObject().put("projectId", projectId).put("stage", STAGE_SUMMARIZE))
            } catch (e: Exception) {
                fail(projectId, e)
            } finally {
                App.repo.setProjectStatus(projectId, "pending", null)
                jobs.remove(projectId)
            }
        }.start()
    }

    fun regenerateRewrite(projectId: String, chapterIds: List<String>) {
        val job = Job()
        jobs[projectId] = job
        Thread {
            try {
                App.repo.setProjectStatus(projectId, "running", STAGE_REWRITE)
                runRewrite(projectId, job, chapterIds)
                App.emit("pipeline-stage-complete", JSONObject().put("projectId", projectId).put("stage", STAGE_REWRITE))
            } catch (e: Exception) {
                fail(projectId, e)
            } finally {
                App.repo.setProjectStatus(projectId, "pending", null)
                jobs.remove(projectId)
            }
        }.start()
    }

    fun retryFailed(projectId: String, summaryOnly: Boolean, rewriteOnly: Boolean) {
        val job = Job()
        jobs[projectId] = job
        Thread {
            var summaryRetried = 0
            var rewriteRetried = 0
            try {
                if (!rewriteOnly) {
                    val failed = App.repo.chaptersByStage(projectId, STAGE_SUMMARIZE, "error")
                    if (failed.isNotEmpty()) {
                        runSummarize(projectId, job, failed.map { it.optString("id") })
                        summaryRetried = failed.size
                    }
                }
                if (!summaryOnly) {
                    val failed = App.repo.chaptersByStage(projectId, STAGE_REWRITE, "error")
                    if (failed.isNotEmpty()) {
                        runRewrite(projectId, job, failed.map { it.optString("id") })
                        rewriteRetried = failed.size
                    }
                }
                App.emit("pipeline-retry-complete", JSONObject().put("projectId", projectId)
                    .put("summaryRetried", summaryRetried).put("rewriteRetried", rewriteRetried))
            } catch (e: Exception) {
                fail(projectId, e)
            } finally {
                jobs.remove(projectId)
            }
        }.start()
    }

    // ---------------------------------------------------------------- 清理

    fun cleanupAfterStage(projectId: String, targetStage: Int) {
        val ws = App.repo.workspace(projectPath(projectId))
        if (targetStage <= STAGE_SUMMARIZE) File(ws, "summaries").deleteRecursively()
        if (targetStage <= STAGE_IDENTIFY) File(ws, "identify").deleteRecursively()
        if (targetStage <= STAGE_REWRITE) File(ws, "rewritten").deleteRecursively()
        if (targetStage <= STAGE_MERGE) File(ws, "output").deleteRecursively()
        val chapters = App.repo.listChapters(projectId, false)
        for (i in 0 until chapters.length()) {
            val c = chapters.optJSONObject(i) ?: continue
            val cid = c.optString("id")
            if (targetStage <= STAGE_SUMMARIZE) {
                App.repo.clearSummary(cid)
                App.repo.setStageStatus(cid, STAGE_SUMMARIZE, "pending")
            }
            if (targetStage <= STAGE_IDENTIFY) App.repo.setStageStatus(cid, STAGE_IDENTIFY, "pending")
            if (targetStage <= STAGE_REWRITE) App.repo.setStageStatus(cid, STAGE_REWRITE, "pending")
            if (targetStage <= STAGE_MERGE) App.repo.setStageStatus(cid, STAGE_MERGE, "pending")
        }
    }

    fun cleanupCurrentStage(projectId: String, stage: Int) {
        val ws = App.repo.workspace(projectPath(projectId))
        when (stage) {
            STAGE_SUMMARIZE -> File(ws, "summaries").deleteRecursively()
            STAGE_IDENTIFY -> File(ws, "identify").deleteRecursively()
            STAGE_REWRITE -> File(ws, "rewritten").deleteRecursively()
            STAGE_MERGE -> File(ws, "output").deleteRecursively()
        }
        val chapters = App.repo.listChapters(projectId, false)
        for (i in 0 until chapters.length()) {
            val c = chapters.optJSONObject(i) ?: continue
            val cid = c.optString("id")
            App.repo.setStageStatus(cid, stage, "pending")
            if (stage == STAGE_SUMMARIZE) App.repo.clearSummary(cid)
        }
    }

    // ---------------------------------------------------------------- 工具

    private fun projectPath(projectId: String): String =
        App.repo.getProjectRow(projectId)?.optString("outputPath").orEmpty()

    private fun projectConcurrency(projectId: String): Int {
        val n = App.repo.getProjectRow(projectId)?.optInt("concurrency", 3) ?: 3
        return if (n <= 0) 1 else minOf(n, 8)
    }

    private fun readChapterFile(projectId: String, index: Int): String {
        val ws = App.repo.workspace(projectPath(projectId))
        val f = File(ws, "chapters/$index.txt")
        return if (f.exists()) f.readText(Charsets.UTF_8) else ""
    }

    private fun emitProgress(projectId: String, chapter: JSONObject, stage: Int, status: String) {
        App.repo.setStageStatusByProject(projectId, stage, "", status)
        App.emit("pipeline-progress", JSONObject().apply {
            put("projectId", projectId)
            put("chapterId", chapter.optString("id"))
            put("chapterTitle", chapter.optString("title"))
            put("stage", stage)
            put("status", status)
        })
        App.emit("pipeline-chapter-status", JSONObject().apply {
            put("projectId", projectId)
            put("chapterId", chapter.optString("id"))
            put("chapterTitle", chapter.optString("title"))
            put("stage", stage)
            put("status", status)
            put("timestamp", Util.nowSql())
        })
    }

    fun aiConfig(projectId: String): AiConfig {
        val project = App.repo.getProjectRow(projectId)
        var modelId = project?.optString("modelId").orEmpty()
        if (modelId.isEmpty()) modelId = App.repo.getDefaultModelId().orEmpty()
        if (modelId.isEmpty()) throw AiException("请先配置 AI 模型", "MODEL_NOT_FOUND")
        val model = App.repo.getAiModel(modelId)
            ?: throw AiException("AI 模型不存在", "MODEL_NOT_FOUND")
        return AiConfig.fromJson(model)
    }

    private fun fail(projectId: String, e: Exception) {
        val code = when (e) {
            is AiException -> e.code
            else -> "UNKNOWN"
        }
        val global = code in listOf(
            "INVALID_API_KEY", "QUOTA_EXCEEDED", "RATE_LIMIT", "MODEL_NOT_FOUND",
            "SERVICE_UNAVAILABLE", "CONNECTION_ERROR"
        )
        val stage = App.repo.getProjectRow(projectId)?.optInt("currentStage") ?: 0
        val message = e.message ?: "未知错误"
        App.repo.setProjectError(projectId, stage, message, if (global) "GLOBAL_ERROR" else code, "error")
        App.emit("pipeline-error", JSONObject().apply {
            put("projectId", projectId)
            put("message", message)
            put("code", if (global) "GLOBAL_ERROR" else code)
            put("stage", stage)
        })
        if (global) {
            App.emit("pipeline-global-error", JSONObject().apply {
                put("projectId", projectId)
                put("message", message)
                put("code", code)
                put("stage", stage)
                put("retryable", true)
                put("timestamp", Util.nowSql())
            })
        }
    }
}
