@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.dsphone

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val Pri = Color(0xFF4D6BFE)
val PriCont = Color(0xFFE7EDFF)
val PriBorder = Color(0xFFC7D4FF)
val PriText = Color(0xFF2F4BD8)
val Grey = Color(0xFFF4F4F6)
val Mut = Color(0xFF8B8D98)
val LineC = Color(0xFFE3E4EA)
val Ink = Color(0xFF16171A)

private val BG_CHOICES: List<List<Color>> = listOf(
    listOf(Color(0xFFEEF3FF)), listOf(Color(0xFFFFF1F3)), listOf(Color(0xFFEFFAF2)), listOf(Color(0xFFFFF8E6)),
    listOf(Color(0xFFDFE9FF), Color(0xFFF3E4FF)), listOf(Color(0xFFFFE9D9), Color(0xFFFFD9E6)),
)

private fun brushOf(i: Int): Brush {
    val c = BG_CHOICES[i.coerceIn(0, BG_CHOICES.lastIndex)]
    return if (c.size == 1) SolidColor(c[0]) else Brush.linearGradient(c)
}

class Speaker(ctx: Context) {
    private var ready = false
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(ctx) { if (it == TextToSpeech.SUCCESS) { ready = true; tts.language = Locale.CHINA } }
    }

    fun say(text: String) {
        if (!ready) return
        if (tts.isSpeaking) { tts.stop(); return }
        tts.speak(text.take(3500), TextToSpeech.QUEUE_FLUSH, null, "msg")
    }

    fun shutdown() { tts.stop(); tts.shutdown() }
}

fun decodeSampled(path: String, max: Int): Bitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, o)
    var s = 1
    while (maxOf(o.outWidth, o.outHeight) / s > max * 2) s *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
}

@Composable
fun LocalImage(path: String, modifier: Modifier, crop: Boolean = true) {
    val bmp by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { decodeSampled(path, 800)?.asImageBitmap() }
    }
    bmp?.let { Image(it, null, modifier, contentScale = if (crop) ContentScale.Crop else ContentScale.Fit) }
}

@Composable
fun App(vm: ChatViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    var showSettings by remember { mutableStateOf(vm.s.apiKey.isBlank()) }
    var showModels by remember { mutableStateOf(false) }
    val speaker = remember { Speaker(ctx) }
    DisposableEffect(Unit) { onDispose { speaker.shutdown() } }
    LaunchedEffect(vm.speakRequest) { vm.speakRequest?.let { speaker.say(plainText(it)); vm.speakRequest = null } }
    LaunchedEffect(vm.toastMsg) { vm.toastMsg?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show(); vm.toastMsg = null } }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            HistoryDrawer(vm, onClose = { scope.launch { drawer.close() } },
                onSettings = { scope.launch { drawer.close() }; showSettings = true })
        },
    ) {
        Box(Modifier.fillMaxSize().background(Color.White)) {
            ChatBackground(vm)
            Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
                TopBar(vm, onMenu = { scope.launch { drawer.open() } }, onTitle = { showModels = true })
                MessageList(vm, onSpeak = { speaker.say(plainText(it)) }, modifier = Modifier.weight(1f))
                Dock(vm)
            }
        }
    }
    if (showModels) ModelSheet(vm) { showModels = false }
    if (showSettings) SettingsPage(vm) { showSettings = false }
}

// ---------------------------------------------------------------- 背景
@Composable
fun ChatBackground(vm: ChatViewModel) {
    val ctx = LocalContext.current
    val show = vm.cur.msgs.isNotEmpty() && vm.s.bgType != "none"
    AnimatedVisibility(show, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize()) {
            if (vm.s.bgType == "color") {
                Box(Modifier.fillMaxSize().background(brushOf(vm.s.bgColor)))
            } else {
                val bmp by produceState<ImageBitmap?>(null, vm.s.bgVersion) {
                    value = withContext(Dispatchers.IO) {
                        BitmapFactory.decodeFile(File(ctx.filesDir, "bg.jpg").path)?.asImageBitmap()
                    }
                }
                bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = vm.s.bgDim / 100f)))
            }
        }
    }
}

// ---------------------------------------------------------------- 顶栏
@Composable
fun IconBtn(icon: ImageVector, desc: String, onClick: () -> Unit) {
    IconButton(onClick, Modifier.size(44.dp)) { Icon(icon, desc, tint = Ink) }
}

@Composable
fun TopBar(vm: ChatViewModel, onMenu: () -> Unit, onTitle: () -> Unit) {
    val s = vm.s
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBtn(Icons.Outlined.Menu, "历史对话", onMenu)
        Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable(onClick = onTitle).padding(horizontal = 8.dp)) {
            Text(vm.cur.title.ifEmpty { "DeepSeek" }, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                vm.info.name + (if (s.think) " · 思考" + effortCn(s.effort) else "") + (if (s.web) " · 联网" else ""),
                fontSize = 11.5.sp, color = Mut, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        IconBtn(if (s.speak) Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff, "自动朗读") { s.speak = !s.speak; s.save() }
        IconBtn(Icons.Outlined.AddComment, "新对话") { vm.newConv() }
    }
}

fun effortCn(e: String) = when (e) { "low" -> "低"; "max" -> "最大"; else -> "高" }

// ---------------------------------------------------------------- 消息列表
@Composable
fun MessageList(vm: ChatViewModel, onSpeak: (String) -> Unit, modifier: Modifier) {
    val msgs = vm.cur.msgs
    val state = rememberLazyListState()
    val last = msgs.lastOrNull()
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(state.isScrollInProgress) { if (!state.isScrollInProgress) follow = !state.canScrollForward }
    LaunchedEffect(msgs.size) { follow = true }
    LaunchedEffect(msgs.size, last?.content?.length, last?.reasoning?.length, last?.status) {
        if (follow && msgs.isNotEmpty()) state.scrollToItem(msgs.size)   // 末尾占位项,滚到它 = 滚到底
    }
    val backing = vm.s.bgType == "image"
    LazyColumn(
        modifier, state, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        items(msgs) { m -> if (m.role == "user") UserBubble(m) else AssistantBlock(m, backing, onSpeak) }
        item { Spacer(Modifier.height(1.dp)) }
    }
}

@Composable
fun UserBubble(m: Msg) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(
            Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)).background(Color(0xFFF0F1F5)).padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            m.attachments.forEach { a ->
                if (a.kind == "image") {
                    LocalImage(a.path, Modifier.padding(bottom = 6.dp).size(180.dp, 140.dp).clip(RoundedCornerShape(10.dp)))
                } else {
                    Text(
                        "📄 ${a.name}" + if (a.kind == "missing") "(导出文件未包含内容)" else "",
                        fontSize = 13.sp, color = Mut, modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
            }
            if (m.content.isNotEmpty()) Text(m.content, fontSize = 16.sp, lineHeight = 25.sp)
        }
        if (m.asrSec > 0) Text("🎤 语音 ${m.asrSec} 秒 · ${Cost.yuan(m.asrCost)}", fontSize = 11.sp, color = Mut)
    }
}

@Composable
fun AssistantBlock(m: Msg, backing: Boolean, onSpeak: (String) -> Unit) {
    val clip = LocalClipboardManager.current
    val ctx = LocalContext.current
    val box = if (backing) Modifier.clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.8f)).padding(12.dp, 12.dp, 12.dp, 4.dp) else Modifier
    Column(Modifier.fillMaxWidth().then(box)) {
        if (m.reasoning != null && (m.reasoning!!.isNotEmpty() || m.streaming)) ThinkBlock(m)
        if (m.status.isNotEmpty()) Text(m.status, fontSize = 13.sp, color = Mut, modifier = Modifier.padding(bottom = 8.dp))
        if (m.content.isNotEmpty()) MdText(m.content)
        else if (m.streaming && m.status.isEmpty() && m.reasoning.isNullOrEmpty()) Text("…", fontSize = 16.sp, color = Mut)
        if (m.sources.isNotEmpty()) SourcesBlock(m.sources)
        if (!m.streaming && m.content.isNotEmpty()) {
            Row(Modifier.offset(x = (-8).dp)) {
                IconButton({ clip.setText(AnnotatedString(plainText(m.content))); Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show() }, Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.ContentCopy, "复制", Modifier.size(22.dp), tint = Color(0xFF5D5F6B))
                }
                IconButton({ onSpeak(m.content) }, Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.VolumeUp, "朗读", Modifier.size(22.dp), tint = Color(0xFF5D5F6B))
                }
            }
            if (m.costText.isNotEmpty()) Text(m.costText, fontSize = 11.5.sp, color = Mut)
        }
    }
}

@Composable
fun ThinkBlock(m: Msg) {
    var open by remember { mutableStateOf(false) }
    val expanded = open || (m.streaming && m.content.isEmpty())
    Column(Modifier.padding(bottom = 12.dp)) {
        Row(
            Modifier.clip(RoundedCornerShape(14.dp)).background(Grey).clickable { open = !open }.padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Psychology, null, Modifier.size(16.dp), tint = Mut)
            Spacer(Modifier.width(6.dp))
            Text(
                if (m.thinkSec != null) "已深度思考(用时 ${m.thinkSec} 秒)" else "正在思考…",
                fontSize = 13.sp, color = Mut,
            )
        }
        if (expanded && !m.reasoning.isNullOrEmpty()) {
            Row(Modifier.padding(top = 8.dp).height(IntrinsicSize.Min)) {
                Box(Modifier.width(2.dp).fillMaxHeight().background(LineC))
                Text(m.reasoning!!, fontSize = 14.sp, lineHeight = 23.sp, color = Mut, modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}

private val DOT_COLORS = listOf(Color(0xFFE5483D), Color(0xFFF2A33A), Color(0xFF7A8CFF), Color(0xFF34B27B))

@Composable
fun SourcesBlock(sources: List<Source>) {
    var show by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier.padding(top = 2.dp, bottom = 8.dp).clip(shape).border(1.dp, LineC, shape).clickable { show = !show }.padding(10.dp, 7.dp, 14.dp, 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
            sources.take(4).forEachIndexed { i, _ ->
                Box(Modifier.size(20.dp).clip(CircleShape).background(DOT_COLORS[i % 4]).border(2.dp, Color.White, CircleShape))
            }
        }
        Spacer(Modifier.width(8.dp))
        Text("${sources.size} 个网页", fontSize = 15.sp)
    }
    if (show) {
        Column(Modifier.padding(bottom = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, LineC, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp)) {
            sources.forEachIndexed { i, s ->
                Column(Modifier.fillMaxWidth().clickable { runCatching { uri.openUri(s.url) } }.padding(vertical = 9.dp)) {
                    Text("[${i + 1}] ${s.title}", fontSize = 13.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(s.url, fontSize = 12.sp, color = Mut, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (i < sources.lastIndex) HorizontalDivider(color = LineC)
            }
        }
    }
}

// ---------------------------------------------------------------- 底部输入区
@Composable
fun Dock(vm: ChatViewModel) {
    var panel by remember { mutableStateOf(false) }
    var voiceMode by remember { mutableStateOf(false) }
    val s = vm.s
    Column(
        Modifier.padding(horizontal = 6.dp).clip(RoundedCornerShape(26.dp, 26.dp, 0.dp, 0.dp)).background(Grey)
            .navigationBarsPadding().padding(start = 10.dp, end = 10.dp, top = 10.dp),
    ) {
        // 提示条:错误优先,否则显示费用
        val uri = LocalUriHandler.current
        val noMoney = vm.error?.contains("HTTP 402") == true   // 余额不足:点提示条直接去充值
        Row(
            Modifier.then(if (noMoney) Modifier.clickable { runCatching { uri.openUri(TOPUP_URL) } } else Modifier)
                .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Info, null, Modifier.size(18.dp), tint = if (vm.error != null) Color(0xFFD93025) else Color(0xFF797B87))
            Spacer(Modifier.width(8.dp))
            Text(
                vm.error?.let { it + if (noMoney) "  → 点此去充值" else "" } ?: "本对话 ${Cost.yuan(vm.cur.cost)} · 累计 ${Cost.yuan(vm.total)} · ${if (Cost.isPeak()) "⏰ 高峰价" else "🌙 空闲价"}",
                fontSize = 13.sp, color = if (vm.error != null) Color(0xFFD93025) else Color(0xFF797B87), lineHeight = 18.sp,
            )
        }
        // 输入卡
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color.White).padding(14.dp, 12.dp, 14.dp, 10.dp)) {
            if (vm.pending.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    vm.pending.toList().forEach { a -> PendingChip(a) { vm.removePending(a) } }
                }
            }
            if (voiceMode) HoldToTalk(vm) { voiceMode = false }
            else BasicTextField(
                vm.input, { vm.input = it }, Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, color = Ink, lineHeight = 24.sp),
                cursorBrush = SolidColor(Pri), maxLines = 6,
                decorationBox = { inner ->
                    Box { if (vm.input.isEmpty()) Text("发消息或按住说话", fontSize = 16.sp, color = Color(0xFFB3B5BE)); inner() }
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 点一下按 关 → 低 → 高 → 最大 循环;按钮形状固定,只变文字和颜色
                Pill(selected = s.think, onClick = { s.cycleEffort() }, icon = Icons.Outlined.Psychology, text = "深度思考",
                    trailing = if (s.think) effortCn(s.effort) else "关")
                Spacer(Modifier.width(6.dp))
                Pill(selected = s.web, onClick = {
                    s.web = !s.web; s.save()
                    if (s.web && !vm.info.serverWeb) vm.toast("Pro 联网:由手机端调用搜索/抓取接口(各 5 元/千次)")
                }, icon = Icons.Outlined.Language, text = "智能搜索")
                Spacer(Modifier.weight(1f))
                RoundBtn(if (panel) Icons.Outlined.Close else Icons.Outlined.Add, filled = false) { panel = !panel }
                Spacer(Modifier.width(6.dp))
                val has = vm.input.isNotBlank() || vm.pending.isNotEmpty()
                when {
                    vm.busy -> RoundBtn(Icons.Outlined.Stop, filled = true) { vm.stop() }
                    has -> RoundBtn(Icons.Outlined.ArrowUpward, filled = true) { panel = false; vm.send() }
                    else -> RoundBtn(if (voiceMode) Icons.Outlined.Keyboard else Icons.Outlined.GraphicEq, filled = false) { voiceMode = !voiceMode }
                }
            }
        }
        AnimatedVisibility(panel) { AttachPanel(vm) { panel = false } }
        Text("回答由 AI 生成,仅供参考", fontSize = 11.5.sp, color = Color(0xFFC0C2CA), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
    }
}

@Composable
fun Pill(selected: Boolean, onClick: () -> Unit, icon: ImageVector, text: String, trailing: String? = null) {
    val shape = RoundedCornerShape(19.dp)
    val fg = if (selected) PriText else Color(0xFF5D5F6B)
    Row(
        Modifier.height(36.dp).clip(shape).background(if (selected) PriCont else Color.White)
            .border(1.dp, if (selected) PriBorder else LineC, shape).clickable(onClick = onClick).padding(start = 8.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = fg)
        Spacer(Modifier.width(4.dp))
        Text(text, fontSize = 14.sp, color = fg, maxLines = 1)
        if (trailing != null) {
            Spacer(Modifier.width(6.dp))
            Box(Modifier.width(1.dp).height(16.dp).background(if (selected) PriBorder else LineC))
            Text(trailing, fontSize = 12.5.sp, color = fg, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.width(32.dp))
        }
    }
}

@Composable
fun RoundBtn(icon: ImageVector, filled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(if (filled) Pri else Color.White)
            .border(1.8.dp, if (filled) Pri else Ink, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(19.dp), tint = if (filled) Color.White else Ink) }
}

@Composable
fun PendingChip(a: Attach, onRemove: () -> Unit) {
    Box {
        if (a.kind == "image") LocalImage(a.path, Modifier.size(58.dp).clip(RoundedCornerShape(12.dp)))
        else Box(Modifier.height(58.dp).widthIn(max = 150.dp).clip(RoundedCornerShape(12.dp)).background(Grey).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text("📄 ${a.name}", fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier.align(Alignment.TopEnd).offset(6.dp, (-6).dp).size(18.dp).clip(CircleShape).background(Color(0xFF333333)).clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Close, "移除", Modifier.size(12.dp), tint = Color.White) }
    }
}

@Composable
fun HoldToTalk(vm: ChatViewModel, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val recorder = remember { WavRecorder() }
    var recording by remember { mutableStateOf(false) }
    var secs by remember { mutableIntStateOf(0) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) vm.toast("需要麦克风权限才能语音输入")
    }
    LaunchedEffect(recording) { secs = 0; while (recording) { delay(1000); secs++ } }
    Box(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).height(46.dp).clip(RoundedCornerShape(12.dp))
            .background(if (recording) Color(0xFFFFE3E1) else Grey)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        perm.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        val f = File(ctx.cacheDir, "rec_${System.nanoTime()}.wav")
                        recorder.start(f); recording = true
                        tryAwaitRelease()
                        recording = false
                        val sec = recorder.stop()
                        if (sec < 1) { f.delete(); vm.toast("说话时间太短") } else { vm.transcribe(f); onDone() }
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            when { vm.transcribing -> "识别中…"; recording -> "录音中 $secs 秒 · 松开结束(最长 120 秒)"; else -> "按住 说话" },
            fontWeight = FontWeight.SemiBold, color = if (recording) Color(0xFFD93025) else Color(0xFF555555),
        )
    }
}

// ---------------------------------------------------------------- 附件面板
@Composable
fun AttachPanel(vm: ChatViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var camFile by remember { mutableStateOf<File?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        camFile?.let { if (ok) vm.addPhoto(it) else it.delete() }
        onClose()
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) { vm.addUris(it); onClose() }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.addUris(it); onClose() }

    Column(Modifier.padding(top = 12.dp, bottom = 8.dp)) {
        PhotoStrip(vm)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigBtn(Icons.Outlined.CameraAlt, "拍照", Modifier.weight(1f)) {
                val f = File(ctx.cacheDir.resolve("cam").apply { mkdirs() }, "${System.nanoTime()}.jpg")
                camFile = f
                camera.launch(FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f))
            }
            BigBtn(Icons.Outlined.Image, "相册", Modifier.weight(1f)) {
                gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            BigBtn(Icons.Outlined.AttachFile, "文件", Modifier.weight(1f)) { files.launch(arrayOf("*/*")) }
        }
    }
}

@Composable
fun BigBtn(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.height(76.dp).clip(RoundedCornerShape(18.dp)).background(Color.White).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, label, Modifier.size(26.dp), tint = Ink)
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 15.sp)
    }
}

private fun mediaPerm() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

private fun recentImages(ctx: Context): List<Uri> {
    val out = ArrayList<Uri>()
    val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    ctx.contentResolver.query(base, arrayOf(MediaStore.Images.Media._ID), null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
        while (c.moveToNext() && out.size < 20) out.add(ContentUris.withAppendedId(base, c.getLong(0)))
    }
    return out
}

@Composable
fun PhotoStrip(vm: ChatViewModel) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, mediaPerm()) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var uris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    LaunchedEffect(granted) { if (granted) uris = withContext(Dispatchers.IO) { runCatching { recentImages(ctx) }.getOrDefault(emptyList()) } }
    if (!granted) {
        TextButton({ ask.launch(mediaPerm()) }, Modifier.padding(bottom = 6.dp)) { Text("允许读取相册,显示最近的照片") }
        return
    }
    LazyRow(Modifier.padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(uris) { u ->
            val sel = vm.pending.firstOrNull { it.src == u.toString() }
            val thumb by produceState<ImageBitmap?>(null, u) {
                value = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.loadThumbnail(u, Size(240, 300), null).asImageBitmap() }.getOrNull() }
            }
            Box(
                Modifier.size(92.dp, 112.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFFE6E7EC))
                    .clickable { if (sel != null) vm.removePending(sel) else vm.addUris(listOf(u)) },
            ) {
                thumb?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape)
                        .background(if (sel != null) Pri else Color(0x44000000)).border(2.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { if (sel != null) Text("✓", color = Color.White, fontSize = 12.sp) }
            }
        }
    }
}

// ---------------------------------------------------------------- 浮层
@Composable
fun ModelSheet(vm: ChatViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 28.dp)) {
            Text("选择模型", color = Mut, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp))
            Models.all.forEach { m ->
                val sel = vm.s.model == m.id
                val shape = RoundedCornerShape(14.dp)
                Column(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(shape).background(if (sel) PriCont else Color.Transparent)
                        .border(1.5.dp, if (sel) PriBorder else Color.Transparent, shape)
                        .clickable { vm.setModel(m.id); onDismiss() }.padding(12.dp),
                ) {
                    Text(m.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(m.desc, fontSize = 12.5.sp, color = Mut, lineHeight = 18.sp)
                }
            }
        }
    }
}

@Composable
fun Swatch(bg: Modifier, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(30.dp).clip(CircleShape).then(bg).border(2.dp, if (selected) Pri else LineC, CircleShape).clickable(onClick = onClick))
}

// ---------------------------------------------------------------- 设置页(整页分组列表)
private val PageBg = Color(0xFFF6F6F9)
private val RowLine = Color(0xFFF0F0F4)

@Composable
private fun GroupLabel(t: String) =
    Text(t, fontSize = 13.sp, color = Mut, modifier = Modifier.padding(start = 6.dp, top = 18.dp, bottom = 8.dp))

@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White), content = content)

@Composable
private fun Note(t: String) = Text(t, fontSize = 12.sp, color = Mut, lineHeight = 18.sp, modifier = Modifier.padding(16.dp, 8.dp, 16.dp, 12.dp))

/** 一行:左边名称(可带小字说明),右边放值/开关/控件 */
@Composable
private fun SRow(
    label: String, sub: String? = null, labelColor: Color = Ink, divider: Boolean = true,
    onClick: (() -> Unit)? = null, right: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 54.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 16.sp, color = labelColor)
            if (sub != null) Text(sub, fontSize = 12.sp, color = Mut)
        }
        right()
    }
    if (divider) HorizontalDivider(color = RowLine)
}

@Composable
private fun ValueText(t: String) = Text(t, fontSize = 15.sp, color = Mut, maxLines = 1)

@Composable
fun SettingsPage(vm: ChatViewModel, onBack: () -> Unit) {
    val s = vm.s
    var key by remember { mutableStateOf(s.apiKey) }
    var showModels by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val pickBg = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(vm::setBackgroundImage) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { it?.let(vm::exportTo) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importFrom) }
    val uriHandler = LocalUriHandler.current
    val openTopUp = { runCatching { uriHandler.openUri(TOPUP_URL) }; Unit }
    // 打开设置页、以及改完 Key 停顿 0.8 秒后,自动查询余额
    LaunchedEffect(key) { delay(800); vm.refreshBalance() }
    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize().background(PageBg).pointerInput(Unit) { detectTapGestures { } }.statusBarsPadding()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Icons.AutoMirrored.Outlined.ArrowBack, "返回", onBack)
            Text("设置", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp).navigationBarsPadding().padding(bottom = 30.dp)) {
            GroupLabel("账号")
            Group {
                SRow("API Key") {
                    BasicTextField(
                        key, { key = it; s.apiKey = it.trim(); s.save() }, Modifier.width(190.dp), singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), cursorBrush = SolidColor(Pri),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, color = Ink, textAlign = TextAlign.End),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterEnd) {
                                if (key.isEmpty()) Text("sk-…", fontSize = 16.sp, color = Color(0xFFB3B5BE))
                                inner()
                            }
                        },
                    )
                }
                val bal = vm.balance
                SRow(
                    "账户余额",
                    sub = when {
                        s.apiKey.isBlank() -> "填写 API Key 后显示"
                        vm.balanceLoading -> "查询中…"
                        vm.balanceError != null -> "查询失败:${vm.balanceError}(点击重试)"
                        bal == null -> "点击查询"
                        bal.items.isEmpty() -> "未返回余额信息"
                        else -> bal.items.joinToString(" · ") { "赠金 ${it.symbol}${it.granted} / 充值 ${it.symbol}${it.toppedUp}" } + "(点击刷新)"
                    },
                    onClick = { vm.refreshBalance() },
                ) {
                    if (bal != null && bal.items.isNotEmpty())
                        Text(bal.items.joinToString(" / ") { it.symbol + it.total }, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = if (bal.available) Ink else Color(0xFFD93025))
                }
                SRow("去充值", "跳转官方充值页,支付不经过本应用", divider = false, onClick = { openTopUp() }) {
                    Text("›", fontSize = 22.sp, color = Color(0xFFC0C2CA))
                }
                Note("Key 只保存在本机应用的私有目录,修改后自动保存。官方没有开放充值接口,充值需在网页完成。")
            }

            GroupLabel("通用")
            Group {
                SRow("默认模型", onClick = { showModels = true }) { ValueText(vm.info.name); Text("  ›", fontSize = 22.sp, color = Color(0xFFC0C2CA)) }
                SRow("自动朗读回答", divider = false) {
                    Switch(s.speak, { s.speak = it; s.save() }, colors = SwitchDefaults.colors(checkedTrackColor = Pri))
                }
            }

            GroupLabel("聊天背景")
            Group {
                SRow("颜色") {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Swatch(Modifier.background(Color.White), s.bgType == "none") { s.bgType = "none"; s.save() }
                        BG_CHOICES.indices.forEach { i ->
                            Swatch(Modifier.background(brushOf(i)), s.bgType == "color" && s.bgColor == i) { s.bgType = "color"; s.bgColor = i; s.save() }
                        }
                    }
                }
                SRow("从相册选择图片", onClick = { pickBg.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Text("›", fontSize = 22.sp, color = Color(0xFFC0C2CA))
                }
                if (s.bgType == "image") SRow("图片遮罩") {
                    Slider(s.bgDim / 100f, { s.bgDim = (it * 100).toInt() }, Modifier.width(170.dp), valueRange = 0f..0.85f, onValueChangeFinished = { s.save() })
                }
                Note("新对话保持空白,背景只在有对话时显示。")
            }

            GroupLabel("计费说明(元)")
            Group {
                SRow("Flash 每百万 tokens", "输入 / 缓存命中 / 输出(高峰)") { ValueText("2 / 0.04 / 8") }
                SRow("V4 Pro 每百万 tokens", "输入 / 缓存命中 / 输出(高峰)") { ValueText("9 / 0.30 / 27") }
                SRow("搜索 / 抓取") { ValueText("5 元 / 千次") }
                SRow("语音识别") { ValueText("0.39 元 / 小时") }
                SRow("OCR / 文件解析") { ValueText("1.5 元 / 千页") }
                Note("空闲时段(工作日 9–12、14–18 点以外)为高峰价的一半;语音识别为限时折扣价。")
            }

            GroupLabel("数据")
            Group {
                SRow("导出聊天记录", "含图片,格式与 DeepSeek 官方导出一致", onClick = {
                    exportLauncher.launch("deepseek_data-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.zip")
                }) { Text("›", fontSize = 22.sp, color = Color(0xFFC0C2CA)) }
                SRow("导入聊天记录", "选择 DeepSeek 官方导出的 .zip,重复导入会自动跳过", onClick = {
                    importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                }) { Text("›", fontSize = 22.sp, color = Color(0xFFC0C2CA)) }
                SRow("清空所有对话", labelColor = Color(0xFFD93025), divider = false, onClick = { confirmClear = true })
            }
        }
    }

    if (showModels) ModelSheet(vm) { showModels = false }
    if (vm.portStatus.isNotEmpty()) AlertDialog(
        onDismissRequest = {}, confirmButton = {},
        title = { Text(vm.portStatus, fontSize = 16.sp) },
        text = { LinearProgressIndicator(Modifier.fillMaxWidth(), color = Pri) },
    )
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("清空所有对话?") },
        text = { Text("聊天记录和其中的图片会从本机删除,无法恢复。") },
        confirmButton = { TextButton({ vm.clearAll(); confirmClear = false }) { Text("清空", color = Color(0xFFD93025)) } },
        dismissButton = { TextButton({ confirmClear = false }) { Text("取消") } },
    )
}

private val dateFmt = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)

@Composable
fun HistoryDrawer(vm: ChatViewModel, onClose: () -> Unit, onSettings: () -> Unit) {
    ModalDrawerSheet(Modifier.width(310.dp), drawerContainerColor = Color.White) {
        Column(Modifier.fillMaxHeight().statusBarsPadding().padding(top = 14.dp)) {
            Text("历史对话", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(18.dp, 4.dp, 18.dp, 10.dp))
            if (vm.convs.isEmpty()) {
                Text("还没有历史对话", color = Mut, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 30.dp).weight(1f))
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(vm.convs.toList()) { c ->
                        Row(
                            Modifier.padding(horizontal = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(if (c === vm.cur) Grey else Color.Transparent)
                                .clickable { vm.open(c); onClose() }.padding(start = 18.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(c.title.ifEmpty { "新对话" }, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${dateFmt.format(Date(c.updated))} · ${Cost.yuan(c.cost)}", fontSize = 12.sp, color = Mut)
                            }
                            IconButton({ vm.delete(c) }) { Icon(Icons.Outlined.Delete, "删除", Modifier.size(18.dp), tint = Mut) }
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = LineC)
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(18.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "累计 ${Cost.yuan(vm.total)}" + (vm.balanceShort?.let { "  ·  余额 $it" } ?: ""),
                    fontSize = 13.sp, color = Mut, modifier = Modifier.weight(1f),
                )
                FilledTonalButton(onSettings, colors = ButtonDefaults.filledTonalButtonColors(containerColor = Grey, contentColor = Ink)) { Text("设置") }
            }
        }
    }
}
