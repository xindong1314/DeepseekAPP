package com.example.dsphone

import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/** 单价:元 / 百万 tokens。idle=空闲时段,peak=高峰时段 */
data class Price(val idle: Double, val peak: Double) {
    fun at(isPeak: Boolean) = if (isPeak) peak else idle
}

data class ModelInfo(
    val id: String,
    val name: String,
    val desc: String,
    val hit: Price,
    val miss: Price,
    val out: Price,
    /** 能否直接看图(否则图片/文档先走 OCR 转文字) */
    val vision: Boolean,
    /** 能否使用服务端联网工具 deepseek_web_search;否则手机端自己调搜索/抓取接口 */
    val serverWeb: Boolean,
)

object Models {
    val flash = ModelInfo(
        "deepseek-flash", "DeepSeek Flash", "快速、便宜,支持图片理解与联网搜索",
        Price(0.02, 0.04), Price(1.0, 2.0), Price(4.0, 8.0), vision = true, serverWeb = true,
    )
    val pro = ModelInfo(
        "deepseek-v4-pro", "DeepSeek V4 Pro", "能力更强,价格更高;不能直接看图(图片/文档先 OCR 转文字);联网由手机端调用搜索/抓取接口完成",
        Price(0.15, 0.30), Price(4.5, 9.0), Price(13.5, 27.0), vision = false, serverWeb = false,
    )
    val all = listOf(flash, pro)
    fun byId(id: String) = all.firstOrNull { it.id == id } ?: flash
}

data class Usage(
    var hit: Int = 0,
    var miss: Int = 0,
    var out: Int = 0,
    var search: Int = 0,
    var fetch: Int = 0,
) {
    fun add(o: Usage) {
        hit += o.hit; miss += o.miss; out += o.out; search += o.search; fetch += o.fetch
    }
}

object Cost {
    const val SEARCH = 5.0 / 1000      // 元/次
    const val FETCH = 5.0 / 1000       // 元/次
    const val OCR_PAGE = 1.5 / 1000    // 元/页
    const val ASR_SEC = 0.39 / 3600    // 元/秒(限时折扣价)

    /** 北京时间工作日 9-12、14-18 为高峰(未计入法定节假日) */
    fun isPeak(now: ZonedDateTime = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))): Boolean {
        if (now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY) return false
        val h = now.hour
        return h in 9..11 || h in 14..17
    }

    fun tokens(m: ModelInfo, u: Usage, peak: Boolean): Double =
        (u.hit * m.hit.at(peak) + u.miss * m.miss.at(peak) + u.out * m.out.at(peak)) / 1e6

    fun yuan(v: Double): String =
        "¥" + String.format(Locale.US, if (v < 0.01) "%.5f" else "%.4f", v)
}
