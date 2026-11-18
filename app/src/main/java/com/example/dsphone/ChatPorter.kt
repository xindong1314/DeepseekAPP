package com.example.dsphone

import android.content.Context
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

class ImportResult(
    val convs: List<Conv>,
    val skippedExisting: Int,   // 已存在(按来源 ID 判断)而跳过的对话
    val droppedBranches: Int,   // 含有其他分支、只导入了当前分支的对话数
    val missingFiles: Int,      // 导出文件里没带内容的附件数
    val failed: Int,            // 解析失败的对话数
)

/**
 * 聊天记录导入 / 导出,格式与 DeepSeek 官方导出的 zip 一致(export_format_version = 3):
 *
 *   manifest.json                         {"export_format_version": 3}
 *   user.json                             账号信息(本应用导出时留空)
 *   chat_session_index.json               会话索引
 *   chat_sessions/<会话id>.json           一个会话,messages 是以 parent_id 连成的树
 *   chat_sessions/<会话id>_files/<文件id>_<文件名>   附件(官方只导出图片)
 */
object ChatPorter {
    const val FORMAT_VERSION = 3
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    private fun iso(ms: Long): String = fmt.format(Instant.ofEpochMilli(ms))
    private fun parseIso(s: String?, fallback: Long): Long =
        runCatching { Instant.parse(s).toEpochMilli() }.getOrDefault(fallback)

    /** 本应用对话对应的会话 UUID:导入来的沿用原 ID,自己的按对话 ID 稳定生成 */
    fun uuidOf(c: Conv): String = c.srcId.ifEmpty { UUID.nameUUIDFromBytes("dsphone:${c.id}".toByteArray()).toString() }

    /** JSON 里的 null:Android 的 optString 会返回字符串 "null",这里统一当作默认值 */
    private fun JSONObject.txt(k: String, def: String = ""): String = if (isNull(k)) def else optString(k, def)

    private fun JSONObject.strOrNull(k: String): String? = if (isNull(k)) null else optString(k)

    // =============================================================== 导出
    /** 返回导出的对话数 */
    fun export(convs: List<Conv>, out: OutputStream): Int {
        val list = convs.filter { it.msgs.isNotEmpty() }
        val index = JSONArray()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
            put("manifest.json", JSONObject().put("export_format_version", FORMAT_VERSION).toString())
            // 官方的 user.json 含邮箱、手机号;这里只写空占位,不带任何个人信息
            put("user.json", JSONObject().put("user_id", "").put("email", JSONObject.NULL)
                .put("mobile", JSONObject.NULL).put("oauth_profiles", JSONArray()).toString())

            for (c in list) {
                val sid = uuidOf(c)
                val title = c.title.ifEmpty { "新对话" }
                val msgs = JSONArray()
                c.msgs.forEachIndexed { i, m ->
                    val asst = if (m.role == "assistant") m else c.msgs.getOrNull(i + 1)?.takeIf { it.role == "assistant" }
                    val t = if (m.time > 0) m.time else c.updated
                    val frags = JSONArray()
                    if (m.role == "user") {
                        val files = JSONArray()
                        for (a in m.attachments) {
                            val f = File(a.path)
                            if (a.kind == "missing" || a.path.isEmpty() || !f.exists()) continue
                            val fid = UUID.randomUUID().toString()
                            val path = "chat_sessions/${sid}_files/${fid}_${a.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")}"
                            zip.putNextEntry(ZipEntry(path)); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                            val o = JSONObject().put("id", fid).put("file_name", a.name).put("file_size", f.length())
                                .put("token_usage", 0).put("inserted_at", iso(t)).put("export_path", path)
                                .put("is_image", a.kind == "image")
                            if (a.kind == "image") {
                                val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeFile(a.path, b)
                                o.put("width", b.outWidth).put("height", b.outHeight)
                            } else o.put("width", JSONObject.NULL).put("height", JSONObject.NULL)
                            files.put(o)
                        }
                        if (files.length() > 0) frags.put(JSONObject().put("type", "FILE").put("files", files))
                        frags.put(JSONObject().put("type", "REQUEST").put("content", m.content))
                    } else {
                        if (!m.reasoning.isNullOrEmpty())
                            frags.put(JSONObject().put("type", "THINK").put("content", m.reasoning).put("elapsed_secs", (m.thinkSec ?: 0).toDouble()))
                        if (m.sources.isNotEmpty()) {
                            val results = JSONArray()
                            m.sources.forEach {
                                results.put(JSONObject().put("url", it.url).put("title", it.title).put("snippet", "")
                                    .put("published_at", JSONObject.NULL).put("site_icon", JSONObject.NULL)
                                    .put("site_name", it.site ?: JSONObject.NULL))
                            }
                            frags.put(JSONObject().put("type", "TOOL_SEARCH").put("queries", JSONArray()).put("results", results)
                                .put("status", "FINISHED").put("stage_id", 1))
                        }
                        frags.put(JSONObject().put("type", "RESPONSE").put("content", m.content.replace(Regex("\\[\\[\\d+]]"), "")))
                    }
                    msgs.put(
                        JSONObject().put("id", i + 1).put("parent_id", if (i == 0) JSONObject.NULL else i)
                            .put("role", if (m.role == "user") "USER" else "ASSISTANT")
                            .put("status", if (m.role == "assistant" && m.content == "(已停止)") "INCOMPLETE" else "FINISHED")
                            .put("thinking_enabled", asst?.reasoning != null)
                            .put("search_enabled", asst?.sources?.isNotEmpty() == true)
                            .put("accumulated_token_usage", 0).put("inserted_at", iso(t)).put("fragments", frags),
                    )
                }
                put("chat_sessions/$sid.json", JSONObject().put("id", sid).put("title", title).put("pinned", false)
                    .put("current_message_id", c.msgs.size).put("inserted_at", iso(c.id)).put("updated_at", iso(c.updated))
                    .put("messages", msgs).toString())
                index.put(JSONObject().put("id", sid).put("title", title).put("pinned", false)
                    .put("message_count", c.msgs.size).put("inserted_at", iso(c.id)).put("updated_at", iso(c.updated)))
            }
            put("chat_session_index.json", index.toString())
        }
        return list.size
    }

    // =============================================================== 导入
    fun import(ctx: Context, zipFile: File, existing: List<Conv>, onProgress: (done: Int, total: Int) -> Unit): ImportResult {
        val out = ArrayList<Conv>()
        var skipped = 0; var dropped = 0; var missing = 0; var failed = 0
        ZipFile(zipFile).use { z ->
            val entries = z.entries().asSequence().associateBy { it.name }
            fun text(name: String) = z.getInputStream(entries.getValue(name)).use { String(it.readBytes(), Charsets.UTF_8) }

            val manifest = entries["manifest.json"]
                ?: throw IllegalArgumentException("这不是 DeepSeek 导出的聊天记录文件(缺少 manifest.json)")
            val ver = JSONObject(text(manifest.name)).optInt("export_format_version", 0)
            if (ver != FORMAT_VERSION) throw IllegalArgumentException("不支持的导出格式版本 $ver(目前只支持 $FORMAT_VERSION)")

            val sessions = entries.keys.filter { it.startsWith("chat_sessions/") && it.endsWith(".json") && !it.substringAfter("chat_sessions/").contains('/') }
            val known = existing.map { uuidOf(it) }.toHashSet()
            val usedIds = existing.map { it.id }.toHashSet()
            sessions.forEachIndexed { n, name ->
                onProgress(n, sessions.size)
                try {
                    val d = JSONObject(text(name))
                    if (d.getString("id") in known) { skipped++; return@forEachIndexed }
                    val created = parseIso(d.txt("inserted_at"), System.currentTimeMillis())
                    var id = created
                    while (!usedIds.add(id)) id++
                    val stats = IntArray(2)   // [0]缺失附件 [1]是否丢弃了其他分支
                    val conv = convert(ctx, d, z, entries, Conv(id), created, stats)
                    if (conv != null) { out.add(conv); missing += stats[0]; dropped += stats[1] }
                } catch (e: Exception) {
                    failed++
                }
            }
            onProgress(sessions.size, sessions.size)
        }
        return ImportResult(out, skipped, dropped, missing, failed)
    }

    private fun convert(ctx: Context, d: JSONObject, z: ZipFile, entries: Map<String, ZipEntry>, conv: Conv, created: Long, stats: IntArray): Conv? {
        val arr = d.getJSONArray("messages")
        if (arr.length() == 0) return null
        val byId = HashMap<Int, JSONObject>()
        for (i in 0 until arr.length()) arr.getJSONObject(i).let { byId[it.getInt("id")] = it }
        // 官方记录是分支树:从当前选中的消息沿 parent_id 回溯到根,得到当前这条对话线
        var cur: JSONObject? = byId[d.optInt("current_message_id", -1)] ?: arr.getJSONObject(arr.length() - 1)
        val chain = ArrayList<JSONObject>(); val seen = HashSet<Int>()
        while (cur != null && seen.add(cur.getInt("id"))) {
            chain.add(cur)
            cur = if (cur.isNull("parent_id")) null else byId[cur.getInt("parent_id")]
        }
        chain.reverse()
        if (chain.size < arr.length()) stats[1] = 1

        conv.srcId = d.getString("id")
        conv.title = d.txt("title").ifEmpty { "新对话" }
        conv.updated = parseIso(d.txt("updated_at"), created)
        for (m in chain) conv.msgs.add(convertMsg(ctx, m, z, entries, conv.updated, stats))
        return conv
    }

    private fun convertMsg(ctx: Context, m: JSONObject, z: ZipFile, entries: Map<String, ZipEntry>, fallbackTime: Long, stats: IntArray): Msg {
        val frs = m.getJSONArray("fragments")
        val time = parseIso(m.txt("inserted_at"), fallbackTime)
        if (m.getString("role") == "USER") {
            val msg = Msg("user")
            val parts = ArrayList<String>()
            for (i in 0 until frs.length()) {
                val fr = frs.getJSONObject(i)
                when (fr.txt("type")) {
                    "REQUEST" -> parts.add(fr.txt("content"))
                    "FILE" -> {
                        val files = fr.optJSONArray("files") ?: continue
                        for (k in 0 until files.length()) {
                            val fl = files.getJSONObject(k)
                            val name = fl.txt("file_name", "file")
                            val e = entries[fl.txt("export_path")]
                            val att = if (e == null) null else runCatching {
                                Media.importBytes(ctx, z.getInputStream(e).use { it.readBytes() }, name, fl.optBoolean("is_image"))
                            }.getOrNull()
                            if (att != null) msg.attachments.add(att)
                            else { msg.attachments.add(Attach("missing", name, "")); stats[0]++ }   // 官方导出不含非图片文件的内容
                        }
                    }
                }
            }
            msg.content = parts.joinToString("\n")
            msg.time = time
            return msg
        }
        // ASSISTANT:思考、搜索来源、正文
        val msg = Msg("assistant")
        val think = ArrayList<String>(); var elapsed = 0.0; var hasThink = false
        val resp = ArrayList<String>()
        val seenUrl = HashSet<String>()
        fun addSource(url: String?, title: String?, site: String?) {
            if (!url.isNullOrEmpty() && seenUrl.add(url)) msg.sources.add(Source(title?.ifEmpty { url } ?: url, url, site))
        }
        for (i in 0 until frs.length()) {
            val fr = frs.getJSONObject(i)
            when (fr.txt("type")) {
                "THINK" -> { hasThink = true; fr.txt("content").takeIf { it.isNotEmpty() }?.let(think::add); elapsed += fr.optDouble("elapsed_secs", 0.0) }
                "RESPONSE", "TEMPLATE_RESPONSE" -> fr.txt("content").takeIf { it.isNotEmpty() }?.let(resp::add)
                "TOOL_SEARCH" -> fr.optJSONArray("results")?.let { rs ->
                    for (k in 0 until rs.length()) rs.getJSONObject(k).let { addSource(it.strOrNull("url"), it.strOrNull("title"), it.strOrNull("site_name")) }
                }
                "TOOL_OPEN" -> addSource(fr.strOrNull("url"), fr.strOrNull("title"), fr.strOrNull("site_name"))
            }
        }
        msg.reasoning = if (hasThink) think.joinToString("\n\n") else null
        if (hasThink && elapsed > 0) msg.thinkSec = max(1, elapsed.roundToInt())
        msg.content = resp.joinToString("\n\n").trim().ifEmpty { if (m.txt("status") == "FINISHED") "" else "(未完成)" }
        msg.time = time
        return msg
    }
}
