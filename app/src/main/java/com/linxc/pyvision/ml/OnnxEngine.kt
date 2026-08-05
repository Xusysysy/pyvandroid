package com.linxc.pyvision.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap

/**
 * ONNX Runtime 推理引擎（对应桌面版 CNNProcessor._load_onnx / _detect_onnx）。
 * 支持检测（2D [N,6] / 3D [N,5+]）与分类两种输出；首次推理探测任务类型并缓存。
 */
class OnnxEngine private constructor(
    private val session: OrtSession,
    private val inputName: String,
    private val inputShape: LongArray,
) : ModelEngine {

    override val isLoaded get() = true
    override var loadError: String? = null

    @Volatile
    private var task: ModelTask? = null

    private val smoothProbs = HashMap<String, Float>()
    private var cachedDetections: List<Detection> = emptyList()
    private var cachedProbs: List<Pair<String, Float>> = emptyList()

    override fun detect(bitmap: Bitmap): List<Detection> {
        if (task == ModelTask.CLASSIFY) return emptyList()
        runOnce(bitmap)
        return cachedDetections
    }

    override fun classify(bitmap: Bitmap): List<Pair<String, Float>> {
        if (task == ModelTask.DETECT) return emptyList()
        runOnce(bitmap)
        return cachedProbs
    }

    /** 单次推理：内部探测任务类型并按类型解析，避免双重推理 */
    override fun infer(bitmap: Bitmap): InferenceResult {
        runOnce(bitmap)
        return InferenceResult(cachedDetections, cachedProbs)
    }

    private fun runOnce(bitmap: Bitmap) {
        try {
            val tensor = bitmapToTensor(bitmap)
            val outputs = session.run(mapOf(inputName to tensor))
            outputs.use {
                val out = it.get(0).value
                when (out) {
                    is FloatArray -> { task = ModelTask.CLASSIFY; parseClassifyVector(out) }
                    is Array<*> -> {
                        val first = out.firstOrNull() as? Array<*>
                        val cols = first?.size ?: 0
                        if (cols == 6 || (cols >= 5 && out.size > 1)) {
                            task = ModelTask.DETECT
                            parseDetect(out)
                        } else {
                            task = ModelTask.CLASSIFY
                            parseClassifyArray(out)
                        }
                    }
                    else -> task = ModelTask.CLASSIFY
                }
            }
        } catch (e: Exception) {
            loadError = e.message
        }
    }

    private fun bitmapToTensor(bmp: Bitmap): OnnxTensor {
        val ih = inputShape.getOrNull(2) ?: 640L
        val iw = inputShape.getOrNull(3) ?: 640L
        val resized = Bitmap.createScaledBitmap(bmp, iw.toInt(), ih.toInt(), true)
        val pixels = IntArray(resized.width * resized.height)
        resized.getPixels(pixels, 0, resized.width, 0, 0, resized.width, resized.height)
        val data = FloatArray(1 * 3 * resized.height * resized.width)
        var idx = 0
        for (p in pixels) {
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            data[idx] = r
            data[idx + resized.width * resized.height] = g
            data[idx + 2 * resized.width * resized.height] = b
            idx++
        }
        val shape = longArrayOf(1, 3, resized.height.toLong(), resized.width.toLong())
        return OnnxTensor.createTensor(
            OrtEnvironment.getEnvironment(),
            java.nio.ByteBuffer.allocateDirect(data.size * 4).order(java.nio.ByteOrder.nativeOrder())
                .also { it.asFloatBuffer().put(data) },
            shape,
        )
    }

    // ──────── 输出解析 ────────

    private fun parseClassifyVector(probs: FloatArray) {
        val items = probs.withIndex()
            .sortedByDescending { it.value }
            .take(5)
            .map { it.index.toString() to it.value }
        smooth(items)
    }

    private fun parseClassifyArray(out: Array<*>) {
        val row = out.firstOrNull() as? Array<*> ?: return
        val probs = FloatArray(row.size)
        var sum = 0f
        for (i in row.indices) {
            val v = (row[i] as? Number)?.toFloat() ?: 0f
            probs[i] = v
            sum += v
        }
        if (sum > 0) for (i in probs.indices) probs[i] /= sum
        val items = probs.withIndex()
            .sortedByDescending { it.value }
            .take(5)
            .map { it.index.toString() to it.value }
        smooth(items)
    }

    /** EMA 平滑（对应桌面版 _smooth_probs） */
    private fun smooth(items: List<Pair<String, Float>>) {
        items.forEach { (label, conf) ->
            val prev = smoothProbs[label]
            smoothProbs[label] = if (prev != null) prev * 0.5f + conf * 0.5f else conf
        }
        cachedProbs = items.map { (l, c) -> l to (smoothProbs[l] ?: c) }
        cachedDetections = emptyList()
    }

    private fun parseDetect(out: Array<*>) {
        val results = mutableListOf<Detection>()
        val first = out.firstOrNull() as? Array<*>
        val cols = first?.size ?: 0
        if (cols == 6) {
            for (det in out) {
                val row = det as? Array<*> ?: continue
                val x1 = (row[0] as? Number)?.toFloat() ?: continue
                val y1 = (row[1] as? Number)?.toFloat() ?: continue
                val x2 = (row[2] as? Number)?.toFloat() ?: continue
                val y2 = (row[3] as? Number)?.toFloat() ?: continue
                val conf = (row[4] as? Number)?.toFloat() ?: continue
                val cls = (row[5] as? Number)?.toInt() ?: 0
                if (conf < 0.25f) continue
                results.add(Detection(x1.toInt(), y1.toInt(), (x2 - x1).toInt(), (y2 - y1).toInt(), cls.toString(), conf))
            }
        } else {
            // 3D xywh 格式
            for (det in out) {
                val row = det as? Array<*> ?: continue
                if (row.size < 5) continue
                val cx = (row[0] as? Number)?.toFloat() ?: continue
                val cy = (row[1] as? Number)?.toFloat() ?: continue
                val w = (row[2] as? Number)?.toFloat() ?: continue
                val h = (row[3] as? Number)?.toFloat() ?: continue
                val conf = (row[4] as? Number)?.toFloat() ?: continue
                if (conf < 0.25f) continue
                val x = (cx - w / 2).toInt()
                val y = (cy - h / 2).toInt()
                val cls = if (row.size > 5) (row[5] as? Number)?.toInt()?.toString() ?: "0" else "0"
                results.add(Detection(x, y, w.toInt(), h.toInt(), cls, conf))
            }
        }
        cachedDetections = results
        cachedProbs = emptyList()
    }

    override fun close() {
        session.close()
    }

    companion object {
        fun create(path: String): OnnxEngine {
            val env = OrtEnvironment.getEnvironment()
            val session = env.createSession(path, OrtSession.SessionOptions())
            val inputName = session.inputNames.first()
            val tensorInfo = session.inputInfo[inputName]?.info as? ai.onnxruntime.TensorInfo
            val inputShape = tensorInfo?.shape ?: longArrayOf(1, 3, 640, 640)
            return OnnxEngine(session, inputName, inputShape)
        }
    }
}
