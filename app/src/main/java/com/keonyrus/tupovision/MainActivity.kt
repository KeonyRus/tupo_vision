package com.keonyrus.tupovision

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Accent = Color(0xFF19C37D)
private val Warning = Color(0xFFFFB454)

class MainActivity : ComponentActivity() {

  private val androidPermissions =
      registerForActivityResult(RequestMultiplePermissions()) { Engine.refreshEnvironment() }

  // Camera access on the glasses is granted in the Meta AI app, which this launcher opens.
  private val permissionMutex = Mutex()
  private var permissionContinuation: CancellableContinuation<PermissionStatus>? = null
  private val wearablesPermission =
      registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        val status = result.getOrDefault(PermissionStatus.Denied)
        permissionContinuation?.resume(status)
        permissionContinuation = null
      }

  private suspend fun requestWearablesPermission(permission: Permission): PermissionStatus =
      permissionMutex.withLock {
        suspendCancellableCoroutine { continuation ->
          permissionContinuation = continuation
          continuation.invokeOnCancellation { permissionContinuation = null }
          wearablesPermission.launch(permission)
        }
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      val state by Engine.state.collectAsStateWithLifecycle()
      MaterialTheme(colorScheme = darkColorScheme(primary = Accent, onPrimary = Color.Black)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          Screen(
              state = state,
              onGrantPermissions = ::requestAndroidPermissions,
              onInstallMetaAi = ::openMetaAiInStore,
              onRegister = { Engine.register(this) },
              onUnregister = { Engine.unregister(this) },
              onSignIn = { Engine.signIn(this) },
              onStart = { Engine.start(::requestWearablesPermission) },
              onCopyLog = ::copyLog,
          )
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    Engine.refreshEnvironment()
  }

  private fun requestAndroidPermissions() {
    val wanted = ArrayList<String>()
    wanted.addAll(Engine.REQUIRED_PERMISSIONS)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      wanted.add(Manifest.permission.POST_NOTIFICATIONS)
    }
    androidPermissions.launch(wanted.toTypedArray())
  }

  private fun openMetaAiInStore() {
    val page = "details?id=com.facebook.stella"
    try {
      startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://$page")))
    } catch (e: Exception) {
      startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/$page")))
    }
  }

  private fun copyLog() {
    val clipboard = getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("Tupo Vision log", Engine.logText()))
  }
}

@Composable
private fun Screen(
    state: UiState,
    onGrantPermissions: () -> Unit,
    onInstallMetaAi: () -> Unit,
    onRegister: () -> Unit,
    onUnregister: () -> Unit,
    onSignIn: () -> Unit,
    onStart: () -> Unit,
    onCopyLog: () -> Unit,
) {
  Column(
      modifier =
          Modifier.fillMaxSize()
              .safeDrawingPadding()
              .verticalScroll(rememberScrollState())
              .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text("Tupo Vision", style = MaterialTheme.typography.headlineSmall)

    val error = state.error
    if (error != null) {
      Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(error, color = Warning)
          TextButton(onClick = { Engine.clearError() }) { Text("Скрыть") }
        }
      }
    }

    ConversationSection(state, onStart)
    GlassesSection(state, onGrantPermissions, onInstallMetaAi, onRegister, onUnregister)
    LiveSection(state)
    if (!state.liveMode) ChatGptSection(state, onSignIn)
    SettingsSection(state)
    LogSection(state, onCopyLog)
  }
}

@Composable
private fun ConversationSection(state: UiState, onStart: () -> Unit) {
  val brainReady = if (state.liveMode) state.geminiKeySet else state.signedIn
  val ready = brainReady && state.registered && state.permissionsGranted
  Section("Разговор") {
    Button(
        onClick = { if (state.running) Engine.stop() else onStart() },
        enabled = state.running || ready,
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
      Text(if (state.running) "Остановить" else "Начать разговор")
    }
    if (!ready && !state.running) {
      Text(
          "Сначала пройди шаги ниже: разрешения, привязка очков, " +
              if (state.liveMode) "ключ Gemini." else "вход в ChatGPT.",
          style = MaterialTheme.typography.bodySmall,
      )
    }
    Text(state.phase.label, style = MaterialTheme.typography.titleMedium, color = Accent)
    if (state.partial.isNotEmpty()) Text("… ${state.partial}")
    if (state.question.isNotEmpty()) Text("Ты: ${state.question}")
    if (state.answer.isNotEmpty()) {
      Text((if (state.liveMode) "Gemini: " else "GPT: ") + state.answer)
    }
    val latency = state.latencyMs
    if (latency != null) {
      Text(
          "До первого звука: %.1f с".format(latency / 1000.0),
          style = MaterialTheme.typography.bodySmall,
      )
    }
    val frame = state.frame
    if (frame != null) {
      Text(
          if (state.liveMode) "Что сейчас видит модель:" else "Кадр, который ушёл в ChatGPT:",
          style = MaterialTheme.typography.bodySmall,
      )
      Image(
          bitmap = frame.asImageBitmap(),
          contentDescription = "Кадр с камеры очков",
          modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
          contentScale = ContentScale.Fit,
      )
    }
    if (state.running) {
      Text(
          "Сессия: ${state.sessionState} · поток: ${state.streamState} · кадр: ${state.frameInfo}",
          style = MaterialTheme.typography.bodySmall,
      )
      Text(
          "Один тап по дужке очков ставит на паузу, второй продолжает.",
          style = MaterialTheme.typography.bodySmall,
      )
    }
  }
}

@Composable
private fun GlassesSection(
    state: UiState,
    onGrantPermissions: () -> Unit,
    onInstallMetaAi: () -> Unit,
    onRegister: () -> Unit,
    onUnregister: () -> Unit,
) {
  Section("1. Очки") {
    StatusLine(state.permissionsGranted, "Разрешения: Bluetooth и микрофон")
    if (!state.permissionsGranted) {
      OutlinedButton(onClick = onGrantPermissions) { Text("Выдать разрешения") }
    }
    StatusLine(state.metaInstalled, "Приложение Meta AI установлено")
    if (!state.metaInstalled) {
      OutlinedButton(onClick = onInstallMetaAi) { Text("Открыть Meta AI в Google Play") }
    }
    Text(
        "Сначала включи Developer Mode в Meta AI, очки при этом надеты и на связи: " +
            "Settings → App info, пять тапов по App version, переключатель Developer Mode, " +
            "Enable. Там же у Device Access Toolkit нажми Install, если он не установлен. " +
            "Если Meta AI пишет Error while opening link, значит Developer Mode не включён: " +
            "выключи и включи его заново, перезапусти Meta AI и привяжи ещё раз.",
        style = MaterialTheme.typography.bodySmall,
    )
    StatusLine(state.registered, "Приложение привязано к очкам (${state.registration})")
    if (state.registered) {
      StatusLine(state.hasActiveDevice, "Очки на связи (устройств: ${state.deviceCount})")
      TextButton(onClick = onUnregister) { Text("Отвязать") }
    } else {
      OutlinedButton(onClick = onRegister, enabled = state.permissionsGranted) {
        Text("Привязать очки через Meta AI")
      }
    }
  }
}

@Composable
private fun LiveSection(state: UiState) {
  Section("2. Мозг") {
    SettingSwitch(
        title = "Живой голос (Gemini Live)",
        subtitle =
            "Модель сама слышит и говорит, её можно перебивать, видео идёт постоянно. " +
                "Нужен ключ Gemini. Выключено: GPT по подписке ChatGPT.",
        checked = state.liveMode,
        enabled = !state.running,
        onChange = { Engine.setLiveMode(it) },
    )
    if (state.liveMode) {
      StatusLine(state.geminiKeySet, "Ключ Gemini сохранён на телефоне")
      if (state.geminiKeySet) {
        TextButton(onClick = { Engine.clearGeminiKey() }) { Text("Удалить ключ") }
      } else {
        var draft by remember { mutableStateOf("") }
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text("Ключ из aistudio.google.com") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
              Engine.setGeminiKey(draft)
              draft = ""
            },
            enabled = draft.isNotBlank(),
        ) {
          Text("Сохранить ключ")
        }
        Text(
            "Ключ хранится только на этом телефоне в зашифрованном виде и уходит только в Google.",
            style = MaterialTheme.typography.bodySmall,
        )
      }
    }
  }
}

@Composable
private fun ChatGptSection(state: UiState, onSignIn: () -> Unit) {
  Section("ChatGPT") {
    StatusLine(state.signedIn, "Подписка ChatGPT подключена")
    if (state.signedIn) {
      var expanded by remember { mutableStateOf(false) }
      Box {
        OutlinedButton(onClick = { expanded = true }) { Text("Модель: ${state.model}") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
          state.models.forEach { id ->
            DropdownMenuItem(
                text = { Text(id) },
                onClick = {
                  Engine.setModel(id)
                  expanded = false
                },
            )
          }
        }
      }
      Text(
          "Запросы расходуют лимиты плана. Расход по приложениям: chatgpt.com/settings/usage",
          style = MaterialTheme.typography.bodySmall,
      )
      TextButton(onClick = { Engine.signOut() }) { Text("Выйти") }
    } else {
      Button(onClick = onSignIn) { Text("Continue with ChatGPT") }
      if (state.loginInProgress) {
        Text(
            "Жду вход в браузере. Если вкладка закрыта, нажми кнопку ещё раз.",
            style = MaterialTheme.typography.bodySmall,
        )
      }
      Text(
          "Нужен план Go, Plus или Pro. На экране входа разреши использовать план.",
          style = MaterialTheme.typography.bodySmall,
      )
    }
  }
}

@Composable
private fun SettingsSection(state: UiState) {
  Section("Настройки") {
    Text(
        "Меняются на остановленном разговоре и действуют со следующего запуска.",
        style = MaterialTheme.typography.bodySmall,
    )
    SettingSwitch(
        title = "Микрофон очков",
        subtitle = "Выключи, если очки слышат хуже телефона. Тогда слушает телефон.",
        checked = state.glassesMic,
        enabled = !state.running,
        onChange = { Engine.setGlassesMic(it) },
    )
    SettingSwitch(
        title = "Перебивать голосом",
        subtitle =
            "Для живого голоса. Выключи, если модель обрывает сама себя: " +
                "тогда она не слушает, пока говорит.",
        checked = state.bargeIn,
        enabled = !state.running,
        onChange = { Engine.setBargeIn(it) },
    )
    SettingSwitch(
        title = "Фото вместо видеокадра",
        subtitle = "Для режима ChatGPT. Чётче для мелкого текста, но медленнее.",
        checked = state.photoMode,
        enabled = !state.running,
        onChange = { Engine.setPhotoMode(it) },
    )
    SettingSwitch(
        title = "Говорю по-английски",
        subtitle = "Для режима ChatGPT: язык распознавания и озвучки. Выключено: русский.",
        checked = state.language == "en-US",
        enabled = !state.running,
        onChange = { Engine.setLanguage(if (it) "en-US" else "ru-RU") },
    )
    OutlinedTextField(
        value = state.wakeWord,
        onValueChange = { Engine.setWakeWord(it) },
        label = { Text("Кодовое слово (необязательно)") },
        supportingText = { Text("Для режима ChatGPT: отвечаю только на фразы с этим словом.") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
  }
}

@Composable
private fun LogSection(state: UiState, onCopyLog: () -> Unit) {
  Section("Журнал") {
    TextButton(onClick = onCopyLog) { Text("Скопировать журнал") }
    Text(
        state.log.takeLast(60).joinToString("\n"),
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    )
  }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(title, style = MaterialTheme.typography.titleMedium)
      content()
    }
  }
}

@Composable
private fun StatusLine(ok: Boolean, text: String) {
  Text(
      (if (ok) "✓ " else "○ ") + text,
      color = if (ok) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
  Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
      Text(title)
      Text(subtitle, style = MaterialTheme.typography.bodySmall)
    }
    Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
  }
}
