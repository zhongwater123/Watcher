# 视频分析管线技术文档

> 最后更新：2026-05-28 | 对应版本：run_33 验证通过

## 一、整体架构

视频分析管线由两条严格解耦的子管线组成：

```
┌────────────────────────────────────────────────────────────────────┐
│                        录制阶段（并行）                              │
├────────────────────────────────────────────────────────────────────┤
│  麦克风管线：ContinuousAudioRecorder → 逐段 segment_audio.m4a      │
│  视频管线：  MjpegVideoRecorder → 逐段 segment_video.mp4           │
│  合并：      AudioSegmentSlicer.mergeVideoAndAudio()               │
│              → merged_segment_video.mp4（音画一体，单一分析资产）     │
└────────────────────────────────────────────────────────────────────┘
                              ↓
┌────────────────────────────────────────────────────────────────────┐
│                   分片分析阶段（并发异步）                           │
├────────────────────────────────────────────────────────────────────┤
│  VideoSegmentProcessor.analyzeRecordedSegment()                    │
│  → 上传 merged_segment_video（assetKind=MergedSegmentVideo）       │
│  → 单一 input_video 模型调用（音轨内含于视频中）                    │
│  → 产出 segment fact packet（结构化证据 JSON）                     │
└────────────────────────────────────────────────────────────────────┘
                              ↓
┌────────────────────────────────────────────────────────────────────┐
│                    Summary 阶段（串行）                             │
├────────────────────────────────────────────────────────────────────┤
│  ┌─ 音频管线 ─────────────────────────────────────────────────┐   │
│  │ AudioOutlineProcessor.buildMasterAudioFromFiles()           │   │
│  │   → 合成 master_audio.m4a（MediaMuxer 拼接）               │   │
│  │ AudioOutlineProcessor.extractAdtsAac()                      │   │
│  │   → 产出 master_audio.aac（绕过 MediaMuxer 容器）          │   │
│  │ AudioOutlineProcessor.generateAudioOutline()                │   │
│  │   → 上传 .aac + input_audio 模型调用 → 初版大纲            │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                              ↓                                     │
│  ┌─ 视频管线 ─────────────────────────────────────────────────┐   │
│  │ VideoSegmentProcessor.summarizeSegments()                   │   │
│  │   → 融合 outlineMarkdown + segment facts → 最终报告        │   │
│  │ VideoSegmentProcessor.refineReportWithVideo()（条件触发）   │   │
│  │   → 视频母带/块补证精修                                     │   │
│  └─────────────────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────────────────┘
```

## 二、关键文件职责

| 文件 | 职责 | 管线归属 |
|------|------|---------|
| `AudioOutlineProcessor.kt` | 音频母带构建、ADTS 提取、上传、模型调用生成大纲 | 音频管线 |
| `VideoSegmentProcessor.kt` | 视频录制、merged mp4 分析、视频合并、视频补证、最终报告 | 视频管线 |
| `VideoAudioAssetBuilder.kt` | 底层音频文件操作工具（AAC 拼接、诊断） | 共享工具 |
| `AudioSegmentSlicer.kt` | 音频切片、音视频 mux 合并 | 共享工具 |
| `VideoSegmentMerger.kt` | 视频文件合并 | 视频管线 |
| `VideoRemoteFileResolver.kt` | 远端文件上传/绑定/状态管理 | 共享基础设施 |
| `VideoExecutionOrchestrator.kt` | 调度两条管线的执行时序 | 编排层 |
| `VideoProcessRepository.kt` | 实例化所有处理器，对外暴露 Flow | 入口 |

## 三、音频管线的 ADTS 方案

### 问题背景

Android `MediaMuxer` 使用 `MUXER_OUTPUT_MPEG_4` 格式封装音频时，即使只有单一音频 track，产出的 .m4a 文件仍包含视频相关的 MP4 atom。Ark API 服务器将此类文件识别为 `video/mp4`，导致 `input_audio` 内容类型被拒绝（HTTP 400）。

### 解决方案

在 `AudioOutlineProcessor.generateAudioOutline()` 内部，上传前将 .m4a 转为 raw ADTS AAC（.aac）：

```
MediaExtractor 从 .m4a 逐帧读取 AAC 压缩帧
  → 为每帧添加 7 字节 ADTS header
  → 写入 .aac 文件（无 MP4 容器）
  → 上传时 mime type = "audio/aac"
  → 服务器识别为 audio/aac → 预处理成功 → input_audio 可用
```

### 为什么不用其他方案

| 方案 | 问题 |
|------|------|
| 直接上传 .m4a | 服务器识别为 video/mp4，input_audio 被拒 |
| 转 MP3（LAME） | 需要 NDK，MP3 编码标准逐步被淘汰 |
| 转 WAV | 文件 ~63MB（11分钟），上传慢，内存占用高 |
| 用 input_video 引用 .m4a | 服务器拒绝（"file type is not video"） |
| reencodeM4aClean | 仍使用 MediaMuxer，容器仍被识别为 video/mp4 |
| FFmpegKit | ~15MB APK 增量，MP3 技术路线不理想 |
| **提取 ADTS .aac** | **零依赖、纯 Android API、文件小、服务器正确识别** |

### 验证数据

- 服务器对 .aac 的 mime 识别：`audio/aac` ✅
- 预处理状态：`active` ✅
- `input_audio` 模型调用：HTTP 200 ✅
- 多段拼接后的 ADTS 文件同样有效 ✅
- 设备端 run_33 全链路验证通过 ✅

## 四、分片分析的 Merged Video 方案

### 问题背景

原链路将 `segment_video`（无音轨）和 `segment_audio`（独立上传）作为双输入传给模型。当音频远端预处理失败时，整个分片分析被阻断。

### 解决方案

录制阶段即将音视频合并为单一 `merged_segment_video.mp4`，分析时只传一个 `input_video`：

```
录制 → segment_video.mp4 + segment_audio.m4a
         ↓ AudioSegmentSlicer.mergeVideoAndAudio()
       merged_segment_video.mp4 (音画一体)
         ↓ 上传 (assetKind = MergedSegmentVideo)
       远端预处理 → active
         ↓ 模型调用 (单一 input_video)
       segment fact packet
```

### 降级策略

1. **优先**：`merged_segment_video`（音画一体 mp4）
2. **兜底拼接**：如果录制阶段未产出 merged（非 continuous audio 模式），`analyzeRecordedSegment` 会尝试现场合并
3. **最终降级**：仅视频分析，标记 `coverageLimitation`

## 五、双阶段报告生成

### 阶段 A：音频初版大纲

- 时序：录制结束后立即开始（与分片分析并行）
- 输入：master_audio.aac（ADTS 格式）
- 模型：`doubao-seed-2-0-lite-260428`（音频理解能力）
- 输出：Markdown 格式大纲（含时间线、说话人、关键信息点）
- 存储：`run.outlineMarkdown`

### 阶段 B：视频补证精修（条件触发）

- 触发条件：分片事实完成度 < 75%（`shouldAnalyzeMergedChunks`）
- 输入：merged segment videos 按 400MB 阈值合并为 chunk → chunk fileId
- 模型调用：`refineReportWithVideo()` 以初版报告 + 视频块进行补证
- 输出：精修后的 Markdown 报告

### 最终报告融合

`summarizeSegments()` 接收：
- `outlineMarkdown`（音频大纲，作为报告骨架）
- `segmentFacts`（视频分片事实，作为视觉补充）
- `mergedChunkEvidence`（视频块证据，如有）

产出融合了音频语义 + 视觉证据的最终结构化报告。

## 六、数据库与诊断

### 新增字段（Migration 53→54）

| 字段 | 类型 | 含义 |
|------|------|------|
| `mergedSegmentCountActual` | Int | 使用 merged video 分析的分片数 |
| `segmentsMissingMergedAnalysisAsset` | Int | 降级为 video-only 的分片数 |
| `audioOutlineAvailable` | Boolean | 音频大纲是否成功生成 |
| `videoRefinementApplied` | Boolean | 视频补证是否执行 |
| `videoRefinementInputMode` | String | 补证输入模式（single_master / chunked_master） |
| `reportPipelineStagesJson` | String | 管线阶段时间戳记录 |

### Pipeline Stages 格式

```json
["timestamp:stage_name", ...]
```

阶段名：
- `audio_outline_started` / `audio_outline_completed` / `audio_outline_failed`
- `segment_analysis_started` / `segment_analysis_completed` / `segment_analysis_failed`
- `segment_merge_started` / `segment_merge_completed`
- `video_refinement_started` / `video_refinement_completed` / `video_refinement_failed`

### 调试导出

`HistoryWorkbenchPage.buildPerSegmentAssetDiagnostics()` 为每个分片输出：
- `localFile`：本地文件路径/大小/存在性
- `segmentVideoBinding`：原始视频绑定
- `segmentAudioBinding`：原始音频绑定
- `mergedSegmentVideoBinding`：merged 分析资产绑定
- `analysisUsedMergedVideo`：是否使用 merged 视频作为实际分析输入

## 七、Ark API 对接要点

### 视频分片分析

- 上传：`POST /api/v3/files` + `preprocess_configs[video][fps]=1` + Content-Type `video/mp4`
- 推理：`{"type": "input_video", "file_id": "..."}`
- 模型：`doubao-seed-2-0-lite-260428`

### 音频大纲生成

- 上传：`POST /api/v3/files` + `purpose=user_data` + Content-Type `audio/aac`（无 preprocess_configs）
- 推理：`{"type": "input_audio", "file_id": "..."}`
- 模型：`doubao-seed-2-0-lite-260428`（同一模型支持音视频理解）

### 关键发现（来自实测验证）

| 文件类型 | 服务器识别 | input_audio | input_video |
|---------|-----------|-------------|-------------|
| `.m4a`（MediaMuxer 产出） | `video/mp4` | ❌ 400 | ✅ 200 |
| `.m4a`（ffmpeg 产出） | `audio/x-m4a` | ✅ 200 | ❌ 400 |
| `.aac`（ADTS 裸流） | `audio/aac` | ✅ 200 | ❌ 400 |

**结论**：服务器以文件内容（而非上传 mime type）判断类型。Android MediaMuxer 产出的 .m4a 包含视频 atom，被服务器视为视频。

## 八、本地测试脚本

位于 `app/src/test/`：

| 脚本 | 用途 |
|------|------|
| `test_ark_audio_m4a.py` | 测试 .m4a 上传 + 各种 mime/content type 组合 |
| `test_ark_audio_concat.py` | 测试多段拼接后的 .m4a 是否被正确识别 |
| `test_ark_audio_adts.py` | 验证 ADTS .aac 提取 + 上传 + input_audio 完整链路 |
| `test_ark_audio_outline.py` | 通用音频上传诊断工具 |

运行前提：
- PC 端安装 ffmpeg（`winget install ffmpeg`）
- `test_audio_extracted.m4a` 已从测试 MP4 中提取（`ffmpeg -i xxx.mp4 -vn -acodec copy test_audio_extracted.m4a`）

## 九、已知限制与未来方向

1. **降级原因 "Manual stop"**：用户手动停止任务时产生，不是错误
2. **视频补证触发阈值**：分片事实完成度 < 75% 才触发 chunk 分析 + 补证
3. **ADTS 提取内存**：当前实现使用 64KB buffer 流式写入，内存占用恒定
4. **MediaMuxer 的 .m4a 仍保留**：用于 fullMedia 回看视频的音轨合成（本地播放无问题）
5. **未来可扩展**：音频管线已独立，可单独升级模型、添加多轮对话、支持更长音频分段上传等
6. **Android MediaMuxer 的固有限制**：无法产出被远端正确识别为纯音频的 .m4a，这是 Android 平台级行为，不是代码 bug
