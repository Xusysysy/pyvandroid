package com.linxc.pyvision.ui.trainer

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.linxc.pyvision.camera.CameraController
import com.linxc.pyvision.data.DatasetRepository
import com.linxc.pyvision.data.SettingsRepository
import com.linxc.pyvision.ml.LightTrainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class TrainerUiState(
    val classIndex: Int = 0,
    val classes: List<String> = DatasetRepository.DEFAULT_CLASSES,
    val rawCounts: List<Int> = listOf(0, 0, 0),
    val trainCounts: List<Int> = listOf(0, 0, 0),
    val valCounts: List<Int> = listOf(0, 0, 0),
    val valRatio: Float = 0.2f,
    val epochs: Int = 30,
    val imgsz: Int = 28,
    val batch: Int = 16,
    val status: String = "就绪",
    val cameraName: String = "后置",
    val training: Boolean = false,
    val lastSaved: String? = null,
    val log: String = "",
    val offsetX: Int = 0,
    val offsetY: Int = 0,
    val trainedModelPath: String? = null,
    val progress: Float = 0f,
    /** 模型保存目录（SAF tree uri），空 = 默认应用目录 */
    val modelSaveDir: String = "",
    /** 输出模型文件名（不含扩展名），空 = 默认按数据集命名 */
    val modelName: String = "",
    /** 全部数据集名称 */
    val datasets: List<String> = listOf(DatasetRepository.DEFAULT_DATASET),
    /** 当前激活的数据集名称 */
    val datasetName: String = DatasetRepository.DEFAULT_DATASET,
)

/** 训练工作台 ViewModel（对应桌面版 TrainerGUI） */
class TrainerViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsRepo = SettingsRepository(app)
    private val _state = MutableStateFlow(TrainerUiState())
    val state: StateFlow<TrainerUiState> = _state.asStateFlow()

    private val _preview = MutableStateFlow<Bitmap?>(null)
    /** 预览帧流：降采样后推送（仅新帧变化触发重绘） */
    val preview: StateFlow<Bitmap?> = _preview.asStateFlow()

    private var cameraController: CameraController? = null
    private var previewJob: Job? = null
    private var trainJob: Job? = null
    private val latestRaw = java.util.concurrent.atomic.AtomicReference<Bitmap?>(null)
    /** 最近一帧原始帧（采集保存用，独立于预览消费） */
    @Volatile
    private var recentRaw: Bitmap? = null
    private val processLock = Any()

    fun init(camera: CameraController) {
        cameraController = camera
        // 相机线程只存最新帧立即返回；后台协程降采样发布预览
        camera.onFrame = { frame ->
            synchronized(processLock) {
                recentRaw = frame
                latestRaw.set(frame)
            }
        }
        viewModelScope.launch {
            val s = settingsRepo.settings.first()
            val active = s.datasetName.ifEmpty { DatasetRepository.DEFAULT_DATASET }
            val names = DatasetRepository.listDatasets(getApplication())
                .ifEmpty { listOf(DatasetRepository.DEFAULT_DATASET) }
            val datasetName = if (active in names) active else names.first()
            val classes = DatasetRepository.loadClasses(getApplication(), datasetName)
            _state.value = _state.value.copy(
                valRatio = s.valRatio,
                epochs = s.epochs,
                imgsz = s.imgsz,
                batch = s.batch,
                modelSaveDir = s.modelSaveDir,
                modelName = s.modelName,
                datasets = names,
                datasetName = datasetName,
                classes = classes,
            )
            DatasetRepository.ensureDirs(getApplication(), _state.value.datasetName, _state.value.classes)
            refreshStats()
        }
        camera.start()
        previewJob = viewModelScope.launch(Dispatchers.Default) { previewLoop() }
    }

    /** 预览降采样循环：始终取最新帧，只做缩放（轻量），不阻塞相机线程 */
    private suspend fun previewLoop() {
        while (true) {
            val raw = synchronized(processLock) {
                val f = latestRaw.get()
                latestRaw.set(null)
                f
            } ?: run { kotlinx.coroutines.delay(1); continue }
            val state = _state.value
            var disp = raw
            if (state.offsetX != 0 || state.offsetY != 0) {
                disp = com.linxc.pyvision.processing.FramePipeline.applyOffset(raw, state.offsetX, state.offsetY)
            }
            _preview.value = com.linxc.pyvision.processing.FramePipeline.scaleDown(disp, 960)
        }
    }

    /** 返回最近一帧原始帧（采集保存用，最高分辨率） */
    fun getCurrentFrame(): Bitmap? = recentRaw

    fun availableCameras(): List<Pair<String, Int>> =
        cameraController?.availableCameras() ?: emptyList()

    fun switchCamera(lens: Int, name: String) {
        cameraController?.switchCamera(lens)
        _state.value = _state.value.copy(status = "已切换到 $name", cameraName = name)
    }

    // ───────────── 采集 ─────────────

    fun selectClass(idx: Int) {
        _state.value = _state.value.copy(classIndex = idx)
    }

    fun saveFrame() {
        val frame = getCurrentFrame() ?: run {
            _state.value = _state.value.copy(status = "没有可保存的帧")
            return
        }
        val cls = _state.value.classes[_state.value.classIndex]
        val offset = _state.value
        val toSave = if (offset.offsetX != 0 || offset.offsetY != 0) {
            com.linxc.pyvision.processing.FramePipeline.applyOffset(frame, offset.offsetX, offset.offsetY)
        } else {
            frame
        }
        val file = DatasetRepository.saveFrame(getApplication(), _state.value.datasetName, cls, toSave)
        if (file != null) {
            refreshStats()
            _state.value = _state.value.copy(
                status = "已保存: ${file.name}",
                lastSaved = file.name,
            )
        } else {
            _state.value = _state.value.copy(status = "保存失败")
        }
    }

    fun setOffset(x: Int, y: Int) {
        _state.value = _state.value.copy(offsetX = x, offsetY = y)
    }

    fun resetOffset() = setOffset(0, 0)

    fun refreshStats() {
        val context = getApplication<Application>()
        val name = _state.value.datasetName
        val classes = _state.value.classes
        val raw = DatasetRepository.countByClass(DatasetRepository.rawDir(context, name), classes)
        val train = DatasetRepository.countByClass(File(DatasetRepository.datasetDir(context, name), "train"), classes)
        val valCnt = DatasetRepository.countByClass(File(DatasetRepository.datasetDir(context, name), "val"), classes)
        _state.value = _state.value.copy(rawCounts = raw, trainCounts = train, valCounts = valCnt)
    }

    // ───────────── 数据集管理 ─────────────

    /** 切换当前数据集 */
    fun selectDataset(name: String) {
        if (name == _state.value.datasetName) return
        val classes = DatasetRepository.loadClasses(getApplication(), name)
        _state.value = _state.value.copy(
            datasetName = name,
            classes = classes,
            classIndex = 0,
            status = "已切换数据集: $name",
        )
        viewModelScope.launch { settingsRepo.update(datasetName = name) }
        refreshStats()
    }

    /** 新建数据集（命名），成功后自动切换过去 */
    fun createDataset(rawName: String) {
        val name = DatasetRepository.create(getApplication(), rawName)
        if (name == null) {
            _state.value = _state.value.copy(status = "新建失败：名称非法或已存在")
            return
        }
        val names = DatasetRepository.listDatasets(getApplication())
        val classes = DatasetRepository.loadClasses(getApplication(), name)
        _state.value = _state.value.copy(
            datasets = names,
            datasetName = name,
            classes = classes,
            classIndex = 0,
            status = "已新建数据集: $name",
        )
        viewModelScope.launch { settingsRepo.update(datasetName = name) }
        refreshStats()
    }

    /** 删除数据集（至少保留一个；删当前激活的则切到第一个剩余） */
    fun deleteDataset(name: String) {
        if (_state.value.datasets.size <= 1) {
            _state.value = _state.value.copy(status = "至少保留一个数据集")
            return
        }
        DatasetRepository.delete(getApplication(), name)
        val names = DatasetRepository.listDatasets(getApplication())
        val next = if (name == _state.value.datasetName)
            names.firstOrNull() ?: DatasetRepository.DEFAULT_DATASET
        else
            _state.value.datasetName
        val classes = DatasetRepository.loadClasses(getApplication(), next)
        _state.value = _state.value.copy(
            datasets = names,
            datasetName = next,
            classes = classes,
            classIndex = 0,
            status = "已删除数据集: $name",
        )
        viewModelScope.launch { settingsRepo.update(datasetName = next) }
        refreshStats()
    }

    // ───────────── 分类标签编辑 ─────────────

    /**
     * 应用分类标签编辑。edits 为 (原标签, 新标签) 列表：原标签标记身份，
     * 改名时数据目录跟随；被移除的行（原标签不再出现）删除其目录。
     */
    fun applyClassEdits(edits: List<Pair<String, String>>) {
        val current = _state.value.classes
        val sanitized = edits.map { (_, cur) -> DatasetRepository.sanitizeName(cur) }
        if (sanitized.size < 2 || sanitized.size > 10 || sanitized.any { it == null }) {
            _state.value = _state.value.copy(status = "分类数量需在 2-10 个，名称不能为空或含非法字符")
            return
        }
        val newLabels = sanitized.map { it!! }
        if (newLabels.size != newLabels.distinct().size) {
            _state.value = _state.value.copy(status = "分类名称不能重复")
            return
        }
        val context = getApplication<Application>()
        val name = _state.value.datasetName
        edits.forEach { (orig, cur) ->
            val to = DatasetRepository.sanitizeName(cur)
            if (orig.isNotEmpty() && to != null && orig != to) {
                DatasetRepository.renameClassDir(context, name, orig, to)
            }
        }
        current.forEach { oldLabel ->
            if (edits.none { it.first == oldLabel }) {
                DatasetRepository.deleteClassDir(context, name, oldLabel)
            }
        }
        DatasetRepository.saveClasses(context, name, newLabels)
        _state.value = _state.value.copy(
            classes = newLabels,
            classIndex = _state.value.classIndex.coerceIn(0, newLabels.size - 1),
            status = "分类标签已更新",
        )
        refreshStats()
    }

    // ───────────── 数据准备 ─────────────

    fun setValRatio(v: Float) {
        _state.value = _state.value.copy(valRatio = v)
        viewModelScope.launch { settingsRepo.update(valRatio = v) }
    }

    fun prepareDataset() {
        val ratio = _state.value.valRatio
        if (ratio <= 0f || ratio >= 1f) {
            _state.value = _state.value.copy(status = "验证集比例必须在 0-1 之间")
            return
        }
        if (DatasetRepository.totalRaw(getApplication(), _state.value.datasetName, _state.value.classes) == 0) {
            _state.value = _state.value.copy(status = "原始数据为空，请先采集数据")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(status = "正在划分数据集...")
            try {
                withContext(Dispatchers.IO) {
                    DatasetRepository.prepare(
                        getApplication(),
                        _state.value.datasetName,
                        _state.value.classes,
                        ratio,
                    )
                }
                refreshStats()
                _state.value = _state.value.copy(status = "数据集划分完成")
            } catch (e: Exception) {
                _state.value = _state.value.copy(status = "划分失败: ${e.message}")
            }
        }
    }

    // ───────────── 训练 ─────────────

    fun setEpochs(v: Int) {
        _state.value = _state.value.copy(epochs = v)
        viewModelScope.launch { settingsRepo.update(epochs = v) }
    }

    fun setImgsz(v: Int) {
        _state.value = _state.value.copy(imgsz = v)
        viewModelScope.launch { settingsRepo.update(imgsz = v) }
    }

    fun setBatch(v: Int) {
        _state.value = _state.value.copy(batch = v)
        viewModelScope.launch { settingsRepo.update(batch = v) }
    }

    fun startTraining() {
        if (_state.value.training) return
        if (_state.value.trainCounts.sum() == 0) {
            _state.value = _state.value.copy(status = "训练集为空，请先采集数据并划分数据集")
            return
        }
        val epochs = _state.value.epochs
        val batch = _state.value.batch
        _state.value = _state.value.copy(
            training = true,
            log = "",
            status = "训练进行中...",
            progress = 0f,
        )
        trainJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                val trainer = LightTrainer()
                val mlp = trainer.train(
                    context = getApplication(),
                    datasetName = _state.value.datasetName,
                    classes = _state.value.classes,
                    epochs = epochs,
                    batch = batch,
                    onProgress = { p ->
                        val accPct = (p.acc * 100).toInt()
                        val valPct = (p.valAcc * 100).toInt()
                        _state.value = _state.value.copy(
                            log = _state.value.log +
                                "[Epoch ${p.epoch}/${p.totalEpochs}] loss=${"%.4f".format(p.loss)} " +
                                "acc=${accPct}% val=${valPct}%\n",
                            status = "训练中: Epoch ${p.epoch}/${p.totalEpochs}",
                            progress = p.epoch.toFloat() / p.totalEpochs,
                        )
                    },
                )
                val modelFile = File(
                    getApplication<Application>().filesDir,
                    _state.value.modelName
                        .ifEmpty { "smart_glasses_cls_${_state.value.datasetName}" } + ".mlp",
                )
                LightTrainer.saveModel(mlp, modelFile)
                // 用户指定了保存目录（SAF tree uri）时复制一份过去
                val saveDir = _state.value.modelSaveDir
                val savedPath: String = if (saveDir.isNotEmpty()) {
                    copyModelToTree(saveDir, modelFile) ?: modelFile.absolutePath
                } else {
                    modelFile.absolutePath
                }
                _state.value = _state.value.copy(
                    training = false,
                    status = "训练完成，模型已保存",
                    trainedModelPath = savedPath,
                    log = _state.value.log + "\n[TRAINER] 模型已保存: $savedPath\n",
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    training = false,
                    status = "训练失败: ${e.message}",
                    log = _state.value.log + "\n[TRAINER] 训练失败: ${e.message}\n",
                )
            }
        }
    }

    fun stopTraining() {
        trainJob?.cancel()
        trainJob = null
        _state.value = _state.value.copy(
            training = false,
            status = "已停止训练",
            log = _state.value.log + "\n[TRAINER] 训练已由用户停止\n",
        )
    }

    fun setStatus(msg: String) {
        _state.value = _state.value.copy(status = msg)
    }

    /** 把模型文件写入 SAF tree 目录，返回写入后的展示路径 */
    private fun copyModelToTree(treeUri: String, src: File): String? = runCatching {
        val app = getApplication<Application>()
        val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(app, android.net.Uri.parse(treeUri)) ?: return null
        val target = tree.findFile(src.name) ?: tree.createFile("application/octet-stream", src.name) ?: return null
        app.contentResolver.openOutputStream(target.uri)?.use { out ->
            src.inputStream().use { it.copyTo(out) }
        }
        target.uri.toString()
    }.getOrNull()

    /** 设置模型保存目录（SAF tree uri），空字符串 = 默认应用目录 */
    fun setModelSaveDir(uri: String) {
        _state.value = _state.value.copy(modelSaveDir = uri)
        viewModelScope.launch { settingsRepo.update(modelSaveDir = uri) }
    }

    /** 设置输出模型名称（不含扩展名），空字符串 = 默认按数据集命名 */
    fun setModelName(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            _state.value = _state.value.copy(modelName = "")
            viewModelScope.launch { settingsRepo.update(modelName = "") }
            return
        }
        val name = DatasetRepository.sanitizeName(trimmed) ?: run {
            _state.value = _state.value.copy(status = "模型名称含非法字符或过长（≤32 字符）")
            return
        }
        _state.value = _state.value.copy(modelName = name)
        viewModelScope.launch { settingsRepo.update(modelName = name) }
    }

    /** 导出当前数据集 zip 到用户通过系统文件管理器选择的 uri，供 PC 端 pyvision trainer 训练 */
    fun exportDatasetZip(uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val name = _state.value.datasetName
            val ok = app.contentResolver.openOutputStream(uri)?.use { out ->
                DatasetRepository.exportZip(app, name, out)
            } ?: false
            _state.value = _state.value.copy(
                status = if (ok) "数据集已导出: $name" else "导出失败：数据集为空或写入错误",
            )
        }
    }

    override fun onCleared() {
        trainJob?.cancel()
        cameraController?.release()
        super.onCleared()
    }
}
