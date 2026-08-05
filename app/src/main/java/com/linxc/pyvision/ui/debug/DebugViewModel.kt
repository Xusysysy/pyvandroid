package com.linxc.pyvision.ui.debug

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.linxc.pyvision.camera.CameraController
import com.linxc.pyvision.data.SettingsRepository
import com.linxc.pyvision.ml.Detection
import com.linxc.pyvision.ml.ModelLoader
import com.linxc.pyvision.processing.FramePipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

data class DebugUiState(
    val processor: String = "直通 (原始)",
    val modelPath: String = "",
    val modelLoaded: Boolean = false,
    val modelLoading: Boolean = false,
    val modelError: String? = null,
    val showFps: Boolean = true,
    val showCrosshair: Boolean = false,
    val mirror: Boolean = false,
    val offsetX: Int = 0,
    val offsetY: Int = 0,
    val cameraName: String = "后置",
    val resolution: String = "--",
    val fps: Float = 0f,
    val frameSize: String = "",
    val detections: List<Detection> = emptyList(),
    val classProbs: List<Pair<String, Float>> = emptyList(),
    val snapshotCount: Int = 0,
    val snapshotPath: String? = null,
    val recording: Boolean = false,
    val status: String = "就绪",
)

/** 摄像头调试 ViewModel（对应桌面版 CameraDebuggerGUI 的状态与逻辑） */
class DebugViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        const val PROCESSOR_PASSTHROUGH = "直通 (原始)"
        const val PROCESSOR_GRAYSCALE = "灰度"
        const val PROCESSOR_EDGE = "边缘检测 (Canny)"
        const val PROCESSOR_CNN = "CNN 模型"
        val PROCESSORS = listOf(PROCESSOR_PASSTHROUGH, PROCESSOR_GRAYSCALE, PROCESSOR_EDGE, PROCESSOR_CNN)

        const val RESOLUTION_AUTO = "自动"
        val RESOLUTION_PRESETS = listOf(
            RESOLUTION_AUTO, "1920x1080", "1280x720", "1024x768", "800x600", "640x480",
        )
    }

    private val settingsRepo = SettingsRepository(app)
    private val _state = MutableStateFlow(DebugUiState())
    val state: StateFlow<DebugUiState> = _state.asStateFlow()

    private var cameraController: CameraController? = null
    private var modelLoader: ModelLoader? = null
    private val lastBitmap = AtomicBoolean(false)
    private var inferenceCounter = 0
    private val inferEvery = 3 // 对应桌面版 infer_every
    private var frameJob: Job? = null
    private var lastFrameTime = 0L
    private var fpsAccum = 0
    private var currentProcessed: Bitmap? = null

    fun init(camera: CameraController) {
        cameraController = camera
        camera.onFrame = { frame -> onFrameArrived(frame) }
        viewModelScope.launch {
            val s = settingsRepo.settings.first()
            _state.value = _state.value.copy(
                processor = s.processor,
                modelPath = s.modelPath,
                showFps = s.showFps,
                showCrosshair = s.showCrosshair,
                mirror = s.mirror,
                offsetX = s.offsetX,
                offsetY = s.offsetY,
                snapshotCount = s.snapshotCount,
            )
            if (s.modelPath.isNotEmpty() && s.processor == PROCESSOR_CNN) {
                loadModel(s.modelPath)
            }
        }
        camera.start()
        frameJob = viewModelScope.launch { frameTicker() }
    }

    /** 帧限频：约 30 FPS 刷新，避免对相同缓存帧的冗余重绘（对应桌面版 after(33)） */
    private suspend fun frameTicker() {
        while (true) {
            delay(33)
            fpsAccum++
            val now = System.currentTimeMillis()
            if (now - lastFrameTime >= 1000) {
                val fps = fpsAccum * 1000f / (now - lastFrameTime).coerceAtLeast(1)
                _state.value = _state.value.copy(fps = fps)
                fpsAccum = 0
                lastFrameTime = now
            }
        }
    }

    private fun onFrameArrived(frame: Bitmap) {
        if (lastBitmap.compareAndSet(false, true)) {
            try {
                processFrame(frame)
            } finally {
                lastBitmap.set(false)
            }
        }
    }

    private fun processFrame(frame: Bitmap) {
        val s = _state.value
        var display = frame
        if (s.mirror) display = FramePipeline.flipHorizontal(frame)
        val mode = when (s.processor) {
            PROCESSOR_GRAYSCALE -> FramePipeline.Mode.GRAYSCALE
            PROCESSOR_EDGE -> FramePipeline.Mode.EDGE
            else -> FramePipeline.Mode.PASSTHROUGH
        }

        val processed = if (s.processor == PROCESSOR_CNN) {
            // CNN 模式：直通显示 + 推理 overlay
            display
        } else {
            try {
                FramePipeline.applyOffset(
                    FramePipeline.processSync(display, mode),
                    s.offsetX, s.offsetY,
                )
            } catch (e: Exception) {
                display
            }
        }
        currentProcessed = processed

        // CNN 推理（每 N 帧一次，复用缓存结果）
        if (s.processor == PROCESSOR_CNN) {
            inferenceCounter++
            val engine = modelLoader?.current
            val doInfer = engine != null && inferenceCounter % inferEvery == 0
            if (engine != null && doInfer) {
                val clean = if (s.mirror) FramePipeline.flipHorizontal(display) else display
                val detections = engine.detect(clean)
                val probs = engine.classify(clean)
                inferenceCounter = 0
                _state.value = _state.value.copy(
                    detections = detections,
                    classProbs = probs,
                    frameSize = "${display.width}x${display.height}",
                )
            }
        } else {
            _state.value = _state.value.copy(frameSize = "${display.width}x${display.height}")
        }
    }

    fun getProcessedFrame(): Bitmap? = currentProcessed

    fun availableCameras(): List<Pair<String, Int>> =
        cameraController?.availableCameras() ?: emptyList()

    // ───────────── 设置变更 ─────────────

    fun setProcessor(name: String) {
        _state.value = _state.value.copy(processor = name)
        viewModelScope.launch { settingsRepo.update(processor = name) }
        if (name != PROCESSOR_CNN) {
            _state.value = _state.value.copy(detections = emptyList(), classProbs = emptyList())
        }
    }

    fun toggleFps() {
        _state.value = _state.value.copy(showFps = !_state.value.showFps)
        viewModelScope.launch { settingsRepo.update(showFps = _state.value.showFps) }
    }

    fun toggleCrosshair() {
        _state.value = _state.value.copy(showCrosshair = !_state.value.showCrosshair)
        viewModelScope.launch { settingsRepo.update(showCrosshair = _state.value.showCrosshair) }
    }

    fun toggleMirror() {
        _state.value = _state.value.copy(mirror = !_state.value.mirror)
        viewModelScope.launch { settingsRepo.update(mirror = _state.value.mirror) }
    }

    fun setOffset(x: Int, y: Int) {
        _state.value = _state.value.copy(offsetX = x, offsetY = y)
        viewModelScope.launch { settingsRepo.update(offsetX = x, offsetY = y) }
    }

    fun resetOffset() = setOffset(0, 0)

    fun switchCamera(lens: Int, name: String) {
        _state.value = _state.value.copy(cameraName = name, status = "已切换到 $name")
        cameraController?.switchCamera(lens)
        viewModelScope.launch { settingsRepo.update(cameraId = lens.toString()) }
    }

    fun setResolution(res: String) {
        val size = if (res == RESOLUTION_AUTO) null else {
            val parts = res.split("x")
            android.util.Size(parts[0].toInt(), parts[1].toInt())
        }
        cameraController?.setResolution(size)
        _state.value = _state.value.copy(resolution = res, status = "分辨率: $res")
        viewModelScope.launch { settingsRepo.update(resolution = res) }
    }

    fun loadModel(path: String) {
        _state.value = _state.value.copy(modelPath = path, modelLoading = true, modelError = null)
        modelLoader?.close()
        modelLoader = ModelLoader(
            onLoaded = { engine ->
                _state.value = _state.value.copy(
                    modelLoading = false, modelLoaded = true, modelError = null,
                    status = "模型已加载: ${File(path).name}",
                )
            },
            onError = { msg ->
                _state.value = _state.value.copy(modelLoading = false, modelLoaded = false, modelError = msg)
            },
        )
        modelLoader?.load(path)
        viewModelScope.launch { settingsRepo.update(modelPath = path) }
    }

    fun setModelError(message: String) {
        _state.value = _state.value.copy(modelError = message, modelLoading = false)
    }

    // ───────────── 快照 ─────────────

    fun snapshot() {
        val bmp = currentProcessed ?: run {
            _state.value = _state.value.copy(status = "没有可保存的帧")
            return
        }
        viewModelScope.launch {
            val path = saveSnapshotToGallery(bmp)
            if (path != null) {
                _state.value = _state.value.copy(
                    snapshotCount = _state.value.snapshotCount + 1,
                    snapshotPath = path,
                    status = "已保存: $path",
                )
                settingsRepo.update(snapshotCount = _state.value.snapshotCount)
            } else {
                _state.value = _state.value.copy(status = "保存失败")
            }
        }
    }

    private suspend fun saveSnapshotToGallery(bmp: Bitmap): String? = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val filename = "snap_$timestamp.png"
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "PyVision")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, filename)
        return@withContext try {
            FileOutputStream(file).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
            MediaScannerConnection.scanFile(app, arrayOf(file.absolutePath), null, null)
            file.absolutePath
        } catch (e: IOException) {
            null
        }
    }

    // ───────────── 录制 ─────────────

    fun toggleRecording() {
        val cam = cameraController ?: return
        if (_state.value.recording) {
            cam.stopRecording()
            _state.value = _state.value.copy(recording = false, status = "录制已停止")
        } else {
            cam.onRecordingEvent = { recording, msg ->
                _state.value = _state.value.copy(recording = recording)
                if (msg != null) _state.value = _state.value.copy(status = msg)
            }
            cam.startRecording()
            _state.value = _state.value.copy(status = "录制中...")
        }
    }

    override fun onCleared() {
        if (_state.value.recording) cameraController?.stopRecording()
        cameraController?.release()
        modelLoader?.close()
        super.onCleared()
    }
}
