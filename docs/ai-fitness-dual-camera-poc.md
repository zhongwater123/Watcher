# AI 健身双摄 POC — 技术文档

> 最后更新：2026-06-04 | 对应会话：双摄并行验证 + 标定探索

## 一、已完成

### 1. 双摄并行 MediaPipe 推理 ✅

- 前置摄像头: CameraX + MediaPipe GPU LITE (~18fps)
- 侧面 Watcher: MJPEG stream + MediaPipe CPU LITE (~14fps)
- 两路独立线程、独立 AtomicBoolean 门控
- 侧面推理与读帧分离（AtomicReference 传最新帧，无延迟累积）

### 2. 流消费管理 ✅

- `MjpegStreamPlayer` 添加生命周期感知（Activity 后台时自动释放连接）
- ESP32 单连接限制自动交接：首页 → AI健身时流自动转移

### 3. 退出安全 ✅

- `frontAlive` / `sideAlive` AtomicBoolean 防止 use-after-release 崩溃
- Engine release 前先置 alive=false + sleep(100-150ms) 等待 in-flight 帧

### 4. 骨骼自适应标定 (实验性)

- `DualCameraCalibration.kt` — 基于肩宽比值的角度估计
- 10 秒收集 → P10/P90 → arccos → 最终角度
- 结论：**不够精确**，受人朝向和距离变化影响大

---

## 二、待实现（下次会话）

### ArUco 标定方案

替代骨骼标定，使用 ArUco 6×6 ID0 marker 一帧精确标定。

**技术栈：**
- OpenCV Android SDK 4.9.0 (`org.opencv:opencv:4.9.0` on Maven Central)
- ArUco 模块: `Aruco.detectMarkers()` + `Aruco.estimatePoseSingleMarkers()`

**流程：**
```
CALIBRATING → CALIBRATED → LOADING_ENGINES → READY
```

**关键文件：**
- 新建: `data/local/pose/ArUcoCalibrator.kt`
- 修改: `ui/screens/FitnessScreen.kt` (状态机重构)
- 修改: `app/build.gradle.kts` (添加 OpenCV 依赖)

---

## 三、关键文件清单

| 文件 | 状态 | 职责 |
|------|------|------|
| `ui/screens/FitnessScreen.kt` | 已创建 | 双流横屏界面 + 诊断面板 |
| `ui/screens/PoseScenarioSelectScreen.kt` | 已修改 | 添加 AI_FITNESS 入口 |
| `PoseEstimationActivity.kt` | 已修改 | Fitness 路由 + 横屏处理 |
| `ui/components/MjpegStreamPlayer.kt` | 已修改 | 生命周期感知（后台释放流） |
| `ui/components/PoseOverlay.kt` | 已修改 | `visibilityThreshold` 参数 |
| `data/local/pose/DualCameraCalibration.kt` | 已创建 | 骨骼标定(实验性，待替换为 ArUco) |
| `data/local/pose/ArUcoCalibrator.kt` | **待创建** | ArUco 标定核心算法 |

---

## 四、已知问题

1. **侧面延迟**: 已通过读帧/推理分离解决。首次连接可能有 1-2 帧延迟。
2. **退出崩溃**: 已通过 alive flag + sleep 解决。极端情况下仍可能有竞态（概率低）。
3. **标定精度**: 骨骼方案不够准确。ArUco 方案待实现。
4. **DualCameraCalibration.kt**: 当前代码可保留作为 fallback（无 marker 时的粗略估计），或在 ArUco 实现后删除。

---

## 五、性能基准

| 指标 | 实测值 |
|------|--------|
| 前置 FPS | 15-20 fps |
| 侧面 FPS | 13-16 fps |
| 前置推理延迟 | 35-50 ms |
| 侧面推理延迟 | 56-75 ms |
| 侧面视频延迟 | <100ms (分离后) |
| 双引擎初始化 | ~7 秒 (顺序) |
| 内存占用 | ~600MB (双 LITE) |

---

## 六、下次会话 TODO

1. 添加 OpenCV 4.9.0 依赖
2. 创建 `ArUcoCalibrator.kt`
3. 重构 FitnessScreen 为状态机 (CALIBRATING → CALIBRATED → LOADING → READY)
4. 标定阶段仅开双路视频流（不加载 MediaPipe）
5. 验证 ArUco 检测精度
6. 标定成功后加载 MediaPipe → 进入运动模式
