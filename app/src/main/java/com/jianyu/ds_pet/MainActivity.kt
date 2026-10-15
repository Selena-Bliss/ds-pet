package com.jianyu.ds_pet

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.jianyu.ds_pet.pet.PetOverlayService
import com.jianyu.ds_pet.ui.theme.DSpetTheme

class MainActivity : ComponentActivity() {
    private var canOverlay by mutableStateOf(false)

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DSpetTheme {
                var backdrop by remember { mutableStateOf(Backdrop.White) }
                val view = LocalView.current
                SideEffect {
                    val window = (view.context as Activity).window
                    WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars =
                        backdrop != Backdrop.Black
                }
                SpikeScreen(
                    canOverlay = canOverlay,
                    backdrop = backdrop,
                    onBackdrop = { backdrop = it },
                    onRequestOverlay = {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName"),
                            ),
                        )
                    },
                    onRequestNotifications = {
                        if (Build.VERSION.SDK_INT >= 33) {
                            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onStart = { startSpike() },
                    onStop = {
                        startService(
                            Intent(this, PetOverlayService::class.java)
                                .setAction(PetOverlayService.ACTION_STOP),
                        )
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        canOverlay = Settings.canDrawOverlays(this)
    }

    private fun startSpike() {
        if (!Settings.canDrawOverlays(this)) return
        ContextCompat.startForegroundService(this, Intent(this, PetOverlayService::class.java))
    }
}

private enum class Backdrop(val label: String) {
    White("白"),
    Black("黑"),
    Pattern("条纹"),
}

@Composable
private fun SpikeScreen(
    canOverlay: Boolean,
    backdrop: Backdrop,
    onBackdrop: (Backdrop) -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestNotifications: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val background = when (backdrop) {
        Backdrop.White -> Modifier.background(Color.White)
        Backdrop.Black -> Modifier.background(Color.Black)
        Backdrop.Pattern -> Modifier.background(
            Brush.linearGradient(
                colors = listOf(
                    Color(0xFFE53935),
                    Color(0xFFFDD835),
                    Color(0xFF43A047),
                    Color(0xFF1E88E5),
                ),
                start = Offset.Zero,
                end = Offset(48f, 48f),
                tileMode = TileMode.Repeated,
            ),
        )
    }
    val labelColor = if (backdrop == Backdrop.Black) Color.White else Color.Black
    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "背景：${backdrop.label}",
            color = labelColor,
            modifier = Modifier.padding(20.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 2.dp,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("透明播放验证", style = MaterialTheme.typography.titleLarge)
                Text(
                    "中央悬浮窗 720×405 物理像素，播放「待机呼吸休闲」。按住画面拖动。分别在白、黑、条纹下看有没有黑底或灰边，再回到桌面看壁纸。",
                )
                Text(if (canOverlay) "悬浮窗权限：已授予" else "悬浮窗权限：未授予")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Backdrop.entries.forEach { item ->
                        FilterChip(
                            selected = backdrop == item,
                            onClick = { onBackdrop(item) },
                            label = { Text(item.label) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRequestOverlay, enabled = !canOverlay) { Text("授予悬浮窗") }
                    Button(onClick = onRequestNotifications) { Text("允许通知") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onStart, enabled = canOverlay) { Text("开始验证") }
                    Button(onClick = onStop) { Text("停止") }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SpikePreview() {
    DSpetTheme {
        SpikeScreen(
            canOverlay = false,
            backdrop = Backdrop.White,
            onBackdrop = {},
            onRequestOverlay = {},
            onRequestNotifications = {},
            onStart = {},
            onStop = {},
        )
    }
}
