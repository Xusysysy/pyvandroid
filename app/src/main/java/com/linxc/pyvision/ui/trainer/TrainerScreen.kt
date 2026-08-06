package com.linxc.pyvision.ui.trainer

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.linxc.pyvision.camera.CameraController
import com.linxc.pyvision.data.DatasetRepository
import com.linxc.pyvision.ui.theme.AccentBlue
import com.linxc.pyvision.ui.theme.AccentOrange
import com.linxc.pyvision.ui.theme.AccentPurple
import com.linxc.pyvision.ui.theme.AccentRed
import com.linxc.pyvision.ui.theme.Primary
import com.linxc.pyvision.ui.theme.Surface
import com.linxc.pyvision.ui.theme.SurfaceHigh
import com.linxc.pyvision.ui.theme.TextPrimary
import com.linxc.pyvision.ui.theme.TextSecondary

@Composable
fun TrainerScreen(
    onBack: () -> Unit,
    granted: Boolean,
    onRequestPermissions: () -> Unit,
    vm: TrainerViewModel = viewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by vm.state.collectAsState()
    var tab by remember { mutableIntStateOf(0) }

    val camera = remember { CameraController(context, lifecycleOwner) }

    DisposableEffect(Unit) {
        onDispose { camera.release() }
    }
    LaunchedEffect(granted) {
        if (granted) vm.init(camera)
    }

    if (!granted) {
        // 权限未授予：全屏提示，不启动相机
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "需要摄像头与文件访问权限",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "请在弹窗中允许权限；若被拒绝可在系统设置中授予后返回",
                    color = TextSecondary,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onRequestPermissions) { Text("重新授权") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onBack) { Text("返回") }
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // 顶栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, "返回", tint = TextPrimary)
            }
            Text("智能眼镜训练工作台", style = MaterialTheme.typography.titleLarge, color = Primary)
            Spacer(Modifier.weight(1f))
            Text(
                state.status,
                color = TextSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 8.dp),
            )
        }

        // 数据集管理：选择/新建/删除（每个数据集独立命名，可训练不同模型）
        var menuOpen by remember { mutableStateOf(false) }
        var showCreate by remember { mutableStateOf(false) }
        var showDelete by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("数据集", fontWeight = FontWeight.Bold, color = AccentBlue)
            Spacer(Modifier.width(8.dp))
            Box(modifier = Modifier.weight(1f)) {
                OutlinedButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        state.datasetName,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Icon(Icons.Default.ArrowDropDown, null)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    state.datasets.forEach { name ->
                        DropdownMenuItem(
                            text = { Text(name, color = if (name == state.datasetName) Primary else TextPrimary) },
                            onClick = {
                                menuOpen = false
                                vm.selectDataset(name)
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { showCreate = true }) { Text("新建") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = { showDelete = true },
                enabled = state.datasets.size > 1,
            ) { Text("删除", color = if (state.datasets.size > 1) AccentRed else TextSecondary) }
        }

        if (showCreate) {
            var name by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showCreate = false },
                title = { Text("新建数据集") },
                text = {
                    TextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text("数据集名称") },
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        vm.createDataset(name)
                        showCreate = false
                    }) { Text("创建") }
                },
                dismissButton = {
                    TextButton(onClick = { showCreate = false }) { Text("取消") }
                },
            )
        }
        if (showDelete) {
            AlertDialog(
                onDismissRequest = { showDelete = false },
                title = { Text("删除数据集") },
                text = { Text("确定删除数据集「${state.datasetName}」？该操作不可恢复。") },
                confirmButton = {
                    Button(
                        onClick = {
                            vm.deleteDataset(state.datasetName)
                            showDelete = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                    ) { Text("删除") }
                },
                dismissButton = {
                    TextButton(onClick = { showDelete = false }) { Text("取消") }
                },
            )
        }

        TabRow(
            selectedTabIndex = tab,
            containerColor = SurfaceHigh,
            contentColor = Primary,
        ) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("1. 采集数据") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("2. 准备数据") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("3. 训练模型") })
        }

        when (tab) {
            0 -> CollectTab(vm, state)
            1 -> PrepareTab(vm, state)
            2 -> TrainTab(vm, state)
        }
    }
}

// ═══════════════════════════════════════════
// Tab 1: 采集数据
// ═══════════════════════════════════════════

@Composable
private fun CollectTab(vm: TrainerViewModel, state: TrainerUiState) {
    // StateFlow 推送：仅新帧变化触发重绘
    val displayBitmap by vm.preview.collectAsState()
    Row(modifier = Modifier.fillMaxSize()) {
        // 左：预览
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(12.dp)
                .background(Surface, RoundedCornerShape(12.dp))
                .padding(4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val bmp = displayBitmap
                if (bmp != null) {
                    val scale = minOf(size.width / bmp.width, size.height / bmp.height)
                    val dw = bmp.width * scale
                    val dh = bmp.height * scale
                    val left = (size.width - dw) / 2
                    val top = (size.height - dh) / 2
                    drawImage(
                        image = bmp.asImageBitmap(),
                        dstOffset = IntOffset(left.toInt(), top.toInt()),
                        dstSize = IntSize(dw.toInt(), dh.toInt()),
                    )
                    // 顶部信息条
                    val paint = android.graphics.Paint().apply {
                        color = android.graphics.Color.rgb(30, 30, 30); isAntiAlias = true
                    }
                    drawContext.canvas.nativeCanvas.drawRect(
                        left, top, (left + dw), (top + 34), paint,
                    )
                    val infoPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.GREEN; textSize = 20f; isAntiAlias = true
                    }
                    val clsLabel = DatasetRepository.CLASSES[state.classIndex]
                    drawContext.canvas.nativeCanvas.drawText(
                        "Class: $clsLabel  |  Smart:${state.rawCounts[0]} " +
                            "Reg:${state.rawCounts[1]}  Neg:${state.rawCounts[2]}",
                        left + 8, top + 24, infoPaint,
                    )
                } else {
                    drawContext.canvas.nativeCanvas.drawText(
                        "相机启动中...", size.width / 2 - 60, size.height / 2,
                        android.graphics.Paint().apply { color = android.graphics.Color.GRAY; textSize = 24f },
                    )
                }
            }
        }

        // 右：控制面板
        Column(
            modifier = Modifier
                .width(300.dp)
                .fillMaxHeight()
                .padding(end = 12.dp, top = 12.dp, bottom = 12.dp)
                .background(SurfaceHigh, RoundedCornerShape(12.dp))
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // 摄像头切换（含 USB 摄像头）
            Text("摄像头", fontWeight = FontWeight.Bold, color = AccentBlue)
            var camList by remember { mutableStateOf(listOf<Pair<String, Int>>()) }
            LaunchedEffect(Unit) {
                while (true) {
                    val cams = vm.availableCameras()
                    if (cams.isNotEmpty()) camList = cams
                    kotlinx.coroutines.delay(500)
                }
            }
            val cams = if (camList.isNotEmpty()) camList else listOf("后置" to 1, "前置" to 0)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                cams.forEach { (name, lens) ->
                    Button(
                        onClick = { vm.switchCamera(lens, name) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (state.cameraName == name) Primary else Surface,
                        ),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            name,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
            Divider(Modifier.padding(vertical = 8.dp))

            Text("选择类别", fontWeight = FontWeight.Bold, color = AccentBlue)
            Spacer(Modifier.height(8.dp))
            val colors = listOf(Primary, AccentOrange, AccentPurple)
            DatasetRepository.CLASS_LABELS.forEachIndexed { i, label ->
                val selected = state.classIndex == i
                Button(
                    onClick = { vm.selectClass(i) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selected) colors[i] else Surface,
                        contentColor = if (selected) com.linxc.pyvision.ui.theme.Background else TextPrimary,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                ) {
                    Text(
                        "${label} (${i + 1})   ${state.rawCounts[i]} 张",
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { vm.saveFrame() },
                colors = ButtonDefaults.buttonColors(containerColor = Primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Icon(Icons.Default.Check, null, Modifier.width(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("保存当前帧 (Space)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }

            Divider(Modifier.padding(vertical = 12.dp))
            Text("画面偏移 (采集校准)", fontWeight = FontWeight.Bold, color = AccentBlue)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("水平", color = TextSecondary, modifier = Modifier.width(44.dp))
                Slider(
                    value = state.offsetX.toFloat(),
                    onValueChange = { vm.setOffset(it.toInt(), state.offsetY) },
                    valueRange = -300f..300f,
                )
                Text("${state.offsetX}", color = TextPrimary, modifier = Modifier.width(40.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("竖直", color = TextSecondary, modifier = Modifier.width(44.dp))
                Slider(
                    value = state.offsetY.toFloat(),
                    onValueChange = { vm.setOffset(state.offsetX, it.toInt()) },
                    valueRange = -300f..300f,
                )
                Text("${state.offsetY}", color = TextPrimary, modifier = Modifier.width(40.dp))
            }
            OutlinedButton(onClick = { vm.resetOffset() }, modifier = Modifier.fillMaxWidth()) {
                Text("清零偏移")
            }

            Divider(Modifier.padding(vertical = 12.dp))
            Text("采集统计", fontWeight = FontWeight.Bold, color = AccentBlue)
            Text(
                "智能眼镜: ${state.rawCounts[0]} 张\n普通眼镜: ${state.rawCounts[1]} 张\n空桌面:   ${state.rawCounts[2]} 张",
                color = TextPrimary,
                modifier = Modifier.padding(top = 4.dp),
            )
            state.lastSaved?.let {
                Text(
                    "最近保存: $it",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Text(
                "\n提示: 智能眼镜 = 带摄像头/电池\n普通眼镜 = 仅镜框镜片\n空桌面 = 无眼镜背景",
                color = TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

// ═══════════════════════════════════════════
// Tab 2: 准备数据
// ═══════════════════════════════════════════

@Composable
private fun PrepareTab(vm: TrainerViewModel, state: TrainerUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text("数据集准备 (自动划分训练/验证集)", fontWeight = FontWeight.Bold, color = AccentBlue)
        Text(
            "数据目录: ${DatasetRepository.datasetDir(LocalContext.current, state.datasetName).absolutePath}",
            color = TextSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(16.dp))

        Card(colors = CardDefaults.cardColors(containerColor = SurfaceHigh)) {
            Column(Modifier.padding(16.dp)) {
                Text("原始数据统计 (dataset/raw)", fontWeight = FontWeight.Bold, color = TextPrimary)
                Text(
                    "智能: ${state.rawCounts[0]} 张 | 普通: ${state.rawCounts[1]} 张 | 空桌面: ${state.rawCounts[2]} 张",
                    color = TextPrimary,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("验证集比例:", color = TextPrimary)
                    Slider(
                        value = state.valRatio,
                        onValueChange = { vm.setValRatio(it) },
                        valueRange = 0.05f..0.5f,
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .weight(1f),
                    )
                    Text("${(state.valRatio * 100).toInt()}%", color = TextPrimary)
                }
                Button(
                    onClick = { vm.prepareDataset() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) { Text("重新划分数据集") }
            }
        }

        Spacer(Modifier.height(16.dp))
        Card(colors = CardDefaults.cardColors(containerColor = SurfaceHigh)) {
            Column(Modifier.padding(16.dp)) {
                Text("划分结果", fontWeight = FontWeight.Bold, color = TextPrimary)
                Text(
                    "训练集: 智能 ${state.trainCounts[0]} / 普通 ${state.trainCounts[1]} / 空桌面 ${state.trainCounts[2]}\n" +
                        "验证集: 智能 ${state.valCounts[0]} / 普通 ${state.valCounts[1]} / 空桌面 ${state.valCounts[2]}",
                    color = TextPrimary,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    "\n说明: 按类别目录直接作为训练样本，无需标注边界框。\n划分会把 dataset/raw 下的图片按比例复制到 train/val。",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

// ═══════════════════════════════════════════
// Tab 3: 训练模型
// ═══════════════════════════════════════════

@Composable
private fun TrainTab(vm: TrainerViewModel, state: TrainerUiState) {
    var epochsText by remember(state.epochs) { mutableStateOf(state.epochs.toString()) }
    var batchText by remember(state.batch) { mutableStateOf(state.batch.toString()) }
    val context = LocalContext.current

    // 系统文件管理器选择模型保存目录（SAF tree）
    val dirPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            vm.setModelSaveDir(uri.toString())
            vm.setStatus("模型保存目录已设置")
        }
    }

    // 系统文件管理器选择数据集 zip 导出位置（SAF create）
    val zipExporter = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            vm.setStatus("正在导出数据集...")
            vm.exportDatasetZip(uri)
        }
    }

    Row(modifier = Modifier.fillMaxSize()) {
        // 左：参数
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("训练参数 (设备端 MLP 分类)", fontWeight = FontWeight.Bold, color = AccentBlue)
            Spacer(Modifier.height(12.dp))

            Text("训练轮数 epochs", color = TextSecondary)
            TextField(
                value = epochsText,
                onValueChange = {
                    epochsText = it
                    it.toIntOrNull()?.let { v -> if (v > 0) vm.setEpochs(v) }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            )

            Text("批大小 batch", color = TextSecondary)
            TextField(
                value = batchText,
                onValueChange = {
                    batchText = it
                    it.toIntOrNull()?.let { v -> if (v > 0) vm.setBatch(v) }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            )

            Spacer(Modifier.height(8.dp))
            Text(
                "数据集统计 (train/val):\n训练集 ${state.trainCounts.sum()} 张 | 验证集 ${state.valCounts.sum()} 张",
                color = TextPrimary,
            )

            Spacer(Modifier.height(16.dp))
            // 模型保存路径设置
            Text("模型保存路径", fontWeight = FontWeight.Bold, color = AccentBlue)
            Text(
                if (state.modelSaveDir.isNotEmpty()) state.modelSaveDir
                else "默认 (应用内部目录: filesDir/smart_glasses_cls.mlp)",
                color = if (state.modelSaveDir.isNotEmpty()) Primary else TextSecondary,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
            )
            OutlinedButton(
                onClick = { dirPicker.launch(null) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.modelSaveDir.isNotEmpty()) "更改保存目录" else "选择保存目录 (系统文件管理器)")
            }
            if (state.modelSaveDir.isNotEmpty()) {
                OutlinedButton(
                    onClick = { vm.setModelSaveDir("") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                ) { Text("恢复默认目录") }
            }
            Spacer(Modifier.height(16.dp))
            if (state.training) {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { vm.stopTraining() },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("停止训练") }
            } else {
                Button(
                    onClick = { vm.startTraining() },
                    colors = ButtonDefaults.buttonColors(containerColor = Primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) { Text("开始训练", fontWeight = FontWeight.Bold) }
            }

            Spacer(Modifier.height(12.dp))
            state.trainedModelPath?.let {
                Text(
                    "模型已保存: $it\n可在调试工具中选择该 .mlp 文件进行实时分类。",
                    color = Primary,
                    fontSize = 12.sp,
                )
            }

            Divider(Modifier.padding(vertical = 12.dp))
            Text("PC 端训练 (高级)", fontWeight = FontWeight.Bold, color = AccentBlue)
            Text(
                "设备端为轻量 MLP 分类器。如需使用 pyvision 桌面版\nYOLO11-cls 训练，可导出数据集 zip 到 PC 运行:\n\n  python trainer.py\n\n训练后把 smart_glasses.onnx 拷入手机，在调试工具加载。",
                color = TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    zipExporter.launch("pyvision_dataset_${state.datasetName}_${System.currentTimeMillis()}.zip")
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Share, null, Modifier.width(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("导出数据集 Zip (供 PC 训练)")
            }
        }

        // 右：日志
        Column(
            modifier = Modifier
                .width(440.dp)
                .fillMaxHeight()
                .padding(end = 16.dp, top = 16.dp, bottom = 16.dp),
        ) {
            Text("训练日志", fontWeight = FontWeight.Bold, color = AccentBlue)
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Surface, RoundedCornerShape(8.dp))
                    .padding(10.dp),
            ) {
                Text(
                    state.log.ifEmpty { "准备就绪...\n" },
                    color = TextPrimary,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}
