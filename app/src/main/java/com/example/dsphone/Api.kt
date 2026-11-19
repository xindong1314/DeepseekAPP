package com.example.dsphone

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class ApiException(msg: String) : Exception(msg)

class ToolCall(var id: String = "", var name: String = "", var args: String = "")

class StreamResult {
    val content = StringBuilder()
    val reasoning = StringBuilder()
    var finish: String? = null
    val toolCalls = mutableListOf<ToolCall>()
    val usage = Usage()
    var annotations: JSONArray? = null   // 联网工具的 url_citation
    var serverCalls: JSONArray? = null   // 联网工具的调用记录
}

/** 自定义服务商 /models 返回的模型:显示名、能否看图、支持的思考强度 */
data class CustomModel(val id: String, val name: String, val vision: Boolean, val efforts: List<String>)

data class Hit(val title: String, val url: String, val summary: String)

/** 账户余额(GET /user/balance)。字段均为字符串,如 "110.00" */
data class BalanceItem(val currency: String, val total: String, val granted: String, val toppedUp: String) {
    val symbol get() = if (currency == "USD") "$" else "¥"
}
data class Balance(val available: Boolean, val items: List<BalanceItem>)

/** 官方没有公开的充值接口,充值只能在官方网页完成(文档「402 余额不足」一条给出的地址) */
const val TOPUP_URL = "https://platform.deepseek.com/top_up"

/** DeepSeek 官方接口封装(全部来自官方文档) */
object Api {
    const val DEEPSEEK_BASE = "https://api.deepseek.com"
    private const val BASE = DEEPSEEK_BASE
    private val JSON = "application/json".toMediaType()
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(200, TimeUnit.SECONDS)
        .writeTimeout(200, TimeUnit.SECONDS)
        .build()

    private fun errorOf(code: Int, body: String): ApiException {
        val m = runCatching { JSONObject(body.trim()).getJSONObject("error").getString("message") }.getOrNull()
        val hint = when (code) {
            401 -> "API Key 无效"
            402 -> "账户余额不足"
            429 -> "请求太频繁或并发超限"
            else -> ""
        }
        return ApiException("HTTP $code${if (hint.isNotEmpty()) "($hint)" else ""}: ${m ?: body.take(200)}")
    }

    // ---------------- 对话(流式) ----------------
    suspend fun chatStream(
        base: String,
        key: String,
        body: JSONObject,
        onReasoning: (String) -> Unit,
        onContent: (String) -> Unit,
        onStatus: (String) -> Unit,
        session: String? = null,   // 自定义服务商:稳定的会话 ID(OpenCode 要求 x-opencode-session)
    ): StreamResult = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$base/chat/completions")
            .header("Authorization", "Bearer $key")
            .apply { if (session != null) header("x-opencode-session", session).header("User-Agent", "DeepseekAPP/1.3.0") }
            .post(body.toString().toRequestBody(JSON)).build()
        val call = http.newCall(req)
        val handle = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        val r = StreamResult()
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) throw errorOf(resp.code, resp.body?.string().orEmpty())
                val src = resp.body!!.source()
                while (true) {
                    val line = src.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.substring(5).trim()
                    if (data.isEmpty()) continue
                    if (data == "[DONE]") break
                    parseChunk(JSONObject(data), r, onReasoning, onContent, onStatus)
                }
            }
        } finally {
            handle?.dispose()
        }
        r
    }

    private fun parseChunk(
        j: JSONObject, r: StreamResult,
        onReasoning: (String) -> Unit, onContent: (String) -> Unit, onStatus: (String) -> Unit,
    ) {
        if (j.has("error")) throw ApiException(j.getJSONObject("error").optString("message", "流式响应出错"))
        j.optJSONObject("usage")?.let { u ->
            if (u.has("prompt_cache_miss_tokens")) {
                r.usage.hit = u.optInt("prompt_cache_hit_tokens")
                r.usage.miss = u.optInt("prompt_cache_miss_tokens")
            } else {   // OpenAI 兼容格式
                val cached = u.optJSONObject("prompt_tokens_details")?.optInt("cached_tokens") ?: 0
                r.usage.hit = cached
                r.usage.miss = (u.optInt("prompt_tokens") - cached).coerceAtLeast(0)
            }
            r.usage.out = u.optInt("completion_tokens")
            u.optJSONObject("server_tool_use")?.let {
                r.usage.search = it.optInt("tool_search")
                r.usage.fetch = it.optInt("tool_fetch")
            }
        }
        j.optJSONObject("usage")?.optJSONObject("opencode")?.let { r.usage.allowance = it.optDouble("allowance", 0.0) }
        val ch = j.optJSONArray("choices")?.optJSONObject(0) ?: return
        ch.optJSONObject("server_tool_event")?.let { ev ->
            if (ev.optString("type") == "call_started") {
                val a = ev.optJSONObject("action")
                when (a?.optString("type")) {
                    "search" -> onStatus("🔍 正在搜索:" + (a.optJSONArray("queries")?.optString(0) ?: ""))
                    "open_page", "read_link" -> onStatus("📄 正在阅读:" + a.optString("url"))
                    "find_in_page" -> onStatus("📄 正在查找:" + a.optString("pattern"))
                }
            }
        }
        ch.optJSONObject("delta")?.let { d ->
            if (!d.isNull("reasoning_content")) d.optString("reasoning_content").takeIf { it.isNotEmpty() }?.let {
                r.reasoning.append(it); onReasoning(it)
            }
            if (!d.isNull("content")) d.optString("content").takeIf { it.isNotEmpty() }?.let {
                r.content.append(it); onContent(it)
            }
            d.optJSONArray("tool_calls")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val tc = arr.getJSONObject(i)
                    val idx = tc.optInt("index", i)
                    while (r.toolCalls.size <= idx) r.toolCalls.add(ToolCall())
                    val cur = r.toolCalls[idx]
                    tc.optString("id").takeIf { it.isNotEmpty() }?.let { cur.id = it }
                    tc.optJSONObject("function")?.let { f ->
                        f.optString("name").takeIf { it.isNotEmpty() }?.let { cur.name = it }
                        cur.args += f.optString("arguments")
                    }
                }
            }
            d.optJSONArray("server_tool_calls")?.let { r.serverCalls = it }
            d.optJSONArray("annotations")?.let { r.annotations = it }
        }
        if (!ch.isNull("finish_reason")) ch.optString("finish_reason").takeIf { it.isNotEmpty() }?.let { r.finish = it }
    }

    // ---------------- 通用请求 ----------------
    private suspend fun exec(req: Request): JSONObject = withContext(Dispatchers.IO) {
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw errorOf(resp.code, text)
            val j = JSONObject(text.trim())   // OCR 等接口会先发换行保持连接
            if (j.has("error")) throw ApiException(j.getJSONObject("error").optString("message", "请求失败"))
            j
        }
    }

    private suspend fun postJson(key: String, path: String, body: JSONObject) = exec(
        Request.Builder().url("$BASE$path").header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody(JSON)).build(),
    )

    private suspend fun postForm(key: String, path: String, purpose: String?, file: File, name: String, mime: String) = exec(
        Request.Builder().url("$BASE$path").header("Authorization", "Bearer $key").post(
            MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                if (purpose != null) addFormDataPart("purpose", purpose)   // purpose 必须在 file 之前
                addFormDataPart("file", name, file.asRequestBody(mime.toMediaType()))
            }.build(),
        ).build(),
    )

    // ---------------- 语音识别 ----------------
    /** 返回 (文字, 音频时长毫秒)。音频需为 16 位 PCM WAV 或 Ogg Opus,≤8MiB,≤120 秒 */
    suspend fun asr(key: String, wav: File): Pair<String, Int> {
        val j = postForm(key, "/v1/tools/asr", null, wav, "speech.wav", "audio/wav")
        return j.optString("text") to j.optInt("duration_ms")
    }

    // ---------------- OCR / 文件解析 ----------------
    /** 返回 (识别文字, 页数) */
    suspend fun ocr(key: String, f: File, name: String, mime: String): Pair<String, Int> {
        val j = postForm(key, "/v1/tools/ocr", "text_recognition", f, name, mime)
        val pages = j.optJSONArray("pages") ?: JSONArray()
        val sb = StringBuilder()
        for (i in 0 until pages.length()) {
            if (i > 0) sb.append("\n\n")
            sb.append(pages.getJSONObject(i).optString("text"))
        }
        return sb.toString() to j.optInt("page_count", pages.length())
    }

    /** 上传文档到 Files API 解析,返回 file_id,供 deepseek-flash 引用 */
    suspend fun uploadFile(key: String, f: File, name: String, mime: String): String {
        val j = postForm(key, "/files", "text_recognition", f, name, mime)
        return j.getString("id")
    }

    // ---------------- 查询余额 ----------------
    suspend fun balance(key: String): Balance {
        val j = exec(Request.Builder().url("$BASE/user/balance").header("Authorization", "Bearer $key").build())
        val arr = j.optJSONArray("balance_infos") ?: JSONArray()
        return Balance(
            j.optBoolean("is_available"),
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                BalanceItem(o.optString("currency"), o.optString("total_balance"), o.optString("granted_balance"), o.optString("topped_up_balance"))
            },
        )
    }

    // ---------------- 自定义服务商:模型列表 ----------------
    suspend fun listModels(base: String, key: String): List<CustomModel> {
        val j = exec(Request.Builder().url("$base/models").apply { if (key.isNotBlank()) header("Authorization", "Bearer $key") }.build())
        val arr = j.optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.optString("id").isNotEmpty() && it.optString("type", "chat") == "chat" }
            .map { o ->
                val inp = o.optJSONObject("modalities")?.optJSONArray("input")
                val vision = inp != null && (0 until inp.length()).any { inp.optString(it).startsWith("image") }
                val ef = o.optJSONObject("capabilities")?.optJSONObject("reasoning")?.optJSONArray("effort")
                CustomModel(o.getString("id"), o.optString("name").ifBlank { o.getString("id") }, vision,
                    if (ef == null) emptyList() else (0 until ef.length()).map { ef.optString(it) })
            }.sortedBy { it.name.lowercase() }
    }

    // ---------------- 搜索 / 抓取(Pro 联网用) ----------------
    suspend fun search(key: String, q: String): List<Hit> {
        val j = postJson(key, "/v1/tools/search", JSONObject().put("query", q.take(128)).put("count", 5))
        val arr = j.optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Hit(o.optString("title"), o.optString("url"), o.optString("summary").ifEmpty { o.optString("snippet") })
        }
    }

    /** 返回 (标题, Markdown 正文) */
    suspend fun fetch(key: String, url: String): Pair<String, String> {
        val j = postJson(key, "/v1/tools/fetch", JSONObject().put("url", url))
        return j.optString("title") to j.optString("content")
    }
}
