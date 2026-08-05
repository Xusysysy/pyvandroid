package com.linxc.pyvision

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.linxc.pyvision.ui.debug.DebugScreen
import com.linxc.pyvision.ui.home.HomeScreen
import com.linxc.pyvision.ui.theme.PyvisionTheme
import com.linxc.pyvision.ui.trainer.TrainerScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 保持屏幕常亮（摄像头调试/采集场景）
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            PyvisionTheme {
                PyvisionApp()
            }
        }
    }
}

/** 简单导航：Home → Debug / Trainer */
@Composable
fun PyvisionApp() {
    var screen by remember { mutableStateOf(Screen.Home) }
    when (screen) {
        Screen.Home -> HomeScreen(
            onOpenDebug = { screen = Screen.Debug },
            onOpenTrainer = { screen = Screen.Trainer },
        )
        Screen.Debug -> DebugScreen(onBack = { screen = Screen.Home })
        Screen.Trainer -> TrainerScreen(onBack = { screen = Screen.Home })
    }
}

enum class Screen { Home, Debug, Trainer }
