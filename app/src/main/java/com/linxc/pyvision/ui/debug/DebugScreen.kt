package com.linxc.pyvision.ui.debug

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.linxc.pyvision.camera.CameraController
import com.linxc.pyvision.ui.theme.AccentRed
import com.linxc.pyvision.ui.theme.Primary
import com.linxc.pyvision.ui.theme.Surface
import com.linxc.pyvision.ui.theme.SurfaceHigh
import com.linxc.pyvision.ui.theme.TextPrimary
import com.linxc.pyvision.ui.theme.TextSecondary
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(onBack: () -> Unit, vm: DebugViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by vm.state.collectAsState()

    val camera = remember { CameraController(context, lifecycleOwner) }

    // 文件选择器：选择模型文件（.onnx/.tflite/.mlp）
    val modelPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val ext = context.contentResolver.getType(uri) ?: "onnx"
            val name = "model_${System.currentTimeMillis()}.$ext"
            val file = File(context.filesDir, name)
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                vm.loadModel(file.absolutePath)
            }.onFailure { vm.setModelError(it.message ?: "模型复制失败") }
        }
    }

    DisposableEffect(Unit) {
        vm.init(camera)
        onDispose { camera.release() }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // ─── 左侧：视频画布 ───
        VideoCanvas(
            vm = vm,
            state = state,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(12.dp),
        )

        // ─── 右侧：控制面板 ───
        ControlPanel(
            state = state,
            vm = vm,
            onBack = onBack,
            onPickModel = { modelPicker.launch(arrayOf("application/octet-stream", "*/*")) },
            modifier = Modifier
                .width(320.dp)
                .fillMaxHeight()
                .padding(end = 12.dp, top = 12.dp, bottom = 12.dp),
        )
    }
}

// ═══════════════════════════════════════════
// 视频画布
// ═══════════════════════════════════════════

@Composable
private fun VideoCanvas(vm: DebugViewModel, state: DebugUiState, modifier: Modifier = Modifier) {
    var displayBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var frameSize by remember { mutableStateOf(0 to 0) }

    // 轮询获取最新处理帧
    LaunchedEffect(Unit) {
        while (true) {
            val bmp = vm.getProcessedFrame()
            if (bmp != null) {
                displayBitmap = bmp
                frameSize = bmp.width to bmp.height
            }
            kotlinx.coroutines.delay(33)
        }
    }

    Box(
        modifier = modifier
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

                // 检测框（坐标映射到显示区域）
                state.detections.forEach { det ->
                    val sx = dw / bmp.width
                    val sy = dh / bmp.height
                    val x = left + det.x * sx
                    val y = top + det.y * sy
                    drawRect(
                        color = androidx.compose.ui.graphics.Color(0xFF00FF00),
                        topLeft = Offset(x, y),
                        size = androidx.compose.ui.geometry.Size(det.w * sx, det.h * sy),
                        style = Stroke(width = 2f),
                    )
                    drawContext.canvas.nativeCanvas.drawText(
                        "${det.label} ${det.confidence}",
                        x, (y - 6).coerceAtLeast(12f),
                        android.graphics.Paint().apply {
                            color = Color.GREEN; textSize = 18f
                        },
                    )
                }

                // 分类概率（左上角）
                if (state.classProbs.isNotEmpty()) {
                    val paint = android.graphics.Paint().apply {
                        color = Color.GREEN; textSize = 20f; isAntiAlias = true
                    }
                    drawContext.canvas.nativeCanvas.drawText("Class:", 10f, 40f, paint)
                    state.classProbs.forEachIndexed { i, (label, conf) ->
                        drawContext.canvas.nativeCanvas.drawText(
                            "$label: $conf", 10f, 40f + 28f * (i + 1), paint,
                        )
                    }
                }

                // 十字准星（固定画面中心，用于偏移校准）
                if (state.showCrosshair) {
                    val cx = size.width / 2
                    val cy = size.height / 2
                    drawLine(
                        androidx.compose.ui.graphics.Color(0xFF00FF00),
                        Offset(cx - 30, cy), Offset(cx + 30, cy), 1f,
                    )
                    drawLine(
                        androidx.compose.ui.graphics.Color(0xFF00FF00),
                        Offset(cx, cy - 30), Offset(cx, cy + 30), 1f,
                    )
                    drawContext.canvas.nativeCanvas.drawText(
                        "OFFSET X:${state.offsetX} Y:${state.offsetY}",
                        cx - 75, cy + 22,
                        android.graphics.Paint().apply {
                            color = Color.GREEN; textSize = 18f
                        },
                    )
                }

                // FPS（左上角）
                if (state.showFps) {
                    drawContext.canvas.nativeCanvas.drawText(
                        "FPS: %.1f".format(state.fps),
                        10f, 30f,
                        android.graphics.Paint().apply {
                            color = Color.GREEN; textSize = 20f; isAntiAlias = true
                        },
                    )
                }

                // 录制红点
                if (state.recording) {
                    drawCircle(
                        color = AccentRed,
                        radius = 8f,
                        center = Offset(size.width - 20f, 20f),
                    )
                }
            } else {
                // 无帧时显示占位
                drawContext.canvas.nativeCanvas.drawText(
                    "相机启动中...",
                    size.width / 2 - 60, size.height / 2,
                    android.graphics.Paint().apply {
                        color = Color.GRAY; textSize = 24f
                    },
                )
            }
        }
    }
}

// ═══════════════════════════════════════════
// 控制面板
// ═══════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ControlPanel(
    state: DebugUiState,
    vm: DebugViewModel,
    onBack: () -> Unit,
    onPickModel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(SurfaceHigh, RoundedCornerShape(12.dp))
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, "返回", tint = TextPrimary)
            }
            Text("摄像头调试", style = MaterialTheme.typography.titleLarge, color = Primary)
        }
        Spacer(Modifier.height(8.dp))
        StatusText(state.status)

        Divider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))

        // ─── 摄像头切换 ───
        SectionTitle("摄像头")
        val cameras = remember { mutableStateOf(listOf<Pair<String, Int>>()) }
        LaunchedEffect(state.cameraName) {
            // 摄像头列表由 CameraController 提供
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.switchCamera(androidx.camera.core.CameraSelector.LENS_FACING_BACK, "后置") },
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = if (state.cameraName == "后置") Primary else SurfaceHigh,
                ),
                modifier = Modifier.weight(1f),
            ) { Text("后置") }
            Button(
                onClick = { vm.switchCamera(androidx.camera.core.CameraSelector.LENS_FACING_FRONT, "前置") },
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = if (state.cameraName == "前置") Primary else SurfaceHigh,
                ),
                modifier = Modifier.weight(1f),
            ) { Text("前置") }
        }

        // ─── 分辨率 ───
        SectionTitle("分辨率设置")
        var resExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = resExpanded, onExpandedChange = { resExpanded = it }) {
            OutlinedButton(
                onClick = { resExpanded = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(state.resolution) }
            ExposedDropdownMenu(expanded = resExpanded, onDismissRequest = { resExpanded = false }) {
                DebugViewModel.RESOLUTION_PRESETS.forEach { res ->
                    DropdownMenuItem(
                        text = { Text(res) },
                        onClick = {
                            resExpanded = false
                            vm.setResolution(res)
                        },
                    )
                }
            }
        }

        // ─── 处理管线 ───
        SectionTitle("处理管线")
        var procExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = procExpanded, onExpandedChange = { procExpanded = it }) {
            OutlinedButton(
                onClick = { procExpanded = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(state.processor) }
            ExposedDropdownMenu(expanded = procExpanded, onDismissRequest = { procExpanded = false }) {
                DebugViewModel.PROCESSORS.forEach { proc ->
                    DropdownMenuItem(
                        text = { Text(proc) },
                        onClick = {
                            procExpanded = false
                            vm.setProcessor(proc)
                        },
                    )
                }
            }
        }

        // CNN 模型路径
        if (state.processor == DebugViewModel.PROCESSOR_CNN) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Surface),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text("模型: ${File(state.modelPath).name.ifEmpty { "未选择" }}", color = TextPrimary, fontSize = 12.sp)
                    if (state.modelLoading) Text("加载中...", color = TextSecondary, fontSize = 12.sp)
                    state.modelError?.let {
                        Text("错误: $it", color = AccentRed, fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = onPickModel, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Icon(Icons.Default.Info, null, Modifier.width(16.dp))
                        Text("选择模型文件")
                    }
                }
            }
        }

        // ─── 显示选项 ───
        SectionTitle("显示选项")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = state.showFps, onCheckedChange = { vm.toggleFps() })
            Text("显示 FPS", color = TextPrimary)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = state.showCrosshair, onCheckedChange = { vm.toggleCrosshair() })
            Text("十字准星", color = TextPrimary)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = state.mirror, onCheckedChange = { vm.toggleMirror() })
            Text("水平镜像", color = TextPrimary)
        }

        // ─── 偏移校准 ───
        SectionTitle("偏移校准 (画面平移)")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("水平", color = TextSecondary, modifier = Modifier.width(48.dp))
            Slider(
                value = state.offsetX.toFloat(),
                onValueChange = { vm.setOffset(it.toInt(), state.offsetY) },
                valueRange = -300f..300f,
            )
            Text("${state.offsetX}", color = TextPrimary, modifier = Modifier.width(40.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("竖直", color = TextSecondary, modifier = Modifier.width(48.dp))
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

        // ─── 操作按钮 ───
        SectionTitle("操作")
        Button(
            onClick = { vm.snapshot() },
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Primary),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Icon(Icons.Default.Star, null, Modifier.width(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("拍照 (S)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        OutlinedButton(
            onClick = { vm.toggleRecording() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        ) {
            Icon(Icons.Default.PlayArrow, null, Modifier.width(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (state.recording) "停止录制 (R)" else "开始录制 (R)")
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "帧: ${state.frameSize} | 已拍: ${state.snapshotCount}",
            color = TextSecondary,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        color = com.linxc.pyvision.ui.theme.AccentBlue,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
    )
}

@Composable
private fun StatusText(text: String) {
    Text(text, color = TextSecondary, fontSize = 12.sp, maxLines = 2)
}
