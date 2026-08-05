package com.linxc.pyvision.ml

import android.graphics.Bitmap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 检测结果（对应桌面版 dict bbox/label/confidence） */
data class Detection(
    val x: Int, val y: Int, val w: Int, val h: Int,
    val label: String, val confidence: Float,
)

/** 推理引擎统一接口：按模型后缀自动选择 ONNX Runtime / TFLite */
interface ModelEngine {
    val isLoaded: Boolean
    val loadError: String?
    /** 检测推理，返回检测框列表 */
    fun detect(bitmap: Bitmap): List<Detection>
    /** 分类推理，返回 [(label, conf)]，内部做 EMA 平滑防跳动 */
    fun classify(bitmap: Bitmap): List<Pair<String, Float>>
    fun close()
}

/** 空引擎（未加载模型） */
object NoopEngine : ModelEngine {
    override val isLoaded get() = false
    override val loadError: String? get() = null
    override fun detect(bitmap: Bitmap) = emptyList<Detection>()
    override fun classify(bitmap: Bitmap) = emptyList<Pair<String, Float>>()
    override fun close() {}
}

/** 模型加载器：自动识别 .onnx / .tflite，异步加载 */
class ModelLoader(private val onLoaded: (ModelEngine) -> Unit, private val onError: (String) -> Unit) {

    private val executor = Executors.newSingleThreadExecutor()
    private val loading = AtomicBoolean(false)
    private var engine: ModelEngine = NoopEngine

    val current: ModelEngine get() = engine

    fun load(path: String) {
        if (loading.get()) return
        loading.set(true)
        executor.execute {
            try {
                val ext = path.substringAfterLast('.', "").lowercase()
                val newEngine = when (ext) {
                    "onnx" -> OnnxEngine.create(path)
                    "tflite" -> TfliteEngine.create(path)
                    "mlp" -> MlpEngine.create(path)
                    else -> throw IllegalArgumentException("不支持的模型格式: $ext")
                }
                engine.close()
                engine = newEngine
                loading.set(false)
                onLoaded(newEngine)
            } catch (e: Exception) {
                loading.set(false)
                onError(e.message ?: "模型加载失败")
            }
        }
    }

    fun close() {
        executor.shutdown()
        engine.close()
    }
}
