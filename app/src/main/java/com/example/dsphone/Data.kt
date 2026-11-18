package com.example.dsphone

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

fun JSONObject.str(k: String): String? = if (isNull(k)) null else getString(k)

data class Source(val title: String, val url: String, val site: String? = null)

/** 附件:image 图片 / doc PDF·Word·PPT / text 纯文本 */
class Attach(val kind: String, val name: String, val path: String, val src: String? = null) {
    var fileId: String? = null     // Files API(Flash 引用文档)
    var ocrText: String? = null    // OCR 结果(Pro 用)
    var pages: Int = 0

    fun toJson(): JSONObject = JSONObject().put("kind", kind).put("name", name).put("path", path)
        .put("fileId", fileId ?: JSONObject.NULL).put("ocr", ocrText ?: JSONObject.NULL).put("pages", pages)

    companion object {
        fun from(j: JSONObject) = Attach(j.getString("kind"), j.getString("name"), j.getString("path")).apply {
            fileId = j.str("fileId"); ocrText = j.str("ocr"); pages = j.optInt("pages")
        }
    }
}

class Msg(val role: String, content: String = "", reasoning: String? = null) {
    var content by mutableStateOf(content)
    var reasoning by mutableStateOf(reasoning)
    var status by mutableStateOf("")
    var streaming by mutableStateOf(false)
    var thinkSec by mutableStateOf<Int?>(null)
    var costText by mutableStateOf("")
    var cost = 0.0
    var model = ""
    var time = System.currentTimeMillis()   // 消息时间(导出用);旧数据没有则为 0
    var asrSec = 0
    var asrCost = 0.0
    val attachments = mutableListOf<Attach>()
    val sources = mutableStateListOf<Source>()

    fun toJson(): JSONObject = JSONObject().put("role", role).put("content", content)
        .put("reasoning", reasoning ?: JSONObject.NULL).put("thinkSec", thinkSec ?: JSONObject.NULL)
        .put("costText", costText).put("cost", cost).put("model", model).put("t", time)
        .put("asrSec", asrSec).put("asrCost", asrCost)
        .put("att", JSONArray().also { a -> attachments.forEach { a.put(it.toJson()) } })
        .put("src", JSONArray().also { a ->
            sources.forEach { a.put(JSONObject().put("t", it.title).put("u", it.url).put("s", it.site ?: JSONObject.NULL)) }
        })

    companion object {
        fun from(j: JSONObject) = Msg(j.getString("role"), j.optString("content"), j.str("reasoning")).apply {
            thinkSec = if (j.isNull("thinkSec")) null else j.getInt("thinkSec")
            costText = j.optString("costText"); cost = j.optDouble("cost", 0.0); model = j.optString("model")
            time = j.optLong("t", 0L)
            asrSec = j.optInt("asrSec"); asrCost = j.optDouble("asrCost", 0.0)
            j.optJSONArray("att")?.let { a -> for (i in 0 until a.length()) attachments.add(Attach.from(a.getJSONObject(i))) }
            j.optJSONArray("src")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let { sources.add(Source(it.getString("t"), it.getString("u"), it.str("s"))) }
            }
        }
    }
}

class Conv(val id: Long) {
    var title by mutableStateOf("")
    var cost by mutableDoubleStateOf(0.0)
    var updated by mutableLongStateOf(System.currentTimeMillis())
    var srcId = ""   // 导入时记录官方会话 UUID,用于重复导入去重
    var pinned by mutableStateOf(false)
    val msgs = mutableStateListOf<Msg>()

    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("cost", cost).put("updated", updated).put("srcId", srcId).put("pinned", pinned)
        .put("msgs", JSONArray().also { a -> msgs.forEach { a.put(it.toJson()) } })

    companion object {
        fun from(j: JSONObject) = Conv(j.getLong("id")).apply {
            title = j.optString("title"); cost = j.optDouble("cost", 0.0); updated = j.optLong("updated")
            srcId = j.optString("srcId"); pinned = j.optBoolean("pinned", false)
            val a = j.getJSONArray("msgs")
            for (i in 0 until a.length()) msgs.add(Msg.from(a.getJSONObject(i)))
        }
    }
}

/** 聊天记录:每个对话一个 JSON 文件,存在应用私有目录 */
class Store(ctx: Context) {
    private val dir = File(ctx.filesDir, "convs").apply { mkdirs() }

    fun loadAll(): List<Conv> = (dir.listFiles { f -> f.extension == "json" } ?: emptyArray())
        .mapNotNull { f -> runCatching { Conv.from(JSONObject(f.readText())) }.getOrNull() }
        .sortedByDescending { it.updated }

    fun save(c: Conv) {
        if (c.msgs.isEmpty()) return
        File(dir, "${c.id}.json").writeText(c.toJson().toString())
    }

    fun delete(c: Conv) {
        File(dir, "${c.id}.json").delete()
        c.msgs.forEach { m -> m.attachments.forEach { File(it.path).delete() } }
    }
}

const val DEFAULT_CUSTOM_URL = "https://opencode.ai/inference/go/openai/v1"

class Settings(ctx: Context) {
    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var apiKey by mutableStateOf(sp.getString("key", "") ?: "")
    var model by mutableStateOf(sp.getString("model", Models.flash.id) ?: Models.flash.id)
    var think by mutableStateOf(sp.getBoolean("think", true))
    var effort by mutableStateOf(sp.getString("effort", "high") ?: "high")
    var web by mutableStateOf(sp.getBoolean("web", false))
    var speak by mutableStateOf(sp.getBoolean("speak", false))
    var bgType by mutableStateOf(sp.getString("bgType", "none") ?: "none") // none | color | image
    var bgColor by mutableIntStateOf(sp.getInt("bgColor", 0))
    var bgDim by mutableIntStateOf(sp.getInt("bgDim", 35))
    var bgVersion by mutableIntStateOf(0)

    // 服务商:deepseek(官方)或 custom(任意 OpenAI 兼容接口,如 OpenCode Go)
    var provider by mutableStateOf(sp.getString("provider", "deepseek") ?: "deepseek")
    var customUrl by mutableStateOf(sp.getString("customUrl", DEFAULT_CUSTOM_URL) ?: DEFAULT_CUSTOM_URL)
    var customKey by mutableStateOf(sp.getString("customKey", "") ?: "")
    var customModel by mutableStateOf(sp.getString("customModel", "") ?: "")
    val isCustom get() = provider == "custom"
    val activeKey get() = if (isCustom) customKey else apiKey
    val activeBase get() = if (isCustom) customUrl.trim().trimEnd('/') else Api.DEEPSEEK_BASE

    /** 思考强度循环:关 → 低 → 高 → 最大 → 关 */
    fun cycleEffort() {
        if (!think) { think = true; effort = "low" }
        else when (effort) { "low" -> effort = "high"; "high" -> effort = "max"; else -> think = false }
        save()
    }

    fun save() {
        sp.edit().putString("key", apiKey).putString("model", model).putBoolean("think", think)
            .putString("effort", effort).putBoolean("web", web).putBoolean("speak", speak)
            .putString("bgType", bgType).putInt("bgColor", bgColor).putInt("bgDim", bgDim)
            .putString("provider", provider).putString("customUrl", customUrl)
            .putString("customKey", customKey).putString("customModel", customModel).apply()
    }
}
