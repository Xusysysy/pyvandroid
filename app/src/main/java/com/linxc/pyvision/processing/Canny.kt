package com.linxc.pyvision.processing

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 纯 Kotlin Canny 边缘检测（对应 pyvision 桌面版 cv2.Canny）。
 * 步骤：灰度 → 高斯模糊 → Sobel 梯度 → 非极大值抑制 → 双阈值滞后连接。
 */
object Canny {

    fun detect(gray: IntArray, width: Int, height: Int, low: Double = 50.0, high: Double = 150.0): IntArray {
        val blurred = gaussianBlur(gray, width, height)
        val gx = IntArray(gray.size)
        val gy = IntArray(gray.size)
        val mag = FloatArray(gray.size)
        val angle = IntArray(gray.size) // 0=0°, 1=45°, 2=90°, 3=135°

        for (y in 1 until height - 1) {
            var i = y * width
            for (x in 1 until width - 1) {
                val idx = y * width + x
                val dx = -blurred[idx - width - 1] - 2 * blurred[idx - 1] - blurred[idx + width - 1]
                        + blurred[idx - width + 1] + 2 * blurred[idx + 1] + blurred[idx + width + 1]
                val dy = -blurred[idx - width - 1] - 2 * blurred[idx - width] - blurred[idx - width + 1]
                        + blurred[idx + width - 1] + 2 * blurred[idx + width] + blurred[idx + width + 1]
                gx[idx] = dx
                gy[idx] = dy
                mag[idx] = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                angle[idx] = if (abs(dx) > abs(dy)) {
                    if (dx * dy > 0) 3 else 1 // 45° / 135°
                } else {
                    0 // 0° / 90°
                }
            }
        }

        // 非极大值抑制
        val nms = FloatArray(gray.size)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val idx = y * width + x
                val a = angle[idx]
                val m = mag[idx]
                val (m1, m2) = when (a) {
                    0 -> mag[idx - 1] to mag[idx + 1]
                    1 -> mag[idx - width + 1] to mag[idx + width - 1]
                    3 -> mag[idx - width - 1] to mag[idx + width + 1]
                    else -> mag[idx - width] to mag[idx + width]
                }
                if (m >= m1 && m >= m2) nms[idx] = m
            }
        }

        // 双阈值 + 滞后连接
        val out = IntArray(gray.size)
        val visited = BooleanArray(gray.size)
        fun trace(x: Int, y: Int) {
            if (x <= 0 || y <= 0 || x >= width - 1 || y >= height - 1) return
            val idx = y * width + x
            if (visited[idx] || nms[idx] < low) return
            visited[idx] = true
            out[idx] = 255
            for (dy in -1..1) for (dx in -1..1) {
                if (dx != 0 || dy != 0) trace(x + dx, y + dy)
            }
        }

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val idx = y * width + x
                if (!visited[idx] && nms[idx] >= high) trace(x, y)
            }
        }
        return out
    }

    private fun gaussianBlur(gray: IntArray, width: Int, height: Int): IntArray {
        val kernel = doubleArrayOf(1.0, 2.0, 1.0, 2.0, 4.0, 2.0, 1.0, 2.0, 1.0)
        val sum = 16.0
        val out = IntArray(gray.size)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                var acc = 0.0
                var k = 0
                for (dy in -1..1) for (dx in -1..1) {
                    acc += gray[(y + dy) * width + (x + dx)] * kernel[k++]
                }
                out[y * width + x] = (acc / sum).toInt().coerceIn(0, 255)
            }
        }
        return out
    }
}
