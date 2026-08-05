package com.linxc.pyvision.ml

import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * TFLite 推理引擎（分类模型，对应桌面版 _classify 的 EMA 平滑展示）。
 * 支持设备端训练产出的 .tflite 模型（输入 1x28x28x1 灰度）。
 */
class TfliteEngine private constructor(
    private val interpreter: Interpreter,
    private val inputShape: IntArray,
) : ModelEngine {

    override val isLoaded get() = true
    override var loadError: String? = null

    private val smoothProbs = HashMap<String, Float>()
    private var cachedProbs: List<Pair<String, Float>> = emptyList()

    override fun detect(bitmap: Bitmap): List<Detection> {
        loadError = "TFLite 引擎仅支持分类"
        return emptyList()
    }

    override fun infer(bitmap: Bitmap): InferenceResult = InferenceResult(classProbs = classify(bitmap))

    override fun classify(bitmap: Bitmap): List<Pair<String, Float>> {
        try {
            val (w, h, ch) = Triple(
                inputShape.getOrElse(1) { 28 },
                inputShape.getOrElse(2) { 28 },
                inputShape.getOrElse(3) { 1 },
            )
            val scaled = if (w == 1) {
                // 模型可能是 NCHW
                tripleToNchw(bitmap, w, h, ch)
            } else {
                tripleToNhwc(bitmap, w, h, ch)
            }
            val input = scaled.first
            val outputShape = interpreter.getOutputTensor(0).shape().map { it.toInt() }
            val output = Array(1) { FloatArray(outputShape.getOrElse(1) { 3 }) }
            interpreter.run(input, output)
            val row = output[0]
            val sum = row.sum()
            val probs = if (sum > 0) row.map { it / sum }.toFloatArray() else row
            val items = probs.withIndex()
                .sortedByDescending { it.value }
                .take(5)
                .map { it.index.toString() to it.value }
            items.forEach { (label, conf) ->
                val prev = smoothProbs[label]
                smoothProbs[label] = if (prev != null) prev * 0.5f + conf * 0.5f else conf
            }
            cachedProbs = items.map { (l, c) -> l to (smoothProbs[l] ?: c) }
        } catch (e: Exception) {
            loadError = e.message
        }
        return cachedProbs
    }

    private fun tripleToNhwc(bmp: Bitmap, w: Int, h: Int, ch: Int): Pair<ByteBuffer, Boolean> {
        val resized = Bitmap.createScaledBitmap(bmp, w, h, true)
        val pixels = IntArray(w * h)
        resized.getPixels(pixels, 0, w, 0, 0, w, h)
        val buf = ByteBuffer.allocateDirect(1 * w * h * ch * 4).order(ByteOrder.nativeOrder())
        for (p in pixels) {
            if (ch == 3) {
                buf.putFloat(((p shr 16) and 0xFF) / 255f)
                buf.putFloat(((p shr 8) and 0xFF) / 255f)
                buf.putFloat((p and 0xFF) / 255f)
            } else {
                buf.putFloat((((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)) / (3f * 255f))
            }
        }
        buf.rewind()
        return buf to true
    }

    private fun tripleToNchw(bmp: Bitmap, w: Int, h: Int, ch: Int): Pair<ByteBuffer, Boolean> {
        val resized = Bitmap.createScaledBitmap(bmp, w, h, true)
        val pixels = IntArray(w * h)
        resized.getPixels(pixels, 0, w, 0, 0, w, h)
        val gray = FloatArray(w * h) {
            val p = pixels[it]
            (((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)) / (3f * 255f)
        }
        val buf = ByteBuffer.allocateDirect(1 * w * h * ch * 4).order(ByteOrder.nativeOrder())
        if (ch == 1) {
            buf.asFloatBuffer().put(gray)
        }
        buf.rewind()
        return buf to true
    }

    override fun close() {
        interpreter.close()
    }

    companion object {
        fun create(path: String): TfliteEngine {
            val interpreter = Interpreter(File(path))
            val inputShape = interpreter.getInputTensor(0).shape().map { it.toInt() }.toIntArray()
            return TfliteEngine(interpreter, inputShape)
        }
    }
}
