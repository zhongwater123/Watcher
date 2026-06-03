# Dance Segmentation & Beat Analysis — 技术文档

> 最后更新：2026-05-29 | 基于实际代码状态

## 一、功能概述

本次开发为舞蹈学习模块新增两大能力：

1. **Beat Analysis** — 从舞蹈视频中提取节拍/BPM/音乐结构（本地 DSP + 云端 LLM 混合方案）
2. **Dance Segmentation** — 从 .pose 骨骼数据中检测动作边界，输出层次化切片（纯本地信号处理）

产品定位：Dance Segmentation + Teaching 构成独立 AI 产品闭环，Game 是后续自然延伸。

---

## 二、系统架构

```
用户导入舞蹈视频 (MP4)
        ↓
[DanceProcessingScreen 流程]
        ↓
Step 1: Beat Analysis (内联步骤，pose 处理前)
  ├── 本地 TarsosDSP onset detection → BPM + onset timestamps
  ├── 上传源视频 MP4 → Ark Files API (file_id 缓存到 session)
  ├── Doubao LLM (input_video) → 音乐结构 + 学习分段
  └── 输出: .beat 文件 (二进制)
        ↓
Step 2: MediaPipe Pose Processing (实时播放 + 检测)
  └── 输出: .pose 文件 (二进制)
        ↓
Step 3: Dance Segmentation (自动触发，<1s)
  ├── 多信号动作边界检测 (6 维融合)
  ├── 智能合并碎片 + 递归拆分长段
  └── 输出: .segments.json
        ↓
Session 状态: SEGMENTED
```

---

## 三、文件资产体系

所有文件存储在 `context.filesDir/pose_data/`，通过 session ID 关联：

| 文件 | 格式 | 内容 |
|------|------|------|
| `session_{id}.pose` | 二进制 (PoseFileFormat v2) | 逐帧骨骼数据，预分配定长 slot + bitmap |
| `session_{id}.beat` | 二进制 (BeatFileFormat v1) | BPM、beat timestamps、音乐段落、学习短语 |
| `session_{id}.segments.json` | JSON | 原子动作切片 + 短语分组 |

对齐保证：三个文件共享相同的 `fps`、`totalFrameCount`、`videoDurationMs`。

---

## 四、Beat Analysis 详细设计

### 4.1 核心文件

| 文件 | 职责 |
|------|------|
| `data/local/pose/BeatAnalysisProcessor.kt` | 编排器：音频提取 → DSP → 上传 → LLM → 写文件 |
| `data/local/pose/BeatFileFormat.kt` | .beat 二进制文件读写 |
| `data/local/pose/BeatAnalysisSchemas.kt` | LLM JSON Schema + prompt 构建 |
| `data/local/pose/TarsosDspAnalyzer.kt` | TarsosDSP 封装 (onset detection + BPM) |

### 4.2 依赖

```toml
# gradle/libs.versions.toml
tarsosdsp = "2.5"
tarsosdsp-core = { group = "be.tarsos.dsp", name = "core", version.ref = "tarsosdsp" }
tarsosdsp-jvm = { group = "be.tarsos.dsp", name = "jvm", version.ref = "tarsosdsp" }
```

需要自定义 Maven 仓库 (`settings.gradle.kts`):
```kotlin
maven { url = uri("https://mvn.0110.be/releases") }
```

### 4.3 数据流

```
源视频 MP4
  ├── convertToWav() → WAV → TarsosDSP → DspResult(bpm, onsets)
  └── uploadFile(video/mp4, fps=1) → file_id → Doubao Responses API
        → input_video + prompt (含 DSP 结果)
        → JSON 输出: correctedBpm, firstBeatMs, tempoChanges, accents, segments, phrases

本地生成完整 beat grid:
  beat[n] = firstBeatMs + n × (60000 / currentBpm)
  遇到 tempoChange 切换 bpm
  遇到 accent 标记重音
```

### 4.4 LLM 调用关键点

- 使用 **Responses API** (`POST /api/v3/responses`)
- `response_format: json_schema` 与 `input_video` 不兼容 → 自动降级为无 schema（靠 prompt 约束 JSON）
- File ID 缓存在 `PoseVideoSession.audioFileId`，避免重复上传 115MB 视频
- 降级链：LLM 失败 → DSP-only fallback → 跳过

### 4.5 .beat 文件格式

```
[Header: 128 bytes]
  magic "BEAT", version 1, totalFrameCount, fps, videoDurationMs
  bpmTenths (BPM×10), beatCount, segmentCount, phraseCount
  timeSignature, flags (bit0:hasLLM, bit1:dspOnly)

[Beat entries: N × 16 bytes]
  timestampMs(4B), frameIndex(4B), strengthTenths(2B), beatType(1B), confidence(1B), reserved(4B)

[Segment entries: N × 32 bytes]
  startMs, endMs, startFrameIdx, endFrameIdx, segmentType(1B), energyLevel(1B), reserved(14B)

[Phrase entries: N × 24 bytes]
  startMs, endMs, startFrameIdx, endFrameIdx, beatCountInPhrase(2B), phraseType(1B), difficulty(1B), reserved(4B)
```

---

## 五、Dance Segmentation 详细设计

### 5.1 核心文件

| 文件 | 职责 |
|------|------|
| `data/local/pose/DanceSegmentationEngine.kt` | 多信号动作边界检测 + 切片生成 |
| `data/local/pose/DanceSegmentationModels.kt` | 数据模型定义 |

### 5.2 六维信号融合

从 .pose 文件的 33 个 MediaPipe 关键点计算：

| 信号 | 权重 | 数据来源 | 作用 |
|------|------|----------|------|
| 全身位移速度 | 35% | 所有点的 (nx,ny) 帧间差分 | 整体运动量 |
| 关节角度变化 | 20% | 肘/膝/肩角度帧间差分 | 弯曲伸展转折 |
| 质心速度 | 15% | 左右髋(23,24)中点位移 | 全身移动 vs 原地 |
| 可见度突变 | 10% | visibility 帧间差分 | 转身/遮挡检测 |
| 区域速度差异 | 10% | 上半身(0-22) vs 下半身(23-32) vs 手部(15-22) | 运动不同步检测 |
| 深度变化 | 10% | nz 帧间差分 | 前后移动/旋转 |

### 5.3 切分算法步骤

```
1. 读取所有已填充帧的 landmark 数据 (nx, ny, nz, visibility)
2. 计算时间索引的运动强度曲线 (speed per second, 归一化帧间距)
3. 高斯平滑 (σ=2)
4. Valley detection (local minima < median×0.75) → 候选切点
5. Acceleration reversal detection (速度骤降 >50%) → 额外切点
6. 合并过近切点 (间距 < 700ms)
7. 递归拆分过长段 (>2500ms):
   - 优先在 valley 处切
   - 无 valley 时在 peak 后的下降点切
   - 最终兜底：中点切
8. 智能合并碎片 (<700ms):
   - 高强度短动作保留 (peakVelocity > 邻居×1.5)
   - 低强度碎片合并到相邻 intensity 更低侧
9. 分组为 Phrase (最多 4 moves 或 10s)
```

### 5.4 稀疏帧适配

.pose 文件通过增量播放填充，首遍通常只有 ~16% 覆盖率。算法设计：

- **速度归一化为位移/秒**：帧间距大不产生虚假低速
- **时间索引而非数组索引**：valley 直接映射到真实时间戳
- **增量友好**：数据越多 → 时间序列越密 → 更多有效 valley → 切分更精细
- 已有切点在数据增加后基本稳定（锚定在真实运动间歇上）

### 5.5 输出格式 (.segments.json)

```json
{
  "sessionId": 18,
  "totalDurationMs": 210009,
  "fps": 49,
  "atomicMoves": [
    {"id": "move_01", "startMs": 0, "endMs": 1408, "startFrame": 0, "endFrame": 68, "peakVelocity": 0.075, "boundaryType": "motion"},
    ...
  ],
  "phrases": [
    {"id": "phrase_A", "startMs": 0, "endMs": 6551, "moveIds": ["move_01","move_02","move_03"], "difficulty": 0.18},
    ...
  ]
}
```

### 5.6 当前调参结果 (session_18, 韩舞 3:30)

```
帧覆盖率: 16.7% (1942/11604), effective fps: 9.2
Motion intensity: min=0.0017, max=1.9516, mean=0.7930
109 atomic moves, 35 phrases
平均 move 时长: 1.93s
```

---

## 六、UI 集成

### 6.1 处理流程 (DanceProcessingScreen)

```
ConfigPanel [选择模型/委托]
  → [确认]
  → BeatAnalysisInlineStep (进度: 提取音频→本地检测→上传视频→AI校准→写入)
  → 完成/跳过 (2s 后自动)
  → ProcessingActiveScreen (ExoPlayer + MediaPipe 实时检测)
  → 播放结束 → 自动 Segmentation → onComplete
```

### 6.2 预览界面 (DancePosePlaybackScreen)

同时显示三层可视化信息（互不排斥）：

| 层 | 组件 | 数据来源 |
|---|------|---------|
| 骨骼着色 | PoseOverlay(moveColorIndex) | .segments.json |
| 动作色块条 | MoveColorBar | .segments.json |
| 音乐段落条 | SegmentColorBar | .beat |
| 节拍鼓点 | BeatPulseIndicator | .beat |

**骨骼彩虹着色规则：**
```
7色循环: 红→橙→黄→绿→青→蓝→紫
每个 Phrase 的首个 move 重置为红色
颜色切换 = 动作切换，回到红色 = 新短语开始
```

### 6.3 Session 状态

| 状态 | 含义 | UI pill |
|------|------|---------|
| `pending` | 未完成首遍 pose 处理 | 待处理 (灰) |
| `ready` | pose 可预览 | 可预览 (绿) |
| `segmented` | 动作切片完成 | 已切分 (蓝) |

### 6.4 用户操作入口

点击 READY/SEGMENTED session 弹出选项：
- 预览效果
- 继续优化
- 切分动作 → 手动触发 segmentation

---

## 七、Room 数据库变更

### Migration 56→57

```sql
ALTER TABLE pose_video_sessions ADD COLUMN beatFilePath TEXT NOT NULL DEFAULT '';
ALTER TABLE pose_video_sessions ADD COLUMN audioFileId TEXT NOT NULL DEFAULT '';
```

`audioFileId` 实际存储 Ark Files API 的视频 file_id（历史命名），用于缓存避免重复上传。

---

## 八、已知限制与后续方向

### 当前限制

1. **Beat 精度**：LLM 提供的 firstBeatMs 不够精确到 ±10ms，暂不能直接用于音游判定
2. **帧覆盖率低**：首遍仅 16.7%，segmentation 受限于有效 fps (9.2)
3. **response_format 不兼容 input_video**：Ark Responses API 不支持 json_schema + 视频，靠 prompt 约束 JSON
4. **TarsosDSP 超时**：部分设备首次加载可能卡住，设有 15s 超时保护

### 三结构融合路线图 (Motion 已完成，其余待做)

```
Layer 1: Motion Structure (70%) ← 已实现
  → .pose 多信号 valley detection

Layer 2: Rhythm Structure (20%) ← 待叠加
  → 切点 snap 到最近 beat grid 位置 (±100ms 内对齐)

Layer 3: Song Structure (10%) ← 待叠加
  → LLM 段落边界处强制添加切点
```

### 后续阶段

| 阶段 | 内容 | 依赖 |
|------|------|------|
| Dance Teaching | 每个 move → LLM 生成自然语言动作描述 | Segmentation |
| Dance Coach | 实时 pose 比较 + 评分反馈 | Segmentation + Camera |
| Dance Game | 时间轴 + 判定 + 积分 | All above + precise beats |

---

## 九、关键调试方法

### Logcat 过滤

| Tag | 内容 |
|-----|------|
| `BeatAnalysis` | 节拍分析全流程 (音频提取/DSP/上传/LLM/写文件) |
| `DanceSegment` | 动作切片算法 (帧覆盖/信号值/切点数/分布统计) |
| `PoseProcessing` | MediaPipe 检测循环 (帧率/写入/缓存命中) |

### 分布统计日志

`DanceSegment` 输出 `DISTRIBUTION: <1s=X | 1-2.5s=Y | >2.5s=Z` 行，目标 90%+ 在 1-2.5s。

### .segments.json 直接查看

```bash
adb shell cat /data/data/com.example.watcher/files/pose_data/session_18.segments.json | python -m json.tool
```

---

## 十、文件清单（本次新增/修改）

### 新建

| 文件 | 行数 |
|------|------|
| `data/local/pose/BeatFileFormat.kt` | ~250 |
| `data/local/pose/BeatAnalysisProcessor.kt` | ~400 |
| `data/local/pose/BeatAnalysisSchemas.kt` | ~130 |
| `data/local/pose/TarsosDspAnalyzer.kt` | ~130 |
| `data/local/pose/DanceSegmentationEngine.kt` | ~450 |
| `data/local/pose/DanceSegmentationModels.kt` | ~40 |
| `ui/screens/BeatAnalysisStepScreen.kt` | ~170 (已转为内联使用) |

### 修改

| 文件 | 改动摘要 |
|------|---------|
| `gradle/libs.versions.toml` | +tarsosdsp 2.5 |
| `settings.gradle.kts` | +mvn.0110.be/releases repo |
| `app/build.gradle.kts` | +tarsosdsp-core, tarsosdsp-jvm |
| `data/local/pose/PoseVideoSession.kt` | +beatFilePath, +audioFileId, +SEGMENTED status |
| `data/local/AppDatabase.kt` | version 57, MIGRATION_56_57 |
| `data/local/PoseVideoSessionDao.kt` | (无实质改动，已有方法覆盖) |
| `ui/components/PoseOverlay.kt` | +moveColorIndex 参数, +MOVE_RAINBOW_COLORS |
| `ui/screens/DanceProcessingScreen.kt` | +BeatAnalysisInlineStep, +auto segmentation |
| `ui/screens/DancePosePlaybackScreen.kt` | +beat/segmentation 加载, +MoveColorBar, +BeatPulseIndicator |
| `ui/screens/DanceLearningScreen.kt` | +onSegmentSession, +已切分 pill, +3选项弹窗 |
| `ui/viewmodel/DanceLearningViewModel.kt` | +runSegmentation, +segmentationResult flow, +deleteSession 清理 |
| `PoseEstimationActivity.kt` | +onSegmentSession, +segResult dialog |
