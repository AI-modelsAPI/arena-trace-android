# Arena Trace (Android)

Chrome 扩展 `arena-trace-inspector` 的原生 Android 移植骨架。用 WebView 打开 arena.ai，
页面层 JS 钩子截获运行令牌，原生层拉取 trace 和额度，悬浮卡片展示结果。

## 构建

**环境要求**：JDK 17、Gradle 8.7、Android SDK 34（AGP 8.5.2 / Kotlin 2.0.21）。Java 与 Kotlin
的字节码目标已统一为 17（`app/build.gradle.kts` 的 `compileOptions` 与 `kotlin.compilerOptions`）。

Android Studio：
1. 安装 Android Studio（含 Android SDK 34，使用 JDK 17）
2. 打开本目录，等待 Gradle 同步完成
3. 连接手机（开 USB 调试）或起模拟器，点 Run

命令行（调试包）：
```bash
./gradlew testDebugUnitTest assembleDebug   # 若已配置 Gradle Wrapper
# 或使用本机 gradle：
gradle testDebugUnitTest assembleDebug
```
产物：`app/build/outputs/apk/debug/app-debug.apk`（调试签名）。

> 说明：本仓库暂无单元测试，`testDebugUnitTest` 为 `NO-SOURCE`；编译通过不等于已在真机验证登录与令牌截获。
> 校验方法与 APK 摘要见 `docs/verification/2026-09-22-jvm17-build.md`。

## 架构对照（扩展 → 本工程）

| 扩展模块 | 本工程 | 说明 |
|---|---|---|
| `snoop.js`（页面钩子） | `app/src/main/assets/snoop.js` | 几乎原样，仅把 `postMessage` 换成 `ArenaTrace.onSnoop()` 桥 |
| `snoop-bridge.js` | `bridge/ArenaBridge.kt` | `@JavascriptInterface` 直达原生层 |
| `core.js validateToken` | `net/TraceClient.kt validate()` | 令牌校验规则逐条移植（pub/iss/aud/exp/scope） |
| `background.js` trace 轮询 | `net/TraceClient.kt fetchModels()` | 同一 Trigger.dev 接口，8 次 × 3 秒轮询 |
| `core.js extractModels` | `net/TraceClient.kt extractModels()` | 只读 `ai.streamText.doStream` 的 cube 标签 |
| `pulse.js` + 额度缓存 | `net/PulseClient.kt` + `MainActivity` | 60 秒轮询；cookie 签名变化（切账号）立即刷新；429 退避 |
| HUD 浮层（含进度条三色） | `res/layout/activity_main.xml` 卡片 | 剩余 <20% 黄、<10% 红、其余绿；倒计时每秒跳动 |

## 已实现

- WebView 打开 arena.ai，Cookie 与页面登录态共享
- 发送消息后自动截获令牌 → 拉 trace → 显示服务端模型名
- 额度百分比 + 进度条 + 重置倒计时（秒级）
- 切账号即时刷新额度；429 按 Retry-After 退避

## 待移植（二期）

- 自动抽卡 / 自动探针（`auto-draw.js`：新建对话、填 prompt、点发送、目标匹配、命中改名加 -NNN 后缀）
- 一键清理探测残留（侧栏算式标题归档）
- 会话历史本地记录（`history.js` → Room/SharedPreferences）
- HUD 拖动 / 收起自动隐藏

## 注意

- `chrome.debugger` 捕获通道在 WebView 里不存在，本工程只保留页面钩子通道；
  如果 Arena 把令牌移出 SSE 响应体，需要改用 `WebViewClient.shouldInterceptRequest` 补一条腿
- snoop.js 在 `onPageFinished` 注入；若 Arena 首屏就开会话流，可改到 `onPageStarted` 注入
