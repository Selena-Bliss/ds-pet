package com.jianyu.ds_pet.pet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.jianyu.ds_pet.ui.theme.DSpetTheme
import kotlin.concurrent.thread

/** 悬浮窗不能弹出输入法，对话放到这个透明页面的底部输入框里。 */
class PetChatActivity : ComponentActivity() {
    private var text by mutableStateOf("")
    private var sending by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private lateinit var petId: String
    private lateinit var petName: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        petId = intent.getStringExtra(EXTRA_PET_ID)?.ifBlank { "main" } ?: "main"
        petName = intent.getStringExtra(EXTRA_PET_NAME)?.ifBlank { petId } ?: petId
        enableEdgeToEdge()
        setContent {
            DSpetTheme {
                ChatScreen(
                    petName = petName,
                    text = text,
                    sending = sending,
                    error = error,
                    onText = { if (it.length <= 2000) text = it },
                    onSend = { send() },
                    onDismiss = { if (!sending) finish() },
                )
            }
        }
    }

    private fun send() {
        if (sending) return
        val draft = text.trim()
        if (draft.isEmpty()) {
            error = "消息为空"
            return
        }
        val engine = PetOverlayService.aiEngine
        if (engine == null) {
            error = "桌宠没有在运行"
            return
        }
        sending = true
        error = null
        thread(name = "pet-chat", isDaemon = true) {
            val outcome = engine.chat(petId, draft)
            if (isDestroyed) return@thread
            runOnUiThread {
                sending = false
                if (outcome.ok) {
                    finish()
                } else {
                    error = "对话失败：${outcome.message}"
                }
            }
        }
    }

    companion object {
        const val EXTRA_PET_ID = "petId"
        const val EXTRA_PET_NAME = "petName"
    }
}

@Composable
private fun ChatScreen(
    petName: String,
    text: String,
    sending: Boolean,
    error: String?,
    onText: (String) -> Unit,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("和「$petName」说一句", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = onText,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !sending,
                    placeholder = { Text("说点什么…") },
                    minLines = 2,
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                )
                if (!error.isNullOrBlank()) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    Button(onClick = onSend, enabled = !sending && text.isNotBlank()) {
                        Text(if (sending) "发送中" else "发送")
                    }
                }
            }
        }
    }
}
