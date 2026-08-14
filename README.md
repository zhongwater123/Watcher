# Watcher

> 都在看别人，什么时候看自己？

Watcher 是一个 Android + 外部视觉节点的 AI 原型项目。它的核心问题不是“再做一个摄像头 App”，而是：当手机无法同时承担主交互设备和固定视觉传感器两种角色时，一个低负担、易固定、可独立运行的第二视觉节点，能否提供手机摄像头无法提供的价值？

当前项目有一个已经落地得最完整的应用抓手：**AI 课堂记录与学习助手**。在此基础上，项目下一阶段会重点推进**手机 + 外部硬件摄像头的双视角力量训练分析**，并保留本地模型、Agent、Gateway、多设备协同等底层探索。

## 当前最完整场景：AI 课堂

AI 课堂是目前最适合作为演示入口的具体应用场景。它把一节课从“视频和声音”整理成可追问、可回看、可复制的学习材料。

### 已实现能力

- 一键录课：支持课程名称、录制时长和输入源选择。
- 实时语音识别：录制过程中持续接收转写文本。
- 滚动课堂要点：边录边从转写中提取稳定的课堂要点。
- 课堂字幕选择：用户可以选择几段刚刚出现的字幕作为追问上下文。
- 即时追问：支持“解释这段”“举个例子”“为什么这样”等课堂内追问。
- 视觉证据补充：追问时可以调取近邻视频帧，帮助模型结合板书、投影或画面内容回答。
- 测试视频导入：支持用本地测试视频验证完整流程。
- 分片处理：长时间课程会拆成多个片段处理，避免一次性分析过大。
- 最终课堂笔记：生成结构化 Markdown 笔记，包含课程主题、知识点、例子、总结和复习线索。
- 历史回看：保留录制结果、片段、转写、报告和笔记。

### 代码入口

| 目标 | 主要文件 |
| --- | --- |
| 课堂页面和交互 | `app/src/main/java/com/example/watcher/ui/screens/ClassroomRecordingPage.kt` |
| 课堂结果 UI 模型 | `app/src/main/java/com/example/watcher/ui/screens/ClassroomRecordingResultModels.kt` |
| 课堂流程总编排 | `app/src/main/java/com/example/watcher/data/repository/ClassroomRecordingOrchestrator.kt` |
| 课堂默认时长和提示词配置 | `app/src/main/java/com/example/watcher/data/model/ClassroomRecordingDefaults.kt` |
| 课堂输入源模型 | `app/src/main/java/com/example/watcher/data/model/ClassroomRecordingInput.kt` |
| 实时音频与 ASR | `app/src/main/java/com/example/watcher/data/repository/ClassroomRealtimeAudio.kt` |
| 录制音频会话 | `app/src/main/java/com/example/watcher/data/repository/ClassroomAudioCaptureSession.kt` |
| 分片分析 | `app/src/main/java/com/example/watcher/data/repository/ClassroomSegmentAnalyzer.kt` |
| 片段处理 | `app/src/main/java/com/example/watcher/data/repository/ClassroomSegmentProcessor.kt` |
| 课堂提示词构造 | `app/src/main/java/com/example/watcher/data/repository/ClassroomPromptBuilder.kt` |
| 字幕选择策略 | `app/src/main/java/com/example/watcher/data/repository/ClassroomTranscriptSelectionPolicy.kt` |
| 字幕草稿构造 | `app/src/main/java/com/example/watcher/data/repository/ClassroomTranscriptDraftBuilder.kt` |
| 即时追问处理 | `app/src/main/java/com/example/watcher/data/repository/ClassroomInlineQuestionProcessor.kt` |
| 视觉证据分析 | `app/src/main/java/com/example/watcher/data/repository/ClassroomVisualEvidenceAnalyzer.kt` |
| 附近帧缓存 | `app/src/main/java/com/example/watcher/data/repository/ClassroomFrameEvidenceCache.kt` |
| 课堂笔记合成 | `app/src/main/java/com/example/watcher/data/repository/ClassroomNoteSynthesizer.kt` |
| 测试视频导入 | `app/src/main/java/com/example/watcher/data/repository/ClassroomTestVideoImporter.kt` |

## 下一阶段重点：双视角力量训练

Watcher 后续的硬件方向会围绕双视角力量训练展开，尤其是深蹲、硬拉、推举、弓步等需要稳定外部视角的动作。

这里的目标不是“多一个摄像头”，而是把手机从固定拍摄任务里解放出来：手机负责交互、提示和结果展示；外部 Watcher 节点负责放在侧面、器械上、支架上或其他稳定位置，提供持续外部视角。

### 已实现和正在验证的能力

- 手机前摄 + 外部 MJPEG 摄像头双路输入。
- ArUco marker 标定状态机：`CALIBRATING -> CALIBRATED -> LOADING_ENGINES -> READY`。
- 正面 / 侧面双路 MediaPipe 推理。
- 正面 GPU LITE、侧面 CPU LITE 的分工验证。
- 双路姿态 overlay、FPS 和推理延迟诊断。
- 侧面流读取和推理分离，避免帧积压。
- 退出安全处理，降低 MediaPipe engine release 时的竞态崩溃。
- 双摄角度、距离和高度差估计探索。

### 计划中的训练理解能力

- 深蹲动作阶段识别。
- 次数统计。
- 膝盖轨迹、髋膝角、躯干角度、重心稳定性评估。
- 正面左右平衡 + 侧面脊柱/髋膝角度的组合判断。
- 代偿风险提示和训练反馈。

### 代码入口

| 目标 | 主要文件 |
| --- | --- |
| 双摄健身主界面 | `app/src/main/java/com/example/watcher/ui/screens/FitnessScreen.kt` |
| 姿态场景选择 | `app/src/main/java/com/example/watcher/ui/screens/PoseScenarioSelectScreen.kt` |
| 姿态识别入口页 | `app/src/main/java/com/example/watcher/ui/screens/PoseEstimationScreen.kt` |
| 姿态识别 Activity | `app/src/main/java/com/example/watcher/PoseEstimationActivity.kt` |
| 姿态推理引擎 | `app/src/main/java/com/example/watcher/data/local/pose/PoseEstimationEngine.kt` |
| 姿态数据模型 | `app/src/main/java/com/example/watcher/data/local/pose/PoseModels.kt` |
| ArUco 标定 | `app/src/main/java/com/example/watcher/data/local/pose/ArUcoCalibrator.kt` |
| 双摄粗标定实验 | `app/src/main/java/com/example/watcher/data/local/pose/DualCameraCalibration.kt` |
| 姿态可视化叠层 | `app/src/main/java/com/example/watcher/ui/components/PoseOverlay.kt` |
| 双摄 POC 文档 | `docs/ai-fitness-dual-camera-poc.md` |
| ArUco 实现文档 | `docs/aruco-calibration-implementation.md` |

## 功能索引与代码导航

下面按真实代码整理项目已经开发出的功能。状态说明：

- **可演示**：已有相对完整 UI 和流程。
- **POC**：已有验证代码，但仍偏实验。
- **底层能力**：主要服务于其他场景。
- **探索中**：功能存在，但不是当前主线。

| 功能 | 状态 | App 入口 / 说明 | 代码入口 |
| --- | --- | --- | --- |
| AI 课堂记录 | 可演示 | `Analysis` 简洁模式 / 课堂记录页 | `ClassroomRecordingPage.kt`, `ClassroomRecordingOrchestrator.kt` |
| 课堂实时 ASR | 可演示 | 课堂录制过程中自动运行 | `ClassroomRealtimeAudio.kt`, `StreamingAsrClient.kt`, `AsrConfigRepository.kt` |
| 课堂即时追问 | 可演示 | 选中字幕后弹出追问浮层 | `ClassroomInlineQuestionProcessor.kt`, `ClassroomVisualEvidenceAnalyzer.kt` |
| 课堂笔记合成 | 可演示 | 录课结束后生成 Markdown | `ClassroomNoteSynthesizer.kt`, `ClassroomAudioOutlineProcessor.kt` |
| 测试视频录课 | 可演示 | 课堂页面导入测试视频 | `ClassroomTestVideoImporter.kt`, `TestVideoAudioFramePlayer.kt`, `TestVideoFrameProvider.kt` |
| 双视角 AI 健身 | POC / 下一阶段主线 | `Hub -> Pose Estimation -> AI健身` | `FitnessScreen.kt`, `ArUcoCalibrator.kt`, `PoseEstimationEngine.kt` |
| 实时姿态识别 | 可演示 | `Hub -> Pose Estimation -> 实时识别` | `PoseEstimationScreen.kt`, `PoseEstimationEngine.kt`, `PoseOverlay.kt` |
| 舞蹈学习 | POC | `Hub -> Pose Estimation -> 舞蹈学习` | `DanceLearningScreen.kt`, `DanceProcessingScreen.kt`, `DancePosePlaybackScreen.kt` |
| 舞蹈分段与节拍 | POC | 舞蹈学习流程内 | `DanceSegmentationEngine.kt`, `BeatAnalysisProcessor.kt`, `TarsosDspAnalyzer.kt` |
| 通用长视频分析 | 可演示 / 探索 | `Analysis` 完整模式 | `VideoAnalysisWorkbenchPage.kt`, `VideoProcessRepository.kt`, `VideoExecutionOrchestrator.kt` |
| 视频任务规划 | 可演示 / 探索 | 长视频分析流程内 | `VideoTaskPlanner.kt`, `VideoExecutionModels.kt` |
| 视频证据补强 | 可演示 / 探索 | 长视频分析和课堂笔记复用 | `VideoEvidenceChunkAnalyzer.kt`, `VideoReportRefiner.kt`, `VideoAiTraceLogger.kt` |
| API Wallet | 可演示 | 顶部设置入口 / API 钱包 | `ApiWalletScreen.kt`, `ApiWalletViewModel.kt`, `LlmWalletRepository.kt` |
| ASR 配置钱包 | 可演示 | API Wallet 内 | `ApiWalletAsrCard.kt`, `AsrConfigRepository.kt`, `VolcengineAsrWireProtocol.kt` |
| Provider 加密存储 | 底层能力 | API Wallet 保存 provider 后生效 | `ProviderSecretStore.kt`, `LlmProviderDao`, `LlmProviderEntity` |
| LiteRT 本地模型工作台 | POC | `Hub -> 本地大模型工作台` | `LiteRtScreen.kt`, `LiteRtViewModel.kt`, `data/local/litert/*` |
| 本地 Agent | POC | `Hub -> 本地 Agent` | `LocalAgentScreen.kt`, `LocalAgentViewModel.kt`, `localagent/*` |
| Google ADK Kotlin 验证 | POC | 本地 Agent 页面启动时检测 | `LocalAgentQuickstartAgent.kt`, `LocalAgentAdkModel.kt`, `TimeService.kt` |
| Agent Framework | 底层能力 | Gateway / Agent Config / 运行时服务 | `agentframework/service/AgentFrameworkService.kt`, `agentframework/autonomy/*`, `agentframework/graph/*` |
| Agent brain catalog | 底层能力 | App 启动时装配 | `WatcherApplication.kt`, `AppDefaultAgentBrainFactory.kt`, `LiteRtAgentBrainFactory.kt` |
| 多端聚合 Gateway | 可演示 / 底层能力 | `Hub -> 多端聚合` | `MultiDeviceScreen.kt`, `GatewayServer.kt`, `GatewayRoutes.kt` |
| ntfy 对话接力 | POC | 多端聚合 / MCP relay tools | `NtfyRelayClient.kt`, `GatewayDelegate.kt`, `mcp/lib/ntfy-client.js` |
| MCP companion | 可用工具层 | `mcp/` Node package | `mcp/server.js`, `mcp/lib/gateway-client.js`, `mcp/lib/discovery.js` |
| Digital Life Card | 探索中 | `Hub -> 用户行为模型工作台` | `DigitalLifeCardWorkspacePage.kt`, `DigitalLifeCardViewModel.kt` |
| Live Room | 探索中 | 横屏入口 | `LiveRoomScreen.kt`, `LiveInteractionController.kt`, `LiveCommentaryRepository.kt` |
| Council 多专家 | 探索中 | 横屏入口 / Council | `CouncilModeScreen.kt`, `CouncilManager.kt`, `CouncilExpertRepository.kt` |
| Council 专家 Agent | 业务运行时 | Council 分析与讨论 | `data/council/agent/*` |
| 健身规划与旧视觉反馈 Agent | 业务运行时 | 健身助手 | `data/fitness/agent/*` |
| AI Audience | 探索中 | Live / Audience 管理 | `AiAudienceManagementCard.kt`, `AiAudienceManager.kt`, `data/repository/agent/*` |

## 目录结构

```text
app/
  src/main/java/com/example/watcher/
    agentframework/         Agent runtime、graph runtime、memory、knowledge、tools
    data/council/agent/     Council expert agent runtime、tools、memory
    data/fitness/agent/     Fitness planning and isolated legacy visual feedback agents
    data/training/fitness/  Fitness frame-analyzer contracts
    data/gateway/           Embedded LAN gateway、pairing、ntfy relay、task APIs
    data/local/             Room、LiteRT、pose、local stores
    data/model/             Room entities and shared domain models
    data/remote/            Retrofit services、streaming clients、provider adapters
    data/repository/        Classroom、video、ASR、pose workflows and orchestration
    localagent/             Google ADK Kotlin + LiteRT local brain experiment
    ui/components/          Reusable Compose components
    ui/screens/             App pages and experiment workspaces
    ui/theme/               Compose theme
    ui/viewmodel/           View models and orchestration delegates
  src/main/res/             Android resources
  src/test/                 JVM unit tests
  src/androidTest/          Instrumentation and Compose UI tests
docs/                       Product notes, technical notes, historical docs
mcp/                        Node MCP companion for local gateway and ntfy relay tools
tools/                      Helper scripts
```

## 运行与配置

### Requirements

- Android Studio with current Android SDK tooling
- JDK 17 for the current Gradle/Kotlin setup
- Android SDK 35
- Android device or emulator on Android 10+ (`minSdk = 29`)

Build targets:

- `compileSdk = 35`
- `targetSdk = 35`
- `minSdk = 29`

### local.properties

`local.properties` is used for local development configuration. Android Studio usually manages `sdk.dir` automatically. Optional credentials can also live here.

```properties
API_KEY=your_remote_model_api_key
VOLCENGINE_ASR_APP_KEY=your_volcengine_asr_app_key
VOLCENGINE_ASR_ACCESS_KEY=your_volcengine_asr_access_key
VOLCENGINE_ASR_RESOURCE_ID=volc.seedasr.sauc.duration
```

Optional release signing values:

```properties
RELEASE_STORE_FILE=keystore.jks
RELEASE_STORE_PASSWORD=your_store_password
RELEASE_KEY_ALIAS=your_alias
RELEASE_KEY_PASSWORD=your_key_password
```

Notes:

- `API_KEY` is only injected into debug builds through `BuildConfig`.
- Runtime model providers can also be configured inside the app through API Wallet.
- Do not commit `local.properties`.

### Build

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat installDebug
```

Useful verification commands:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat connectedDebugAndroidTest
.\gradlew.bat lintDebug
```

## 第三方开源致谢

Watcher 使用或参考了多个开源项目。更完整的 notices 见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

| Project | Usage in Watcher |
| --- | --- |
| [Paidax01/math-curve-loaders](https://github.com/Paidax01/math-curve-loaders) | `RoseFourLoader` 的数学曲线 loading 动画灵感来源 |
| [binwiederhier/ntfy](https://github.com/binwiederhier/ntfy) | 多端对话接力和 pub/sub relay 方案 |
| [google/adk-kotlin](https://github.com/google/adk-kotlin) | 本地 Agent / ADK Kotlin 验证 |
| [google-ai-edge/LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) | Android 端侧本地大模型推理 |
| [ollama/ollama](https://github.com/ollama/ollama) | 本地模型生态和 OpenAI-compatible/local runtime 方向参考 |

Watcher is independent and is not affiliated with or endorsed by the maintainers of these projects.

## 文档地图

- [docs/product-positioning.md](docs/product-positioning.md)
- [docs/ai-fitness-dual-camera-poc.md](docs/ai-fitness-dual-camera-poc.md)
- [docs/aruco-calibration-implementation.md](docs/aruco-calibration-implementation.md)
- [docs/dance-segmentation-and-beat-analysis.md](docs/dance-segmentation-and-beat-analysis.md)
- [docs/agent-framework.md](docs/agent-framework.md)
- [docs/multi-device-relay.md](docs/multi-device-relay.md)
- [CONTRIBUTING.md](CONTRIBUTING.md)
- [AGENTS.md](AGENTS.md)
- [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)

The dated docs under `docs/` and some architecture notes are historical design and iteration records. Treat them as context, not as the canonical product story.

## 安全提示

- `local.properties` may contain development secrets and must stay uncommitted.
- Runtime provider secrets are stored locally and should be treated as sensitive.
- API Wallet export text can contain API keys. Share it carefully.
- Local media, screenshots, transcripts and AI traces may contain sensitive personal scene data.
- The embedded gateway and relay tooling are designed for trusted development scenarios, not public internet exposure.

## License Status

This repository includes third-party notices, but it does not currently include a top-level project license file. Add an explicit project license before treating the repository as a published open-source distribution.
