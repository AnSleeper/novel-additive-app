package com.fleshout.android

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File

/**
 * 安卓端文件系统适配：统一处理 content:// 与普通文件路径。
 * 工作区固定在应用外部私有目录，避免分区存储限制。
 */
object FileStore {

    fun root(ctx: Context): File {
        val base = ctx.getExternalFilesDir("FleshOut") ?: File(ctx.filesDir, "FleshOut")
        if (!base.exists()) base.mkdirs()
        return base
    }

    fun booksDir(ctx: Context): File = File(root(ctx), "books").apply { mkdirs() }

    fun defaultOutputDir(ctx: Context, name: String): File =
        File(booksDir(ctx), Util.safeName(name)).apply { mkdirs() }

    fun isContentUri(path: String?): Boolean = path != null && path.startsWith("content://")

    fun isFileUri(path: String?): Boolean = path != null && path.startsWith("file://")

    /** 把用户选择的文件复制到应用缓存，返回可读的普通文件 */
    fun importUri(ctx: Context, raw: String): File {
        if (!isContentUri(raw)) {
            val p = if (isFileUri(raw)) Uri.parse(raw).path.orEmpty() else raw
            return File(p)
        }
        val uri = Uri.parse(raw)
        val name = displayName(ctx, uri)
        val dir = File(ctx.cacheDir, "imports").apply { mkdirs() }
        var target = File(dir, Util.safeName(name))
        val srcSize = sizeOf(ctx, uri)
        if (target.exists() && srcSize > 0 && target.length() == srcSize) {
            // 同名同大小视为同一本书，直接复用，避免出现 "书名 (2).txt"
            return target
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
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { out -> input.copyTo(out) }
        }
        return target
    }

    fun sizeOf(ctx: Context, uri: Uri): Long {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx)
                }
            }
        } catch (_: Exception) {
        }
        return -1
    }

    fun displayName(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val n = c.getString(idx)
                        if (!n.isNullOrBlank()) return n
                    }
                }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment ?: "未命名"
    }

    fun readText(ctx: Context, path: String): String {
        if (isContentUri(path)) {
            val uri = Uri.parse(path)
            return ctx.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                String(bytes, Charsets.UTF_8)
            } ?: ""
        }
        val p = if (isFileUri(path)) Uri.parse(path).path.orEmpty() else path
        val f = File(p)
        if (!f.exists()) throw IllegalStateException("文件不存在: $p")
        return f.readText(Charsets.UTF_8)
    }

    fun writeText(ctx: Context, path: String, text: String) {
        if (isContentUri(path)) {
            val uri = Uri.parse(path)
            ctx.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
            } ?: throw IllegalStateException("无法写入目标文件")
            return
        }
        val p = if (isFileUri(path)) Uri.parse(path).path.orEmpty() else path
        val f = File(p)
        f.parentFile?.mkdirs()
        f.writeText(text, Charsets.UTF_8)
    }

    /**
     * 把导出产物复制到公共 Download 目录，用户能在文件管理器里直接看到。
     * Android 10+ 走 MediaStore，低版本写公共目录。
     */
    fun exportToDownloads(ctx: Context, file: File): String {
        if (!file.exists()) return ""
        val name = file.name
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mimeFor(name))
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return ""
            val written = try {
                ctx.contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { input -> input.copyTo(out) }
                } != null
            } catch (e: Exception) {
                false
            }
            if (!written) return ""
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            try {
                ctx.contentResolver.update(uri, values, null, null)
            } catch (_: Exception) {
            }
            uri.toString()
        } else {
            @Suppress("DEPRECATION")
            val target = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                name
            )
            try {
                file.copyTo(target, overwrite = true)
                target.absolutePath
            } catch (e: Exception) {
                ""
            }
        }
    }

    fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "txt" -> "text/plain"
        "epub" -> "application/epub+zip"
        "mobi" -> "application/x-mobipocket-ebook"
        "azw3" -> "application/vnd.amazon.ebook"
        "json" -> "application/json"
        "zip" -> "application/zip"
        "md" -> "text/markdown"
        else -> "*/*"
    }

    fun openFile(ctx: Context, file: File) {
        if (!file.exists()) throw IllegalStateException("文件不存在: ${file.absolutePath}")
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeFor(file.name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }

    fun shareFile(ctx: Context, file: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeFor(file.name)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "导出文件").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(chooser)
    }
}
