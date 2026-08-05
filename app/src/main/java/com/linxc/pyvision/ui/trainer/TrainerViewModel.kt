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
            _state.value = _state.value.copy(
                valRatio = s.valRatio,
                epochs = s.epochs,
                imgsz = s.imgsz,
                batch = s.batch,
            )
            DatasetRepository.ensureDirs(getApplication())
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
        val cls = DatasetRepository.CLASSES[_state.value.classIndex]
        val offset = _state.value
        val toSave = if (offset.offsetX != 0 || offset.offsetY != 0) {
            com.linxc.pyvision.processing.FramePipeline.applyOffset(frame, offset.offsetX, offset.offsetY)
        } else {
            frame
        }
        val file = DatasetRepository.saveFrame(getApplication(), cls, toSave)
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
        val raw = DatasetRepository.countByClass(context, DatasetRepository.rawDir(context))
        val train = DatasetRepository.countByClass(context, File(DatasetRepository.root(context), "train"))
        val valCnt = DatasetRepository.countByClass(context, File(DatasetRepository.root(context), "val"))
        _state.value = _state.value.copy(rawCounts = raw, trainCounts = train, valCounts = valCnt)
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
        if (DatasetRepository.totalRaw(getApplication()) == 0) {
            _state.value = _state.value.copy(status = "原始数据为空，请先采集数据")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(status = "正在划分数据集...")
            try {
                withContext(Dispatchers.IO) {
                    DatasetRepository.prepare(getApplication(), ratio)
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
                val modelFile = File(getApplication<Application>().filesDir, "smart_glasses_cls.mlp")
                LightTrainer.saveModel(mlp, modelFile)
                _state.value = _state.value.copy(
                    training = false,
                    status = "训练完成，模型已保存",
                    trainedModelPath = modelFile.absolutePath,
                    log = _state.value.log + "\n[TRAINER] 模型已保存: ${modelFile.name}\n",
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

    /** 导出数据集 zip 到 Download 目录，供 PC 端 pyvision trainer 训练 */
    fun exportDatasetZip(): File? {
        val app = getApplication<Application>()
        val target = File(
            app.getExternalFilesDir(null),
            "pyvision_dataset_${System.currentTimeMillis()}.zip",
        )
        val ok = DatasetRepository.exportZip(app, target)
        return if (ok) target else null
    }

    override fun onCleared() {
        trainJob?.cancel()
        cameraController?.release()
        super.onCleared()
    }
}
