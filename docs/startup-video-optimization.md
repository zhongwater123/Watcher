# 开屏视频动画优化总结

## 任务概述

对 Watcher 应用的冷启动开屏视频动画进行全面优化，解决卡顿、黑屏、系统栏闪烁等问题，实现流畅的全屏视频播放和丝滑的过渡效果。

## 解决的问题

| 问题 | 根因 | 解决方案 |
|------|------|----------|
| 视频播放严重卡顿 | Compose 框架初始化与 ExoPlayer 竞争主线程 | 将视频播放移至 Window DecorView 层级，独立于 Compose |
| TextureView Surface 延迟 1.4 秒 | Compose 重度 measure/layout 阻塞绘制循环 | setContent 首帧只渲染空黑 Box，延迟加载 MainScreen |
| 视频播放到 400ms 崩溃 (error -62) | APK 中 mp4 文件被 AAPT2 压缩，MediaCodec 无法 seek | `build.gradle.kts` 添加 `noCompress += "mp4"` |
| MediaPlayer 不兼容 | 设备硬件 codec 问题 + playbackParams 不稳定 | 改回 ExoPlayer（更健壮的软件解码支持） |
| LiteRT 模型加载导致 MediaCodec 崩溃 | Application.onCreate 中 IO 密集操作抢占 CPU/内存 | 将 LiteRT 初始化延迟到视频播放完成后 |
| 转场时 MainScreen 加载阻塞淡出动画 | Compose 首次 composition 约 1.4 秒阻塞主线程 | 三阶段过渡：视频→黑屏（期间加载）→渐显主界面 |
| 系统栏在过渡期间闪烁 | MainScreen 的 DisposableEffect 主动调用 show(systemBars) | 添加 `manageSystemBars` 参数，视频期间禁止管理 |
| 全屏模式不生效 | enableEdgeToEdge() 与 immersive mode 冲突 | 视频期间不调用 enableEdgeToEdge，结束后再启用 |

## 最终架构

### 关键文件

| 文件 | 职责 |
|------|------|
| `ui/components/StartupVideoController.kt` | 自包含的 DecorView 层级视频 overlay 组件 |
| `MainActivity.kt` | 编排启动流程，协调视频与 Compose 的加载时序 |
| `WatcherApplication.kt` | 暴露 `initializeLiteRt()` 供延迟调用 |
| `ui/screens/MainScreen.kt` | 添加 `manageSystemBars` 参数控制系统栏管理 |
| `app/build.gradle.kts` | 添加 `noCompress += "mp4"` |

### 组件 API

```kotlin
class StartupVideoController private constructor(context: Context) {
    companion object {
        /** 每进程只播放一次。已播放返回 null。 */
        fun createIfFirstLaunch(context: Context): StartupVideoController?
    }

    /** 挂载 overlay 到 Window DecorView */
    fun attach(window: Window, onFadeStart: () -> Unit, onFinished: () -> Unit)

    /** 提前释放（Activity 销毁时） */
    fun release()
}
```

## 程序逻辑流程

```
┌─────────────────────────────────────────────────────────────────┐
│                    进程启动                                       │
├─────────────────────────────────────────────────────────────────┤
│ WatcherApplication.onCreate()                                    │
│   - 不再做 LiteRT 初始化（延迟到视频后）                           │
│   - agentFrameworkContainer 保持 lazy                            │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                 MainActivity.onCreate()                           │
├─────────────────────────────────────────────────────────────────┤
│ 1. installSplashScreen()                                         │
│ 2. super.onCreate()                                              │
│ 3. 不调用 enableEdgeToEdge()（视频期间不启用）                     │
│                                                                   │
│ 4. StartupVideoController.createIfFirstLaunch(this)              │
│    - AtomicBoolean 判断：首次启动返回实例，否则返回 null            │
│                                                                   │
│ 5. controller.attach(window, onFadeStart, onFinished)            │
│    ┌─────────────────────────────────────────────────────────┐   │
│    │ attach() 内部:                                           │   │
│    │ a. enterImmersiveMode() — 隐藏系统栏 + 设黑色栏底色      │   │
│    │ b. 启动 immersiveEnforcer（每100ms强制全屏）              │   │
│    │ c. buildOverlayView() → 添加到 DecorView 最顶层          │   │
│    │    - FrameLayout (黑色背景, MATCH_PARENT)                │   │
│    │      - videoContainer > TextureView (居中, 72%W x 64%H) │   │
│    │      - VignetteView (圆形渐变遮罩, 中心透明→边缘纯黑)    │   │
│    │ d. postDelayed(timeoutRunnable, 5000ms) 超时保护          │   │
│    └─────────────────────────────────────────────────────────┘   │
│                                                                   │
│ 6. setContent { WatcherTheme { Box(黑色) } }                     │
│    - 首帧极轻量，不加载 MainScreen                                │
│    - 让 Compose 绘制循环快速完成 → TextureView 尽早获得 Surface   │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼ (~300-400ms)
┌─────────────────────────────────────────────────────────────────┐
│              TextureView Surface 就绪                             │
├─────────────────────────────────────────────────────────────────┤
│ onSurfaceReady(textureView):                                     │
│ 1. 构建 ExoPlayer                                                │
│    - volume = 0 (静音)                                           │
│    - setMediaItem(android.resource://...R.raw.app_openvideo)     │
│    - setVideoTextureView(textureView)                            │
│    - prepare() + playWhenReady = true                            │
│ 2. 注册 Player.Listener:                                         │
│    - onRenderedFirstFrame → hasStartedPlayback = true            │
│    - onPlaybackStateChanged(ENDED) → fadeOutAndDetach()          │
│    - onPlayerError → finishAndDetach()                           │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼ (~600ms)
┌─────────────────────────────────────────────────────────────────┐
│              视频首帧渲染 → 6秒流畅播放                            │
├─────────────────────────────────────────────────────────────────┤
│ - 主线程几乎空闲（Compose 只维持一个空黑 Box）                     │
│ - LiteRT 不初始化，无后台资源竞争                                  │
│ - immersiveEnforcer 确保系统栏持续隐藏                             │
│ - 用户看到：全屏视频 + 圆形暗角遮罩                                │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼ (~6600ms)
┌─────────────────────────────────────────────────────────────────┐
│              三阶段过渡 fadeOutAndDetach()                         │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│ Phase 1: 视频→黑屏 (600ms)                                       │
│ ─────────────────────────────                                     │
│ - player.release() — TextureView 清空                            │
│ - overlay 保持 alpha=1.0（纯黑背景可见）                          │
│ - 用户看到：视频内容消失，留下纯黑屏                               │
│                                                                   │
│ Phase 2: 加载 MainScreen (1600ms等待)                             │
│ ─────────────────────────────────────                             │
│ - onFadeStart() 回调触发 → showMain.value = true                 │
│ - Compose recompose → MainScreen(manageSystemBars=false)         │
│ - MainScreen 初始化 ViewModel/Repository (~1.4秒)                │
│ - 期间黑色 overlay 盖在上面，用户只看到黑屏（不感知卡顿）          │
│ - manageSystemBars=false 阻止 MainScreen 操控系统栏               │
│                                                                   │
│ Phase 3: 黑屏渐显主界面 (800ms)                                   │
│ ─────────────────────────────────                                 │
│ - postDelayed(1600ms) 确保 MainScreen 已初始化完毕               │
│ - overlay.animate().alpha(0f).duration(800ms)                    │
│ - 用户看到：黑屏逐渐变透明，MainScreen 从下面浮现                  │
│ - withEndAction → finishAndDetach()                              │
│                                                                   │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼ (~9500ms)
┌─────────────────────────────────────────────────────────────────┐
│              finishAndDetach() — 清理与交接                        │
├─────────────────────────────────────────────────────────────────┤
│ 1. released = true                                               │
│ 2. 停止 immersiveEnforcer                                        │
│ 3. exitImmersiveMode() — 恢复系统栏颜色 + 显示系统栏              │
│ 4. removeOverlay() — 从 DecorView 移除 overlay                   │
│ 5. onFinished() 回调:                                            │
│    - enableEdgeToEdge()                                          │
│    - allowSystemBars.value = true → MainScreen 接管系统栏管理     │
│    - requestNotificationPermissionIfNeeded()                     │
│    - watcherApplication().initializeLiteRt() — 此时才初始化模型   │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│              正常运行状态                                          │
├─────────────────────────────────────────────────────────────────┤
│ - MainScreen 完全可见，正常管理系统栏                              │
│ - LiteRT 模型在后台 IO 线程加载                                   │
│ - 后续进程内重启不再播放视频（AtomicBoolean gate）                 │
└─────────────────────────────────────────────────────────────────┘
```

## 关键设计决策

### 为什么用 DecorView overlay 而非 Compose 内嵌？

Compose 框架首次初始化（Recomposer、AndroidComposeView、measure/layout）约占 1.4 秒主线程时间。如果视频在 Compose 内部播放，ExoPlayer 的帧投递必须与 Compose 竞争主线程，导致严重卡顿。DecorView overlay 完全绕过 Compose，视频播放零干扰。

### 为什么延迟 LiteRT 初始化？

LiteRT 模型加载（`liteRtEngineManager.initialize`）在 IO 线程运行，但消耗大量 CPU 和内存。这直接导致 ExoPlayer 的 MediaCodec 在播放约 2 秒后因资源不足崩溃（error what=1 extra=-62）。延迟到视频完成后初始化彻底消除了这个问题。

### 为什么用 ExoPlayer 而非 MediaPlayer？

MediaPlayer 对视频文件的兼容性差，在测试设备上无论如何重编码都在 ~400ms 处报 error -62 崩溃。ExoPlayer 的软件解码路径更健壮，能正确处理各种编码格式。

### 为什么首帧用空黑 Box？

`setContent` 注册 ComposeView 后，Android 的绘制循环需要完成一次 traversal 才能为 TextureView 创建 Surface。如果首帧包含重量级 MainScreen，traversal 耗时 1.4 秒，Surface 延迟到 1.4 秒后才可用。首帧只画一个空 Box，traversal 在 ~16ms 内完成，Surface 在 ~300ms 即可用。

### 三阶段过渡的意义

MainScreen 首次 composition 阻塞主线程约 1.4 秒，期间无法执行任何动画。三阶段设计：
1. 先让视频自然结束为黑屏
2. 在黑屏遮盖下加载 MainScreen（用户看不到卡顿）
3. 加载完毕后平滑淡出黑屏，揭露已就绪的 MainScreen

### manageSystemBars 参数

MainScreen 内部有 `DisposableEffect` 在竖屏时调用 `insetsController.show(systemBars())`。如果在视频 overlay 存在期间 compose，会导致系统栏闪烁。通过 `manageSystemBars = false` 参数在过渡期间禁用此行为。

## 典型时序（基于实际日志）

```
T+0ms      attach() — overlay 挂载，immersive mode 启动
T+300ms    TextureView Surface 就绪
T+400ms    ExoPlayer prepared, playWhenReady=true
T+600ms    视频首帧渲染
T+600-6600ms  视频流畅播放 (6秒)
T+6600ms   playback ended → Phase 1 开始
T+7200ms   Phase 2: MainScreen 加载触发
T+8800ms   Phase 3: 黑屏淡出开始 (800ms)
T+9600ms   finishAndDetach — overlay 移除，主界面完全接管
```

## 构建配置变更

```kotlin
// app/build.gradle.kts
androidResources {
    noCompress += "litertlm"
    noCompress += "mp4"  // 防止 AAPT2 压缩视频资源，确保 MediaCodec 可 seek
}
```

## 视频资源要求

- 格式：H.264 MP4
- 存放：`app/src/main/res/raw/app_openvideo.mp4`
- 建议编码参数：Main profile, CRF 23, GOP 30, 无音轨 (`-an`)
- 尺寸不限（运行时按 72%W x 64%H viewport 自适应缩放）
