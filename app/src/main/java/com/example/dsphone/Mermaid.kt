package com.example.dsphone

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * Mermaid 图表:用内置网页组件加载打包在 assets 里的 mermaid.min.js 离线渲染。
 * 安全:禁止联网、禁止文件访问、mermaid 使用 strict 安全级别;网页只通过一个接口回传高度。
 */
private fun pageHtml(code: String, interactive: Boolean): String {
    val viewport = if (interactive) "width=device-width,initial-scale=1,minimum-scale=0.3,maximum-scale=6,user-scalable=yes"
    else "width=device-width,initial-scale=1,user-scalable=no"
    return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="$viewport">
<style>html,body{margin:0;padding:0;background:transparent}#box{padding:12px 8px;text-align:center}
svg{max-width:100%;height:auto}.err{color:#b3261e;font:14px sans-serif;text-align:left;padding:12px}</style>
<script src="mermaid.min.js"></script></head><body><div id="box"></div>
<script>
const CODE = ${JSONObject.quote(code)};
mermaid.initialize({startOnLoad:false, securityLevel:'strict', theme:'default', fontFamily:'sans-serif'});
(async () => {
  try { await mermaid.parse(CODE); const r = await mermaid.render('g', CODE); document.getElementById('box').innerHTML = r.svg; }
  catch (e) { document.getElementById('box').innerHTML = '<div class="err">图表语法有误,可切到「代码」查看</div>'; }
  setTimeout(() => Android.onHeight(Math.ceil(document.documentElement.scrollHeight)), 60);
})();
</script></body></html>"""
}

private class HeightBridge(private val cb: (Int) -> Unit) {
    @JavascriptInterface
    fun onHeight(h: Int) { Handler(Looper.getMainLooper()).post { cb(h) } }
}

@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
@Composable
private fun MermaidWebView(
    code: String, interactive: Boolean, modifier: Modifier,
    onHeight: (Int) -> Unit = {}, onView: (WebView) -> Unit = {},
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(AColor.TRANSPARENT)
                with(settings) {
                    javaScriptEnabled = true
                    blockNetworkLoads = true          // 不允许任何联网
                    allowFileAccess = false           // assets 仍可访问
                    allowContentAccess = false
                    domStorageEnabled = false
                    if (interactive) {
                        setSupportZoom(true); builtInZoomControls = true; displayZoomControls = false
                        useWideViewPort = true; loadWithOverviewMode = true
                    }
                }
                if (!interactive) {
                    isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
                }
                addJavascriptInterface(HeightBridge(onHeight), "Android")
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true   // 不允许跳转
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        if (request.url.toString().startsWith("file:///android_asset/")) null
                        else WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))   // 其余请求一律拦截
                }
                loadDataWithBaseURL("file:///android_asset/", pageHtml(code, interactive), "text/html", "UTF-8", null)
                onView(this)
            }
        },
        onRelease = { it.stopLoading(); it.destroy() },
    )
}

/** 把网页当前画面存成 PNG 到「图片/DeepSeek」 */
private fun saveWebViewPng(ctx: Context, wv: WebView) {
    runCatching {
        val bmp = Bitmap.createBitmap(wv.width.coerceAtLeast(1), wv.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.drawColor(AColor.WHITE); wv.draw(c)
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "diagram_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DeepSeek")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)!!
        ctx.contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }.onSuccess { Toast.makeText(ctx, "已保存到相册(DeepSeek 文件夹)", Toast.LENGTH_SHORT).show() }
        .onFailure { Toast.makeText(ctx, "保存失败", Toast.LENGTH_SHORT).show() }
}

@Composable
fun MermaidBlock(code: String) {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }       // 0 图表  1 代码
    var full by remember { mutableStateOf(false) }
    var heightDp by remember(code) { mutableIntStateOf(220) }
    var ready by remember(code) { mutableStateOf(false) }   // 图表画好之前显示占位提示
    var web by remember(code) { mutableStateOf<WebView?>(null) }

    Column(Modifier.padding(bottom = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFFF7F7F9))) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFFEDEEF2)).padding(3.dp)) {
                listOf("图表", "代码").forEachIndexed { i, label ->
                    Box(
                        Modifier.clip(RoundedCornerShape(8.dp)).background(if (tab == i) Color.White else Color.Transparent)
                            .clickable { tab = i }.padding(horizontal = 16.dp, vertical = 6.dp),
                    ) { Text(label, fontSize = 14.sp, color = if (tab == i) Color(0xFF16171A) else Color(0xFF8B8D98), fontWeight = if (tab == i) FontWeight.Medium else FontWeight.Normal) }
                }
            }
            Spacer(Modifier.weight(1f))
            if (tab == 0) {
                Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable { web?.let { saveWebViewPng(ctx, it) } }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.FileDownload, "保存", Modifier.size(18.dp), tint = Color(0xFF6B6D78))
                    Spacer(Modifier.width(4.dp)); Text("保存", fontSize = 14.sp, color = Color(0xFF6B6D78))
                }
                Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable { full = true }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.OpenInFull, "全屏", Modifier.size(18.dp), tint = Color(0xFF6B6D78))
                    Spacer(Modifier.width(4.dp)); Text("全屏", fontSize = 14.sp, color = Color(0xFF6B6D78))
                }
            }
        }
        if (tab == 0) {
            Box(Modifier.fillMaxWidth().height(heightDp.coerceIn(120, 600).dp)) {
                MermaidWebView(code, false, Modifier.fillMaxSize(), onHeight = { heightDp = it + 8; ready = true }, onView = { web = it })
                if (!ready) Text("图表渲染中…", fontSize = 14.sp, color = Color(0xFF8B8D98), modifier = Modifier.align(Alignment.Center))
                Box(Modifier.fillMaxSize().clickable { full = true })   // 盖一层透明层:点击进全屏,拖动仍交给列表滚动
            }
        } else {
            Text(
                code, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp,
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            )
        }
    }
    if (full) MermaidFullscreen(code) { full = false }
}

@Composable
private fun MermaidFullscreen(code: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var web by remember { mutableStateOf<WebView?>(null) }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.White) {
            Column(Modifier.fillMaxSize().padding(top = 28.dp)) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("图表(双指缩放、拖动查看)", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconButton({ web?.let { saveWebViewPng(ctx, it) } }) { Icon(Icons.Outlined.FileDownload, "保存") }
                    IconButton(onClose) { Icon(Icons.Outlined.Close, "关闭") }
                }
                MermaidWebView(code, true, Modifier.weight(1f).fillMaxWidth(), onView = { web = it })
            }
        }
    }
}
