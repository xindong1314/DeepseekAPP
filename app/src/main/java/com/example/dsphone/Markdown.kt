package com.example.dsphone

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private sealed class Block {
    data class Para(val t: String) : Block()
    data class Bullet(val t: String, val marker: String?, val level: Int) : Block()
    data class Head(val t: String, val level: Int) : Block()
    data class Code(val lang: String, val t: String, val closed: Boolean) : Block()
    data class Quote(val lines: List<String>) : Block()
    data class Table(val header: List<String>, val aligns: List<TextAlign>, val rows: List<List<String>>) : Block()
    data object Rule : Block()
}

private val sepRe = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")
private val ruleRe = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")
private val bulletRe = Regex("""^(\s*)[-*•+]\s+(.*)$""")
private val numRe = Regex("""^(\s*)(\d+)[.、)]\s+(.*)$""")
private val headRe = Regex("""^(#{1,6})\s+(.*)$""")

private fun splitRow(line: String): List<String> {
    var s = line.trim()
    if (s.startsWith("|")) s = s.substring(1)
    if (s.endsWith("|") && !s.endsWith("\\|")) s = s.dropLast(1)
    val out = ArrayList<String>(); val cur = StringBuilder(); var inCode = false; var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            c == '`' -> { inCode = !inCode; cur.append(c) }
            c == '\\' && i + 1 < s.length && s[i + 1] == '|' -> { cur.append('|'); i++ }
            c == '|' && !inCode -> { out.add(cur.toString().trim()); cur.clear() }
            else -> cur.append(c)
        }
        i++
    }
    out.add(cur.toString().trim())
    return out
}

private fun parse(text: String): List<Block> {
    val lines = text.split('\n')
    val out = ArrayList<Block>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val t = line.trim()
        // 代码块(含 mermaid)
        if (t.startsWith("```")) {
            val lang = t.removePrefix("```").trim().lowercase()
            val sb = StringBuilder(); var closed = false; i++
            while (i < lines.size) {
                if (lines[i].trim().startsWith("```")) { closed = true; i++; break }
                sb.appendLine(lines[i]); i++
            }
            out.add(Block.Code(lang, sb.toString().trimEnd(), closed)); continue
        }
        // 表格:当前行含 | 且下一行是分隔行
        if (t.contains('|') && i + 1 < lines.size && sepRe.matches(lines[i + 1]) && lines[i + 1].contains('-')) {
            val header = splitRow(t)
            val aligns = splitRow(lines[i + 1]).map {
                when { it.startsWith(":") && it.endsWith(":") -> TextAlign.Center; it.endsWith(":") -> TextAlign.End; else -> TextAlign.Start }
            }
            val rows = ArrayList<List<String>>(); i += 2
            while (i < lines.size && lines[i].isNotBlank() && lines[i].contains('|')) { rows.add(splitRow(lines[i])); i++ }
            out.add(Block.Table(header, aligns, rows)); continue
        }
        if (t.startsWith(">")) {
            val q = ArrayList<String>()
            while (i < lines.size && lines[i].trim().startsWith(">")) { q.add(lines[i].trim().removePrefix(">").trim()); i++ }
            out.add(Block.Quote(q)); continue
        }
        when {
            t.isEmpty() -> {}
            ruleRe.matches(t) -> out.add(Block.Rule)
            headRe.matches(t) -> headRe.find(t)!!.let { out.add(Block.Head(it.groupValues[2], it.groupValues[1].length)) }
            bulletRe.matches(line) -> bulletRe.find(line)!!.let { out.add(Block.Bullet(it.groupValues[2], null, (it.groupValues[1].length / 2).coerceAtMost(3))) }
            numRe.matches(line) -> numRe.find(line)!!.let { out.add(Block.Bullet(it.groupValues[3], it.groupValues[2] + ".", (it.groupValues[1].length / 2).coerceAtMost(3))) }
            else -> out.add(Block.Para(t))
        }
        i++
    }
    return out
}

// 1 粗体 2 删除线 3 引用角标 4 链接文字 5 链接地址 6 行内代码 7 斜体
private val inlineRe = Regex("""\*\*(.+?)\*\*|~~(.+?)~~|\[\[(\d+)]]|\[([^\]]+)]\((https?://[^)\s]+)\)|`([^`]+)`|(?<![*\w])\*([^*\s][^*]*?)\*(?![*\w])""")

private fun inline(s: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    for (m in inlineRe.findAll(s)) {
        append(s.substring(i, m.range.first))
        val g = m.groups
        when {
            g[1] != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[1]) }
            g[2] != null -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(m.groupValues[2]) }
            g[3] != null -> withStyle(SpanStyle(fontSize = 11.sp, color = Mut, background = Grey)) { append(" ${m.groupValues[3]} ") }
            g[4] != null -> withLink(LinkAnnotation.Url(m.groupValues[5], TextLinkStyles(SpanStyle(color = Pri, textDecoration = TextDecoration.Underline)))) { append(m.groupValues[4]) }
            g[6] != null -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = UserBg, fontSize = 14.sp)) { append(m.groupValues[6]) }
            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(m.groupValues[7]) }
        }
        i = m.range.last + 1
    }
    append(s.substring(i))
}

/** Markdown:标题、粗体/斜体/删除线、链接、行内代码、列表、引用、分隔线、表格、代码块、Mermaid 图表、[[n]] 引用角标 */
@Composable
fun MdText(text: String, modifier: Modifier = Modifier, size: Int = 16) {
    val blocks = remember(text) { parse(text) }
    SelectionContainer {
        Column(modifier) {
            blocks.forEach { b ->
                val lh = (size * 1.7).sp
                when (b) {
                    is Block.Para -> Text(inline(b.t), fontSize = size.sp, lineHeight = lh, modifier = Modifier.padding(bottom = 10.dp))
                    is Block.Head -> Text(
                        inline(b.t), fontWeight = FontWeight.Bold,
                        fontSize = (size + when (b.level) { 1 -> 6; 2 -> 4; 3 -> 2; else -> 0 }).sp,
                        lineHeight = (size * 1.6).sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                    )
                    is Block.Bullet -> Row(Modifier.padding(start = (b.level * 18).dp, bottom = 8.dp)) {
                        if (b.marker == null) {
                            Box(Modifier.padding(start = 6.dp, top = 11.dp, end = 10.dp).size(6.dp).clip(CircleShape).background(ChevC))
                        } else {
                            Text(b.marker, fontSize = size.sp, color = Mut, modifier = Modifier.padding(end = 8.dp))
                        }
                        Text(inline(b.t), fontSize = size.sp, lineHeight = lh)
                    }
                    is Block.Quote -> Row(Modifier.padding(bottom = 10.dp).height(IntrinsicSize.Min)) {
                        Box(Modifier.width(3.dp).fillMaxHeight().background(LineC))
                        Column(Modifier.padding(start = 12.dp)) {
                            b.lines.forEach { Text(inline(it), fontSize = (size - 1).sp, lineHeight = lh, color = QuoteC) }
                        }
                    }
                    Block.Rule -> HorizontalDivider(Modifier.padding(vertical = 10.dp), color = LineC)
                    is Block.Code -> if (b.lang == "mermaid" && b.closed) MermaidBlock(b.t) else CodeBlock(b.lang, b.t)
                    is Block.Table -> TableBlock(b)
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(lang: String, code: String) {
    val clip = LocalClipboardManager.current
    val ctx = LocalContext.current
    Column(Modifier.padding(bottom = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CodeBg)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(lang.ifEmpty { "代码" }, fontSize = 12.sp, color = Mut, modifier = Modifier.weight(1f))
            IconButton({ clip.setText(AnnotatedString(code)); Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show() }, Modifier.size(36.dp)) {
                Icon(Icons.Outlined.ContentCopy, "复制", Modifier.size(17.dp), tint = Mut)
            }
        }
        Text(
            code, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        )
    }
}

private fun units(s: String) = s.sumOf { (if (it.code > 0x2E80) 2 else 1).toInt() }

@Composable
private fun TableBlock(t: Block.Table) {
    val clip = LocalClipboardManager.current
    val ctx = LocalContext.current
    var full by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val line = LineC
    fun copyMd() {
        val sb = StringBuilder("| " + t.header.joinToString(" | ") + " |\n|" + t.header.joinToString("|") { " --- " } + "|\n")
        t.rows.forEach { r -> sb.append("| " + r.joinToString(" | ") + " |\n") }
        clip.setText(AnnotatedString(sb.toString().trimEnd())); Toast.makeText(ctx, "已复制表格", Toast.LENGTH_SHORT).show()
    }
    Column(Modifier.padding(bottom = 12.dp).fillMaxWidth().clip(shape).border(1.dp, line, shape)) {
        Row(Modifier.fillMaxWidth().background(CodeBg).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("表格", fontSize = 13.sp, color = Mut, modifier = Modifier.weight(1f))
            IconButton(::copyMd, Modifier.size(40.dp)) { Icon(Icons.Outlined.ContentCopy, "复制", Modifier.size(19.dp), tint = IconC) }
            IconButton({ full = true }, Modifier.size(40.dp)) { Icon(Icons.Outlined.OpenInFull, "全屏", Modifier.size(19.dp), tint = IconC) }
        }
        HorizontalDivider(color = line)
        TableGrid(t, Modifier.horizontalScroll(rememberScrollState()))
    }
    if (full) Dialog({ full = false }, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Bg) {
            Column(Modifier.fillMaxSize().padding(top = 28.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 4.dp)) {
                    Text("表格", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconButton(::copyMd) { Icon(Icons.Outlined.ContentCopy, "复制") }
                    IconButton({ full = false }) { Icon(Icons.Outlined.Close, "关闭") }
                }
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp)) {
                    Box(Modifier.clip(shape).border(1.dp, line, shape)) { TableGrid(t, Modifier) }
                }
            }
        }
    }
}

@Composable
private fun TableGrid(t: Block.Table, modifier: Modifier) {
    val cols = t.header.size
    val widths = (0 until cols).map { c ->
        val mx = maxOf(units(t.header[c]), t.rows.maxOfOrNull { r -> units(r.getOrElse(c) { "" }) } ?: 0)
        (mx * 8 + 28).coerceIn(72, 260).dp
    }
    val line = LineC
    Column(modifier) {
        Row(Modifier.background(CodeBg)) {
            for (c in 0 until cols) Text(
                inline(t.header[c]), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, textAlign = t.aligns.getOrElse(c) { TextAlign.Start },
                modifier = Modifier.width(widths[c]).padding(horizontal = 12.dp, vertical = 11.dp),
            )
        }
        t.rows.forEach { r ->
            HorizontalDivider(color = line)
            Row(verticalAlignment = Alignment.Top) {
                for (c in 0 until cols) Text(
                    inline(r.getOrElse(c) { "" }), fontSize = 14.sp, lineHeight = 21.sp, textAlign = t.aligns.getOrElse(c) { TextAlign.Start },
                    modifier = Modifier.width(widths[c]).padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** 朗读/复制用的纯文本 */
fun plainText(md: String) = md.replace(Regex("\\[\\[\\d+]]"), "").replace("**", "").replace("`", "")
