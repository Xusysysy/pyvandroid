package com.linxc.pyvision.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.linxc.pyvision.ui.theme.AccentBlue
import com.linxc.pyvision.ui.theme.Primary
import com.linxc.pyvision.ui.theme.SurfaceHigh
import com.linxc.pyvision.ui.theme.TextSecondary

@Composable
fun HomeScreen(onOpenDebug: () -> Unit, onOpenTrainer: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "PyVision",
                style = MaterialTheme.typography.displaySmall,
                color = Primary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "摄像头调试 + 视觉识别训练工作台",
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(48.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                HomeCard(
                    title = "摄像头调试",
                    subtitle = "预览 / 偏移校准\nCNN 检测 / 拍照录制",
                    icon = { Icon(Icons.Default.Settings, null, tint = Color.White) },
                    accent = Primary,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenDebug,
                )
                HomeCard(
                    title = "训练工作台",
                    subtitle = "采集数据 / 数据集划分\n设备端训练模型",
                    icon = { Icon(Icons.Default.List, null, tint = Color.White) },
                    accent = AccentBlue,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenTrainer,
                )
            }
        }
    }
}

@Composable
private fun HomeCard(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier
            .height(220.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(accent, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) { icon() }
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
