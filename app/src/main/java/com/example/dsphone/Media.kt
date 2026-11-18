package com.example.dsphone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.math.max

object Media {
    private val docExt = setOf("pdf", "doc", "docx", "ppt", "pptx")

    private fun displayName(ctx: Context, uri: Uri): String {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return uri.lastPathSegment ?: "file"
    }

    private fun attDir(ctx: Context) = File(ctx.filesDir, "att").apply { mkdirs() }

    fun mimeOfDoc(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        else -> "application/octet-stream"
    }

    /** 从系统选择器返回的 Uri 导入为本地附件。图片会缩放为 JPEG */
    fun importUri(ctx: Context, uri: Uri): Attach {
        val name = displayName(ctx, uri)
        val mime = ctx.contentResolver.getType(uri).orEmpty()
        val ext = name.substringAfterLast('.', "").lowercase()
        if (mime.startsWith("image/")) {
            val out = File(attDir(ctx), "${System.nanoTime()}.jpg")
            ctx.contentResolver.openInputStream(uri)!!.use { saveScaledJpeg(it.readBytes(), out, 2048) }
            return Attach("image", name, out.absolutePath, src = uri.toString())
        }
        val out = File(attDir(ctx), "${System.nanoTime()}_$name")
        ctx.contentResolver.openInputStream(uri)!!.use { i -> FileOutputStream(out).use { o -> i.copyTo(o) } }
        if (ext in docExt) {
            val a = Attach("doc", name, out.absolutePath, src = uri.toString())
            a.pages = if (ext == "pdf") pdfPages(out) else 1
            return a
        }
        // 其余当作纯文本(含 NUL 字节说明是二进制,拒绝)
        val head = out.inputStream().use { it.readNBytes(4096) }
        if (head.any { it.toInt() == 0 }) { out.delete(); throw IllegalArgumentException("不支持的文件类型:$name") }
        return Attach("text", name, out.absolutePath, src = uri.toString())
    }

    /** 从内存字节导入附件(聊天记录导入用)。无法识别的二进制文件会抛异常 */
    fun importBytes(ctx: Context, bytes: ByteArray, name: String, isImage: Boolean): Attach {
        if (isImage) {
            val out = File(attDir(ctx), "${System.nanoTime()}.jpg")
            saveScaledJpeg(bytes, out, 2048)
            return Attach("image", name, out.absolutePath)
        }
        val ext = name.substringAfterLast('.', "").lowercase()
        val out = File(attDir(ctx), "${System.nanoTime()}_${name.replace(Regex("[\\\\/:*?\"<>|]"), "_")}")
        out.writeBytes(bytes)
        if (ext in docExt) return Attach("doc", name, out.absolutePath).also { it.pages = if (ext == "pdf") pdfPages(out) else 1 }
        if (bytes.take(4096).any { it.toInt() == 0 }) { out.delete(); throw IllegalArgumentException("不支持的文件类型:$name") }
        return Attach("text", name, out.absolutePath)
    }

    /** 相机拍的照片(已写入 file) */
    fun importPhoto(ctx: Context, file: File): Attach {
        val out = File(attDir(ctx), "${System.nanoTime()}.jpg")
        saveScaledJpeg(file.readBytes(), out, 2048)
        file.delete()
        return Attach("image", "photo.jpg", out.absolutePath)
    }

    private fun saveScaledJpeg(bytes: ByteArray, dest: File, maxSide: Int) {
        val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bo)
        var sample = 1
        while (max(bo.outWidth, bo.outHeight) / sample > maxSide * 2) sample *= 2
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalArgumentException("无法解码图片")
        val orientation = runCatching { ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
        val deg = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val k = minOf(1f, maxSide.toFloat() / max(bmp.width, bmp.height))
        if (deg != 0f || k < 1f) {
            val m = Matrix().apply { postRotate(deg); postScale(k, k) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        FileOutputStream(dest).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    }

    /** 背景图:缩放后存到私有目录 */
    fun importBackground(ctx: Context, uri: Uri): String {
        val out = File(ctx.filesDir, "bg.jpg")
        ctx.contentResolver.openInputStream(uri)!!.use { saveScaledJpeg(it.readBytes(), out, 1600) }
        return out.absolutePath
    }

    private val dataUrlCache = HashMap<String, String>()
    fun dataUrl(path: String): String = dataUrlCache.getOrPut(path) {
        "data:image/jpeg;base64," + Base64.encodeToString(File(path).readBytes(), Base64.NO_WRAP)
    }

    fun pdfPages(f: File): Int = runCatching {
        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { it.pageCount }
        }
    }.getOrDefault(1)

    fun readText(path: String, limit: Int = 100_000): String =
        File(path).readText().let { if (it.length > limit) it.take(limit) + "\n…(已截断)" else it }
}

/** 录 16kHz / 单声道 / 16 位 PCM 的 WAV,最长 120 秒(符合官方语音识别接口要求) */
class WavRecorder {
    private val sr = 16000
    @Volatile private var running = false
    private var thread: Thread? = null
    private var bytes = 0L

    @Suppress("MissingPermission")
    fun start(file: File) {
        val min = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, sr),
        )
        running = true; bytes = 0
        rec.startRecording()
        thread = Thread {
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(0); raf.write(ByteArray(44))
                val buf = ByteArray(4096)
                val maxBytes = sr * 2L * 120
                while (running && bytes < maxBytes) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) { raf.write(buf, 0, n); bytes += n } else if (n < 0) break
                }
                rec.stop(); rec.release()
                raf.seek(0); raf.write(header(bytes))
            }
        }.also { it.start() }
    }

    /** 停止并返回录音秒数(向上取整) */
    fun stop(): Int {
        running = false
        thread?.join()
        return ((bytes + sr * 2 - 1) / (sr * 2)).toInt()
    }

    private fun header(dataLen: Long): ByteArray {
        val total = dataLen + 36
        val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(total.toInt()).put("WAVE".toByteArray()).put("fmt ".toByteArray())
        b.putInt(16).putShort(1).putShort(1).putInt(sr).putInt(sr * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(dataLen.toInt())
        return b.array()
    }
}
