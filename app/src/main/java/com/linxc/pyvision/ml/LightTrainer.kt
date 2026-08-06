package com.linxc.pyvision.ml

import android.graphics.Bitmap
import com.linxc.pyvision.data.DatasetRepository
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.exp
import kotlin.math.max
import kotlin.random.Random

/**
 * 设备端轻量分类训练器（纯 Kotlin MLP：784→64→3，ReLU + Softmax + Adam）。
 * Android 端无法运行 ultralytics/PyTorch，此实现完成"采集→划分→训练→出模型"设备端闭环。
 * 输入：28x28 灰度图（对应桌面版 imgsz=28 的简化）。
 */
class LightTrainer {

    data class Params(val input: Int = 784, val hidden: Int = 64, val output: Int = 3)
    data class Progress(val epoch: Int, val totalEpochs: Int, val loss: Float, val acc: Float, val valAcc: Float)

    class Mlp(val p: Params = Params()) {
        var w1 = Array(p.input) { FloatArray(p.hidden) { (Random.nextFloat() - 0.5f) * 0.3f } }
        var b1 = FloatArray(p.hidden) { (Random.nextFloat() - 0.5f) * 0.3f }
        var w2 = Array(p.hidden) { FloatArray(p.output) { (Random.nextFloat() - 0.5f) * 0.3f } }
        var b2 = FloatArray(p.output) { (Random.nextFloat() - 0.5f) * 0.3f }
    }

    private fun rgbToGray28(bmp: Bitmap): FloatArray {
        val resized = Bitmap.createScaledBitmap(bmp, 28, 28, true)
        val pixels = IntArray(28 * 28)
        resized.getPixels(pixels, 0, 28, 0, 0, 28, 28)
        return FloatArray(784) { i ->
            val p = pixels[i]
            val g = ((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)
            (g / (3f * 255f)) - 0.5f
        }
    }

    private fun forward(x: FloatArray, m: Mlp): Pair<FloatArray, FloatArray> {
        val p = m.p
        val h1 = FloatArray(p.hidden)
        for (j in 0 until p.hidden) {
            var acc = m.b1[j]
            var k = 0
            while (k < p.input) { acc += x[k] * m.w1[k][j]; k++ }
            h1[j] = max(0f, acc) // ReLU
        }
        val logits = FloatArray(p.output)
        for (j in 0 until p.output) {
            var acc = m.b2[j]
            var k = 0
            while (k < p.hidden) { acc += h1[k] * m.w2[k][j]; k++ }
            logits[j] = acc
        }
        // softmax
        val maxL = logits.max()
        val exps = FloatArray(p.output) { exp(logits[it] - maxL) }
        val sum = exps.sum()
        val probs = FloatArray(p.output) { exps[it] / sum }
        return h1 to probs
    }

    /**
     * 训练指定数据集。datasetName 对应 datasets/<name>/ 目录（含 train/val/<class>/）。
     * onProgress 回调每完成一个 epoch 触发。
     */
    suspend fun train(
        context: android.content.Context,
        datasetName: String,
        epochs: Int,
        batch: Int,
        onProgress: (Progress) -> Unit,
    ): Mlp {
        val root = DatasetRepository.datasetDir(context, datasetName)
        val trainData = loadDataset(File(root, "train"))
        val valData = loadDataset(File(root, "val"))
        require(trainData.first.isNotEmpty()) { "训练集为空，请先采集数据并划分数据集" }

        val m = Mlp()
        val p = m.p
        val lr = 0.001f
        val beta1 = 0.9f; val beta2 = 0.999f; val eps = 1e-8f
        val mW1 = Array(p.input) { FloatArray(p.hidden) }; val vW1 = Array(p.input) { FloatArray(p.hidden) }
        val mB1 = FloatArray(p.hidden); val vB1 = FloatArray(p.hidden)
        val mW2 = Array(p.hidden) { FloatArray(p.output) }; val vW2 = Array(p.hidden) { FloatArray(p.output) }
        val mB2 = FloatArray(p.output); val vB2 = FloatArray(p.output)

        val xs = trainData.first
        val ys = trainData.second
        val n = xs.size
        var t = 0
        var bestValAcc = 0f

        for (epoch in 0 until epochs) {
            var lossSum = 0f
            var correct = 0
            val idx = (0 until n).toList().shuffled(Random(System.nanoTime()))
            for (start in idx.indices step batch) {
                val end = minOf(start + batch, n)
                val gW1 = Array(p.input) { FloatArray(p.hidden) }
                val gB1 = FloatArray(p.hidden)
                val gW2 = Array(p.hidden) { FloatArray(p.output) }
                val gB2 = FloatArray(p.output)
                var batchLoss = 0f
                var batchCorrect = 0
                for (bi in start until end) {
                    val i = idx[bi]
                    val (h1, probs) = forward(xs[i], m)
                    val target = ys[i]
                    lossSum -= kotlin.math.ln((probs[target] + 1e-8f).toDouble()).toFloat()
                    batchLoss -= kotlin.math.ln((probs[target] + 1e-8f).toDouble()).toFloat()
                    if (probs.indices.maxByOrNull { probs[it] } == target) { correct++; batchCorrect++ }
                    // 反向传播
                    val dLogits = FloatArray(p.output) { probs[it] - (if (it == target) 1f else 0f) }
                    for (j in 0 until p.hidden) {
                        for (k in 0 until p.output) gW2[j][k] += h1[j] * dLogits[k]
                    }
                    for (k in 0 until p.output) gB2[k] += dLogits[k]
                    val dH = FloatArray(p.hidden)
                    for (j in 0 until p.hidden) {
                        var acc = 0f
                        for (k in 0 until p.output) acc += m.w2[j][k] * dLogits[k]
                        dH[j] = acc * (if (h1[j] > 0) 1f else 0f)
                    }
                    for (j in 0 until p.hidden) {
                        for (k in 0 until p.input) gW1[k][j] += xs[i][k] * dH[j]
                        gB1[j] += dH[j]
                    }
                }
                val bs = (end - start).toFloat()
                // Adam 更新
                t++
                val bc1 = (1f - Math.pow(beta1.toDouble(), t.toDouble())).toFloat()
                val bc2 = (1f - Math.pow(beta2.toDouble(), t.toDouble())).toFloat()
                fun adamUpdate(g: Float, mAcc: FloatArray, vAcc: FloatArray, i: Int): Float {
                    mAcc[i] = beta1 * mAcc[i] + (1 - beta1) * g
                    vAcc[i] = beta2 * vAcc[i] + (1 - beta2) * g * g
                    val mHat = mAcc[i] / bc1
                    val vHat = vAcc[i] / bc2
                    return lr * mHat / (kotlin.math.sqrt(vHat) + eps)
                }
                for (k in 0 until p.input) for (j in 0 until p.hidden) {
                    m.w1[k][j] -= adamUpdate(gW1[k][j] / bs, mW1[k], vW1[k], j)
                }
                for (j in 0 until p.hidden) m.b1[j] -= adamUpdate(gB1[j] / bs, mB1, vB1, j)
                for (j in 0 until p.hidden) for (k in 0 until p.output) {
                    m.w2[j][k] -= adamUpdate(gW2[j][k] / bs, mW2[j], vW2[j], k)
                }
                for (k in 0 until p.output) m.b2[k] -= adamUpdate(gB2[k] / bs, mB2, vB2, k)
            }
            val acc = correct.toFloat() / n
            val valAcc = evaluate(valData, m)
            if (valAcc > bestValAcc) bestValAcc = valAcc
            onProgress(Progress(epoch + 1, epochs, lossSum / n, acc, valAcc))
            kotlinx.coroutines.delay(1) // 让 UI 线程刷新
        }
        return m
    }

    private fun evaluate(data: Pair<List<FloatArray>, List<Int>>, m: Mlp): Float {
        if (data.first.isEmpty()) return 0f
        var correct = 0
        for (i in data.first.indices) {
            val (_, probs) = forward(data.first[i], m)
            if (probs.indices.maxByOrNull { probs[it] } == data.second[i]) correct++
        }
        return correct.toFloat() / data.first.size
    }

    private fun loadDataset(dir: File): Pair<List<FloatArray>, List<Int>> {
        val xs = mutableListOf<FloatArray>()
        val ys = mutableListOf<Int>()
        DatasetRepository.CLASSES.forEachIndexed { clsIdx, cls ->
            val classDir = File(dir, cls)
            DatasetRepository.listImages(classDir).forEach { img ->
                val bmp = android.graphics.BitmapFactory.decodeFile(img.absolutePath) ?: return@forEach
                xs.add(rgbToGray28(bmp))
                ys.add(clsIdx)
                bmp.recycle()
            }
        }
        return xs to ys
    }

    companion object {
        fun saveModel(mlp: Mlp, file: File) {
            DataOutputStream(FileOutputStream(file)).use { out ->
                val p = mlp.p
                out.writeInt(p.input); out.writeInt(p.hidden); out.writeInt(p.output)
                for (k in 0 until p.input) for (j in 0 until p.hidden) out.writeFloat(mlp.w1[k][j])
                for (j in 0 until p.hidden) out.writeFloat(mlp.b1[j])
                for (j in 0 until p.hidden) for (k in 0 until p.output) out.writeFloat(mlp.w2[j][k])
                for (k in 0 until p.output) out.writeFloat(mlp.b2[k])
            }
        }

        fun loadModel(file: File): Mlp? = runCatching {
            DataInputStream(FileInputStream(file)).use { inp ->
                val input = inp.readInt(); val hidden = inp.readInt(); val output = inp.readInt()
                val m = Mlp(Params(input, hidden, output))
                for (k in 0 until input) for (j in 0 until hidden) m.w1[k][j] = inp.readFloat()
                for (j in 0 until hidden) m.b1[j] = inp.readFloat()
                for (j in 0 until hidden) for (k in 0 until output) m.w2[j][k] = inp.readFloat()
                for (k in 0 until output) m.b2[k] = inp.readFloat()
                m
            }
        }.getOrNull()
    }
}
