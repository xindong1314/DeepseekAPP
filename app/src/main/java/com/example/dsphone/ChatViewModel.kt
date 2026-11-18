package com.example.dsphone

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import kotlin.math.ceil

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app.applicationContext
    private val store = Store(ctx)
    val s = Settings(ctx)

    val convs = mutableStateListOf<Conv>()
    var cur by mutableStateOf(Conv(System.currentTimeMillis()))
    val pending = mutableStateListOf<Attach>()
    var input by mutableStateOf("")
    var busy by mutableStateOf(false)
    var transcribing by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var speakRequest by mutableStateOf<String?>(null)
    var toastMsg by mutableStateOf<String?>(null)

    private var asrSec = 0
    private var asrCost = 0.0
    private var job: Job? = null

    var balance by mutableStateOf<Balance?>(null)
    var balanceError by mutableStateOf<String?>(null)
    var balanceLoading by mutableStateOf(false)

    init { convs.addAll(store.loadAll()); refreshBalance() }

    /** 查询账户余额(只读接口,不产生费用) */
    fun refreshBalance() {
        val key = s.apiKey
        if (key.isBlank()) { balance = null; balanceError = null; return }
        viewModelScope.launch {
            balanceLoading = true
            try { balance = Api.balance(key); balanceError = null }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { balance = null; balanceError = e.message ?: "查询失败" }
            finally { balanceLoading = false }
        }
    }

    /** 抽屉里用的简短余额文字,如 ¥110.00 */
    val balanceShort: String? get() = balance?.items?.firstOrNull()?.let { it.symbol + it.total }

    val info get() = Models.byId(s.model)
    val total get() = convs.filter { it !== cur }.sumOf { it.cost } + cur.cost

    fun toast(t: String) { toastMsg = t }

    // ---------------- 对话管理 ----------------
    fun newConv() { stop(); cur = Conv(System.currentTimeMillis()); pending.clear(); error = null; input = "" }
    fun open(c: Conv) { stop(); cur = c; pending.clear(); error = null }
    fun delete(c: Conv) {
        convs.remove(c); store.delete(c)
        if (c === cur) newConv()
    }

    // ---------------- 聊天记录导入 / 导出(与 DeepSeek 官方导出格式一致) ----------------
    var portStatus by mutableStateOf("")

    fun exportTo(uri: Uri) {
        if (portStatus.isNotEmpty()) return
        viewModelScope.launch {
            portStatus = "正在导出…"
            try {
                val n = withContext(Dispatchers.IO) {
                    val all = convs.toList()
                    ctx.contentResolver.openOutputStream(uri)!!.use { ChatPorter.export(all, it) }
                }
                toast(if (n == 0) "没有可导出的对话" else "已导出 $n 个对话")
            } catch (e: Exception) {
                toast("导出失败:${e.message}")
            } finally { portStatus = "" }
        }
    }

    fun importFrom(uri: Uri) {
        if (portStatus.isNotEmpty()) return
        viewModelScope.launch {
            portStatus = "正在读取文件…"
            val tmp = File(ctx.cacheDir, "import.zip")
            try {
                val res = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                    ChatPorter.import(ctx, tmp, convs.toList()) { d, t -> portStatus = "正在导入… $d / $t" }
                }
                withContext(Dispatchers.IO) { res.convs.forEach { store.save(it) } }
                convs.addAll(res.convs)
                convs.sortByDescending { it.updated }
                val extra = buildList {
                    if (res.skippedExisting > 0) add("${res.skippedExisting} 个已存在而跳过")
                    if (res.droppedBranches > 0) add("${res.droppedBranches} 个对话只导入了当前分支")
                    if (res.missingFiles > 0) add("${res.missingFiles} 个附件文件未包含在导出中")
                    if (res.failed > 0) add("${res.failed} 个解析失败")
                }
                toast("已导入 ${res.convs.size} 个对话" + if (extra.isEmpty()) "" else "(" + extra.joinToString(",") + ")")
            } catch (e: java.util.zip.ZipException) {
                toast("不是有效的 zip 文件")
            } catch (e: Exception) {
                toast("导入失败:${e.message}")
            } finally {
                tmp.delete(); portStatus = ""
            }
        }
    }

    fun clearAll() {
        stop()
        convs.toList().forEach { store.delete(it) }
        convs.clear()
        newConv()
    }

    fun stop() { job?.cancel() }

    // ---------------- 设置 ----------------
    fun setModel(id: String) {
        s.model = id
        if (!Models.byId(id).serverWeb && s.web) toast("已切到 Pro:联网改由手机端调用搜索/抓取接口")
        s.save()
    }

    fun setBackgroundImage(uri: Uri) = viewModelScope.launch {
        runCatching { withContext(Dispatchers.IO) { Media.importBackground(ctx, uri) } }
            .onSuccess { s.bgType = "image"; s.bgVersion++; s.save() }
            .onFailure { toast("背景图片读取失败") }
    }

    // ---------------- 附件 ----------------
    fun addUris(uris: List<Uri>) {
        viewModelScope.launch {
            for (u in uris) {
                runCatching { withContext(Dispatchers.IO) { Media.importUri(ctx, u) } }
                    .onSuccess { pending.add(it) }
                    .onFailure { toast(it.message ?: "无法读取文件") }
            }
            if (!info.vision && pending.any { it.kind == "image" || it.kind == "doc" })
                toast("V4 Pro 不能直接看图:发送时先用 OCR 转文字(1.5 元/千页)")
        }
    }

    fun addPhoto(f: File) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { Media.importPhoto(ctx, f) } }
                .onSuccess { pending.add(it) }
                .onFailure { toast("照片读取失败") }
        }
    }

    fun removePending(a: Attach) { pending.remove(a); File(a.path).delete() }

    // ---------------- 语音输入 ----------------
    fun transcribe(wav: File) {
        if (s.apiKey.isBlank()) { error = "请先在设置里填写 API Key"; return }
        viewModelScope.launch {
            transcribing = true
            try {
                val (text, ms) = Api.asr(s.apiKey, wav)
                val sec = ceil(ms / 1000.0).toInt().coerceAtLeast(1)
                val cost = sec * Cost.ASR_SEC
                cur.cost += cost; asrSec += sec; asrCost += cost
                input = (input + " " + text).trim()
                toast("识别完成 · 音频 $sec 秒 · ${Cost.yuan(cost)}")
            } catch (e: Exception) {
                error = e.message ?: "语音识别失败"
            } finally {
                transcribing = false; wav.delete()
            }
        }
    }

    // ---------------- 发送 ----------------
    fun send() {
        val text = input.trim()
        if (text.isEmpty() && pending.isEmpty()) return
        if (busy) return
        if (s.apiKey.isBlank()) { error = "请先在设置里填写 API Key"; return }
        val um = Msg("user", text)
        um.attachments.addAll(pending); pending.clear()
        um.asrSec = asrSec; um.asrCost = asrCost; asrSec = 0; asrCost = 0.0
        input = ""; error = null
        cur.msgs.add(um)
        if (cur.title.isEmpty()) cur.title = text.ifEmpty { um.attachments.firstOrNull()?.name ?: "新对话" }.take(18)
        generate()
    }

    private fun generate() {
        val model = info
        val conv = cur
        val web = s.web
        val thinking = s.think
        val effort = s.effort
        val key = s.apiKey
        job = viewModelScope.launch {
            busy = true
            val m = Msg("assistant", "", if (thinking) "" else null)
            m.model = model.id; m.streaming = true
            conv.msgs.add(m)
            val t0 = System.currentTimeMillis()
            val usage = Usage()
            var ocrPages = 0
            val sources = ArrayList<Source>()
            try {
                // 1. 预处理附件:Flash 上传文档解析;Pro 先 OCR
                for (um in conv.msgs) if (um.role == "user") for (a in um.attachments) ocrPages += prepare(key, a, model, m)

                val clientWeb = web && !model.serverWeb
                val serverWeb = web && model.serverWeb
                val msgs = buildMessages(conv, m, model, thinking, withTools = clientWeb, system = if (clientWeb) clientSystem() else null)

                val onR = { t: String -> m.reasoning = (m.reasoning ?: "") + t }
                val onC = { t: String ->
                    if (m.status.isNotEmpty()) m.status = ""
                    if (thinking && m.thinkSec == null) m.thinkSec = ((System.currentTimeMillis() - t0) / 1000).toInt().coerceAtLeast(1)
                    m.content += t
                }
                val onS = { t: String -> m.status = t }

                fun body(withTools: Boolean): JSONObject {
                    val b = JSONObject().put("model", model.id).put("messages", msgs).put("stream", true)
                    if (thinking) b.put("thinking", JSONObject().put("type", "enabled")).put("reasoning_effort", effort)
                    else b.put("thinking", JSONObject().put("type", "disabled"))
                    if (serverWeb) b.put("tools", JSONArray().put(JSONObject().put("type", "deepseek_web_search")).put(JSONObject().put("type", "deepseek_read_url")))
                    if (withTools && clientWeb) b.put("tools", clientTools())
                    return b
                }

                if (!clientWeb) {
                    val res = Api.chatStream(key, body(false), onR, onC, onS)
                    usage.add(res.usage)
                    if (serverWeb) {
                        sources.addAll(sourcesOf(res.serverCalls))
                        if (sources.isNotEmpty()) m.content = withCites(res.content.toString(), res.annotations, sources)
                    }
                } else {
                    var searches = 0; var fetches = 0
                    fun register(title: String, url: String): Int {
                        val i = sources.indexOfFirst { it.url == url }
                        if (i >= 0) return i + 1
                        sources.add(Source(title.ifEmpty { url }, url)); m.sources.add(sources.last())
                        return sources.size
                    }
                    for (round in 0 until 6) {
                        val res = Api.chatStream(key, body(round < 5), onR, onC, onS)
                        usage.add(res.usage)
                        if (res.toolCalls.isEmpty()) break
                        val am = JSONObject().put("role", "assistant").put("content", res.content.toString())
                        if (thinking) am.put("reasoning_content", res.reasoning.toString())
                        am.put("tool_calls", JSONArray().also { arr ->
                            res.toolCalls.forEach {
                                arr.put(JSONObject().put("id", it.id).put("type", "function")
                                    .put("function", JSONObject().put("name", it.name).put("arguments", it.args.ifEmpty { "{}" })))
                            }
                        })
                        msgs.put(am)
                        m.content = ""   // 丢弃工具调用前的过渡语句
                        if (!m.reasoning.isNullOrEmpty()) m.reasoning += "\n"
                        for (tc in res.toolCalls) {
                            val out = try {
                                val args = JSONObject(tc.args.ifEmpty { "{}" })
                                when (tc.name) {
                                    "web_search" -> {
                                        val q = args.optString("query")
                                        m.status = "🔍 正在搜索:$q"
                                        val hits = Api.search(key, q)
                                        if (hits.isNotEmpty()) searches++
                                        if (hits.isEmpty()) "没有搜索结果" else hits.joinToString("\n\n") { h ->
                                            "[${register(h.title, h.url)}] ${h.title}\n${h.url}\n${h.summary}"
                                        }
                                    }
                                    "fetch_url" -> {
                                        val u = args.optString("url")
                                        m.status = "📄 正在阅读:$u"
                                        val (t, c) = Api.fetch(key, u)
                                        fetches++
                                        "[${register(t, u)}] $t\n${c.take(8000)}"
                                    }
                                    else -> "未知工具:${tc.name}"
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                "工具调用失败:${e.message}"
                            }
                            msgs.put(JSONObject().put("role", "tool").put("tool_call_id", tc.id).put("content", out))
                        }
                        m.status = ""
                    }
                    usage.search = searches; usage.fetch = fetches
                }
            } catch (e: CancellationException) {
                // 用户点了停止:保留已生成的部分
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                withContext(NonCancellable) { finish(conv, m, model, usage, ocrPages, sources) }
            }
        }
    }

    private fun finish(conv: Conv, m: Msg, model: ModelInfo, usage: Usage, ocrPages: Int, sources: List<Source>) {
        m.streaming = false; m.status = ""
        if (m.sources.isEmpty()) m.sources.addAll(sources)
        if (m.content.isEmpty() && m.reasoning.isNullOrEmpty() && usage.out == 0) {
            conv.msgs.remove(m)   // 什么都没生成:不留空气泡
        } else {
            if (m.content.isEmpty()) m.content = "(已停止)"
            if (m.reasoning != null && m.thinkSec == null && !m.reasoning.isNullOrEmpty())
                m.thinkSec = 1
            val peak = Cost.isPeak()
            val cost = Cost.tokens(model, usage, peak) + usage.search * Cost.SEARCH + usage.fetch * Cost.FETCH + ocrPages * Cost.OCR_PAGE
            m.cost = cost
            m.costText = buildString {
                append(Cost.yuan(cost)).append(" · 输入 ${usage.hit + usage.miss}(缓存 ${usage.hit}) 输出 ${usage.out}")
                if (usage.search > 0) append(" · 搜索 ${usage.search} 次")
                if (usage.fetch > 0) append(" · 抓取 ${usage.fetch} 次")
                if (ocrPages > 0) append(" · OCR/解析 $ocrPages 页")
                append(if (peak) " · 高峰价" else " · 空闲价")
            }
            conv.cost += cost
            if (s.speak && m.content != "(已停止)") speakRequest = m.content
        }
        conv.updated = System.currentTimeMillis()
        if (conv.msgs.isNotEmpty() && convs.none { it === conv }) convs.add(0, conv)
        store.save(conv)
        busy = false
        refreshBalance()   // 一轮结束后刷新余额
    }

    // ---------------- 附件预处理 ----------------
    /** 返回本次产生的计费页数 */
    private suspend fun prepare(key: String, a: Attach, model: ModelInfo, m: Msg): Int {
        val f = File(a.path)
        var pages = 0
        try {
            if (a.kind == "doc" && model.vision && a.fileId == null) {
                m.status = "📄 正在解析 ${a.name}…"
                a.fileId = Api.uploadFile(key, f, a.name, Media.mimeOfDoc(a.name))
                pages = a.pages.coerceAtLeast(1)
            } else if ((a.kind == "doc" || a.kind == "image") && !model.vision && a.ocrText == null) {
                m.status = "🔎 正在识别 ${a.name}…"
                val mime = if (a.kind == "image") "image/jpeg" else Media.mimeOfDoc(a.name)
                val (t, p) = Api.ocr(key, f, a.name, mime)
                a.ocrText = cleanOcr(t).ifBlank { "(未识别到文字)" }
                a.pages = p; pages = p
            }
        } finally { m.status = "" }
        return pages
    }

    private fun cleanOcr(t: String) = t
        .replace(Regex("<｜box｜>.*?<｜/box｜>", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("<｜/?ref｜>"), "")
        .trim()

    // ---------------- 请求消息 ----------------
    private fun buildMessages(conv: Conv, current: Msg, model: ModelInfo, thinking: Boolean, withTools: Boolean, system: String?): JSONArray {
        val arr = JSONArray()
        system?.let { arr.put(JSONObject().put("role", "system").put("content", it)) }
        for (m in conv.msgs) {
            if (m === current) continue
            if (m.role == "user") {
                val texts = StringBuilder()
                val blocks = JSONArray()
                for (a in m.attachments) when (a.kind) {
                    "image" -> if (model.vision)
                        blocks.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", Media.dataUrl(a.path))))
                    else texts.append("【图片「${a.name}」的识别文字】\n${a.ocrText}\n\n")
                    "doc" -> if (model.vision)
                        blocks.put(JSONObject().put("type", "file").put("file_id", a.fileId))
                    else texts.append("【文档「${a.name}」的内容】\n${a.ocrText}\n\n")
                    "text" -> texts.append("【文件「${a.name}」】\n${Media.readText(a.path)}\n\n")
                }
                texts.append(m.content)
                val content: Any = if (blocks.length() == 0) texts.toString()
                else blocks.put(JSONObject().put("type", "text").put("text", texts.toString()))
                arr.put(JSONObject().put("role", "user").put("content", content))
            } else if (m.content.isNotEmpty()) {
                val o = JSONObject().put("role", "assistant").put("content", m.content.replace(Regex("\\[\\[\\d+]]"), ""))
                // 携带 tools 的请求,历史轮次的 reasoning_content 必须回传,否则 400
                if (withTools && thinking) o.put("reasoning_content", m.reasoning ?: "")
                arr.put(o)
            }
        }
        return arr
    }

    private fun clientSystem() =
        "你可以使用 web_search 和 fetch_url 工具获取实时信息(今天是 ${LocalDate.now()})。" +
            "需要最新信息或不确定时先搜索;引用来源时,在相应句子末尾用 [[n]] 标注,n 是工具结果里的来源编号。用中文回答。"

    private fun clientTools(): JSONArray {
        fun fn(name: String, desc: String, prop: String, pdesc: String) = JSONObject().put("type", "function").put(
            "function", JSONObject().put("name", name).put("description", desc).put(
                "parameters", JSONObject().put("type", "object")
                    .put("properties", JSONObject().put(prop, JSONObject().put("type", "string").put("description", pdesc)))
                    .put("required", JSONArray().put(prop)),
            ),
        )
        return JSONArray()
            .put(fn("web_search", "搜索互联网,返回网页标题、链接和摘要", "query", "搜索词"))
            .put(fn("fetch_url", "抓取指定网页,返回 Markdown 正文", "url", "完整的 http(s) 网址"))
    }

    // ---------------- 联网结果处理 ----------------
    private fun sourcesOf(calls: JSONArray?): List<Source> {
        val out = ArrayList<Source>()
        fun add(url: String?, title: String?, site: String?) {
            if (url.isNullOrEmpty() || out.any { it.url == url }) return
            out.add(Source(title?.ifEmpty { url } ?: url, url, site))
        }
        if (calls == null) return out
        for (i in 0 until calls.length()) {
            val cs = calls.getJSONObject(i).optJSONArray("calls") ?: continue
            for (k in 0 until cs.length()) {
                val c = cs.getJSONObject(k)
                c.optJSONArray("sources")?.let { ss ->
                    for (n in 0 until ss.length()) ss.getJSONObject(n).let { add(it.str("url"), it.str("title"), it.str("site_name")) }
                }
                c.optJSONObject("page")?.let { add(it.str("url"), it.str("title"), null) }
            }
        }
        return out
    }

    /** 联网工具的引用在 annotations 里(按字符位置),把它们变成正文里的 [[n]] 角标 */
    private fun withCites(content: String, ann: JSONArray?, sources: List<Source>): String {
        if (ann == null) return content
        val ins = LinkedHashSet<Pair<Int, Int>>()
        val cps = content.codePointCount(0, content.length)
        for (i in 0 until ann.length()) {
            val c = ann.getJSONObject(i).optJSONObject("url_citation") ?: continue
            val n = sources.indexOfFirst { it.url == c.optString("url") } + 1
            if (n == 0) continue
            val end = c.optInt("end_index").coerceIn(0, cps)
            ins.add(content.offsetByCodePoints(0, end) to n)
        }
        val sb = StringBuilder(content)
        ins.sortedByDescending { it.first }.forEach { (p, n) -> sb.insert(p, "[[$n]]") }
        return sb.toString()
    }
}
