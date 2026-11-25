package com.fleshout.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 手动模式：流式对话 + 工具调用。
 *
 * 事件统一走 `manual:stream`，载荷类型与前端 ManualChatPanel 的 handleStreamEvent 一致：
 *  content / tool_call / tool_result / done / error。
 */
object Manual {

    private const val MAX_TOOL_ROUNDS = 8
    private const val HISTORY_LIMIT = 30

    // ------------------------------------------------------------------ 系统提示词

    private const val SYSTEM_TEMPLATE =
        "你是小说手动改写助手。\n\n" +
            "## 当前工作区\n" +
            "项目：{project_name}\n" +
            "总章节数：{total_chapters}\n\n" +
            "## 已打开的章节（当前可见）\n" +
            "第 {chapter_index} 章 - {chapter_title}\n" +
            "字数：{word_count}\n\n" +
            "--- 章节内容（已加载）---\n" +
            "{chapter_content}\n" +
            "--- 内容结束 ---\n\n" +
            "## 可用操作\n" +
            "- write_chapter: 修改当前章节内容（支持插入、替换）\n" +
            "- switch_chapter: 切换到其他章节\n" +
            "- read_chapter: 读取其他章节内容（用于了解上下文）\n" +
            "- get_project_info: 获取项目信息和章节列表\n\n" +
            "## 重要提示\n" +
            "当前章节的内容已经在上方显示，你可以直接阅读和修改，无需再次读取。\n" +
            "只有在需要了解其他章节（如前后章节）时，才使用 read_chapter。\n\n" +
            "用户现在可以直接给你修改指令。"

    private fun tools(): JSONArray = JSONArray().apply {
        put(
            tool(
                "write_chapter",
                "【核心功能】生成并保存手动改写后的章节内容。\n\n" +
                    "这是你完成手动改写任务的唯一方式。当用户要求改写时，你必须：\n" +
                    "1. 生成完整的改写后的章节内容\n" +
                    "2. 调用此工具保存内容\n\n" +
                    "支持三种操作：\n" +
                    "1. replace_all - 替换整个章节内容（改写时使用此操作）\n" +
                    "2. insert - 在指定位置插入新内容\n" +
                    "3. replace_range - 替换指定段落范围\n\n" +
                    "重要：不要只返回文字说明，必须调用此工具来实际保存内容。",
                JSONObject()
                    .put("operation", prop("string", "操作类型，改写时使用 replace_all", JSONArray().put("replace_all").put("insert").put("replace_range")))
                    .put("content", prop("string", "完整的改写后的章节内容"))
                    .put("position", prop("string", "插入位置（仅 insert 操作），如 'after_paragraph_3' 或 'before_paragraph_5'"))
                    .put("startParagraph", prop("integer", "起始段落号（仅 replace_range 操作，从 1 开始）"))
                    .put("endParagraph", prop("integer", "结束段落号（仅 replace_range 操作，包含）")),
                JSONArray().put("operation").put("content")
            )
        )
        put(
            tool(
                "switch_chapter",
                "切换到指定章节进行操作。切换后，write_chapter 将操作新的章节。\n\n" +
                    "参数：\n" +
                    "- chapter: 章节标识，可以是：\n" +
                    "  - \"previous\" - 上一章（相对于当前章节）\n" +
                    "  - \"next\" - 下一章（相对于当前章节）\n" +
                    "  - 数字（如 10、20、35）- 直接指定章节号",
                JSONObject().put("chapter", prop("string", "章节标识：'previous'、'next' 或章节号")),
                JSONArray().put("chapter")
            )
        )
        put(
            tool(
                "read_chapter",
                "读取其他章节的内容（用于了解上下文）。\n\n" +
                    "注意：当前章节的内容已经在系统提示词中提供，无需使用此工具读取。\n" +
                    "此工具仅用于读取其他章节（如前后章节）以保持故事连贯性。\n\n" +
                    "参数：\n" +
                    "- chapter: 章节标识，可以是：\n" +
                    "  - \"previous\" - 上一章（相对于当前章节）\n" +
                    "  - \"next\" - 下一章（相对于当前章节）\n" +
                    "  - 数字（如 10、20、35）- 直接指定章节号\n\n" +
                    "不支持 \"current\"，因为当前章节已在上下文中。",
                JSONObject().put("chapter", prop("string", "章节标识：'previous'、'next' 或章节号（如 '100'）。不支持 'current'")),
                JSONArray().put("chapter")
            )
        )
        put(
            tool(
                "get_project_info",
                "获取项目信息和章节列表。返回项目名称、总章节数以及所有章节的标题和索引。",
                JSONObject(),
                JSONArray()
            )
        )
    }

    private fun tool(name: String, desc: String, props: JSONObject, required: JSONArray): JSONObject =
        JSONObject().put("type", "function").put(
            "function",
            JSONObject()
                .put("name", name)
                .put("description", desc)
                .put(
                    "parameters",
                    JSONObject()
                        .put("type", "object")
                        .put("properties", props)
                        .put("required", required)
                )
        )

    private fun prop(type: String, desc: String, enum: JSONArray? = null): JSONObject =
        JSONObject().put("type", type).put("description", desc).apply {
            if (enum != null) put("enum", enum)
        }

    // ------------------------------------------------------------------ 发送

    fun send(req: JSONObject) {
        val projectId = req.optString("projectId")
        val conversationId = req.optString("conversationId")
        val chapterId = req.optString("chapterId")
        val message = req.optString("message")
        val selectedText = req.optString("selectedText")

        if (projectId.isEmpty() || conversationId.isEmpty()) {
            App.emit("manual:stream", JSONObject().put("type", "error").put("message", "缺少项目或会话"))
            return
        }

        Thread {
            try {
                run(projectId, conversationId, chapterId, message, selectedText)
            } catch (e: Exception) {
                App.emit(
                    "manual:stream",
                    JSONObject().put("type", "error").put("message", e.message ?: "发送失败")
                )
            }
        }.start()
    }

    private fun run(
        projectId: String,
        conversationId: String,
        chapterId: String,
        message: String,
        selectedText: String
    ) {
        val repo = App.repo
        val project = repo.getProjectRow(projectId) ?: throw IllegalStateException("项目不存在")
        val cfg = PipelineEngine.aiConfig(projectId)

        // 用户消息入库
        repo.addMessage(conversationId, "user", message, chapterId.ifEmpty { null })

        var currentChapterId = chapterId
        var chapter = if (chapterId.isEmpty()) null else repo.getChapter(chapterId, includeContent = true)
        if (chapter == null) {
            val first = repo.listChapters(projectId, false).optJSONObject(0)
                ?: throw IllegalStateException("项目没有章节")
            currentChapterId = first.optString("id")
            chapter = repo.getChapter(currentChapterId, includeContent = true)
        }
        chapter ?: throw IllegalStateException("章节不存在")

        val allChapters = repo.listChapters(projectId, false)

        val systemText = buildSystem(project, allChapters, chapter, selectedText)
        val messages = ArrayList<AiMessage>()
        messages.add(AiMessage("system", systemText))

        val history = repo.listMessages(conversationId, HISTORY_LIMIT)
        for (i in 0 until history.length()) {
            val o = history.optJSONObject(i) ?: continue
            val role = o.optString("role", "")
            val content = o.optString("content", "")
            if (role != "user" && role != "assistant") continue
            if (role == "assistant" && content.isBlank()) continue
            messages.add(AiMessage(role, content))
        }

        val toolList = tools()
        val client = AiClient(cfg)
        val executedCalls = JSONArray()
        var answer = StringBuilder()

        for (round in 0 until MAX_TOOL_ROUNDS) {
            val roundText = StringBuilder()
            val result = client.chatStream(messages, toolList) { delta ->
                roundText.append(delta)
                App.emit("manual:stream", JSONObject().put("type", "content").put("content", delta))
            }

            val content = result.content.ifEmpty { roundText.toString() }
            answer.append(content)

            val calls = result.toolCalls
            if (calls.length() == 0) break

            messages.add(AiMessage("assistant", content, calls))

            for (i in 0 until calls.length()) {
                val call = calls.optJSONObject(i) ?: continue
                val fn = call.optJSONObject("function") ?: JSONObject()
                val id = call.optString("id", "call_$i")
                val name = fn.optString("name", "")
                val args = Util.jsonObjectOrEmpty(fn.optString("arguments", "{}"))

                App.emit(
                    "manual:stream",
                    JSONObject()
                        .put("type", "tool_call")
                        .put("toolCallId", id)
                        .put("toolName", name)
                        .put("arguments", args)
                )

                var resultText = ""
                var errorText = ""
                try {
                    val exec = execute(name, args, projectId, currentChapterId, allChapters)
                    resultText = exec.first
                    exec.second?.let { currentChapterId = it }
                } catch (e: Exception) {
                    errorText = e.message ?: "工具执行失败"
                }

                App.emit(
                    "manual:stream",
                    JSONObject()
                        .put("type", "tool_result")
                        .put("toolCallId", id)
                        .put("result", resultText)
                        .put("error", if (errorText.isEmpty()) JSONObject.NULL else errorText)
                )

                executedCalls.put(
                    JSONObject()
                        .put("id", id)
                        .put("name", name)
                        .put("arguments", args)
                        .put("status", if (errorText.isEmpty()) "success" else "failed")
                        .put("result", resultText)
                        .put("error", if (errorText.isEmpty()) JSONObject.NULL else errorText)
                )

                messages.add(
                    AiMessage(
                        role = "tool",
                        content = if (errorText.isEmpty()) resultText else "错误：$errorText",
                        toolCallId = id,
                        toolName = name
                    )
                )
            }
        }

        if (answer.toString().isNotBlank() || executedCalls.length() > 0) {
            repo.addMessage(
                conversationId,
                "assistant",
                answer.toString(),
                currentChapterId.ifEmpty { null },
                executedCalls.toString()
            )
            repo.touchConversation(conversationId)
        }

        App.emit("manual:stream", JSONObject().put("type", "done"))
    }

    // ------------------------------------------------------------------ 系统提示词构建

    private fun buildSystem(
        project: JSONObject,
        chapters: JSONArray,
        chapter: JSONObject,
        selectedText: String
    ): String {
        val content = chapter.optString("content", "")
        var text = SYSTEM_TEMPLATE
            .replace("{project_name}", project.optString("name", "未命名项目"))
            .replace("{total_chapters}", chapters.length().toString())
            .replace("{chapter_index}", chapter.optInt("index", 1).toString())
            .replace("{chapter_title}", chapter.optString("title", ""))
            .replace("{word_count}", chapter.optInt("wordCount", Util.wordCount(content)).toString())
            .replace("{chapter_content}", content)

        if (selectedText.isNotBlank()) {
            text += "\n\n## 用户选中的文本\n```text\n" + selectedText + "\n```\n" +
                "用户的指令针对上述选中的文本，修改时请聚焦这一段。"
        }
        return text
    }

    // ------------------------------------------------------------------ 工具执行

    /** 返回 (给模型的文字结果, 切换后的章节 id) */
    private fun execute(
        name: String,
        args: JSONObject,
        projectId: String,
        currentChapterId: String,
        chapters: JSONArray
    ): Pair<String, String?> {
        val repo = App.repo
        return when (name) {
            "write_chapter" -> writeChapter(args, projectId, currentChapterId) to null

            "switch_chapter" -> {
                val target = resolveChapter(args.optString("chapter", ""), currentChapterId, chapters)
                    ?: return "未找到目标章节：${args.optString("chapter")}" to null
                "已切换到第 ${target.optInt("index")} 章《${target.optString("title")}》" to target.optString("id")
            }

            "read_chapter" -> {
                val target = resolveChapter(args.optString("chapter", ""), currentChapterId, chapters)
                    ?: return "未找到目标章节：${args.optString("chapter")}" to null
                val full = repo.getChapter(target.optString("id"), includeContent = true)
                val index = target.optInt("index")
                val title = target.optString("title")
                val body = full?.optString("content", "").orEmpty()
                "第 $index 章 $title\n\n$body" to null
            }

            "get_project_info" -> {
                val project = repo.getProjectRow(projectId) ?: JSONObject()
                val sb = StringBuilder()
                sb.append("项目：").append(project.optString("name", "")).append("\n")
                sb.append("总章节数：").append(chapters.length()).append("\n\n")
                sb.append("章节列表：\n")
                for (i in 0 until chapters.length()) {
                    val c = chapters.optJSONObject(i) ?: continue
                    sb.append("- 第").append(c.optInt("index")).append("章 ")
                        .append(c.optString("title")).append("（").append(c.optInt("wordCount")).append(" 字）\n")
                }
                sb.toString() to null
            }

            else -> "未知工具：$name" to null
        }
    }

    private fun writeChapter(args: JSONObject, projectId: String, chapterId: String): String {
        val repo = App.repo
        val chapter = repo.getChapter(chapterId, includeContent = true)
            ?: throw IllegalStateException("当前章节不存在")
        val current = chapter.optString("content", "")
        val operation = args.optString("operation", "replace_all")
        val incoming = args.optString("content", "")

        val updated = when (operation) {
            "replace_all" -> incoming

            "insert" -> {
                val position = args.optString("position", "")
                val paras = current.split("\n").toMutableList()
                val after = position.startsWith("after_paragraph_")
                val n = position.substringAfterLast('_', "0").toIntOrNull() ?: 0
                val at = if (after) n else (n - 1).coerceAtLeast(0)
                val idx = at.coerceIn(0, paras.size)
                paras.add(idx, incoming)
                paras.joinToString("\n")
            }

            "replace_range" -> {
                val start = args.optInt("startParagraph", 1).coerceAtLeast(1)
                val end = args.optInt("endParagraph", start).coerceAtLeast(start)
                val paras = current.split("\n")
                if (start > paras.size) throw IllegalStateException("起始段落超出范围")
                val head = paras.take(start - 1)
                val tail = paras.drop(minOf(end, paras.size))
                (head + incoming.split("\n") + tail).joinToString("\n")
            }

            else -> throw IllegalStateException("不支持的操作类型：$operation")
        }

        repo.saveChapterContent(chapterId, updated)
        val wc = Util.wordCount(updated)
        return "已保存第 ${chapter.optInt("index")} 章《${chapter.optString("title")}》，当前 $wc 字。"
    }

    private fun resolveChapter(raw: String, currentChapterId: String, chapters: JSONArray): JSONObject? {
        if (raw.isBlank()) return null
        if (chapters.length() == 0) return null

        var currentPos = 0
        for (i in 0 until chapters.length()) {
            if (chapters.optJSONObject(i)?.optString("id") == currentChapterId) {
                currentPos = i
                break
            }
        }

        val key = raw.trim().lowercase()
        return when (key) {
            "current" -> chapters.optJSONObject(currentPos)
            "previous", "prev" -> chapters.optJSONObject((currentPos - 1).coerceAtLeast(0))
            "next" -> chapters.optJSONObject((currentPos + 1).coerceAtMost(chapters.length() - 1))
            else -> {
                val n = key.toIntOrNull() ?: return null
                for (i in 0 until chapters.length()) {
                    val c = chapters.optJSONObject(i) ?: continue
                    if (c.optInt("index") == n) return c
                }
                null
            }
        }
    }
}
