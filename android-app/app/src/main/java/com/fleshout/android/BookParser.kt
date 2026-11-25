package com.fleshout.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 电子书解析：EPUB / TXT / MOBI / AZW3。
 * 与原桌面端一致，输出章节列表（含纯文本正文）。
 */
object BookParser {

    class Chapter(
        val index: Int,
        val title: String,
        val href: String,
        val content: String,
        val lineNumber: Int = 0
    ) {
        val wordCount: Int get() = Util.wordCount(content)
    }

    class Parsed(
        val title: String,
        val author: String,
        val publisher: String,
        val language: String,
        val description: String,
        val coverImage: String?,
        val chapters: List<Chapter>
    )

    fun parse(file: File, format: String): Parsed {
        return when (format.lowercase()) {
            "txt" -> parseTxt(file)
            "mobi", "azw3" -> parseMobi(file)
            else -> parseEpub(file)
        }
    }

    // ------------------------------------------------------------------ TXT

    fun readTextAuto(file: File): String {
        val bytes = file.readBytes()
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        // 先按 UTF-8 严格解码，失败再回退 GBK
        return try {
            val dec = Charsets.UTF_8.newDecoder()
            dec.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: Exception) {
            try {
                String(bytes, charset("GBK"))
            } catch (e2: Exception) {
                String(bytes, Charsets.ISO_8859_1)
            }
        }
    }

    fun parseTxt(file: File): Parsed {
        val text = readTextAuto(file)
        val chapters = splitTxt(text, Repo(App.ctx).getTxtSplitRules())
        val title = file.nameWithoutExtension
        return Parsed(title, "", "", "zh", "", null, chapters)
    }

    fun splitTxt(text: String, rules: JSONObject): List<Chapter> {
        // 智能识别：6 条正则 + 统一前置条件 + 兜底切分
        if (rules.optString("mode", "simple") == "smart") return splitSmart(text)

        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val mode = rules.optString("mode", "simple")
        val patterns = ArrayList<Regex>()

        if (mode != "custom") {
            if (rules.optBoolean("useLinePrefix", true)) {
                val prefix = Regex.escape(rules.optString("linePrefix", "第"))
                val numerals = when (rules.optString("numberFormat", "mixed")) {
                    "arabic", "number" -> "[0-9]+"
                    "chinese" -> "[零一二三四五六七八九十百千万〇]+"
                    else -> "[零一二三四五六七八九十百千万〇0-9]+"
                }
                val suffix = Regex.escape(rules.optString("chapterSuffix", "章回卷节集部"))
                patterns.add(
                    Regex(
                        "^\\s*$prefix\\s*$numerals\\s*[$suffix](?:[：:、.．，,\\-\\s]*.*)?(?:\\s|$)"
                    )
                )
            }
            val extra = rules.optString("additionalPattern", "")
            if (extra.isNotBlank()) {
                try {
                    patterns.add(Regex(extra))
                } catch (_: Exception) {
                }
            }
        } else {
            val custom = rules.optString("customPattern", "")
            if (custom.isNotBlank()) {
                try {
                    patterns.add(Regex(custom))
                } catch (_: Exception) {
                }
            }
        }

        val starts = ArrayList<Int>()
        for ((i, line) in lines.withIndex()) {
            if (line.isBlank()) continue
            if (patterns.any { it.containsMatchIn(line) }) starts.add(i)
        }

        val chapters = ArrayList<Chapter>()
        var idx = 1

        if (starts.isEmpty()) {
            val body = text.trim()
            if (body.isNotEmpty()) {
                chapters.add(Chapter(1, "全文", "txt", body))
            }
            return chapters
        }

        // 前言部分
        val pre = lines.subList(0, starts[0]).joinToString("\n").trim()
        if (pre.length >= 20) {
            chapters.add(Chapter(idx++, "前言", "txt", pre, 1))
        }

        for ((n, s) in starts.withIndex()) {
            val end = if (n + 1 < starts.size) starts[n + 1] else lines.size
            val seg = lines.subList(s, end)
            val titleLine = seg.firstOrNull()?.trim().orEmpty()
            val body = seg.joinToString("\n").trim()
            val title = titleLine.ifBlank { "第${idx}章" }
            chapters.add(Chapter(idx++, title, "txt", body, s + 1))
        }
        return chapters
    }

    // ------------------------------------------------------------------ 智能识别

    /**
     * 智能识别模式。
     *
     * 统一前置条件（所有规则共用）：
     *   - trim 后整行匹配
     *   - 行长 ≤ 78
     *   - 不以中文句末标点结尾
     *
     * 规则：
     *   1  第<数字><单位>     数字最多 12 位（半角/全角/中文），单位只认 章节回卷集幕话篇
     *   2  特殊篇名           序章/楔子/引子/前言/后记/尾声/终章/大结局/番外，序、序言单列
     *   3  Chapter N          阿拉伯数字或 I/V/X/L/C 罗马数字（1~8 位）
     *   4  数字+分隔符+标题   半角数字 1~4 位，分隔符 、 . ．
     *   5  (数字)标题         左括号可选，右括号必须
     *   6  EP+数字            如 EP0004
     */
    private val smartPatterns: List<Regex> = listOf(
        Regex("^第[0-9０-９零一二三四五六七八九十百千万两〇]{1,12}[章节回卷集幕话篇]([\\s\\u3000]+\\S.{0,60})?$"),
        Regex("^(序章|楔子|引子|前言|后记|尾声|终章|大结局|番外|序言)(.{0,60})$"),
        Regex("^序([\\s\\u3000].{0,60})?$"),
        Regex("^[Cc][Hh][Aa][Pp][Tt][Ee][Rr]\\s*([0-9]{1,8}|[IVXLCDMivxlcdm]{1,8})([\\s\\u3000]+\\S.{0,60})?$"),
        Regex("^[0-9]{1,4}[、.．]\\S.{0,60}$"),
        Regex("^[（(【\\[]?[0-9]{1,4}[）)】\\]].{0,60}$"),
        // 数字 + 冒号（中文网文常见的「001：标题」格式，实测样本即为此类）
        Regex("^[0-9]{1,4}[：:]\\s*\\S.{0,60}$"),
        Regex("^[Ee][Pp][\\s\\u3000.．·—:：-]*[0-9]{1,6}([\\s\\u3000]+\\S[^。！？]{0,50})?$")
    )

    private fun isSmartHeading(line: String): Boolean {
        if (line.length > 78) return false
        if (line.last() in "。，！？") return false
        return smartPatterns.any { it.matches(line) }
    }

    fun splitSmart(text: String): List<Chapter> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val marks = ArrayList<Int>()
        for ((i, raw) in lines.withIndex()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (isSmartHeading(line)) marks.add(i)
        }

        // 兜底：匹配不到 2 个章节标记就不切章
        if (marks.size < 2) return fallbackWhole(text)

        val chapters = ArrayList<Chapter>()
        var idx = 1

        val pre = lines.subList(0, marks[0]).joinToString("\n").trim()
        if (Util.wordCount(pre) > 60) {
            chapters.add(Chapter(idx++, "序", "txt", pre, 1))
        }

        for ((n, s) in marks.withIndex()) {
            val end = if (n + 1 < marks.size) marks[n + 1] else lines.size
            val seg = lines.subList(s, end)
            val title = seg.firstOrNull()?.trim().orEmpty().ifBlank { "第${idx}章" }
            val body = seg.joinToString("\n").trim()
            if (Util.wordCount(body) > 60000) {
                val parts = chunkByWordCount(body, 60000)
                for ((k, part) in parts.withIndex()) {
                    chapters.add(Chapter(idx++, "$title（${k + 1}）", "txt", part, s + 1))
                }
            } else {
                chapters.add(Chapter(idx++, title, "txt", body, s + 1))
            }
        }
        return chapters
    }

    /** 整本不切章；超过 6 万字按 6000 字一段切开 */
    private fun fallbackWhole(text: String): List<Chapter> {
        val body = text.trim()
        if (body.isEmpty()) return emptyList()
        if (Util.wordCount(body) <= 60000) {
            return listOf(Chapter(1, "正文", "txt", body, 1))
        }
        val out = ArrayList<Chapter>()
        for ((i, part) in chunkByWordCount(body, 6000).withIndex()) {
            out.add(Chapter(i + 1, "第${i + 1}节", "txt", part, 0))
        }
        return out
    }

    /** 按段落累积切块，尽量不切断段落 */
    private fun chunkByWordCount(text: String, limit: Int): List<String> {
        val paras = text.split("\n")
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var count = 0
        for (p in paras) {
            val w = Util.wordCount(p)
            if (count > 0 && count + w > limit) {
                out.add(sb.toString().trim())
                sb.setLength(0)
                count = 0
            }
            sb.append(p).append('\n')
            count += w
        }
        if (sb.isNotBlank()) out.add(sb.toString().trim())
        return out.filter { it.isNotEmpty() }
    }

    // ------------------------------------------------------------------ EPUB

    private fun parseEpub(file: File): Parsed {
        val zip = ZipFile(file)
        zip.use { z ->
            val container = z.getEntry("META-INF/container.xml")
            var opfPath: String? = null
            if (container != null) {
                val xml = z.getInputStream(container).readBytes().toString(Charsets.UTF_8)
                opfPath = Regex("full-path\\s*=\\s*\"([^\"]+)\"").find(xml)?.groupValues?.get(1)
            }
            if (opfPath == null) {
                opfPath = z.entries().asSequence()
                    .map { it.name }
                    .firstOrNull { it.endsWith(".opf") }
            }
            if (opfPath == null) throw IllegalStateException("未找到 EPUB 内容清单")

            val opfEntry = z.getEntry(opfPath) ?: throw IllegalStateException("EPUB 清单缺失")
            val opfBytes = z.getInputStream(opfEntry).readBytes()
            val opf = String(opfBytes, Charsets.UTF_8)
            val baseDir = opfPath.substringBeforeLast('/', "")

            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            val doc = factory.newDocumentBuilder().parse(opfBytes.inputStream())

            fun firstText(tag: String): String {
                val nodes = doc.getElementsByTagName(tag)
                return if (nodes.length > 0) nodes.item(0).textContent.trim() else ""
            }

            val title = firstText("dc:title").ifBlank { file.nameWithoutExtension }
            val author = firstText("dc:creator")
            val publisher = firstText("dc:publisher")
            val language = firstText("dc:language")
            val description = firstText("dc:description")

            // manifest
            val manifest = HashMap<String, Pair<String, String>>() // id -> href, mediaType
            val items = doc.getElementsByTagName("item")
            for (i in 0 until items.length) {
                val el = items.item(i)
                val id = el.attributes.getNamedItem("id")?.nodeValue ?: continue
                val href = el.attributes.getNamedItem("href")?.nodeValue ?: continue
                val media = el.attributes.getNamedItem("media-type")?.nodeValue ?: ""
                manifest[id] = href to media
            }

            // spine
            val spine = ArrayList<String>()
            val itemrefs = doc.getElementsByTagName("itemref")
            for (i in 0 until itemrefs.length) {
                val idref = itemrefs.item(i).attributes.getNamedItem("idref")?.nodeValue ?: continue
                spine.add(idref)
            }

            // cover
            var coverHref: String? = null
            val metas = doc.getElementsByTagName("meta")
            for (i in 0 until metas.length) {
                val el = metas.item(i)
                if (el.attributes.getNamedItem("name")?.nodeValue == "cover") {
                    val cid = el.attributes.getNamedItem("content")?.nodeValue
                    if (cid != null) coverHref = manifest[cid]?.first
                }
            }
            if (coverHref == null) {
                coverHref = manifest.values.firstOrNull {
                    it.second.startsWith("image") && it.first.contains("cover", true)
                }?.first
            }
            val coverImage = coverHref?.let { href ->
                try {
                    val entry = z.getEntry(resolve(baseDir, href))
                    if (entry != null) {
                        val bytes = z.getInputStream(entry).readBytes()
                        val mime = when {
                            href.endsWith(".png", true) -> "image/png"
                            href.endsWith(".gif", true) -> "image/gif"
                            else -> "image/jpeg"
                        }
                        "data:$mime;base64," + android.util.Base64.encodeToString(
                            bytes,
                            android.util.Base64.NO_WRAP
                        )
                    } else null
                } catch (e: Exception) {
                    null
                }
            }

            val chapters = ArrayList<Chapter>()
            var idx = 1
            for (idref in spine) {
                val info = manifest[idref] ?: continue
                val href = info.first
                val media = info.second
                if (!media.contains("html") && !media.contains("xml")) continue
                val path = resolve(baseDir, href)
                val entry = z.getEntry(path) ?: continue
                val raw = z.getInputStream(entry).readBytes()
                val html = decodeHtml(raw)
                val bodyText = htmlToText(html)
                if (bodyText.isBlank()) continue
                val chapterTitle = extractTitle(html).ifBlank { "第${idx}章" }
                chapters.add(Chapter(idx++, chapterTitle, href, bodyText))
            }

            if (chapters.isEmpty()) {
                throw IllegalStateException("EPUB 未解析出任何章节")
            }

            return Parsed(title, author, publisher, language, description, coverImage, chapters)
        }
    }

    private fun resolve(baseDir: String, href: String): String {
        val clean = href.substringBefore('#')
        if (baseDir.isEmpty()) return clean
        return "$baseDir/$clean"
    }

    private fun decodeHtml(bytes: ByteArray): String {
        val head = String(bytes, 0, minOf(bytes.size, 400), Charsets.ISO_8859_1)
        val m = Regex("charset\\s*=\\s*[\"']?([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE).find(head)
        val charsetName = m?.groupValues?.get(1) ?: "UTF-8"
        return try {
            String(bytes, charset(charsetName))
        } catch (e: Exception) {
            String(bytes, Charsets.UTF_8)
        }
    }

    private fun extractTitle(html: String): String {
        val opts = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        val h = Regex("<h[1-3][^>]*>(.*?)</h[1-3]>", opts).find(html)
        if (h != null) {
            val t = htmlToText(h.groupValues[1]).trim()
            if (t.isNotEmpty()) return t
        }
        val t = Regex("<title[^>]*>(.*?)</title>", opts).find(html)
        return t?.groupValues?.get(1)?.let { htmlToText(it) }?.trim().orEmpty()
    }

    fun htmlToText(html: String): String {
        var s = html
        s = Regex("(?is)<(script|style|head)[^>]*>.*?</\\1>").replace(s, "")
        s = Regex("(?i)<br\\s*/?>").replace(s, "\n")
        s = Regex("(?i)</(p|div|h[1-6]|li|tr|section|article|blockquote)>").replace(s, "\n")
        s = Regex("(?i)<mbp:pagebreak[^>]*>").replace(s, "\n")
        s = Regex("(?s)<[^>]+>").replace(s, "")
        s = s.replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&mdash;", "—")
            .replace("&ldquo;", "“")
            .replace("&rdquo;", "”")
            .replace("&hellip;", "…")
            .replace("&amp;", "&")
        s = Regex("&#(\\d+);").replace(s) { m ->
            try {
                String(Character.toChars(m.groupValues[1].toInt()))
            } catch (e: Exception) {
                m.value
            }
        }
        s = Regex("&#x([0-9a-fA-F]+);").replace(s) { m ->
            try {
                String(Character.toChars(m.groupValues[1].toInt(16)))
            } catch (e: Exception) {
                m.value
            }
        }
        // 规整空白
        val lines = s.replace("\r", "").split('\n').map { it.trim() }
        val sb = StringBuilder()
        var blank = 0
        for (line in lines) {
            if (line.isEmpty()) {
                blank++
                if (blank <= 1) sb.append('\n')
            } else {
                blank = 0
                sb.append(line).append('\n')
            }
        }
        return sb.toString().trim()
    }

    // ------------------------------------------------------------------ MOBI / AZW3

    private fun parseMobi(file: File): Parsed {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(78)
            raf.readFully(header)
            val name = String(header, 0, 32, Charsets.ISO_8859_1).trim('\u0000', ' ')
            val type = String(header, 60, 4, Charsets.ISO_8859_1)
            if (!type.startsWith("BOOK") && !type.startsWith("TEXt")) {
                // 可能仍是合法 mobi，继续尝试
            }
            val numRecords = ((header[76].toInt() and 0xFF) shl 8) or (header[77].toInt() and 0xFF)

            val offsets = LongArray(numRecords)
            for (i in 0 until numRecords) {
                val b = ByteArray(8)
                raf.readFully(b)
                offsets[i] = readU32(b, 0)
            }
            fun record(i: Int): ByteArray {
                val start = offsets[i]
                val end = if (i + 1 < numRecords) offsets[i + 1] else raf.length()
                val len = (end - start).toInt()
                val data = ByteArray(len)
                raf.seek(start)
                raf.readFully(data)
                return data
            }

            val rec0 = record(0)
            val compression = readU16(rec0, 0)
            val textLength = readU32(rec0, 4).toInt()
            val recordCount = readU16(rec0, 8)

            var encoding = 65001
            var fullName = name
            if (rec0.size > 0x18 && String(rec0, 16, 4, Charsets.ISO_8859_1) == "MOBI") {
                val mobiHeaderLen = readU32(rec0, 20).toInt()
                encoding = readU32(rec0, 28).toInt()
                val fullNameOffset = readU32(rec0, 84).toInt()
                val fullNameLength = readU32(rec0, 88).toInt()
                if (fullNameOffset > 0 && fullNameLength > 0 &&
                    fullNameOffset + fullNameLength <= rec0.size
                ) {
                    fullName = String(
                        rec0,
                        fullNameOffset,
                        fullNameLength,
                        if (encoding == 65001) Charsets.UTF_8 else charset("windows-1252")
                    )
                }
                if (mobiHeaderLen <= 0) { /* noop */
                }
            }

            val out = java.io.ByteArrayOutputStream()
            for (i in 1..recordCount) {
                if (i >= numRecords) break
                val rec = record(i)
                val data = when (compression) {
                    1 -> rec
                    2 -> palmDocDecompress(rec)
                    17480 -> throw IllegalStateException("暂不支持 HUFF/CDIC 压缩的 MOBI，请转换为 EPUB 或 TXT 后重试")
                    else -> rec
                }
                out.write(data)
            }

            var bytes = out.toByteArray()
            if (textLength in 1..bytes.size) bytes = bytes.copyOf(textLength)

            val cs = if (encoding == 65001) Charsets.UTF_8 else try {
                charset("windows-1252")
            } catch (e: Exception) {
                Charsets.ISO_8859_1
            }
            val raw = String(bytes, cs)

            // 按分页符拆章
            val parts = Regex("(?i)<mbp:pagebreak[^>]*>").split(raw)
            val chapters = ArrayList<Chapter>()
            var idx = 1
            if (parts.size > 1) {
                for (p in parts) {
                    val text = htmlToText(p)
                    if (text.isBlank()) continue
                    val t = extractTitle(p).ifBlank { "第${idx}章" }
                    chapters.add(Chapter(idx++, t, "mobi", text))
                }
            }
            if (chapters.isEmpty()) {
                val text = htmlToText(raw)
                val split = splitTxt(text, Repo(App.ctx).getTxtSplitRules())
                if (split.isEmpty()) {
                    if (text.isNotBlank()) chapters.add(Chapter(1, fullName, "mobi", text))
                } else {
                    chapters.addAll(split)
                }
            }

            return Parsed(fullName, "", "", "zh", "", null, chapters)
        }
    }

    private fun readU16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun readU32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or
            ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or
            (b[off + 3].toLong() and 0xFF)

    /** PalmDOC LZ77 解压 */
    private fun palmDocDecompress(input: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(input.size * 2)
        var i = 0
        while (i < input.size) {
            val c = input[i].toInt() and 0xFF
            i++
            when {
                c == 0 -> out.write(0)
                c in 1..8 -> {
                    for (k in 0 until c) {
                        if (i < input.size) out.write(input[i].toInt())
                        i++
                    }
                }
                c in 0x09..0x7F -> out.write(c)
                c in 0x80..0xBF -> {
                    if (i >= input.size) break
                    val c2 = input[i].toInt() and 0xFF
                    i++
                    val pair = (c shl 8) or c2
                    val distance = (pair shr 3) and 0x7FF
                    val length = (pair and 0x7) + 3
                    if (distance == 0) continue
                    val buf = out.toByteArray()
                    var src = buf.size - distance
                    for (k in 0 until length) {
                        val cur = out.toByteArray()
                        val pos = src + k
                        if (pos in 0 until cur.size) {
                            out.write(cur[pos].toInt())
                        } else {
                            out.write(0)
                        }
                    }
                }
                else -> {
                    out.write(' '.code)
                    out.write(c xor 0x80)
                }
            }
        }
        return out.toByteArray()
    }
}
