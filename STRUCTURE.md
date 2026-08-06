# STRUCTURE.md

## 项目结构

```
pyvandroid/
├── app/                                  # Android 应用模块
│   ├── build.gradle.kts                  # 应用构建配置（Compose + CameraX + ONNX/TFLite）
│   └── src/main/
│       ├── AndroidManifest.xml           # 相机/录音权限，横屏入口
│       ├── java/com/linxc/pyvision/
│       │   ├── MainActivity.kt           # 入口 + 全屏 + 首次权限申请 + Home/Debug/Trainer 导航
│       │   ├── camera/
│       │   │   └── CameraController.kt   # CameraX 封装（前后/USB 外部摄像头切换、分辨率、帧回调）
│       │   ├── data/
│       │   │   ├── SettingsRepository.kt # DataStore 设置持久化（对应 settings.json）
│       │   │   └── DatasetRepository.kt  # 多数据集管理（datasets/<name>/raw/train/val + classes.json 分类标签 + zip 导出）
│       │   ├── ml/
│       │   │   ├── ModelEngine.kt        # 推理引擎接口 + ModelLoader（按后缀分发）
│       │   │   ├── OnnxEngine.kt         # ONNX Runtime 检测/分类引擎
│       │   │   ├── TfliteEngine.kt       # TFLite 分类引擎
│       │   │   ├── MlpEngine.kt          # 设备端训练模型推理
│       │   │   └── LightTrainer.kt       # 纯 Kotlin MLP 训练器（784→64→3 + Adam）
│       │   ├── processing/
│       │   │   ├── FramePipeline.kt      # 直通/灰度/边缘/镜像/偏移管线
│       │   │   └── Canny.kt              # 纯 Kotlin Canny 边缘检测
│       │   └── ui/
│       │       ├── theme/                # 深色主题（沿用 #1a1a2e 配色）
│       │       ├── home/HomeScreen.kt    # 主页导航卡片
│       │       ├── debug/                # 摄像头调试（DebugScreen + ViewModel）
│       │       └── trainer/              # 训练工作台（数据集管理 + 分类标签编辑 + 三 Tab + ViewModel）
│       └── res/                          # 主题/图标/字符串资源
├── gradle/libs.versions.toml             # 依赖版本目录
├── build.gradle.kts                      # 顶层构建配置
├── CLAUDE.md                             # AI 编码规范
├── README.md                             # 项目说明
└── STRUCTURE.md                          # 本文件
```

## 模块职责

| 模块 | 对应桌面版 | 职责 |
|------|-----------|------|
| `CameraController` | `CameraManager` | CameraX 帧流，前后/USB 外部摄切换，分辨率设置 |
| `FramePipeline` | `FrameProcessor` 系列 | 直通/灰度/Canny 处理，镜像与偏移平移 |
| `Canny` | `cv2.Canny` | 纯 Kotlin 实现的高斯+Sobel+NMS+双阈值 |
| `OnnxEngine` | `CNNProcessor._load_onnx` | ONNX 检测（2D Nx6 / 3D xywh）+ 分类 |
| `TfliteEngine` | `CNNProcessor` 分类 | TFLite 分类推理 |
| `LightTrainer` | `trainer.py` 训练 | 设备端 MLP 迁移训练，EMA 平滑分类 |
| `SettingsRepository` | `Settings` | DataStore 持久化 |
| `DatasetRepository` | `_prepare_dataset` | 多数据集命名管理（新建/删除/切换），raw/train/val 划分与统计，zip 导出 |

## 功能映射

| 桌面版功能 | Android 实现 |
|-----------|-------------|
| 偏移校准滑块 + 十字线 | Slider + Canvas 十字准星 |
| CNN 每 3 帧推理 + EMA 平滑 | `inferEvery=3` + `smoothProbs` 0.5/0.5 |
| 快照/录制 | MediaStore 相册 + CameraX VideoCapture |
| 设置自动恢复 | DataStore Flow + `settings.first()` |
| 数据集划分（seed 42） | Random(42) 划分 |
| 多摄像头/USB 摄像头 | 前后摄 + `LENS_FACING_EXTERNAL`（Android 12+ 系统级 UVC 支持） |
| 全屏显示 | `WindowInsetsControllerCompat` 隐藏系统栏 |
| 运行时权限 | MainActivity 首次进入申请 CAMERA + RECORD_AUDIO + 文件访问权限 |

## 依赖关系

```
CameraX → 帧 Bitmap → FramePipeline → 显示
                      └→ ModelEngine（ONNX/TFLite/MLP）→ 检测框/分类概率 overlay
DataStore ← SettingsRepository ← UI
DatasetRepository ← TrainerScreen ← LightTrainer
```
