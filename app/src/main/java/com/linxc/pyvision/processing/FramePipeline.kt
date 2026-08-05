package com.linxc.pyvision.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 图像处理管线：直通 / 灰度 / Canny 边缘 / 偏移平移（对应 pyvision 桌面版 FrameProcessor） */
object FramePipeline {

    enum class Mode { PASSTHROUGH, GRAYSCALE, EDGE }

    data class ProcessedFrame(val bitmap: Bitmap, val mode: Mode)

    /** 对帧应用处理模式；偏移由 UI 层绘制（Canvas translate）完成 */
    suspend fun process(bmp: Bitmap, mode: Mode): ProcessedFrame = withContext(Dispatchers.Default) {
        val out = when (mode) {
            Mode.PASSTHROUGH -> bmp.copy(Bitmap.Config.ARGB_8888, false)
            Mode.GRAYSCALE -> applyColorMatrix(bmp, grayscaleMatrix())
            Mode.EDGE -> edgeDetect(bmp)
        }
        ProcessedFrame(out, mode)
    }

    /** 同步处理（已在后台线程调用时使用） */
    fun processSync(bmp: Bitmap, mode: Mode): Bitmap = when (mode) {
        Mode.PASSTHROUGH -> bmp.copy(Bitmap.Config.ARGB_8888, false)
        Mode.GRAYSCALE -> applyColorMatrix(bmp, grayscaleMatrix())
        Mode.EDGE -> edgeDetect(bmp)
    }

    /** 水平镜像 */
    fun flipHorizontal(bmp: Bitmap): Bitmap {
        val matrix = android.graphics.Matrix().apply { preScale(-1f, 1f) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
    }

    fun applyOffset(bmp: Bitmap, offsetX: Int, offsetY: Int): Bitmap {
        if (offsetX == 0 && offsetY == 0) return bmp
        val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(android.graphics.Color.BLACK)
        canvas.drawBitmap(bmp, offsetX.toFloat(), offsetY.toFloat(), null)
        return out
    }

    private fun applyColorMatrix(src: Bitmap, matrix: ColorMatrix): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    private fun grayscaleMatrix(): ColorMatrix = ColorMatrix(floatArrayOf(
        0.299f, 0.587f, 0.114f, 0f, 0f,
        0.299f, 0.587f, 0.114f, 0f, 0f,
        0.299f, 0.587f, 0.114f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    ))

    private fun edgeDetect(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val gray = IntArray(w * h)
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            gray[i] = (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255)
        }
        val edges = Canny.detect(gray, w, h)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (i in edges.indices) {
            val v = edges[i]
            out.setPixel(i % w, i / w, android.graphics.Color.rgb(v, v, v))
        }
        return out
    }
}
