# Mermaid 渲染页通过 @JavascriptInterface 回传高度:方法名不能被混淆或删除,否则图表会一直卡在"渲染中"
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.example.dsphone.HeightBridge { *; }

# OkHttp / Okio 自带消费者规则;这里无需额外保留
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
