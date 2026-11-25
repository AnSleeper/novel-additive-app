package com.fleshout.android

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 数据访问层：与原 Rust 后端的仓储层行为保持一致。
 */
class Repo(context: android.content.Context) {

    private val helper = Db(context)

    fun db(): SQLiteDatabase = helper.writableDatabase

    // ---------------------------------------------------------------- 工作区

    fun workspace(outputPath: String): File = File(outputPath, ".fleshout-workspace").apply {
        mkdirs()
        File(this, "chapters").mkdirs()
        File(this, "rewritten").mkdirs()
        File(this, "summaries").mkdirs()
        File(this, "identify").mkdirs()
        File(this, "output").mkdirs()
    }

    fun chapterFile(outputPath: String, index: Int): File =
        File(workspace(outputPath), "chapters/${index}.txt")

    fun rewrittenFile(outputPath: String, index: Int): File =
        File(workspace(outputPath), "rewritten/${index}.txt")

    fun summaryFile(outputPath: String, index: Int): File =
        File(workspace(outputPath), "summaries/${index}.json")

    fun identifyFile(outputPath: String, index: Int): File =
        File(workspace(outputPath), "identify/${index}.json")

    // ---------------------------------------------------------------- 项目

    fun listProjects(): JSONArray {
        // 先收集 id 再逐条取详情：避免在 Cursor 未关闭时发起新查询（SQLite 连接池会阻塞）
        val ids = ArrayList<String>()
        db().rawQuery("SELECT id FROM projects ORDER BY updated_at DESC", null).use { c ->
            while (c.moveToNext()) ids.add(c.getString(0))
        }
        val arr = JSONArray()
        for (id in ids) getProjectRow(id)?.let { arr.put(it) }
        return arr
    }

    fun getProjectRow(id: String): JSONObject? {
        val o = db().rawQuery("SELECT * FROM projects WHERE id = ?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) projectRowJson(c) else null
        } ?: return null
        o.put("bookMetadata", getBookMetadata(id))
        o.put("customPrompts", getCustomPrompts(o.optString("customPromptsId").ifEmpty { null }))
        val errId = o.optString("errorInfoId")
        o.put("errorInfo", if (errId.isEmpty()) JSONObject.NULL else getProjectError(errId))
        return o
    }

    fun createProject(data: JSONObject): JSONObject {
        val id = Util.uuid()
        val name = data.optString("name", "未命名项目")
        val bookPath = data.optString("bookPath", "")
        val outputPath = data.optString("outputPath", "")
        val sourceFormat = data.optString("sourceFormat", "epub")
        val modelId = data.optString("modelId", "")
        val defaultMode = data.optString("defaultMode", "auto")
        val concurrency = data.optInt("concurrency", 3)
        val expandWordCount = data.optInt("expandWordCount", 1000)
        val now = Util.nowSql()

        val cv = ContentValues().apply {
            put("id", id)
            put("name", name)
            put("book_path", bookPath)
            put("output_path", outputPath)
            put("source_format", sourceFormat)
            put("model_id", modelId)
            put("current_stage", 1)
            put("status", "pending")
            put("default_mode", defaultMode)
            put("concurrency", concurrency)
            put("expand_word_count", expandWordCount)
            put("created_at", now)
            put("updated_at", now)
        }
        db().insert("projects", null, cv)

        val customPrompts = data.optJSONObject("customPrompts")
        if (customPrompts != null) {
            val cid = Util.uuid()
            val pcv = ContentValues().apply {
                put("id", cid)
                put("summary_prompt", customPrompts.optString("summaryPrompt", ""))
                put("rewrite_prompt", customPrompts.optString("rewritePrompt", ""))
                put("identify_prompt", customPrompts.optString("identifyPrompt", ""))
                put("breakthrough_prompt", customPrompts.optString("breakthroughPrompt", ""))
            }
            db().insert("project_custom_prompts", null, pcv)
            db().execSQL("UPDATE projects SET custom_prompts_id = ? WHERE id = ?", arrayOf(cid, id))
        }

        val metadata = data.optJSONObject("metadata")
        if (metadata != null) {
            saveBookMetadata(id, metadata)
        }

        val rules = data.optJSONObject("txtSplitRules")
        if (rules != null) saveTxtSplitRules(rules)

        return getProjectRow(id)!!
    }

    fun updateProject(id: String, data: JSONObject): JSONObject? {
        val cv = ContentValues()
        if (data.has("name")) cv.put("name", data.optString("name"))
        if (data.has("concurrency")) cv.put("concurrency", data.optInt("concurrency", 3))
        if (data.has("expandWordCount")) cv.put("expand_word_count", data.optInt("expandWordCount", 1000))
        if (data.has("modelId")) cv.put("model_id", data.optString("modelId"))
        if (data.has("defaultMode")) cv.put("default_mode", data.optString("defaultMode"))
        if (data.has("status")) cv.put("status", data.optString("status"))
        if (data.has("currentStage")) cv.put("current_stage", data.optInt("currentStage"))
        cv.put("updated_at", Util.nowSql())
        db().update("projects", cv, "id = ?", arrayOf(id))

        val customPrompts = data.optJSONObject("customPrompts")
        if (customPrompts != null) {
            val existing = getProjectRow(id)?.optString("customPromptsId").orEmpty()
            if (existing.isNotEmpty()) {
                val pcv = ContentValues().apply {
                    put("summary_prompt", customPrompts.optString("summaryPrompt", ""))
                    put("rewrite_prompt", customPrompts.optString("rewritePrompt", ""))
                    put("identify_prompt", customPrompts.optString("identifyPrompt", ""))
                    put("breakthrough_prompt", customPrompts.optString("breakthroughPrompt", ""))
                }
                db().update("project_custom_prompts", pcv, "id = ?", arrayOf(existing))
            } else {
                val cid = Util.uuid()
                val pcv = ContentValues().apply {
                    put("id", cid)
                    put("summary_prompt", customPrompts.optString("summaryPrompt", ""))
                    put("rewrite_prompt", customPrompts.optString("rewritePrompt", ""))
                    put("identify_prompt", customPrompts.optString("identifyPrompt", ""))
                    put("breakthrough_prompt", customPrompts.optString("breakthroughPrompt", ""))
                }
                db().insert("project_custom_prompts", null, pcv)
                db().execSQL("UPDATE projects SET custom_prompts_id = ? WHERE id = ?", arrayOf(cid, id))
            }
        }
        return getProjectRow(id)
    }

    fun deleteProject(id: String) {
        val p = getProjectRow(id)
        db().delete("chapter_rewrite_reasons", "chapter_id IN (SELECT id FROM chapters WHERE project_id = ?)", arrayOf(id))
        db().delete("chapter_stage_status", "chapter_id IN (SELECT id FROM chapters WHERE project_id = ?)", arrayOf(id))
        db().delete("chapters", "project_id = ?", arrayOf(id))
        db().delete("conversations", "project_id = ?", arrayOf(id))
        db().delete("book_metadata", "project_id = ?", arrayOf(id))
        db().delete("projects", "id = ?", arrayOf(id))
        if (p != null) {
            val cpid = p.optString("customPromptsId")
            if (cpid.isNotEmpty()) db().delete("project_custom_prompts", "id = ?", arrayOf(cpid))
            val out = p.optString("outputPath")
            if (out.isNotEmpty()) {
                File(out, ".fleshout-workspace").deleteRecursively()
            }
        }
    }

    fun setProjectStatus(id: String, status: String, currentStage: Int?) {
        val cv = ContentValues().apply {
            put("status", status)
            put("updated_at", Util.nowSql())
            if (status == "running") put("started_at", Util.nowSql())
            if (status == "completed") put("completed_at", Util.nowSql())
        }
        if (currentStage != null) cv.put("current_stage", currentStage)
        db().update("projects", cv, "id = ?", arrayOf(id))
    }

    fun setCurrentStage(id: String, stage: Int) {
        val cv = ContentValues().apply {
            put("current_stage", stage)
            put("updated_at", Util.nowSql())
        }
        db().update("projects", cv, "id = ?", arrayOf(id))
    }

    /** 只读 projects 表本身的列，不做任何关联查询 */
    private fun projectRowJson(c: Cursor): JSONObject {
        val o = JSONObject()
        o.put("id", c.getString(c.getColumnIndexOrThrow("id")))
        o.put("name", c.getString(c.getColumnIndexOrThrow("name")))
        o.put("bookPath", c.getString(c.getColumnIndexOrThrow("book_path")))
        o.put("outputPath", c.getString(c.getColumnIndexOrThrow("output_path")))
        o.put("sourceFormat", c.getString(c.getColumnIndexOrThrow("source_format")))
        o.put("modelId", c.getString(c.getColumnIndexOrThrow("model_id")))
        o.put("currentStage", c.getInt(c.getColumnIndexOrThrow("current_stage")))
        o.put("status", c.getString(c.getColumnIndexOrThrow("status")))
        o.put("defaultMode", c.getString(c.getColumnIndexOrThrow("default_mode")))
        o.put("concurrency", c.getInt(c.getColumnIndexOrThrow("concurrency")))
        o.put("expandWordCount", c.getInt(c.getColumnIndexOrThrow("expand_word_count")))
        o.put("errorInfoId", c.getString(c.getColumnIndexOrThrow("error_info_id")) ?: "")
        o.put("customPromptsId", c.getString(c.getColumnIndexOrThrow("custom_prompts_id")) ?: "")
        o.put("startedAt", c.getString(c.getColumnIndexOrThrow("started_at")))
        o.put("completedAt", c.getString(c.getColumnIndexOrThrow("completed_at")))
        o.put("createdAt", c.getString(c.getColumnIndexOrThrow("created_at")))
        o.put("updatedAt", c.getString(c.getColumnIndexOrThrow("updated_at")))
        return o
    }

    // ---------------------------------------------------------------- 书籍元数据

    fun saveBookMetadata(projectId: String, meta: JSONObject) {
        val cv = ContentValues().apply {
            put("project_id", projectId)
            put("title", meta.optString("title", "未命名"))
            put("author", meta.optString("author", ""))
            put("publisher", meta.optString("publisher", ""))
            put("language", meta.optString("language", ""))
            put("description", meta.optString("description", ""))
            put("total_chapters", meta.optInt("totalChapters", 0))
            put("total_words", meta.optInt("totalWords", 0))
            put("file_size", meta.optLong("fileSize", 0L))
            put("cover_image", meta.optString("coverImage", ""))
        }
        db().insertWithOnConflict("book_metadata", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getBookMetadata(projectId: String): JSONObject? {
        db().rawQuery("SELECT * FROM book_metadata WHERE project_id = ?", arrayOf(projectId)).use { c ->
            if (!c.moveToFirst()) return null
            return JSONObject().apply {
                put("projectId", projectId)
                put("title", c.getString(c.getColumnIndexOrThrow("title")))
                put("author", c.getString(c.getColumnIndexOrThrow("author")))
                put("publisher", c.getString(c.getColumnIndexOrThrow("publisher")))
                put("language", c.getString(c.getColumnIndexOrThrow("language")))
                put("description", c.getString(c.getColumnIndexOrThrow("description")))
                put("totalChapters", c.getInt(c.getColumnIndexOrThrow("total_chapters")))
                put("totalWords", c.getInt(c.getColumnIndexOrThrow("total_words")))
                put("fileSize", c.getLong(c.getColumnIndexOrThrow("file_size")))
                put("coverImage", c.getString(c.getColumnIndexOrThrow("cover_image")))
            }
        }
    }

    // ---------------------------------------------------------------- 章节

    fun replaceChapters(projectId: String, chapters: List<JSONObject>) {
        db().beginTransaction()
        try {
            db().delete("chapter_stage_status", "chapter_id IN (SELECT id FROM chapters WHERE project_id = ?)", arrayOf(projectId))
            db().delete("chapters", "project_id = ?", arrayOf(projectId))
            val now = Util.nowSql()
            for (ch in chapters) {
                val cv = ContentValues().apply {
                    put("id", ch.getString("id"))
                    put("project_id", projectId)
                    put("chapter_index", ch.getInt("index"))
                    put("title", ch.getString("title"))
                    put("original_href", ch.optString("originalHref", ""))
                    put("word_count", ch.optInt("wordCount", 0))
                    put("created_at", now)
                    put("updated_at", now)
                }
                db().insert("chapters", null, cv)
                for (stage in 1..5) {
                    insertStageStatus(ch.getString("id"), stage, "pending")
                }
            }
            db().setTransactionSuccessful()
        } finally {
            db().endTransaction()
        }
    }

    fun listChapters(projectId: String, includeContent: Boolean = false): JSONArray {
        val project = getProjectRow(projectId) ?: return JSONArray()
        val outputPath = project.optString("outputPath")
        val rows = ArrayList<JSONObject>()
        db().rawQuery("SELECT * FROM chapters WHERE project_id = ? ORDER BY chapter_index ASC", arrayOf(projectId)).use { c ->
            while (c.moveToNext()) rows.add(chapterRowJson(c))
        }
        val arr = JSONArray()
        for (r in rows) arr.put(fillChapter(r, outputPath, includeContent))
        return arr
    }

    fun getChapter(chapterId: String, includeContent: Boolean = false): JSONObject? {
        val row = db().rawQuery("SELECT * FROM chapters WHERE id = ?", arrayOf(chapterId)).use { c ->
            if (c.moveToFirst()) chapterRowJson(c) else null
        } ?: return null
        val outputPath = getProjectRow(row.optString("projectId"))?.optString("outputPath").orEmpty()
        return fillChapter(row, outputPath, includeContent)
    }

    /** 只读 chapters 表本身的列 */
    private fun chapterRowJson(c: Cursor): JSONObject {
        val o = JSONObject()
        o.put("id", c.getString(c.getColumnIndexOrThrow("id")))
        o.put("projectId", c.getString(c.getColumnIndexOrThrow("project_id")))
        o.put("index", c.getInt(c.getColumnIndexOrThrow("chapter_index")))
        o.put("title", c.getString(c.getColumnIndexOrThrow("title")))
        o.put("originalHref", c.getString(c.getColumnIndexOrThrow("original_href")))
        o.put("wordCount", c.getInt(c.getColumnIndexOrThrow("word_count")))
        o.put("summary", c.getString(c.getColumnIndexOrThrow("summary")) ?: "")
        o.put("needsRewrite", c.getInt(c.getColumnIndexOrThrow("needs_rewrite")) == 1)
        o.put("errorId", c.getString(c.getColumnIndexOrThrow("error_id")))
        o.put("createdAt", c.getString(c.getColumnIndexOrThrow("created_at")))
        o.put("updatedAt", c.getString(c.getColumnIndexOrThrow("updated_at")))
        return o
    }

    /** 补充文件内容与阶段状态（此时已无外层 Cursor） */
    private fun fillChapter(o: JSONObject, outputPath: String, includeContent: Boolean): JSONObject {
        val id = o.optString("id")
        val index = o.optInt("index")

        val rw = File(File(outputPath, ".fleshout-workspace/rewritten"), "$index.txt")
        o.put("rewrittenContent", if (rw.exists()) rw.readText(Charsets.UTF_8) else JSONObject.NULL)

        if (includeContent) {
            val cf = File(File(outputPath, ".fleshout-workspace/chapters"), "$index.txt")
            o.put("content", if (cf.exists()) cf.readText(Charsets.UTF_8) else "")
        }

        val stageStatus = JSONObject()
        var ignored = false
        db().rawQuery("SELECT * FROM chapter_stage_status WHERE chapter_id = ?", arrayOf(id)).use { sc ->
            while (sc.moveToNext()) {
                val stage = sc.getInt(sc.getColumnIndexOrThrow("stage"))
                val ign = sc.getInt(sc.getColumnIndexOrThrow("ignored")) == 1
                if (ign) ignored = true
                stageStatus.put(
                    stage.toString(),
                    JSONObject().apply {
                        put("status", sc.getString(sc.getColumnIndexOrThrow("status")))
                        put("ignored", ign)
                        put("errorInfo", sc.getString(sc.getColumnIndexOrThrow("error_info")) ?: JSONObject.NULL)
                    }
                )
            }
        }
        o.put("stageStatus", stageStatus)
        o.put("ignored", ignored)

        var reason: String? = null
        db().rawQuery("SELECT rewrite_suggestion FROM chapter_rewrite_reasons WHERE chapter_id = ? LIMIT 1", arrayOf(id)).use { rc ->
            if (rc.moveToFirst()) reason = rc.getString(0)
        }
        o.put("rewriteReason", reason ?: JSONObject.NULL)
        return o
    }

    fun getOriginalContent(chapterId: String): String {
        val ch = getChapter(chapterId) ?: return ""
        val project = getProjectRow(ch.optString("projectId")) ?: return ""
        val f = File(File(project.optString("outputPath"), ".fleshout-workspace/chapters"), "${ch.getInt("index")}.txt")
        return if (f.exists()) f.readText(Charsets.UTF_8) else ""
    }

    fun saveRewrittenContent(chapterId: String, content: String) {
        val ch = getChapter(chapterId) ?: return
        val project = getProjectRow(ch.optString("projectId")) ?: return
        val f = File(File(project.optString("outputPath"), ".fleshout-workspace/rewritten"), "${ch.getInt("index")}.txt")
        Util.writeText(f, content)
        db().execSQL("UPDATE chapters SET needs_rewrite = 1, updated_at = ? WHERE id = ?", arrayOf(Util.nowSql(), chapterId))
    }

    fun restoreOriginal(chapterId: String) {
        val ch = getChapter(chapterId) ?: return
        val project = getProjectRow(ch.optString("projectId")) ?: return
        val f = File(File(project.optString("outputPath"), ".fleshout-workspace/rewritten"), "${ch.getInt("index")}.txt")
        if (f.exists()) f.delete()
        db().execSQL("UPDATE chapters SET needs_rewrite = 0, updated_at = ? WHERE id = ?", arrayOf(Util.nowSql(), chapterId))
        db().execSQL(
            "UPDATE chapter_stage_status SET status = 'pending', updated_at = ? WHERE chapter_id = ? AND stage = 4",
            arrayOf(Util.nowSql(), chapterId)
        )
    }

    fun setSummary(chapterId: String, summary: String, needsRewrite: Boolean) {
        db().execSQL(
            "UPDATE chapters SET summary = ?, needs_rewrite = ?, updated_at = ? WHERE id = ?",
            arrayOf(summary, if (needsRewrite) 1 else 0, Util.nowSql(), chapterId)
        )
    }

    fun clearSummary(chapterId: String) {
        db().execSQL(
            "UPDATE chapters SET summary = NULL, needs_rewrite = 0, updated_at = ? WHERE id = ?",
            arrayOf(Util.nowSql(), chapterId)
        )
        db().delete("chapter_rewrite_reasons", "chapter_id = ?", arrayOf(chapterId))
    }

    fun saveRewriteReason(chapterId: String, type: String, suggestion: String) {
        db().delete("chapter_rewrite_reasons", "chapter_id = ?", arrayOf(chapterId))
        val cv = ContentValues().apply {
            put("id", Util.uuid())
            put("chapter_id", chapterId)
            put("type", type)
            put("rewrite_suggestion", suggestion)
            put("created_at", Util.nowSql())
        }
        db().insert("chapter_rewrite_reasons", null, cv)
    }

    fun getRewriteReason(chapterId: String): JSONObject? {
        db().rawQuery("SELECT * FROM chapter_rewrite_reasons WHERE chapter_id = ? LIMIT 1", arrayOf(chapterId)).use { c ->
            if (!c.moveToFirst()) return null
            return JSONObject().apply {
                put("id", c.getString(c.getColumnIndexOrThrow("id")))
                put("chapterId", chapterId)
                put("type", c.getString(c.getColumnIndexOrThrow("type")))
                put("rewriteSuggestion", c.getString(c.getColumnIndexOrThrow("rewrite_suggestion")))
            }
        }
    }

    fun clearRewriteReason(chapterId: String) {
        db().delete("chapter_rewrite_reasons", "chapter_id = ?", arrayOf(chapterId))
    }

    // ---------------------------------------------------------------- 阶段状态

    fun insertStageStatus(chapterId: String, stage: Int, status: String) {
        val cv = ContentValues().apply {
            put("id", Util.uuid())
            put("chapter_id", chapterId)
            put("stage", stage)
            put("status", status)
            put("ignored", 0)
            put("created_at", Util.nowSql())
            put("updated_at", Util.nowSql())
        }
        db().insertWithOnConflict("chapter_stage_status", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun getStageStatus(chapterId: String, stage: Int): JSONObject {
        db().rawQuery(
            "SELECT status, ignored, error_info FROM chapter_stage_status WHERE chapter_id = ? AND stage = ?",
            arrayOf(chapterId, stage.toString())
        ).use { c ->
            if (!c.moveToFirst()) return JSONObject().put("status", "pending").put("ignored", false).put("errorInfo", JSONObject.NULL)
            return JSONObject().apply {
                put("status", c.getString(0))
                put("ignored", c.getInt(1) == 1)
                put("errorInfo", c.getString(2) ?: JSONObject.NULL)
            }
        }
    }

    fun setStageStatus(chapterId: String, stage: Int, status: String, errorInfo: String? = null) {
        insertStageStatus(chapterId, stage, "pending")
        db().execSQL(
            "UPDATE chapter_stage_status SET status = ?, error_info = ?, updated_at = ? WHERE chapter_id = ? AND stage = ?",
            arrayOf(status, errorInfo, Util.nowSql(), chapterId, stage.toString())
        )
    }

    fun setStageStatusByProject(projectId: String, stage: Int, fromStatus: String, toStatus: String) {
        db().execSQL(
            "UPDATE chapter_stage_status SET status = ?, updated_at = ? WHERE chapter_id IN (SELECT id FROM chapters WHERE project_id = ?) AND stage = ? AND status = ?",
            arrayOf(toStatus, Util.nowSql(), projectId, stage.toString(), fromStatus)
        )
    }

    /** 忽略 / 恢复某章节的某阶段 */
    fun setStageIgnored(chapterId: String, stage: Int, ignored: Boolean) {
        insertStageStatus(chapterId, stage, "pending")
        db().execSQL(
            "UPDATE chapter_stage_status SET ignored = ?, updated_at = ? WHERE chapter_id = ? AND stage = ?",
            arrayOf(if (ignored) 1 else 0, Util.nowSql(), chapterId, stage.toString())
        )
    }

    fun setNeedsRewrite(chapterId: String, needs: Boolean) {
        db().execSQL(
            "UPDATE chapters SET needs_rewrite = ?, updated_at = ? WHERE id = ?",
            arrayOf(if (needs) 1 else 0, Util.nowSql(), chapterId)
        )
    }

    fun chaptersByStage(projectId: String, stage: Int, status: String): List<JSONObject> {
        val list = ArrayList<JSONObject>()
        db().rawQuery(
            """
            SELECT c.id, c.chapter_index, c.title FROM chapters c
            JOIN chapter_stage_status css ON c.id = css.chapter_id
            WHERE c.project_id = ? AND css.stage = ? AND css.status = ?
            ORDER BY c.chapter_index ASC
            """.trimIndent(),
            arrayOf(projectId, stage.toString(), status)
        ).use { c ->
            while (c.moveToNext()) {
                list.add(JSONObject().put("id", c.getString(0)).put("index", c.getInt(1)).put("title", c.getString(2)))
            }
        }
        return list
    }

    // ---------------------------------------------------------------- 项目错误

    fun setProjectError(projectId: String, stage: Int, message: String, code: String, status: String) {
        val eid = Util.uuid()
        val cv = ContentValues().apply {
            put("id", eid)
            put("stage", stage)
            put("message", message)
            put("code", code)
            put("retryable", 0)
            put("timestamp", Util.nowSql())
        }
        db().insert("project_errors", null, cv)
        db().execSQL(
            "UPDATE projects SET error_info_id = ?, status = ?, updated_at = ? WHERE id = ?",
            arrayOf(eid, status, Util.nowSql(), projectId)
        )
    }

    fun clearProjectError(projectId: String) {
        db().execSQL("UPDATE projects SET error_info_id = NULL, updated_at = ? WHERE id = ?", arrayOf(Util.nowSql(), projectId))
    }

    private fun getProjectError(id: String): JSONObject? {
        db().rawQuery("SELECT * FROM project_errors WHERE id = ?", arrayOf(id)).use { c ->
            if (!c.moveToFirst()) return null
            return JSONObject().apply {
                put("id", id)
                put("stage", c.getInt(c.getColumnIndexOrThrow("stage")))
                put("message", c.getString(c.getColumnIndexOrThrow("message")))
                put("code", c.getString(c.getColumnIndexOrThrow("code")))
                put("retryable", c.getInt(c.getColumnIndexOrThrow("retryable")) == 1)
                put("timestamp", c.getString(c.getColumnIndexOrThrow("timestamp")))
            }
        }
    }

    // ---------------------------------------------------------------- AI 模型

    fun listAiModels(): JSONArray {
        val arr = JSONArray()
        db().rawQuery("SELECT * FROM ai_models ORDER BY created_at DESC", null).use { c ->
            while (c.moveToNext()) arr.put(modelJson(c))
        }
        return arr
    }

    fun getAiModel(id: String): JSONObject? {
        db().rawQuery("SELECT * FROM ai_models WHERE id = ?", arrayOf(id)).use { c ->
            if (!c.moveToFirst()) return null
            return modelJson(c)
        }
    }

    fun createAiModel(data: JSONObject): JSONObject {
        val id = Util.uuid()
        val cv = ContentValues().apply {
            put("id", id)
            put("name", data.optString("name"))
            put("provider", data.optString("provider", "custom"))
            put("api_key", data.optString("apiKey", ""))
            put("base_url", data.optString("baseUrl", ""))
            put("model", data.optString("model", ""))
            put("temperature", data.optDouble("temperature", 0.7))
            put("max_tokens", data.optInt("maxTokens", 4096))
            put("timeout", data.optInt("timeout", 300))
            put("created_at", Util.nowSql())
            put("updated_at", Util.nowSql())
        }
        db().insert("ai_models", null, cv)
        return getAiModel(id)!!
    }

    fun updateAiModel(id: String, data: JSONObject): JSONObject? {
        val cv = ContentValues().apply {
            if (data.has("name")) put("name", data.optString("name"))
            if (data.has("provider")) put("provider", data.optString("provider"))
            if (data.has("apiKey")) put("api_key", data.optString("apiKey"))
            if (data.has("baseUrl")) put("base_url", data.optString("baseUrl"))
            if (data.has("model")) put("model", data.optString("model"))
            if (data.has("temperature")) put("temperature", data.optDouble("temperature", 0.7))
            if (data.has("maxTokens")) put("max_tokens", data.optInt("maxTokens", 4096))
            if (data.has("timeout")) put("timeout", data.optInt("timeout", 300))
            put("updated_at", Util.nowSql())
        }
        db().update("ai_models", cv, "id = ?", arrayOf(id))
        return getAiModel(id)
    }

    fun deleteAiModel(id: String) {
        db().delete("ai_models", "id = ?", arrayOf(id))
    }

    private fun modelJson(c: Cursor): JSONObject = JSONObject().apply {
        put("id", c.getString(c.getColumnIndexOrThrow("id")))
        put("name", c.getString(c.getColumnIndexOrThrow("name")))
        put("provider", c.getString(c.getColumnIndexOrThrow("provider")))
        put("apiKey", c.getString(c.getColumnIndexOrThrow("api_key")))
        put("baseUrl", c.getString(c.getColumnIndexOrThrow("base_url")))
        put("model", c.getString(c.getColumnIndexOrThrow("model")))
        put("temperature", c.getDouble(c.getColumnIndexOrThrow("temperature")))
        put("maxTokens", c.getInt(c.getColumnIndexOrThrow("max_tokens")))
        put("timeout", c.getInt(c.getColumnIndexOrThrow("timeout")))
        put("createdAt", c.getString(c.getColumnIndexOrThrow("created_at")))
        put("updatedAt", c.getString(c.getColumnIndexOrThrow("updated_at")))
    }

    // ---------------------------------------------------------------- 配置

    fun getConfigValue(key: String): String? {
        db().rawQuery("SELECT value FROM app_config WHERE key = ?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return null
    }

    fun setConfigValue(key: String, value: String) {
        val cv = ContentValues().apply {
            put("key", key)
            put("value", value)
            put("updated_at", Util.nowSql())
        }
        db().insertWithOnConflict("app_config", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getDefaultModelId(): String? {
        val raw = getConfigValue("ai_settings") ?: return null
        return try {
            JSONObject(raw).optString("defaultModelId").ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    fun setDefaultModelId(id: String) {
        val existing = Util.jsonObjectOrEmpty(getConfigValue("ai_settings"))
        existing.put("defaultModelId", id)
        setConfigValue("ai_settings", existing.toString())
    }

    // ---------------------------------------------------------------- TXT 拆分规则

    fun getTxtSplitRules(): JSONObject {
        db().rawQuery("SELECT * FROM txt_split_rules WHERE id = 'default'", null).use { c ->
            if (!c.moveToFirst()) return defaultSplitRules()
            return JSONObject().apply {
                put("id", "default")
                put("mode", c.getString(c.getColumnIndexOrThrow("mode")))
                put("useLinePrefix", c.getInt(c.getColumnIndexOrThrow("use_line_prefix")) == 1)
                put("linePrefix", c.getString(c.getColumnIndexOrThrow("line_prefix")))
                put("numberFormat", c.getString(c.getColumnIndexOrThrow("number_format")))
                put("chapterSuffix", c.getString(c.getColumnIndexOrThrow("chapter_suffix")))
                put("additionalPattern", c.getString(c.getColumnIndexOrThrow("additional_pattern")) ?: "")
                put("customPattern", c.getString(c.getColumnIndexOrThrow("custom_pattern")) ?: "")
            }
        }
    }

    private fun defaultSplitRules(): JSONObject = JSONObject().apply {
        put("id", "default")
        put("mode", "simple")
        put("useLinePrefix", true)
        put("linePrefix", "第")
        put("numberFormat", "mixed")
        put("chapterSuffix", "章回卷节集部")
        put("additionalPattern", "^\\s*(序言|序卷|序[1-9]|序曲|楔子|前言|后记|尾声|番外|最终章)")
        put("customPattern", "^\\s*(\\d+)\\s+(.+)$")
    }

    fun saveTxtSplitRules(rules: JSONObject): JSONObject {
        val cv = ContentValues().apply {
            put("id", "default")
            put("mode", rules.optString("mode", "simple"))
            put("use_line_prefix", if (rules.optBoolean("useLinePrefix", true)) 1 else 0)
            put("line_prefix", rules.optString("linePrefix", "第"))
            put("number_format", rules.optString("numberFormat", "mixed"))
            put("chapter_suffix", rules.optString("chapterSuffix", "章回卷节集部"))
            put("additional_pattern", rules.optString("additionalPattern", ""))
            put("custom_pattern", rules.optString("customPattern", ""))
            put("updated_at", Util.nowSql())
        }
        db().insertWithOnConflict("txt_split_rules", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        return getTxtSplitRules()
    }

    // ---------------------------------------------------------------- 提示词模板

    fun listPromptTemplates(): JSONArray {
        val arr = JSONArray()
        db().rawQuery("SELECT * FROM prompt_templates ORDER BY created_at DESC", null).use { c ->
            while (c.moveToNext()) arr.put(templateJson(c))
        }
        return arr
    }

    fun getPromptTemplate(id: String): JSONObject? {
        db().rawQuery("SELECT * FROM prompt_templates WHERE id = ?", arrayOf(id)).use { c ->
            if (!c.moveToFirst()) return null
            return templateJson(c)
        }
    }

    fun findTemplateByName(name: String): JSONObject? {
        db().rawQuery("SELECT * FROM prompt_templates WHERE name = ? LIMIT 1", arrayOf(name)).use { c ->
            if (!c.moveToFirst()) return null
            return templateJson(c)
        }
    }

    fun createPromptTemplate(data: JSONObject): JSONObject {
        val id = data.optString("id").ifEmpty { Util.uuid() }
        val cv = ContentValues().apply {
            put("id", id)
            put("name", data.optString("name", "未命名模板"))
            put("summary_template", data.optString("summaryTemplate", ""))
            put("rewrite_template", data.optString("rewriteTemplate", ""))
            put("breakthrough_template", data.optString("breakthroughTemplate", ""))
            put("identify_template", data.optString("identifyTemplate", ""))
            put("created_at", Util.nowSql())
            put("updated_at", Util.nowSql())
        }
        db().insert("prompt_templates", null, cv)
        return getPromptTemplate(id)!!
    }

    fun updatePromptTemplate(id: String, data: JSONObject): JSONObject? {
        val cv = ContentValues().apply {
            if (data.has("name")) put("name", data.optString("name"))
            if (data.has("summaryTemplate")) put("summary_template", data.optString("summaryTemplate"))
            if (data.has("rewriteTemplate")) put("rewrite_template", data.optString("rewriteTemplate"))
            if (data.has("breakthroughTemplate")) put("breakthrough_template", data.optString("breakthroughTemplate"))
            if (data.has("identifyTemplate")) put("identify_template", data.optString("identifyTemplate"))
            put("updated_at", Util.nowSql())
        }
        db().update("prompt_templates", cv, "id = ?", arrayOf(id))
        return getPromptTemplate(id)
    }

    fun deletePromptTemplate(id: String) {
        db().delete("prompt_templates", "id = ?", arrayOf(id))
    }

    private fun templateJson(c: Cursor): JSONObject = JSONObject().apply {
        put("id", c.getString(c.getColumnIndexOrThrow("id")))
        put("name", c.getString(c.getColumnIndexOrThrow("name")))
        put("summaryTemplate", c.getString(c.getColumnIndexOrThrow("summary_template")))
        put("rewriteTemplate", c.getString(c.getColumnIndexOrThrow("rewrite_template")))
        put("breakthroughTemplate", c.getString(c.getColumnIndexOrThrow("breakthrough_template")))
        put("identifyTemplate", c.getString(c.getColumnIndexOrThrow("identify_template")))
        put("createdAt", c.getString(c.getColumnIndexOrThrow("created_at")))
        put("updatedAt", c.getString(c.getColumnIndexOrThrow("updated_at")))
    }

    // ---------------------------------------------------------------- 项目自定义提示词

    fun getCustomPrompts(id: String?): JSONObject? {
        if (id.isNullOrEmpty()) return null
        db().rawQuery("SELECT * FROM project_custom_prompts WHERE id = ?", arrayOf(id)).use { c ->
            if (!c.moveToFirst()) return null
            return JSONObject().apply {
                put("id", id)
                put("summaryPrompt", c.getString(c.getColumnIndexOrThrow("summary_prompt")))
                put("rewritePrompt", c.getString(c.getColumnIndexOrThrow("rewrite_prompt")))
                put("identifyPrompt", c.getString(c.getColumnIndexOrThrow("identify_prompt")))
                put("breakthroughPrompt", c.getString(c.getColumnIndexOrThrow("breakthrough_prompt")))
            }
        }
    }

    // ---------------------------------------------------------------- 会话 / 消息

    fun listConversations(projectId: String): JSONArray {
        val arr = JSONArray()
        db().rawQuery("SELECT * FROM conversations WHERE project_id = ? ORDER BY updated_at DESC", arrayOf(projectId)).use { c ->
            while (c.moveToNext()) {
                arr.put(
                    JSONObject().apply {
                        put("id", c.getString(c.getColumnIndexOrThrow("id")))
                        put("projectId", projectId)
                        put("name", c.getString(c.getColumnIndexOrThrow("name")))
                        put("createdAt", c.getLong(c.getColumnIndexOrThrow("created_at")))
                        put("updatedAt", c.getLong(c.getColumnIndexOrThrow("updated_at")))
                    }
                )
            }
        }
        return arr
    }

    fun createConversation(projectId: String, name: String): JSONObject {
        val id = Util.uuid()
        val now = Util.nowEpochSeconds()
        val cv = ContentValues().apply {
            put("id", id)
            put("project_id", projectId)
            put("name", name)
            put("created_at", now)
            put("updated_at", now)
        }
        db().insert("conversations", null, cv)
        return JSONObject().put("id", id).put("projectId", projectId).put("name", name)
            .put("createdAt", now).put("updatedAt", now)
    }

    fun deleteConversation(id: String) {
        db().delete("messages", "conversation_id = ?", arrayOf(id))
        db().delete("conversations", "id = ?", arrayOf(id))
    }

    fun touchConversation(id: String) {
        db().execSQL("UPDATE conversations SET updated_at = ? WHERE id = ?", arrayOf(Util.nowEpochSeconds(), id))
    }

    fun addMessage(
        conversationId: String,
        role: String,
        content: String,
        chapterId: String?,
        toolCalls: String? = null
    ): JSONObject {
        val id = Util.uuid()
        val ts = Util.nowEpochSeconds()
        val cv = ContentValues().apply {
            put("id", id)
            put("conversation_id", conversationId)
            put("role", role)
            put("content", content)
            put("chapter_id", chapterId)
            put("timestamp", ts)
            put("tool_calls", toolCalls)
        }
        db().insert("messages", null, cv)
        touchConversation(conversationId)
        return JSONObject().apply {
            put("id", id)
            put("conversationId", conversationId)
            put("role", role)
            put("content", content)
            put("chapterId", chapterId ?: JSONObject.NULL)
            put("timestamp", ts)
            put("toolCalls", if (toolCalls == null) JSONObject.NULL else toolCalls)
        }
    }

    fun listMessages(conversationId: String, limit: Int?): JSONArray {
        val arr = JSONArray()
        val sql = if (limit != null && limit > 0)
            "SELECT * FROM messages WHERE conversation_id = ? ORDER BY timestamp DESC LIMIT ?"
        else
            "SELECT * FROM messages WHERE conversation_id = ? ORDER BY timestamp DESC"
        val args = if (limit != null && limit > 0) arrayOf(conversationId, limit.toString()) else arrayOf(conversationId)
        db().rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                arr.put(
                    JSONObject().apply {
                        put("id", c.getString(c.getColumnIndexOrThrow("id")))
                        put("conversationId", conversationId)
                        put("role", c.getString(c.getColumnIndexOrThrow("role")))
                        put("content", c.getString(c.getColumnIndexOrThrow("content")))
                        put("chapterId", c.getString(c.getColumnIndexOrThrow("chapter_id")) ?: JSONObject.NULL)
                        put("timestamp", c.getLong(c.getColumnIndexOrThrow("timestamp")))
                        put("toolCalls", c.getString(c.getColumnIndexOrThrow("tool_calls")) ?: JSONObject.NULL)
                    }
                )
            }
        }
        // 反转成时间正序
        val ordered = JSONArray()
        for (i in arr.length() - 1 downTo 0) ordered.put(arr.get(i))
        return ordered
    }

    // ---------------------------------------------------------------- 手动模式

    /** 手动模式保存章节正文：覆盖章节文件，并记录一份首次原文备份 */
    fun saveChapterContent(chapterId: String, content: String) {
        val ch = getChapter(chapterId) ?: return
        val project = getProjectRow(ch.optString("projectId")) ?: return
        val index = ch.getInt("index")
        val ws = workspace(project.optString("outputPath"))
        val file = File(ws, "chapters/$index.txt")
        val backup = File(ws, "chapters/orig_$index.txt")
        if (!backup.exists() && file.exists()) {
            backup.parentFile?.mkdirs()
            backup.writeText(file.readText(Charsets.UTF_8), Charsets.UTF_8)
        }
        Util.writeText(file, content)
        // 写入改写文件作为「已手动修改」标记
        Util.writeText(File(ws, "rewritten/$index.txt"), content)
        db().execSQL(
            "UPDATE chapters SET word_count = ?, needs_rewrite = 1, updated_at = ? WHERE id = ?",
            arrayOf(Util.wordCount(content), Util.nowSql(), chapterId)
        )
    }

    fun restoreManualChapter(chapterId: String) {
        val ch = getChapter(chapterId) ?: return
        val project = getProjectRow(ch.optString("projectId")) ?: return
        val index = ch.getInt("index")
        val ws = workspace(project.optString("outputPath"))
        val backup = File(ws, "chapters/orig_$index.txt")
        val file = File(ws, "chapters/$index.txt")
        if (backup.exists()) {
            file.writeText(backup.readText(Charsets.UTF_8), Charsets.UTF_8)
            backup.delete()
        }
        val rw = File(ws, "rewritten/$index.txt")
        if (rw.exists()) rw.delete()
        db().execSQL(
            "UPDATE chapters SET word_count = ?, needs_rewrite = 0, updated_at = ? WHERE id = ?",
            arrayOf(Util.wordCount(if (file.exists()) file.readText(Charsets.UTF_8) else ""), Util.nowSql(), chapterId)
        )
    }

    fun chapterContentByIndex(projectId: String, index: Int): String {
        val project = getProjectRow(projectId) ?: return ""
        val f = File(workspace(project.optString("outputPath")), "chapters/$index.txt")
        return if (f.exists()) f.readText(Charsets.UTF_8) else ""
    }

    fun listManualChapters(projectId: String): JSONArray = listChapters(projectId, includeContent = true)

    fun manualProjectInfo(projectId: String, currentChapterIndex: Int): JSONObject {
        val project = getProjectRow(projectId) ?: JSONObject()
        val all = listChapters(projectId, false)
        val chapters = JSONArray()
        val rewritten = JSONArray()
        for (i in 0 until all.length()) {
            val c = all.optJSONObject(i) ?: continue
            chapters.put(JSONObject().put("index", c.optInt("index")).put("title", c.optString("title")))
            val rw = c.opt("rewrittenContent")
            if (rw != null && rw != JSONObject.NULL) {
                rewritten.put(JSONObject().put("index", c.optInt("index")).put("title", c.optString("title")))
            }
        }
        return JSONObject().apply {
            put("projectId", projectId)
            put("name", project.optString("name"))
            put("totalChapters", all.length())
            put("currentChapterIndex", currentChapterIndex)
            put("chapters", chapters)
            put("rewrittenChapters", rewritten)
        }
    }

    // ---------------------------------------------------------------- 提示词取值

    fun getCustomPromptField(projectId: String, field: String): String? {
        val project = getProjectRow(projectId) ?: return null
        val cp = getCustomPrompts(project.optString("customPromptsId")) ?: return null
        val v = cp.optString(field, "")
        return if (v.isBlank()) null else v
    }

    fun resolveSummaryPrompt(projectId: String): String =
        getCustomPromptField(projectId, "summaryPrompt") ?: Prompts.defaultSummaryPrompt()

    fun resolveIdentifyPrompt(projectId: String): String =
        getCustomPromptField(projectId, "identifyPrompt") ?: Prompts.defaultIdentifyPrompt()

    fun resolveRewritePrompt(projectId: String): String =
        getCustomPromptField(projectId, "rewritePrompt") ?: Prompts.defaultRewritePrompt()

    fun resolveBreakthroughPrompt(projectId: String): String =
        getCustomPromptField(projectId, "breakthroughPrompt") ?: ""

    // ---------------------------------------------------------------- 导出

    fun exportProject(projectId: String): String {
        val project = getProjectRow(projectId) ?: throw IllegalStateException("项目不存在")
        val outputPath = project.optString("outputPath")
        if (outputPath.isEmpty()) throw IllegalStateException("项目未设置工作目录")
        val ws = workspace(outputPath)
        val chapters = listChapters(projectId, false)
        if (chapters.length() == 0) throw IllegalStateException("No chapters found to merge")
        val sb = StringBuilder()
        val manual = project.optString("defaultMode") == "manual"
        for (i in 0 until chapters.length()) {
            val c = chapters.optJSONObject(i) ?: continue
            val index = c.optInt("index")
            val title = c.optString("title")
            var body: String? = null
            if (manual) {
                val f = File(ws, "chapters/$index.txt")
                if (f.exists()) body = f.readText(Charsets.UTF_8)
            } else {
                val rw = File(ws, "rewritten/$index.txt")
                if (rw.exists()) body = rw.readText(Charsets.UTF_8)
                else {
                    val f = File(ws, "chapters/$index.txt")
                    if (f.exists()) body = f.readText(Charsets.UTF_8)
                }
            }
            sb.append(title).append("\n\n")
            if (!body.isNullOrBlank()) sb.append(body.trim()).append("\n\n")
        }
        val ext = Util.extForFormat(project.optString("sourceFormat"))
        val outDir = File(ws, "output").apply { mkdirs() }
        val outFile = File(outDir, Util.safeName(project.optString("name", "未命名")) + "." + ext)
        Util.writeText(outFile, sb.toString().trim() + "\n")
        return outFile.absolutePath
    }

    fun exportChapterSummary(projectId: String): String {
        val project = getProjectRow(projectId) ?: throw IllegalStateException("项目不存在")
        val chapters = listChapters(projectId, false)
        val sb = StringBuilder()
        sb.append("# 章节总结汇总\n\n")
        sb.append("生成时间: ").append(Util.nowSql()).append("\n\n")
        for (i in 0 until chapters.length()) {
            val c = chapters.optJSONObject(i) ?: continue
            if (c.optBoolean("ignored")) continue
            sb.append("## ").append(c.optString("title")).append("\n\n")
            val s = c.optString("summary", "")
            sb.append(if (s.isBlank()) "暂无总结" else s).append("\n\n")
        }
        return sb.toString()
    }
}
