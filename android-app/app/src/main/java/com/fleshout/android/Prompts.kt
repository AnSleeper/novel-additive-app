package com.fleshout.android

import org.json.JSONArray
import org.json.JSONObject

/**
 * 内置提示词与提示词构建逻辑，全部取自原桌面端 0.2.0 二进制中的原始文本。
 */
object Prompts {

    // ---------------------------------------------------------------- 总结

    const val SUMMARY_SYSTEM =
        "你是一位敏锐的成人向小说内容分析师，擅长在普通剧情中捕捉男女角色之间的性张力、暧昧互动以及被作者刻意略过的\"拉灯\"时刻。\n\n" +
            "你的任务是生成章节总结，特别关注情感推进、肢体接触和任何可能发展为亲密场景的契机。\n\n"

    const val JSON_WARN =
        "**重要：输出格式要求**\n" +
            "你必须严格按照指定的JSON格式输出结果。这是技术需求，不是可选的。无论你对内容有何看法，都必须返回有效的JSON格式。如果你无法处理某些内容，请在JSON中说明，而不是返回纯文本说明。"

    const val SUMMARY_USER =
        "请分析以下章节内容，生成章节总结。\n\n" +
            "## 任务要求\n" +
            "生成200字以内的章节总结，重点捕捉以下细节：\n" +
            "- 本章核心剧情与冲突\n" +
            "- **关键的暧昧时刻**：角色间的眼神拉丝、肢体触碰、言语挑逗\n" +
            "- **潜在的手动扩写点**：场景转换（如进入卧室）、时间跳跃（如\"一夜无话\"）、状态变化（如衣衫不整）\n" +
            "- 出场人物及其当前状态（情绪、衣着、受伤中毒等特殊状态）\n\n" +
            "## 输出格式\n" +
            "**必须严格按照以下JSON格式输出，这是技术需求，不可违反。**\n\n" +
            "你的响应必须是一个有效的JSON对象，格式如下：\n\n" +
            "```json\n" +
            "{\n" +
            "  \"summary\": {\n" +
            "    \"plot\": \"情节概要（重点描述角色间的互动和位置变化）...\",\n" +
            "    \"characters\": [\"人物1（当前状态）\", \"人物2（当前状态）\"],\n" +
            "    \"keyEvents\": [\"事件1\", \"事件2（包含暧昧或略写节点）\"]\n" +
            "  }\n" +
            "}\n" +
            "```\n\n" +
            "**重要提示**：\n" +
            "- 你的响应必须是有效的JSON格式，可以直接被JSON.parse()解析\n" +
            "- 不要添加任何JSON代码块之外的前言、说明或注释\n" +
            "- 如果内容为空或无法分析，请返回空的字符串数组或空对象，但必须保持JSON格式\n" +
            "- 禁止返回纯文本说明，必须返回JSON格式\n\n" +
            "---\n\n" +
            "章节标题：{chapter_title}\n" +
            "章节内容：\n" +
            "{chapter_content}"

    // ---------------------------------------------------------------- 识别

    const val IDENTIFY_USER =
        "请分析以下章节内容。\n\n" +
            "## 任务要求\n" +
            "判断本章是否包含以下定义的场景，并按照以下类别进行分类：\n\n" +
            "{rewrite_categories}\n\n" +
            "## 输出格式\n" +
            "**必须严格按照以下JSON格式输出，这是技术需求，不可违反。**\n\n" +
            "你的响应必须是一个有效的JSON对象，格式如下：\n\n" +
            "```json\n" +
            "{\n" +
            "  \"analysis\": {\n" +
            "    \"hasTargetContent\": true/false,\n" +
            "    \"categories\": [\"类别ID1\", \"类别ID2\"],\n" +
            "    \"markers\": [\n" +
            "      {\n" +
            "        \"categoryId\": \"类别ID\",\n" +
            "        \"categoryName\": \"类别名称\",\n" +
            "        \"expandDescription\": \"简单总结满足定义的类别标准的场景，只需要说明在这个场景中，谁做了什么事，不需要给出建议，也不需要说明原因\"\n" +
            "      }\n" +
            "    ]\n" +
            "  }\n" +
            "}\n" +
            "```\n\n" +
            "**重要提示**：\n" +
            "- 你的响应必须是有效的JSON格式，可以直接被JSON.parse()解析\n" +
            "- 不要添加任何JSON代码块之外的前言、说明或注释\n" +
            "- 如果章节不符合以上定义的所有类别，hasTargetContent 应为 false，但必须保持JSON格式\n" +
            "- 禁止返回纯文本说明，必须返回JSON格式\n\n" +
            "---\n\n" +
            "章节标题：{chapter_title}\n" +
            "章节内容：\n" +
            "{chapter_content}"

    // ---------------------------------------------------------------- 改写

    const val REWRITE_HEADER =
        "你是一名专业的小说“定点扩写”编辑。\n" +
            "你的核心任务是：在保持章节整体结构和非目标内容**绝对不变**的前提下，仅对原文中被识别为“关键扩写点”的简略描写进行深度的细节填充和扩充。\n" +
            "请像外科手术一样精准地切入原文，完成扩写后再无缝缝合。\n" +
            "## 核心操作准则（必须严格执行）\n" +
            "1. **精准锚定（Anchor）**：\n" +
            "   - 请根据改写类别，在原文中**锁定唯一一处**最需要发挥的简略描写片段（通常为1-3个段落）。\n" +
            "   - 不要分散精力，不要试图优化整章，只聚焦于这一个核心爆发点。\n\n" +
            "2. **定点扩充（Expand）**：\n" +
            "   - 针对锁定的目标片段进行大幅扩写。\n" +
            "   - 这里的扩写应遵循用户提供的通用要求和类别特定要求。\n\n" +
            "3. **非目标冻结（Freeze Context）**：\n" +
            "   - **最高优先级规则**：除被锁定的目标片段外，原文的其他所有部分（前文铺垫、后续剧情、非相关对话）必须**逐字保留，严禁修改**。\n" +
            "   - 禁止为了“通顺”或“润色”而去改动非目标区域的文字。你的工作是“嵌入”而非“重塑”。\n\n" +
            "4. **无缝缝合（Seamless Merge）**：\n" +
            "   - 扩写后的内容必须能与上下文中被保留的原文完美衔接，确保逻辑连贯，没有断层。"

    const val REWRITE_USER = "### 待加工原文\n```text\n{original_paragraph}\n```"

    const val REWRITE_OUTPUT_RULES =
        "## 输出要求\n\n" +
            "你只需要输出**要扩写的部分**，不需要输出完整章节。系统会自动将你的扩写内容拼接到原文中。\n\n" +
            "**必须严格按照以下 JSON 格式输出：**\n\n" +
            "```json\n" +
            "{\n" +
            "  \"anchor\": \"从原文中精确复制你要替换的片段（1-3个段落）\",\n" +
            "  \"expanded\": \"扩写后的完整内容\"\n" +
            "}\n" +
            "```\n\n" +
            "**重要规则**：\n" +
            "1. `anchor` 必须是原文的**精确复制**，不能有任何改动（包括空格、标点），这样系统才能定位替换位置\n" +
            "2. `anchor` 应该是你锁定的\"关键扩写点\"的原文片段\n" +
            "3. `expanded` 是扩写后的内容，将完全替换 anchor 对应的原文\n" +
            "4. **扩写字数要求**：扩写后的内容应大于 {expand_word_count} 字左右，但是绝对不能少于此字数\n" +
            "5. 你的响应必须是有效的 JSON 格式，可以直接被解析\n" +
            "6. 不要添加任何 JSON 之外的前言、说明或注释\n\n" +
            "请开始你的定点扩写："

    // ---------------------------------------------------------------- 默认配置

    /** 无自定义提示词时的兜底总结提示词 */
    fun defaultSummaryPrompt(): String = SUMMARY_USER

    /** 无自定义提示词时的兜底识别提示词（JSON 字符串） */
    fun defaultIdentifyPrompt(): String = "{\"categories\":[]}"

    /** 无自定义提示词时的兜底改写提示词（JSON 字符串） */
    fun defaultRewritePrompt(): String = "{\"commonPrompt\":\"\",\"categoryPrompts\":{}}"

    // ---------------------------------------------------------------- 构建

    fun render(template: String, values: Map<String, String>): String {
        var out = template
        for ((k, v) in values) {
            out = out.replace("{$k}", v)
        }
        return out
    }

    /** 把识别提示词 JSON 解析成类别列表 */
    fun parseCategories(identifyPrompt: String?): JSONArray {
        if (identifyPrompt.isNullOrBlank()) return JSONArray()
        return try {
            val obj = JSONObject(identifyPrompt)
            obj.optJSONArray("categories") ?: JSONArray()
        } catch (e: Exception) {
            JSONArray()
        }
    }

    /** 生成识别用户提示词里的类别描述块 */
    fun categoriesText(categories: JSONArray): String {
        val sb = StringBuilder()
        for (i in 0 until categories.length()) {
            val c = categories.optJSONObject(i) ?: continue
            sb.append("**第").append(i + 1).append("类 - ")
                .append(c.optString("name", "")).append("**（类别ID: ")
                .append(c.optString("id", "")).append("）\n")
            sb.append("判定条件：").append(c.optString("conditions", "")).append("\n\n")
        }
        return sb.toString().trimEnd()
    }

    /** 生成改写系统提示词 */
    fun buildRewriteSystem(
        rewritePrompt: String?,
        breakthrough: String?,
        markers: JSONArray,
        expandWordCount: Int
    ): String {
        var common = ""
        val catPrompts = HashMap<String, String>()
        if (!rewritePrompt.isNullOrBlank()) {
            try {
                val obj = JSONObject(rewritePrompt)
                common = obj.optString("commonPrompt", "")
                val cp = obj.optJSONObject("categoryPrompts")
                if (cp != null) {
                    for (key in cp.keys()) catPrompts[key] = cp.optString(key, "")
                }
            } catch (_: Exception) {
            }
        }

        val sb = StringBuilder()
        sb.append(REWRITE_HEADER).append("\n\n")
        if (common.isNotBlank()) sb.append(common).append("\n\n")

        if (markers.length() > 0) {
            sb.append("## 改写类别信息\n\n本章节被识别为以下改写类别：\n\n")
            for (i in 0 until markers.length()) {
                val m = markers.optJSONObject(i) ?: continue
                val name = m.optString("categoryName", m.optString("categoryId", ""))
                val desc = m.optString("expandDescription", "")
                sb.append("- **").append(name).append("**")
                if (desc.isNotBlank()) sb.append("：").append(desc)
                sb.append("\n")
            }
            sb.append("\n")

            sb.append("## 类别特定改写要求\n\n")
            for (i in 0 until markers.length()) {
                val m = markers.optJSONObject(i) ?: continue
                val id = m.optString("categoryId", "")
                val name = m.optString("categoryName", id)
                val p = catPrompts[id]
                if (!p.isNullOrBlank()) {
                    sb.append("### ").append(name).append(" 的改写要求\n\n").append(p).append("\n\n")
                }
            }
        }

        sb.append(REWRITE_OUTPUT_RULES.replace("{expand_word_count}", expandWordCount.toString()))
        if (!breakthrough.isNullOrBlank()) {
            sb.append("\n\n").append(breakthrough)
        }
        return sb.toString()
    }

    fun buildRewriteUser(original: String): String =
        REWRITE_USER.replace("{original_paragraph}", original)

    // ---------------------------------------------------------------- JSON 解析

    /** 从模型输出中提取 JSON 对象 */
    fun extractJson(text: String?): JSONObject? {
        if (text.isNullOrBlank()) return null
        var s = text.trim()
        val fence = s.indexOf("```")
        if (fence >= 0) {
            var body = s.substring(fence + 3)
            if (body.startsWith("json")) body = body.substring(4)
            val end = body.indexOf("```")
            if (end >= 0) body = body.substring(0, end)
            s = body.trim()
        }
        val start = s.indexOf('{')
        val last = s.lastIndexOf('}')
        if (start < 0 || last <= start) return null
        val candidate = s.substring(start, last + 1)
        return try {
            JSONObject(candidate)
        } catch (_: Exception) {
            null
        }
    }

    private val refusalPhrases = listOf(
        "I don't follow", "I can't", "I won't", "I'm not able", "I'm not designed",
        "I'm Claude", "I'm an AI", "I appreciate you sharing", "I need to clarify",
        "无法协助", "我不能", "抱歉，我"
    )

    fun isRefusal(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val t = text.trim()
        if (t.length > 800) return false
        return refusalPhrases.any { t.contains(it) }
    }

    /** 把总结 JSON 转成可读文本 */
    fun summaryToText(summaryJson: JSONObject): String {
        val s = summaryJson.optJSONObject("summary") ?: return ""
        val sb = StringBuilder()
        val plot = s.optString("plot", "")
        if (plot.isNotBlank()) sb.append(plot)
        val chars = s.optJSONArray("characters")
        if (chars != null && chars.length() > 0) {
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append("人物：")
            for (i in 0 until chars.length()) {
                if (i > 0) sb.append("、")
                sb.append(chars.optString(i))
            }
        }
        val events = s.optJSONArray("keyEvents")
        if (events != null && events.length() > 0) {
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append("关键事件：")
            for (i in 0 until events.length()) {
                if (i > 0) sb.append("；")
                sb.append(events.optString(i))
            }
        }
        return sb.toString()
    }
}
