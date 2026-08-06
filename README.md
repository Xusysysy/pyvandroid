# pyvandroid

> GitHub: https://github.com/Xusysysy/pyvandroid.git
> Gitee: https://gitee.com/lin-xiaochuan/pyvandroid.git

pyvision 的 Android 移植版 —— 摄像头调试 + 视觉识别训练工作台，Jetpack Compose 原生应用。

## 功能（对应桌面版 pyvision）

### 摄像头调试（camera_debugger）
- **摄像头预览**：前后摄像头切换、分辨率设置（自动/预设）
- **偏移校准**：水平/竖直滑块平移画面，中心十字线对照，校正摄像头安装偏差
- **CNN 目标检测**：ONNX (.onnx) / TFLite (.tflite) / 设备端模型 (.mlp) 实时推理，文件选择器加载
- **基础图像处理**：灰度、边缘检测（Canny，纯 Kotlin 实现）
- **快照与录制**：拍照保存到相册、视频录制
- **设置持久化**：DataStore 自动保存并恢复

### 训练工作台（trainer）
- **1. 采集数据**：摄像头预览，分类一键保存（分类标签可自定义增删改名）
- **2. 准备数据**：自动按比例划分训练集/验证集（随机种子 42）
- **3. 训练模型**：设备端轻量 MLP 分类训练（纯 Kotlin，784→64→N + Adam），实时日志与进度；另支持导出数据集 zip 到 PC 用 pyvision 桌面版训练 YOLO11-cls

## 技术栈

| 组件 | 选型 |
|------|------|
| 语言 | Kotlin |
| UI | Jetpack Compose + Material 3（深色主题） |
| 相机 | CameraX（ImageAnalysis 帧流） |
| 推理 | ONNX Runtime Android + TFLite |
| 持久化 | DataStore Preferences |
| 构建 | Gradle 9.4.1 + AGP 9.2.1，compileSdk 36.1，minSdk 26 |

## 构建

```bash
# 需要 JDK 17+
set JAVA_HOME=D:\software\AndroidStudio\jbr
gradlew.bat :app:assembleDebug
# 输出: app\build\outputs\apk\debug\app-debug.apk
```

## 与桌面版差异

- Android 无法直接运行 ultralytics/PyTorch：训练改为设备端轻量 MLP 分类（设备端闭环），或导出数据集 zip 到 PC 训练 YOLO11-cls 后导入 ONNX 模型
- 模型格式：.onnx / .tflite / .mlp（设备端训练产物）
- 摄像头多摄：前置/后置切换（Android CameraX 语义）
- 快照保存到系统相册 Pictures/PyVision

## License

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)

本项目基于 [GNU General Public License v3.0](https://www.gnu.org/licenses/gpl-3.0) 发布，版权归 linxc 所有。
