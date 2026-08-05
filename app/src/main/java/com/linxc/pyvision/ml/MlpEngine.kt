package com.linxc.pyvision.ml

import android.graphics.Bitmap
import java.io.File
import kotlin.math.exp
import kotlin.math.max

/**
 * 设备端训练模型的推理引擎（加载 LightTrainer 产出的 .mlp 权重）。
 * 与 TfliteEngine 相同的分类语义，供 CNN 处理器直接使用。
 */
class MlpEngine private constructor(private val mlp: LightTrainer.Mlp) : ModelEngine {

    override val isLoaded get() = true
    override var loadError: String? = null

    private val smoothProbs = HashMap<String, Float>()
    private var cachedProbs: List<Pair<String, Float>> = emptyList()

    override fun detect(bitmap: Bitmap): List<Detection> {
        loadError = "设备端模型仅支持分类"
        return emptyList()
    }

    override fun classify(bitmap: Bitmap): List<Pair<String, Float>> {
        try {
            val resized = Bitmap.createScaledBitmap(bitmap, 28, 28, true)
            val pixels = IntArray(28 * 28)
            resized.getPixels(pixels, 0, 28, 0, 0, 28, 28)
            val x = FloatArray(784) { i ->
                val p = pixels[i]
                val g = ((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)
                (g / (3f * 255f)) - 0.5f
            }
            val m = mlp
            val p = m.p
            val h1 = FloatArray(p.hidden)
            for (j in 0 until p.hidden) {
                var acc = m.b1[j]
                for (k in 0 until p.input) acc += x[k] * m.w1[k][j]
                h1[j] = max(0f, acc)
            }
            val logits = FloatArray(p.output)
            for (j in 0 until p.output) {
                var acc = m.b2[j]
                for (k in 0 until p.hidden) acc += h1[k] * m.w2[k][j]
                logits[j] = acc
            }
            val maxL = logits.max()
            val exps = FloatArray(p.output) { exp(logits[it] - maxL) }
            val sum = exps.sum()
            val probs = FloatArray(p.output) { exps[it] / sum }
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

    override fun close() {}

    companion object {
        fun create(path: String): MlpEngine {
            val mlp = LightTrainer.loadModel(File(path))
                ?: throw IllegalArgumentException("模型文件无法解析: $path")
            return MlpEngine(mlp)
        }
    }
}
