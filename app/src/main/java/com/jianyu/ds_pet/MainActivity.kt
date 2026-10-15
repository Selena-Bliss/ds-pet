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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import com.jianyu.ds_pet.pet.ApiKeyStore
import com.jianyu.ds_pet.pet.PetOverlayService
import com.jianyu.ds_pet.pet.PetSettings
import com.jianyu.ds_pet.pet.calendarGranted
import com.jianyu.ds_pet.pet.chineseHolidayCalendarCount
import com.jianyu.ds_pet.ui.theme.DSpetTheme
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
    private var petCount by mutableStateOf(1)
    private var petCollision by mutableStateOf(false)
    private var compact by mutableStateOf(false)
    private var hidden by mutableStateOf(false)
    private var aiModel by mutableStateOf("")
    private var keyDraft by mutableStateOf("")
    private var keySaved by mutableStateOf(false)
    private var aiError by mutableStateOf<String?>(null)
    private var canReadCalendar by mutableStateOf(false)
    private var holidayCalendars by mutableStateOf(0)
    private var askedCalendar = false
    private var askedNotifications = false

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refresh() }

    private val requestCalendar = registerForActivityResult(
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
                    hidden = hidden,
                    sizeDp = sizeDp,
                    corner = corner,
                    marginX = marginX,
                    marginY = marginY,
                    fixed = fixed,
                    petCount = petCount,
                    petCollision = petCollision,
                    compact = compact,
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
                    onToggleHidden = { toggleHidden() },
                    onSize = { sizeDp = it },
                    onMarginX = { marginX = it },
                    onMarginY = { marginY = it },
                    onSliderFinished = { persist(reload = true) },
                    onCorner = {
                        corner = it
                        persist(reload = true)
                    },
                    onFixed = {
                        fixed = it
                        persist(reload = true)
                    },
                    onPetCount = {
                        petCount = it
                        persist(reload = true)
                    },
                    onPetCollision = {
                        petCollision = it
                        persist(reload = true)
                    },
                    onCompact = {
                        compact = it
                        persist(reload = true)
                    },
                    aiModel = aiModel,
                    keyDraft = keyDraft,
                    keySaved = keySaved,
                    aiError = aiError,
                    onAiModel = { aiModel = it },
                    onKeyDraft = { keyDraft = it },
                    onSaveAi = { saveAi() },
                    onClearAi = { clearAi() },
                    canReadCalendar = canReadCalendar,
                    holidayCalendars = holidayCalendars,
                    onRequestCalendar = { askCalendar() },
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
        petCount = saved.petCount.coerceIn(1, 4)
        petCollision = saved.petCollision
        compact = saved.compact
        aiModel = saved.aiModel
    }

    private fun refresh() {
        canOverlay = Settings.canDrawOverlays(this)
        notificationsGranted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        running = PetOverlayService.isRunning
        paused = PetOverlayService.isPaused
        hidden = PetOverlayService.isHidden
        keySaved = ApiKeyStore(this).hasKey()
        val granted = calendarGranted(this)
        canReadCalendar = granted
        holidayCalendars = if (granted) chineseHolidayCalendarCount(this) else 0
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT < 33) return
        askedNotifications = true
        requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun askCalendar() {
        val deniedForGood = askedCalendar &&
            !shouldShowRequestPermissionRationale(Manifest.permission.READ_CALENDAR)
        askedCalendar = true
        if (deniedForGood) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
            )
            return
        }
        requestCalendar.launch(Manifest.permission.READ_CALENDAR)
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
        hidden = false
        if (!PetOverlayService.isRunning) return
        startService(Intent(this, PetOverlayService::class.java).setAction(PetOverlayService.ACTION_STOP))
    }

    private fun toggleHidden() {
        if (!PetOverlayService.isRunning) return
        startService(Intent(this, PetOverlayService::class.java).setAction(PetOverlayService.ACTION_HIDE))
    }

    private fun persist(reload: Boolean) {
        val saved = PetSettings(this)
        val size = sizeDp.roundToInt().coerceIn(160, 360)
        val x = marginX.roundToInt().coerceIn(0, 200)
        val y = marginY.roundToInt().coerceIn(0, 200)
        val place = corner.takeIf { it in CORNERS } ?: "bottom-right"
        val count = petCount.coerceIn(1, 4)
        val cornerChanged = saved.corner != place
        val changed = saved.sizeDp != size ||
            cornerChanged ||
            saved.marginXDp != x ||
            saved.marginYDp != y ||
            saved.fixed != fixed ||
            saved.petCount != count ||
            saved.petCollision != petCollision ||
            saved.compact != compact
        saved.sizeDp = size
        saved.corner = place
        saved.marginXDp = x
        saved.marginYDp = y
        saved.fixed = fixed
        saved.petCount = count
        saved.petCollision = petCollision
        saved.compact = compact
        sizeDp = size.toFloat()
        marginX = x.toFloat()
        marginY = y.toFloat()
        corner = place
        petCount = count
        // 落点优先于角落。改角落时丢掉落点，新角落才会生效；服务没在跑也要清，下次启动用新角落。
        if (cornerChanged) saved.clearPositions()
        if (reload && changed && PetOverlayService.isRunning) {
            val intent = Intent(this, PetOverlayService::class.java).setAction(PetOverlayService.ACTION_RELOAD)
            if (cornerChanged) intent.putExtra(PetOverlayService.EXTRA_CLEAR_POS, true)
            startService(intent)
        }
    }

    private fun saveAi() {
        val model = aiModel.trim()
        if (model.length > 64 || (model.isNotEmpty() && !MODEL_OK.matches(model))) {
            aiError = "模型名只能包含字母、数字、点、下划线和连字符"
            return
        }
        val draft = keyDraft.trim()
        if (draft.length > 512) {
            aiError = "密钥过长"
            return
        }
        val store = ApiKeyStore(this)
        val hadKey = keySaved
        var replaced = false
        if (draft.isNotEmpty()) {
            try {
                store.save(draft)
            } catch (_: Exception) {
                aiError = "密钥没有保存成功"
                return
            }
            keyDraft = ""
            replaced = true
        }
        PetSettings(this).aiModel = model
        aiModel = model
        aiError = null
        keySaved = store.hasKey()
        when {
            hadKey != keySaved -> reloadAi()
            replaced -> kickBalance()
        }
    }

    private fun clearAi() {
        ApiKeyStore(this).clear()
        keyDraft = ""
        keySaved = false
        aiError = null
        reloadAi()
    }

    private fun kickBalance() {
        if (!PetOverlayService.isRunning) return
        startService(Intent(this, PetOverlayService::class.java).setAction(PetOverlayService.ACTION_KICK_AI))
    }

    private fun reloadAi() {
        if (!PetOverlayService.isRunning) return
        startService(
            Intent(this, PetOverlayService::class.java)
                .setAction(PetOverlayService.ACTION_RELOAD)
                .putExtra(PetOverlayService.EXTRA_KICK_AI, true),
        )
    }

    private fun openUpstream() {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UPSTREAM)))
        }
    }

    companion object {
        private const val UPSTREAM = "https://github.com/PC2005-cloud/dsh-pet"
        private val CORNERS = setOf("top-left", "top-right", "bottom-left", "bottom-right")
        private val MODEL_OK = Regex("[A-Za-z0-9._-]+")
    }
}

@Composable
private fun SettingsScreen(
    canOverlay: Boolean,
    notificationsGranted: Boolean,
    askNotifications: Boolean,
    running: Boolean,
    paused: Boolean,
    hidden: Boolean,
    sizeDp: Float,
    corner: String,
    marginX: Float,
    marginY: Float,
    fixed: Boolean,
    petCount: Int,
    petCollision: Boolean,
    compact: Boolean,
    onRequestOverlay: () -> Unit,
    onRequestNotifications: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onToggleHidden: () -> Unit,
    onSize: (Float) -> Unit,
    onMarginX: (Float) -> Unit,
    onMarginY: (Float) -> Unit,
    onSliderFinished: () -> Unit,
    onCorner: (String) -> Unit,
    onFixed: (Boolean) -> Unit,
    onPetCount: (Int) -> Unit,
    onPetCollision: (Boolean) -> Unit,
    onCompact: (Boolean) -> Unit,
    aiModel: String,
    keyDraft: String,
    keySaved: Boolean,
    aiError: String?,
    onAiModel: (String) -> Unit,
    onKeyDraft: (String) -> Unit,
    onSaveAi: () -> Unit,
    onClearAi: () -> Unit,
    canReadCalendar: Boolean,
    holidayCalendars: Int,
    onRequestCalendar: () -> Unit,
    onOpenUpstream: () -> Unit,
) {
    val status = when {
        !running -> "未启动"
        hidden -> "已隐藏"
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
            Text("透明桌宠，浮在其他应用之上。")
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
            Text(
                "全屏游戏或视频时，系统看不出对方是不是全屏。通知栏或这里的「隐藏」会先把宠物收起来。",
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStart, enabled = canOverlay && !running) { Text("启动") }
                Button(onClick = onStop, enabled = running) { Text("停止") }
                Button(onClick = onToggleHidden, enabled = running) { Text(if (hidden) "显示" else "隐藏") }
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
            Text("数量 $petCount")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (n in 1..4) {
                    FilterChip(
                        selected = petCount == n,
                        onClick = { onPetCount(n) },
                        label = { Text("$n") },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("宠物互撞")
                    Text(
                        "打开后，飞起来的那只会把撞到的另一只弹开。关掉就互相穿过。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = petCollision, onCheckedChange = onPetCollision)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("紧凑窗口")
                    Text(
                        "左右各裁掉约 15% 的透明边，旁边更好点到下面的应用。风筝、气球这类伸出身体的道具可能被切掉。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = compact, onCheckedChange = onCompact)
            }
            Text(
                "没有气泡时头顶不占地方，可以直接点到下面的应用。气泡出现时，窗口才按气泡高度向上长出来。正在运行时，改宽度、数量、互撞和紧凑窗口会马上重载，并留在拖拽落点。改角落会清掉落点，宠物移到新角落。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        SectionCard(title = "DeepSeek") {
            Text("填好自己的 API Key 并保存后，长按菜单会出现查看余额、碎碎念和对话。密钥用系统密钥库加密，只存在这台手机上。")
            Text(if (keySaved) "密钥：已保存" else "密钥：还没有填写")
            OutlinedTextField(
                value = keyDraft,
                onValueChange = onKeyDraft,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API Key") },
                placeholder = { Text(if (keySaved) "想更换时再填写" else "sk-…") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            OutlinedTextField(
                value = aiModel,
                onValueChange = onAiModel,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("模型") },
                placeholder = { Text("deepseek-chat") },
                singleLine = true,
                supportingText = { Text("留空时用 deepseek-chat。碎碎念和对话都走这个模型。") },
            )
            if (!aiError.isNullOrBlank()) {
                Text(aiError, color = MaterialTheme.colorScheme.error)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSaveAi) { Text("保存") }
                Button(onClick = onClearAi, enabled = keySaved) { Text("清除密钥") }
            }
            Text(
                "亮屏时按配置里的周期查余额、说碎碎念。长按菜单里的查看余额和碎碎念会马上再来一次。",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "周末全天是空闲，调休要上班的周末也是。其余日子里，法定节假日全天空闲，平常 9:00–12:00、14:00–18:00 是高峰。假日从手机里的中国节假日日历读取，连休的每一天都算。",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                when {
                    !canReadCalendar -> "节假日日历：还没允许读取"
                    holidayCalendars == 0 -> "节假日日历：已允许，还没找到中国节假日。可在系统日历里订阅「中国节假日」。"
                    else -> "节假日日历：已读取 $holidayCalendars 个"
                },
            )
            Button(onClick = onRequestCalendar, enabled = !canReadCalendar) { Text("允许读取日历") }
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
            hidden = false,
            sizeDp = 240f,
            corner = "bottom-right",
            marginX = 8f,
            marginY = 48f,
            fixed = false,
            petCount = 1,
            petCollision = false,
            compact = false,
            onRequestOverlay = {},
            onRequestNotifications = {},
            onStart = {},
            onStop = {},
            onToggleHidden = {},
            onSize = {},
            onMarginX = {},
            onMarginY = {},
            onSliderFinished = {},
            onCorner = {},
            onFixed = {},
            onPetCount = {},
            onPetCollision = {},
            onCompact = {},
            aiModel = "",
            keyDraft = "",
            keySaved = false,
            aiError = null,
            onAiModel = {},
            onKeyDraft = {},
            onSaveAi = {},
            onClearAi = {},
            canReadCalendar = false,
            holidayCalendars = 0,
            onRequestCalendar = {},
            onOpenUpstream = {},
        )
    }
}
