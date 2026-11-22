package com.example.dsphone

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** 当前是否深色。各颜色令牌是 getter,读取时会随这个状态重组,浅/深色切换立即生效 */
object Pal {
    var dark by mutableStateOf(false)
}

private fun c(light: Long, dark: Long) = Color(if (Pal.dark) dark else light)

val Pri = Color(0xFF4D6BFE)
val Bg get() = c(0xFFFFFFFF, 0xFF17181B)         // 页面、卡片、弹窗底色
val PageBg get() = c(0xFFF6F6F9, 0xFF0E0F11)     // 设置页底色
val RowLine get() = c(0xFFF0F0F4, 0xFF2A2B31)
val Ink get() = c(0xFF16171A, 0xFFE8E9ED)
val Grey get() = c(0xFFF4F4F6, 0xFF2A2B31)
val Mut get() = c(0xFF8B8D98, 0xFF9B9DA8)
val LineC get() = c(0xFFE3E4EA, 0xFF3A3B43)
val PriCont get() = c(0xFFE7EDFF, 0xFF252C4D)
val PriBorder get() = c(0xFFC7D4FF, 0xFF3D4F99)
val PriText get() = c(0xFF2F4BD8, 0xFF9DB0FF)
val ChevC get() = c(0xFFC0C2CA, 0xFF5A5C68)      // 箭头、圆点
val IconC get() = c(0xFF5D5F6B, 0xFFB5B7C2)      // 次要图标和文字
val HintC get() = c(0xFFB3B5BE, 0xFF6E707B)      // 输入框占位提示
val UserBg get() = c(0xFFF0F1F5, 0xFF2C2D34)     // 用户气泡、行内代码底色
val CodeBg get() = c(0xFFF4F5F8, 0xFF22232A)     // 代码块、表格、图表卡片底色
val QuoteC get() = c(0xFF4A4C57, 0xFFC4C6D0)     // 引用文字
val RecBg get() = c(0xFFFFE3E1, 0xFF4A2523)      // 语音录音中底色
