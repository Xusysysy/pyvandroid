package com.linxc.pyvision

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.linxc.pyvision.ui.debug.DebugScreen
import com.linxc.pyvision.ui.home.HomeScreen
import com.linxc.pyvision.ui.theme.PyvisionTheme
import com.linxc.pyvision.ui.trainer.TrainerScreen

class MainActivity : ComponentActivity() {

    private val granted = mutableStateOf(false)

    /** 首次进入申请权限。文件访问实际不需要：快照走 MediaStore、数据集走内部存储、模型用 SAF 选择器。
     *  仅 API 28- 保存相册到公共目录需要 WRITE_EXTERNAL_STORAGE（Manifest 已声明 maxSdkVersion=28）。 */
    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        granted.value = allPermissionsGranted()
    }

    private fun requiredPermissions(): List<String> {
        val list = mutableListOf(Manifest.permission.CAMERA)
        // 文件读写（模型保存/选择等）。注意：必须与 Manifest 中声明的权限一致，
        // 未声明的权限会被系统静默拒绝。
        if (Build.VERSION.SDK_INT >= 33) {
            list += Manifest.permission.READ_MEDIA_IMAGES
            list += Manifest.permission.READ_MEDIA_VIDEO
        } else {
            list += Manifest.permission.READ_EXTERNAL_STORAGE
            if (Build.VERSION.SDK_INT <= 28) {
                list += Manifest.permission.WRITE_EXTERNAL_STORAGE
            }
        }
        return list
    }

    private fun allPermissionsGranted(): Boolean =
        requiredPermissions().all { p ->
            ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
        }

    /** 重新发起权限申请（权限被拒后由权限提示界面调用） */
    private fun requestPermissions() {
        permissionsLauncher.launch(requiredPermissions().toTypedArray())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 保持屏幕常亮（摄像头调试/采集场景）
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 全屏沉浸式：隐藏状态栏与导航栏，避免遮挡按钮
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        granted.value = allPermissionsGranted()
        if (!granted.value) {
            requestPermissions()
        }

        setContent {
            PyvisionTheme {
                PyvisionApp(granted = granted.value, onRequestPermissions = ::requestPermissions)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }
}

/** 简单导航：Home → Debug / Trainer */
@Composable
fun PyvisionApp(granted: Boolean, onRequestPermissions: () -> Unit) {
    var screen by remember { mutableStateOf(Screen.Home) }
    when (screen) {
        Screen.Home -> HomeScreen(
            onOpenDebug = { screen = Screen.Debug },
            onOpenTrainer = { screen = Screen.Trainer },
        )
        Screen.Debug -> DebugScreen(
            onBack = { screen = Screen.Home },
            granted = granted,
            onRequestPermissions = onRequestPermissions,
        )
        Screen.Trainer -> TrainerScreen(
            onBack = { screen = Screen.Home },
            granted = granted,
            onRequestPermissions = onRequestPermissions,
        )
    }
}

enum class Screen { Home, Debug, Trainer }
