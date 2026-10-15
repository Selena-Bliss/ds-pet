package com.jianyu.ds_pet

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.jianyu.ds_pet.pet.PetOverlayService
import com.jianyu.ds_pet.pet.PetSettings
import com.jianyu.ds_pet.ui.theme.DSpetTheme
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private var canOverlay by mutableStateOf(false)
    private var notificationsGranted by mutableStateOf(false)
    private var running by mutableStateOf(false)
    private var paused by mutableStateOf(false)
    private var sizeDp by mutableFloatStateOf(240f)
    private var corner by mutableStateOf("bottom-right")
    private var marginX by mutableFloatStateOf(8f)
    private var marginY by mutableFloatStateOf(48f)
    private var fixed by mutableStateOf(false)
    private var topMargin by mutableFloatStateOf(0.3f)
    private var askedNotifications = false

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refresh() }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadSettings()
        enableEdgeToEdge()
        setContent {
            DSpetTheme {
                SettingsScreen(
                    canOverlay = canOverlay,
                    notificationsGranted = notificationsGranted,
                    askNotifications = Build.VERSION.SDK_INT >= 33,
                    running = running,
                    paused = paused,
                    sizeDp = sizeDp,
                    corner = corner,
                    marginX = marginX,
                    marginY = marginY,
                    fixed = fixed,
                    topMargin = topMargin,
                    onRequestOverlay = {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName"),
                            ),
                        )
                    },
                    onRequestNotifications = { askNotifications() },
                    onStart = { startPet() },
                    onStop = { stopPet() },
                    onSize = { sizeDp = it },
                    onMarginX = { marginX = it },
                    onMarginY = { marginY = it },
                    onTopMargin = { topMargin = it },
                    onSliderFinished = { persist(reload = true) },
                    onCorner = {
                        corner = it
                        persist(reload = true)
                    },
                    onFixed = {
                        fixed = it
                        persist(reload = true)
                    },
                    onOpenUpstream = { openUpstream() },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            stateReceiver,
            IntentFilter(PetOverlayService.ACTION_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        if (!askedNotifications && Build.VERSION.SDK_INT >= 33 && !notificationsGranted) {
            askNotifications()
        }
    }

    override fun onStop() {
        unregisterReceiver(stateReceiver)
        super.onStop()
    }

    private fun loadSettings() {
        val saved = PetSettings(this)
        sizeDp = saved.sizeDp.coerceIn(160, 360).toFloat()
        corner = saved.corner.takeIf { it in CORNERS } ?: "bottom-right"
        marginX = saved.marginXDp.coerceIn(0, 200).toFloat()
        marginY = saved.marginYDp.coerceIn(0, 200).toFloat()
        fixed = saved.fixed
        topMargin = saved.topMarginRatio.coerceIn(0.2f, 0.5f)
    }

    private fun refresh() {
        canOverlay = Settings.canDrawOverlays(this)
        notificationsGranted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        running = PetOverlayService.isRunning
        paused = PetOverlayService.isPaused
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT < 33) return
        askedNotifications = true
        requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun startPet() {
        if (!Settings.canDrawOverlays(this)) return
        running = true
        paused = false
        ContextCompat.startForegroundService(this, Intent(this, PetOverlayService::class.java))
    }

    private fun stopPet() {
        running = false
        paused = false
        if (!PetOverlayService.isRunning) return
        startService(Intent(this, PetOverlayService::class.java).setAction(PetOverlayService.ACTION_STOP))
    }

    private fun persist(reload: Boolean) {
        val saved = PetSettings(this)
        val size = sizeDp.roundToInt().coerceIn(160, 360)
        val x = marginX.roundToInt().coerceIn(0, 200)
        val y = marginY.roundToInt().coerceIn(0, 200)
        val ratio = ((topMargin.coerceIn(0.2f, 0.5f) / 0.05f).roundToInt() * 0.05f).coerceIn(0.2f, 0.5f)
        val place = corner.takeIf { it in CORNERS } ?: "bottom-right"
        val cornerChanged = saved.corner != place
        val changed = saved.sizeDp != size ||
            cornerChanged ||
            saved.marginXDp != x ||
            saved.marginYDp != y ||
            saved.fixed != fixed ||
            saved.topMarginRatio != ratio
        saved.sizeDp = size
        saved.corner = place
        saved.marginXDp = x
        saved.marginYDp = y
        saved.fixed = fixed
        saved.topMarginRatio = ratio
        sizeDp = size.toFloat()
        marginX = x.toFloat()
        marginY = y.toFloat()
        topMargin = ratio
        corner = place
        // 落点优先于角落。改角落时丢掉落点，新角落才会生效；服务没在跑也要清，下次启动用新角落。
        if (cornerChanged) saved.clearPositions()
        if (reload && changed && PetOverlayService.isRunning) {
            val intent = Intent(this, PetOverlayService::class.java).setAction(PetOverlayService.ACTION_RELOAD)
            if (cornerChanged) intent.putExtra(PetOverlayService.EXTRA_CLEAR_POS, true)
            startService(intent)
        }
    }

    private fun openUpstream() {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UPSTREAM)))
        }
    }

    companion object {
        private const val UPSTREAM = "https://github.com/PC2005-cloud/dsh-pet"
        private val CORNERS = setOf("top-left", "top-right", "bottom-left", "bottom-right")
    }
}

@Composable
private fun SettingsScreen(
    canOverlay: Boolean,
    notificationsGranted: Boolean,
    askNotifications: Boolean,
    running: Boolean,
    paused: Boolean,
    sizeDp: Float,
    corner: String,
    marginX: Float,
    marginY: Float,
    fixed: Boolean,
    topMargin: Float,
    onRequestOverlay: () -> Unit,
    onRequestNotifications: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSize: (Float) -> Unit,
    onMarginX: (Float) -> Unit,
    onMarginY: (Float) -> Unit,
    onTopMargin: (Float) -> Unit,
    onSliderFinished: () -> Unit,
    onCorner: (String) -> Unit,
    onFixed: (Boolean) -> Unit,
    onOpenUpstream: () -> Unit,
) {
    val status = when {
        !running -> "未启动"
        paused -> "已暂停"
        else -> "正在屏幕上"
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("桌宠", style = MaterialTheme.typography.headlineMedium)
            Text("一只透明桌宠，浮在其他应用之上。")
        }
        SectionCard(title = "权限") {
            Text(if (canOverlay) "悬浮窗：已授予" else "悬浮窗：未授予")
            Text(
                when {
                    !askNotifications -> "通知：系统版本无需单独授权"
                    notificationsGranted -> "通知：已允许"
                    else -> "通知：未允许。前台服务通知需要它才能显示。"
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRequestOverlay, enabled = !canOverlay) { Text("授予悬浮窗") }
                if (askNotifications) {
                    Button(onClick = onRequestNotifications, enabled = !notificationsGranted) { Text("允许通知") }
                }
            }
        }
        SectionCard(title = "运行") {
            Text("当前：$status")
            Text("启动后出现在所选角落。轻点有回应，拖动能甩出去，长按约 0.5 秒打开菜单。")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStart, enabled = canOverlay && !running) { Text("启动") }
                Button(onClick = onStop, enabled = running) { Text("停止") }
            }
        }
        SectionCard(title = "宠物") {
            Text("宽度 ${sizeDp.roundToInt()} dp")
            Slider(
                value = sizeDp,
                onValueChange = onSize,
                onValueChangeFinished = onSliderFinished,
                valueRange = 160f..360f,
            )
            Text("角落")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CornerChip("top-left", "左上", corner, onCorner)
                CornerChip("top-right", "右上", corner, onCorner)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CornerChip("bottom-left", "左下", corner, onCorner)
                CornerChip("bottom-right", "右下", corner, onCorner)
            }
            Text("水平边距 ${marginX.roundToInt()} dp")
            Slider(
                value = marginX,
                onValueChange = onMarginX,
                onValueChangeFinished = onSliderFinished,
                valueRange = 0f..200f,
            )
            Text("垂直边距 ${marginY.roundToInt()} dp")
            Slider(
                value = marginY,
                onValueChange = onMarginY,
                onValueChangeFinished = onSliderFinished,
                valueRange = 0f..200f,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("宠物固定")
                    Text("只在原地做动作，不自己走动或转向。", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = fixed, onCheckedChange = onFixed)
            }
            Text("头顶留白 ${String.format(Locale.US, "%.2f", topMargin)}")
            Slider(
                value = topMargin,
                onValueChange = onTopMargin,
                onValueChangeFinished = onSliderFinished,
                valueRange = 0.2f..0.5f,
            )
            Text(
                "留白按宠物宽度计算，给气泡留位置，这块区域也会挡住下层触摸。正在运行时，改宽度、边距和留白会马上重载，并留在拖拽落点。改角落会清掉落点，宠物移到新角落。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        SectionCard(title = "关于") {
            Text("代码来自上游 dsh-pet，采用 MIT 许可。动画、字体和图片允许开源使用，禁止商用。本应用免费，不含广告。")
            TextButton(onClick = onOpenUpstream) { Text("原作者 PC2005-cloud/dsh-pet") }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = {
                Text(title, style = MaterialTheme.typography.titleLarge)
                content()
            },
        )
    }
}

@Composable
private fun CornerChip(id: String, label: String, selected: String, onCorner: (String) -> Unit) {
    FilterChip(selected = selected == id, onClick = { onCorner(id) }, label = { Text(label) })
}

@Preview(showBackground = true)
@Composable
private fun SettingsPreview() {
    DSpetTheme {
        SettingsScreen(
            canOverlay = false,
            notificationsGranted = false,
            askNotifications = true,
            running = false,
            paused = false,
            sizeDp = 240f,
            corner = "bottom-right",
            marginX = 8f,
            marginY = 48f,
            fixed = false,
            topMargin = 0.3f,
            onRequestOverlay = {},
            onRequestNotifications = {},
            onStart = {},
            onStop = {},
            onSize = {},
            onMarginX = {},
            onMarginY = {},
            onTopMargin = {},
            onSliderFinished = {},
            onCorner = {},
            onFixed = {},
            onOpenUpstream = {},
        )
    }
}
