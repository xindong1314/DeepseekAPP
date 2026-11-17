package com.example.dsphone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private sealed class Block {
    data class Para(val t: String) : Block()
    data class Bullet(val t: String, val marker: String?) : Block()
    data class Head(val t: String) : Block()
    data class Code(val t: String) : Block()
}

private fun parse(text: String): List<Block> {
    val out = ArrayList<Block>()
    var code: StringBuilder? = null
    for (line in text.split('\n')) {
        if (line.trimStart().startsWith("```")) {
            if (code == null) code = StringBuilder() else { out.add(Block.Code(code.toString().trimEnd())); code = null }
            continue
        }
        if (code != null) { code.appendLine(line); continue }
        val t = line.trim()
        when {
            t.isEmpty() -> {}
            Regex("^[-*•] .*").matches(t) -> out.add(Block.Bullet(t.substring(2), null))
            Regex("^\\d+[.、] .*").matches(t) -> {
                val mk = t.takeWhile { it.isDigit() } + "."
                out.add(Block.Bullet(t.substringAfter(' '), mk))
            }
            t.startsWith("#") -> out.add(Block.Head(t.trimStart('#').trim()))
            else -> out.add(Block.Para(t))
        }
    }
    code?.let { out.add(Block.Code(it.toString().trimEnd())) }   // 流式中未闭合的代码块
    return out
}

private val inlineRe = Regex("\\*\\*(.+?)\\*\\*|\\[\\[(\\d+)]]|`([^`]+)`")

private fun inline(s: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    for (m in inlineRe.findAll(s)) {
        append(s.substring(i, m.range.first))
        when {
            m.groups[1] != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[1]) }
            m.groups[2] != null -> withStyle(
                SpanStyle(fontSize = 11.sp, color = Color(0xFF7A7D89), background = Color(0xFFEEF0F4)),
            ) { append(" ${m.groupValues[2]} ") }
            else -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0xFFF0F1F5), fontSize = 14.sp),
            ) { append(m.groupValues[3]) }
        }
        i = m.range.last + 1
    }
    append(s.substring(i))
}

/** 迷你 Markdown:标题、加粗、行内代码、列表、代码块、[[n]] 引用角标 */
@Composable
fun MdText(text: String, modifier: Modifier = Modifier, size: Int = 16) {
    val blocks = remember(text) { parse(text) }
    SelectionContainer {
        Column(modifier) {
            blocks.forEach { b ->
                when (b) {
                    is Block.Para -> Text(inline(b.t), fontSize = size.sp, lineHeight = (size * 1.7).sp, modifier = Modifier.padding(bottom = 10.dp))
                    is Block.Head -> Text(b.t, fontSize = (size + 2).sp, fontWeight = FontWeight.Bold, lineHeight = (size * 1.7).sp, modifier = Modifier.padding(bottom = 8.dp))
                    is Block.Bullet -> Row(Modifier.padding(bottom = 8.dp)) {
                        if (b.marker == null) {
                            Box(Modifier.padding(start = 6.dp, top = 11.dp, end = 10.dp).size(6.dp).clip(CircleShape).background(Color(0xFFB4B6C0)))
                        } else {
                            Text(b.marker, fontSize = size.sp, color = Color(0xFF7A7D89), modifier = Modifier.padding(end = 8.dp))
                        }
                        Text(inline(b.t), fontSize = size.sp, lineHeight = (size * 1.7).sp)
                    }
                    is Block.Code -> Box(
                        Modifier.padding(bottom = 10.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFFF3F4F8)).padding(12.dp),
                    ) { Text(b.t, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp) }
                }
            }
            Spacer(Modifier.height(0.dp))
        }
    }
}

/** 朗读/复制用的纯文本 */
fun plainText(md: String) = md.replace(Regex("\\[\\[\\d+]]"), "").replace("**", "").replace("`", "")
