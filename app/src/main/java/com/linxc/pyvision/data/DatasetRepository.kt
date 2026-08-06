package com.linxc.pyvision.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 数据集目录管理：多数据集（filesDir/datasets/<name>/raw|train|val/<class>/），
 * 对应 pyvision 桌面版 dataset 目录。每个数据集独立命名、独立分类标签（classes.json），
 * 便于训练不同模型。
 */
object DatasetRepository {

    const val DEFAULT_DATASET = "default"
    const val CLASS_SMART = "smart_glasses"
    const val CLASS_REGULAR = "regular_glasses"
    const val CLASS_NEGATIVE = "negative"

    /** 新数据集默认分类标签（同时也是类目录名） */
    val DEFAULT_CLASSES = listOf("智能眼镜", "普通眼镜", "空桌面")

    /** 数据集根目录：filesDir/datasets */
    fun root(context: Context): File = File(context.filesDir, "datasets")

    fun datasetDir(context: Context, name: String): File = File(root(context), name)

    fun rawDir(context: Context, name: String): File = File(datasetDir(context, name), "raw")

    fun classesFile(context: Context, name: String): File = File(datasetDir(context, name), "classes.json")

    fun classDir(context: Context, name: String, label: String): File =
        File(rawDir(context, name), sanitizeName(label) ?: label)

    fun listImages(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && f.extension.lowercase() in setOf("jpg", "jpeg", "png") }
            ?.sortedBy { it.name } ?: emptyList()

    /** 数据集名称列表（按名称排序） */
    fun listDatasets(context: Context): List<String> {
        migrateLegacy(context)
        return root(context).listFiles { f -> f.isDirectory }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
    }

    /** 老版本数据迁移：filesDir/dataset → filesDir/datasets/default */
    private fun migrateLegacy(context: Context) {
        val legacy = File(context.filesDir, "dataset")
        val newRoot = root(context)
        if (legacy.exists() && !newRoot.exists()) {
            newRoot.mkdirs()
            runCatching { legacy.renameTo(File(newRoot, DEFAULT_DATASET)) }
        }
    }

    /** 分类标签/数据集名称校验：去空白、限制长度、禁止非法文件名字符 */
    fun sanitizeName(raw: String): String? {
        val name = raw.trim()
        if (name.isEmpty() || name.length > 32) return null
        if (name == "." || name == "..") return null
        if (name.any { it in "/\\:*?\"<>|" }) return null
        return name
    }

    /** 新建数据集，返回清洗后的名称；名称非法或已存在返回 null */
    fun create(context: Context, rawName: String): String? {
        val name = sanitizeName(rawName) ?: return null
        if (datasetDir(context, name).exists()) return null
        ensureDirs(context, name, DEFAULT_CLASSES)
        saveClasses(context, name, DEFAULT_CLASSES)
        return name
    }

    /** 删除数据集 */
    fun delete(context: Context, name: String): Boolean = runCatching {
        datasetDir(context, name).deleteRecursively()
    }.getOrDefault(false)

    /** 加载数据集分类标签；classes.json 缺失时做老版本迁移并写入默认标签 */
    fun loadClasses(context: Context, name: String): List<String> {
        val f = classesFile(context, name)
        if (f.exists()) {
            runCatching {
                val ja = JSONArray(f.readText())
                return (0 until ja.length()).map { ja.getString(it) }
            }
        }
        val raw = rawDir(context, name)
        val legacyMap = listOf(CLASS_SMART to "智能眼镜", CLASS_REGULAR to "普通眼镜", CLASS_NEGATIVE to "空桌面")
        if (legacyMap.any { (old, _) -> File(raw, old).exists() }) {
            legacyMap.forEach { (old, new) -> renameClassDir(context, name, old, new) }
        }
        saveClasses(context, name, DEFAULT_CLASSES)
        return DEFAULT_CLASSES
    }

    /** 保存分类标签到 classes.json */
    fun saveClasses(context: Context, name: String, labels: List<String>) {
        runCatching {
            datasetDir(context, name).mkdirs()
            classesFile(context, name).writeText(JSONArray(labels).toString())
        }
    }

    /** 重命名分类目录（数据跟随；目标已存在时用临时目录交换，避免覆盖） */
    fun renameClassDir(context: Context, name: String, from: String, to: String): Boolean {
        val f = classDir(context, name, from)
        val t = classDir(context, name, to)
        if (!f.exists()) return true
        if (!t.exists()) return f.renameTo(t)
        val tmp = File(f.parentFile, "~tmp_${System.currentTimeMillis()}")
        return if (f.renameTo(tmp) && t.renameTo(f)) tmp.renameTo(t) else false
    }

    /** 删除单个分类目录（分类被删除时同步清理） */
    fun deleteClassDir(context: Context, name: String, label: String): Boolean =
        runCatching { classDir(context, name, label).deleteRecursively() }.getOrDefault(false)

    fun countByClass(base: File, classes: List<String>): List<Int> =
        classes.map { listImages(File(base, it)).size }

    fun totalRaw(context: Context, name: String, classes: List<String>): Int =
        countByClass(rawDir(context, name), classes).sum()

    fun ensureDirs(context: Context, name: String, classes: List<String> = DEFAULT_CLASSES) {
        classes.forEach { cls ->
            val d = classDir(context, name, cls)
            if (!d.exists()) d.mkdirs()
        }
    }

    /** 保存采集帧到 datasets/<name>/raw/<class>/，返回保存的文件 */
    fun saveFrame(context: Context, name: String, label: String, bmp: android.graphics.Bitmap): File? {
        ensureDirs(context, name, listOf(label))
        val dirName = sanitizeName(label) ?: label
        val file = File(classDir(context, name, label), "%s_%d.jpg".format(dirName, System.currentTimeMillis()))
        val ok = runCatching {
            FileOutputStream(file).use { out ->
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, out)
            }
        }.isSuccess
        return if (ok) file else null
    }

    /** 按比例划分数据集到 train/val（随机种子固定 42，对应桌面版） */
    suspend fun prepare(context: Context, name: String, classes: List<String>, valRatio: Float) =
        withContext(Dispatchers.IO) {
            val raw = rawDir(context, name)
            if (countByClass(raw, classes).sum() == 0) {
                throw RuntimeException("原始数据为空，请先采集数据")
            }
            val root = datasetDir(context, name)
            for (cls in classes) {
                for (split in listOf("train", "val")) {
                    val d = File(root, "$split/$cls")
                    d.deleteRecursively()
                    d.mkdirs()
                }
            }
            val rnd = java.util.Random(42)
            for (cls in classes) {
                val imgs = listImages(File(raw, cls)).shuffled(rnd)
                val nVal = if (imgs.size > 1) (imgs.size * valRatio).toInt().coerceIn(1, imgs.size - 1) else 0
                imgs.forEachIndexed { i, img ->
                    val split = if (i < nVal) "val" else "train"
                    img.copyTo(File(root, "$split/$cls/${img.name}"), overwrite = true)
                }
            }
        }

    /** 导出 raw 数据集为 zip 写入输出流（供 PC 训练），数据集为空返回 false */
    fun exportZip(context: Context, name: String, out: OutputStream): Boolean = runCatching {
        val raw = rawDir(context, name)
        if (raw.listFiles()?.isEmpty() != false) return@runCatching false
        ZipOutputStream(out).use { zos ->
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
