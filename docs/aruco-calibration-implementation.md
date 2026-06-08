# ArUco 双摄标定 — 实现文档

> 完成时间：2026-06-04 | 状态：POC 完成，精度可用

## 一、概述

使用 ArUco 6x6 marker 实现双摄像头（手机前置 + ESP32 Watcher）的空间关系标定。标定在 MediaPipe 加载之前执行，作为 AI 健身功能的前置步骤。

**核心流程**：用户举起 ArUco marker → 两路摄像头同时检测 → 计算夹角/距离 → 进入运动模式

## 二、精度结果

| 指标 | 测量值 | 实际值 | 偏差 | 稳定性 |
|------|--------|--------|------|--------|
| 夹角 | 88.4° | ~80° | +8° | ±3° |
| 距离 | 63.3cm | ~70-80cm | -15% | ±0.5cm |
| 高度差 | 11.5cm | ~0cm | +11cm | ±0.3cm |

- 7 帧中位数，帧间一致性极好
- 对健身深蹲分析场景精度足够（主要需要角度）

## 三、状态机

```
FitnessScreen 状态机:
  CALIBRATING → CALIBRATED → LOADING_ENGINES → READY

CALIBRATING:
  - 仅开启双路视频流（不加载 MediaPipe）
  - 持续检测 ArUco marker，实时反馈 正面✓/✗ | 侧面✓/✗
  - 收集 7 个有效标定样本（异常值过滤 >30° 偏差）
  - 取中位数作为最终结果

CALIBRATED:
  - 显示标定结果 2 秒
  - 自动进入下一阶段

LOADING_ENGINES:
  - 顺序初始化 GPU Engine → CPU Engine
  - 完成后进入 READY

READY:
  - 双流 + 双骨骼 + 诊断面板
  - "重新标定" 按钮可回到 CALIBRATING
```

## 四、关键技术发现

### 4.1 ESP32 图像镜像

ESP32-CAM 的 MJPEG 流输出**水平镜像**图像。ArUco marker 在镜像后 bit pattern 完全不同，无法匹配字典。

**解决方案**：先尝试正常检测，失败后自动 `Core.flip(gray, flipped, 1)` 水平翻转再试。

### 4.2 Bitmap 格式兼容性

`BitmapFactory.decodeByteArray()` 解码的 JPEG Bitmap 通过 `Utils.bitmapToMat()` 转换后，ArUco 检测始终失败（即使图像清晰）。

**解决方案**：侧面使用 `Imgcodecs.imdecode(MatOfByte, IMREAD_COLOR)` 直接从 JPEG 字节解码到 OpenCV Mat，跳过 Android Bitmap 中间层。前置使用 `getPixels/setPixels` 归一化后再 `bitmapToMat`。

### 4.3 动态相机内参

前置摄像头输出 1080x1080（1:1 裁剪），侧面输出 640x480。固定内参会导致严重误差（之前 cx=320 对 1080 图像偏移 300px）。

**解决方案**：根据实际图像尺寸动态计算：
```
fx = (width/2) / tan(hfov/2)
cx = width/2, cy = height/2
```

### 4.4 FOV 参数

| 摄像头 | FOV | 来源 |
|--------|-----|------|
| 手机前置 | 70° | 典型手机前置摄像头估计 |
| ESP32 (H0F3M-118) | 118° | 排线型号标注 |

### 4.5 翻转坐标修正

水平翻转图像检测得到的 pose，在计算摄像头空间位置时需要翻转 X 分量：
```kotlin
if (sideIsFlipped) {
    camPosB = floatArrayOf(-camPosB[0], camPosB[1], camPosB[2])
}
```

### 4.6 高度估计

`-R^T * t` 计算的摄像头位置 Y 分量不可靠（旋转误差被 Z 距离放大）。改用 tvec.y 直接差值：
```kotlin
heightDiff = frontPose.tvec[1] - sidePose.tvec[1]
```

## 五、文件清单

| 文件 | 状态 | 职责 |
|------|------|------|
| `data/local/pose/ArUcoCalibrator.kt` | 新建 | ArUco 检测 + solvePnP + 双摄标定计算 |
| `ui/screens/FitnessScreen.kt` | 重构 | 状态机 + 标定 UI + 检测循环 |
| `app/build.gradle.kts` | 修改 | 添加 OpenCV 4.9.0 + arm64 ABI 过滤 |

## 六、依赖

```kotlin
// OpenCV 4.9.0 (~110MB AAR, arm64-only ~25MB in APK)
implementation("org.opencv:opencv:4.9.0")

// ABI 过滤
ndk { abiFilters += listOf("arm64-v8a") }
```

## 七、已知限制

1. **FOV 近似**：使用估计值（70°/118°），未做棋盘格标定。距离误差 ~15%。
2. **高度偏差**：~11cm 系统性偏差，来自物理安装高度差或 ESP32 轻微俯仰。
3. **角度偏差**：~8°，受 FOV 估计和镜头畸变影响。
4. **Marker 朝向敏感**：用户需要大致面向两摄像头中间。严重倾斜会导致旋转估计不稳定。
5. **ESP32 检测延迟**：118° 超广角 + JPEG 压缩导致 marker 需要足够大/足够近才能检测。

## 八、后续可优化方向

1. **棋盘格标定**：一次性获取精确 fx/fy/cx/cy + 畸变系数
2. **更大 marker**：10cm 代替 5cm，提升 ESP32 检测稳定性
3. **多 marker**：使用 ArUco Board（多个 marker 阵列），大幅提高旋转精度
4. **自动 FOV 校正**：通过已知距离（用户输入摄像头间距）反推 FOV

## 九、Marker 规格

- Dictionary: `DICT_6X6_50`
- ID: 0
- 物理尺寸: 5cm×5cm
- 获取: https://chev.me/arucogen/ → 6x6 (50), ID=0
- 另一台手机/平板显示 或 打印均可
