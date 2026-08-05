package com.linxc.pyvision.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 数据集目录管理：raw 采集暂存 / train / val 划分（对应 pyvision 桌面版 dataset 目录） */
object DatasetRepository {

    const val CLASS_SMART = "smart_glasses"
    const val CLASS_REGULAR = "regular_glasses"
    const val CLASS_NEGATIVE = "negative"

    val CLASSES = listOf(CLASS_SMART, CLASS_REGULAR, CLASS_NEGATIVE)
    val CLASS_LABELS = listOf("智能眼镜", "普通眼镜", "空桌面")

    fun root(context: Context): File = File(context.filesDir, "dataset")

    fun rawDir(context: Context): File = File(root(context), "raw")

    fun classDir(context: Context, cls: String): File = File(rawDir(context), cls)

    fun listImages(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && f.extension.lowercase() in setOf("jpg", "jpeg", "png") }
            ?.sortedBy { it.name } ?: emptyList()

    fun countByClass(context: Context, base: File): List<Int> =
        CLASSES.map { listImages(File(base, it)).size }

    fun totalRaw(context: Context): Int = countByClass(context, rawDir(context)).sum()

    fun ensureDirs(context: Context) {
        CLASSES.forEach { cls ->
            val d = File(rawDir(context), cls)
            if (!d.exists()) d.mkdirs()
        }
    }

    /** 保存采集帧到 raw/<class>/，返回保存的文件 */
    fun saveFrame(context: Context, cls: String, bmp: android.graphics.Bitmap): File? {
        ensureDirs(context)
        val name = "%s_%d.jpg".format(cls, System.currentTimeMillis())
        val file = File(classDir(context, cls), name)
        val ok = runCatching {
            FileOutputStream(file).use { out ->
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, out)
            }
        }.isSuccess
        return if (ok) file else null
    }

    /** 按比例划分数据集到 train/val（随机种子固定 42，对应桌面版） */
    suspend fun prepare(context: Context, valRatio: Float) = withContext(Dispatchers.IO) {
        val raw = rawDir(context)
        if (CLASSES.sumOf { listImages(File(raw, it)).size } == 0) {
            throw RuntimeException("原始数据为空，请先采集数据")
        }
        for (cls in CLASSES) {
            for (split in listOf("train", "val")) {
                val d = File(root(context), "$split/$cls")
                d.deleteRecursively()
                d.mkdirs()
            }
        }
        val rnd = java.util.Random(42)
        for (cls in CLASSES) {
            val imgs = listImages(File(raw, cls)).shuffled(rnd)
            val nVal = if (imgs.size > 1) (imgs.size * valRatio).toInt().coerceIn(1, imgs.size - 1) else 0
            imgs.forEachIndexed { i, img ->
                val split = if (i < nVal) "val" else "train"
                img.copyTo(File(root(context), "$split/$cls/${img.name}"), overwrite = true)
            }
        }
    }

    /** 导出 raw 数据集为 zip，返回 zip 文件 */
    fun exportZip(context: Context, target: File): Boolean = runCatching {
        val raw = rawDir(context)
        ZipOutputStream(FileOutputStream(target)).use { zos ->
            raw.walkTopDown().forEach { f ->
                if (f.isFile) {
                    val entryName = "dataset/raw/${f.relativeTo(raw)}".replace('\\', '/')
                    zos.putNextEntry(ZipEntry(entryName))
                    FileInputStream(f).use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
        true
    }.getOrDefault(false)

    private fun <T> List<T>.shuffled(rnd: java.util.Random): List<T> = toMutableList().apply {
        for (i in size - 1 downTo 1) {
            val j = rnd.nextInt(i + 1)
            val tmp = this[i]; this[i] = this[j]; this[j] = tmp
        }
    }
}
