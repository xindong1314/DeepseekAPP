# DeepseekAPP

一个用 **API Key** 直接调用 DeepSeek 官方接口的安卓客户端。原生 Kotlin + Jetpack Compose 编写,没有中转服务器,Key 只保存在你自己的手机上。

> 非官方项目,与 DeepSeek 公司无关。

## 功能

| 功能 | 说明 |
| --- | --- |
| 对话 | 流式输出,Markdown 渲染(标题、加粗、列表、代码块、引用角标) |
| 模型 | `deepseek-flash`、`deepseek-v4-pro`,点顶部标题切换 |
| 思考强度 | 关 / 低 / 高 / 最大,点一下按钮循环切换 |
| 联网搜索 | Flash 使用官方服务端联网工具;Pro 由手机端调用搜索/抓取接口再回传给模型 |
| 图片 | 相册、拍照、最近照片。Flash 直接看图;Pro 先用 OCR 转成文字 |
| 文件 | PDF / Word / PPT 走文件解析(Flash)或 OCR(Pro);纯文本文件直接读取 |
| 语音输入 | 按住说话,调用官方语音识别接口(16kHz WAV,最长 120 秒) |
| 费用显示 | 每条回复显示 token、搜索、OCR 等明细和费用;顶部显示本对话和累计费用 |
| 聊天记录 | 全部保存在本机,历史抽屉里切换和删除 |
| 自定义背景 | 纯色、渐变或自己的图片;新对话保持空白 |

## 安装使用

1. 到 [Releases](../../releases) 下载 APK(或自己编译,见下文),安装到安卓 10 及以上的手机。
2. 首次打开会进入设置页,填入你的 DeepSeek API Key。Key 在 [DeepSeek 开放平台](https://platform.deepseek.com/) 获取。
3. 回到聊天界面即可使用。

## 计费说明

应用本身免费,调用 DeepSeek 接口产生的费用由你的账户承担。界面上显示的费用是按接口返回的 token 用量和下列价格**在本地估算**的,可能与账单有细微差别,请以官方账单为准。

| 项目 | 价格 |
| --- | --- |
| flash | 输入 2 / 缓存命中 0.04 / 输出 8(元 / 百万 tokens,高峰价) |
| v4-pro | 输入 9 / 缓存命中 0.30 / 输出 27(元 / 百万 tokens,高峰价) |
| 搜索、抓取 | 5 元 / 千次 |
| OCR / 文件解析 | 1.5 元 / 千页 |
| 语音识别 | 0.39 元 / 小时(限时折扣价) |

空闲时段价格为高峰价的一半。高峰时段为北京时间工作日 9:00–12:00、14:00–18:00;程序目前只排除了周末,没有排除法定节假日。价格取自官方文档,可能随时调整。

## 自行编译

需要 JDK 17、Android SDK(`compileSdk` 为 36.1,即 `platforms;android-36.1`)。

```bash
# 在项目根目录创建 local.properties,写入你的 SDK 路径(注意用正斜杠)
echo "sdk.dir=C:/Users/你/AppData/Local/Android/Sdk" > local.properties

./gradlew assembleDebug      # 调试版:app/build/outputs/apk/debug/
./gradlew assembleRelease    # 发布版:app/build/outputs/apk/release/
```

- `gradle/wrapper/gradle-wrapper.properties` 里的 Gradle 下载地址是**腾讯云镜像**,方便国内下载。海外用户可以改回 `https://services.gradle.org/distributions/gradle-8.13-bin.zip`。
- 发布版目前用 debug 签名,可直接安装自用。要上架应用商店需要换成你自己的签名密钥。

## 项目结构

```text
app/src/main/java/com/example/dsphone/
├── Api.kt            官方接口封装:对话流式、语音识别、OCR、文件上传、搜索、抓取
├── ChatViewModel.kt  发送流程、附件预处理、Pro 联网的工具调用循环、费用结算
├── Pricing.kt        模型、价格、高峰判断、费用计算
├── Data.kt           消息 / 对话数据模型、本地存储、设置
├── Media.kt          图片缩放与导入、PDF 页数、WAV 录音
├── Markdown.kt       简易 Markdown 渲染
├── Ui.kt             全部界面(顶栏、消息、输入区、历史、设置)
└── MainActivity.kt
mockup.html           界面原型,浏览器直接打开即可预览
```

## 当前状态

早期版本。已在模拟器里验证:应用能启动、界面正常、请求能发到 DeepSeek 并正确显示错误。**真实对话、联网搜索、图片/文档处理、语音输入、拍照**这些依赖有效 Key 和真机硬件的路径,是按官方文档实现的,还没有充分的真机测试,遇到问题欢迎提 Issue。

## 许可

[MIT](LICENSE)
