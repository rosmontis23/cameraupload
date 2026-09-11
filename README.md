# 启明瞳视觉辅助导航系统

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%207.0%2B-green.svg)](https://android.com)
[![Language](https://img.shields.io/badge/language-Java-orange.svg)](https://java.com)

**启明瞳** 是一款面向视障人士的 Android 视觉辅助导航应用，通过手机摄像头和 AI 技术实现实时障碍物检测、语音唤醒导航和紧急求助功能。

<p align="center">
  <img src="screenshots/detection.png" alt="目标检测" width="300"/>
  <img src="screenshots/navigation.png" alt="语音导航" width="300"/>
</p>

---

## ✨ 功能特性

| 功能 | 描述 |
|------|------|
| 🔍 **实时目标检测** | 基于 TensorFlow Lite 端侧推理，支持 20 种障碍物识别，帧率 ≥ 15 FPS |
| 📏 **距离估算** | 单目视觉测距算法，精确估算障碍物距离 |
| 🗣️ **语音唤醒** | 科大讯飞 AIKit 离线唤醒，自定义唤醒词"小瞳小瞳" |
| 🎙️ **语音指令** | 实时语音识别，支持"导航到XXX"、"停止导航"、"我需要帮助"等指令 |
| 🧭 **步行导航** | 集成高德地图 SDK，提供实时步行导航和 POI 搜索 |
| 🆘 **紧急求助** | 一键求助，自动发送位置信息和语音留言 |
| 🦯 **盲道识别** | 识别盲道并语音引导用户沿盲道行走 |
| 📳 **震动反馈** | 危险检测时触发震动提醒，适配嘈杂环境 |

---

## 🛠️ 技术栈

| 类别 | 技术 | 版本 | 用途 |
|------|------|------|------|
| 开发语言 | Java | 11 | 全部业务逻辑 |
| 最低版本 | Android | 7.0 (API 24) | 系统要求 |
| 目标版本 | Android | 14 (API 34) | 测试目标 |
| 相机框架 | CameraX | 1.3.0 | 实时取景 |
| 视觉推理 | TensorFlow Lite | 2.13.0 | 端侧目标检测 |
| 语音唤醒 | 科大讯飞 AIKit | - | 离线唤醒词识别 |
| 语音识别 | 科大讯飞 SparkChain | 2.0.1 | 在线语音转文字 |
| 地图服务 | 高德地图 SDK | 11.1.0 | 定位、导航、POI |
| 网络通信 | OkHttp | 4.10.0 | HTTP 请求 |

---

## 📁 项目结构

```
cameraupload/
├── app/
│   ├── build.gradle.kts
│   ├── libs/
│   │   ├── AMap3DMap_*.jar          # 高德地图 SDK
│   │   ├── AIKit.aar                # 讯飞唤醒 SDK
│   │   ├── SparkChain.aar           # 讯飞语音识别 SDK
│   │   └── Codec.aar                # 讯飞编解码库
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/
│       │   ├── best_float16.tflite   # TFLite 模型（优先加载）
│       │   ├── best_float32.tflite   # TFLite 模型（备选）
│       │   ├── labels.txt            # 类别标签
│       │   ├── aikit_resources/      # AIKit 唤醒资源
│       │   │   └── ivw/
│       │   │       ├── IVW_FILLER_1
│       │   │       ├── IVW_GRAM_1
│       │   │       ├── IVW_KEYWORD_1
│       │   │       ├── IVW_MLP_1
│       │   │       └── keyword1.txt
│       │   └── ...
│       ├── java/com/example/cameraupload/
│       │   ├── MainActivity.java         # 主活动
│       │   ├── VoiceNaviHelper.java      # 语音导航助手
│       │   ├── NetworkUtils.java          # 网络通信工具
│       │   ├── OverlayView.java           # 检测结果叠加层
│       │   └── DetectionResult.java       # 检测结果数据类
│       └── res/
│           └── values/
│               └── strings.xml            # 配置字符串
├── build.gradle.kts
└── settings.gradle.kts
```

---

## 🚀 快速开始

### 环境要求

- **Android Studio**: Hedgehog (2023.1) 或更高版本
- **JDK**: 11 或更高版本
- **Gradle**: 8.0 或更高版本
- **硬件**: 后置摄像头、GPS、麦克风
- **Android 设备**: Android 7.0+ (API 24+)

### 配置说明

#### 1. 配置 API 密钥

编辑 `app/src/main/res/values/strings.xml`，填入您的 API 密钥：

```xml
<resources>
    <!-- 应用名称 -->
    <string name="app_name">启明瞳</string>
    
    <!-- 科大讯飞 SDK 配置 -->
    <string name="appid">YOUR_IFLYTEK_APPID</string>
    <string name="apikey">YOUR_IFLYTEK_APIKEY</string>
    <string name="apiSecret">YOUR_IFLYTEK_APISECRET</string>
    
    <!-- 高德地图 SDK 配置 -->
    <string name="amap_key">YOUR_AMAP_APIKEY</string>
</resources>
```

#### 2. 配置服务器地址

编辑 `app/src/main/java/com/example/cameraupload/NetworkUtils.java`：

```java
// 修改为您的后端服务器地址
private static final String SERVER_BASE_URL = "https://your-server-url.com/";
```

#### 3. 放置模型文件

确保 `app/src/main/assets/` 目录包含以下文件：

| 文件 | 说明 |
|------|------|
| `best_float16.tflite` | TFLite 模型文件（优先加载，推荐） |
| `best_float32.tflite` | TFLite 模型文件（备选） |
| `labels.txt` | 类别标签文件（必需） |
| `aikit_resources/ivw/` | AIKit 唤醒资源（必需） |

> **注意：** 应用会优先加载 `best_float16.tflite`，如果不存在则加载 `best_float32.tflite`。建议使用 float16 版本以获得更好的性能。

#### 4. 权限说明

应用需要以下权限：

| 权限 | 用途 |
|------|------|
| `CAMERA` | 实时目标检测 |
| `RECORD_AUDIO` | 语音唤醒和识别 |
| `ACCESS_FINE_LOCATION` | 精确定位和导航 |
| `ACCESS_COARSE_LOCATION` | 粗略定位 |
| `ACCESS_BACKGROUND_LOCATION` | 后台定位（可选） |
| `VIBRATE` | 危险震动反馈 |
| `INTERNET` | 网络通信 |

### 构建运行

1. **克隆项目**
```bash
git clone <your-repo-url>
cd cameraupload
```

2. **打开项目**
- 使用 Android Studio 打开项目目录
- 等待 Gradle 同步完成

3. **运行应用**
- 连接 Android 设备或启动模拟器
- 点击 "Run" 按钮或使用快捷键 `Shift+F10`

---

## 🏗️ 核心模块

### MainActivity
主活动，负责：
- 应用生命周期管理
- 相机预览和目标检测协调
- 权限请求和状态显示

### VoiceNaviHelper
语音导航助手（约 1700 行代码），负责：
- AIKit 离线语音唤醒
- SparkChain 在线语音识别
- 高德地图步行导航
- TTS 播报队列管理
- 音频焦点管理
- 唤醒健康检查机制

### NetworkUtils
网络通信工具类（单例模式），负责：
- 检测结果上传
- 求助位置发送
- 语音留言上传

### OverlayView
自定义视图，负责：
- 检测框绘制
- 类别标签和置信度显示
- 警报模式闪烁效果

### DetectionResult
数据模型，封装：
- 目标类别和置信度
- 边界框坐标
- 距离估算值

---

## 🧠 AI 模型

本项目使用 TensorFlow Lite 部署 YOLO 格式的目标检测模型：

- **输入**: 640×640×3 RGB 图像
- **输出**: 8400 个预测框 × (4 + 20) 维（坐标 + 类别置信度）
- **支持类别**: 20 种障碍物（行人、车辆、交通信号、盲道等）
- **置信度阈值**: 0.6
- **NMS IOU 阈值**: 0.5

### 检测类别

| 类别 | 说明 | 真实宽度 |
|------|------|----------|
| 行人 | 站立或行走的人 | 50 cm |
| 自行车 | 两轮交通工具 | 60 cm |
| 汽车 | 轿车 | 180 cm |
| 公交车 | 大型公交车辆 | 250 cm |
| 卡车 | 货运车辆 | 200 cm |
| 红灯 | 交通红灯 | 30 cm |
| 绿灯 | 交通绿灯 | 30 cm |
| 斑马线 | 人行横道 | 300 cm |
| 盲道 | 无障碍通道 | 60 cm |
| ... | 共 20 种 | ... |

---

## 🏗️ 系统架构

```
┌──────────────────────────────────────────────────────────────────┐
│                          MainActivity                            │
│  相机预览(PreviewView) + 检测叠加(OverlayView) + 状态显示        │
├──────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌──────────────────┐  ┌──────────────────┐  ┌───────────────┐  │
│  │ 目标检测引擎      │  │ VoiceNaviHelper  │  │ NetworkUtils  │  │
│  │ ┌──────────────┐ │  │ ┌──────────────┐ │  │ - OkHttp      │  │
│  │ │ TFLite 推理  │ │  │ │ AIKit 唤醒   │ │  │ - JSON 上传   │  │
│  │ │ NMS 过滤     │ │  │ │ SparkChain   │ │  │ - Multipart   │  │
│  │ │ 距离估算     │ │  │ │   ASR 识别   │ │  │                │  │
│  │ │ 模拟检测     │ │  │ │ 高德导航     │ │  │                │  │
│  │ └──────────────┘ │  │ │ TTS 播报队列 │ │  │                │  │
│  └──────────────────┘  │ └──────────────┘ │  └───────────────┘  │
│                                                                  │
├──────────────────────────────────────────────────────────────────┤
│  TensorFlow Lite  │  科大讯飞 AIKit  │  SparkChain  │  高德 SDK  │
└──────────────────────────────────────────────────────────────────┘
```

---

## 🙏 致谢

本项目使用了以下开源项目和服务：

- [TensorFlow Lite](https://www.tensorflow.org/lite) - Google 出品的移动端 AI 推理引擎
- [Android CameraX](https://developer.android.com/camera) - Google 出品的相机开发库
- [OkHttp](https://square.github.io/okhttp/) - Square 出品的 HTTP 客户端
- [科大讯飞 AIKit](https://www.xfyun.cn/) - 语音唤醒和识别服务
- [高德开放平台](https://lbs.amap.com/) - 地图定位和导航服务

---

## 🔧 故障排查

### 常见问题

| 问题 | 解决方案 |
|------|----------|
| 模型加载失败 | 检查 `assets/` 目录下是否包含 `.tflite` 文件 |
| 语音唤醒不工作 | 检查麦克风权限和 AIKit 资源完整性 |
| 定位失败 | 检查 GPS 和网络连接，尝试重新授权 |
| 应用崩溃 | 检查 `minSdk` 版本是否满足 24+ (Android 7.0) |
| 导航无响应 | 检查高德 API Key 是否正确配置 |

### 日志标签

```java
Log.d("YOLO_RealTime", "检测状态...");     // MainActivity
Log.d("VoiceNaviHelper", "语音导航状态..."); // VoiceNaviHelper
Log.d("NetworkUtils", "网络请求状态...");   // NetworkUtils
```

---

## 📄 许可证

本项目采用 MIT 许可证 - 查看 [LICENSE](LICENSE) 文件了解详情。

---

## 📧 联系方式

如有任何问题或建议，请联系项目维护者。

---

**⚠️ 注意：** 本项目仅供学习和研究使用。实际使用时请确保遵守相关法律法规，尊重视障人士的隐私和权利。