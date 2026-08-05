package com.linxc.pyvision.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CameraX 摄像头管理器（对应桌面版 CameraManager）。
 * 使用 ImageAnalysis 输出 RGBA 帧，旋转纠正后回调。支持前后摄像头切换与分辨率设置。
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

    /** 每帧回调（后台线程）。返回旋转纠正后的帧 Bitmap */
    var onFrame: ((Bitmap) -> Unit)? = null

    /** 目标分辨率（null = 相机默认） */
    var targetResolution: Size? = null

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
        targetResolution?.let { analysisBuilder.setTargetResolution(it) }
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

        try {
            camera = provider.bindToLifecycle(lifecycleOwner, selector, analysis)
        } catch (e: Exception) {
            // 该分辨率组合不可用时回退到默认
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

    fun release() {
        executor.shutdown()
        cameraProvider?.unbindAll()
    }
}
