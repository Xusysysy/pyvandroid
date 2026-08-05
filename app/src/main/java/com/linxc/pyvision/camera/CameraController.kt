package com.linxc.pyvision.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CameraX 摄像头管理器（对应桌面版 CameraManager）。
 * ImageAnalysis 输出 RGBA 帧回调；VideoCapture 提供视频录制。
 */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) {
    var cameraProvider: ProcessCameraProvider? = null
        private set
    private var camera: Camera? = null
    private var currentLens = CameraSelector.LENS_FACING_BACK
    private val executor = Executors.newSingleThreadExecutor()
    private val analysisRunning = AtomicBoolean(false)

    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    /** 每帧回调（后台线程）。返回旋转纠正后的帧 Bitmap */
    var onFrame: ((Bitmap) -> Unit)? = null

    /** 目标分辨率（null = 相机默认） */
    var targetResolution: Size? = null

    /** 录制状态回调 */
    var onRecordingEvent: ((Boolean, String?) -> Unit)? = null

    fun availableCameras(): List<Pair<String, Int>> {
        val provider = cameraProvider ?: return emptyList()
        val list = mutableListOf<Pair<String, Int>>()
        if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) list.add("后置" to CameraSelector.LENS_FACING_BACK)
        if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) list.add("前置" to CameraSelector.LENS_FACING_FRONT)
        return list
    }

    fun start() {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                cameraProvider = providerFuture.get()
                bind()
            } catch (e: Exception) {
                // 相机不可用时静默失败，UI 显示提示
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun switchCamera(lens: Int) {
        if (lens == currentLens) return
        currentLens = lens
        bind()
    }

    fun setResolution(size: Size?) {
        targetResolution = size
        bind()
    }

    private fun buildVideoCapture(): VideoCapture<Recorder> {
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(
                    Quality.HD, FallbackStrategy.higherQualityOrLowerThan(Quality.HD)
                )
            )
            .build()
        val builder = VideoCapture.Builder(recorder)
        return builder.build()
    }

    private fun bind() {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val selector = if (currentLens == CameraSelector.LENS_FACING_FRONT) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        val analysisBuilder = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
        targetResolution?.let {
            analysisBuilder.setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(it, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build()
            )
        }
        val analysis = analysisBuilder.build()
        analysis.setAnalyzer(executor) { imageProxy ->
            if (analysisRunning.compareAndSet(false, true)) {
                try {
                    onFrame?.invoke(toBitmap(imageProxy))
                } finally {
                    analysisRunning.set(false)
                    imageProxy.close()
                }
            } else {
                imageProxy.close()
            }
        }

        videoCapture = buildVideoCapture()

        try {
            camera = provider.bindToLifecycle(lifecycleOwner, selector, analysis, videoCapture)
        } catch (e: Exception) {
            try {
                camera = provider.bindToLifecycle(lifecycleOwner, selector, analysis)
            } catch (e2: Exception) {
                // 忽略
            }
        }
    }

    private fun toBitmap(imageProxy: ImageProxy): Bitmap {
        val buffer = imageProxy.planes[0].buffer
        val bitmap = Bitmap.createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        val rotation = imageProxy.imageInfo.rotationDegrees
        if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                .also { if (it != bitmap) bitmap.recycle() }
        }
        return bitmap
    }

    // ───────────── 录制 ─────────────

    fun startRecording() {
        val vc = videoCapture ?: run { onRecordingEvent?.invoke(false, "录像功能不可用"); return }
        if (activeRecording != null) return

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "rec_$timestamp.mp4"
        val outputOptions = if (Build.VERSION.SDK_INT >= 29) {
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Movies/PyVision")
            }
            MediaStoreOutputOptions.Builder(
                context.contentResolver,
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            ).setContentValues(contentValues).build()
        } else {
            MediaStoreOutputOptions.Builder(
                context.contentResolver,
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            ).build()
        }

        val recording = vc.output.prepareRecording(context, outputOptions)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> onRecordingEvent?.invoke(true, null)
                    is VideoRecordEvent.Finalize -> {
                        activeRecording = null
                        val msg = if (event.hasError()) "录制失败: ${event.error}" else "录制已保存"
                        onRecordingEvent?.invoke(false, msg)
                    }
                }
            }
        activeRecording = recording
    }

    fun stopRecording() {
        activeRecording?.stop()
        activeRecording = null
    }

    val isRecording: Boolean get() = activeRecording != null

    fun release() {
        stopRecording()
        executor.shutdown()
        cameraProvider?.unbindAll()
    }
}
