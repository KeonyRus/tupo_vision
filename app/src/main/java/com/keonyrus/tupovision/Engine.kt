package com.keonyrus.tupovision

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

const val DEFAULT_MODEL = "gpt-6.1-sol"

/** Shown until the account's own list is read from the API. */
val FALLBACK_MODELS: List<String> = listOf("gpt-6.1-sol", "gpt-6-sol", "gpt-6-luna")

enum class Phase(val label: String) {
  IDLE("Остановлен"),
  CONNECTING("Подключаюсь к очкам"),
  LISTENING("Слушаю"),
  THINKING("Думаю"),
  SPEAKING("Отвечаю"),
  PAUSED("Пауза"),
}

data class UiState(
    val metaInstalled: Boolean = false,
    val permissionsGranted: Boolean = false,
    val registration: String = "—",
    val registered: Boolean = false,
    val deviceCount: Int = 0,
    val hasActiveDevice: Boolean = false,
    val signedIn: Boolean = false,
    val loginInProgress: Boolean = false,
    val running: Boolean = false,
    val phase: Phase = Phase.IDLE,
    val sessionState: String = "—",
    val streamState: String = "—",
    val frameInfo: String = "—",
    val partial: String = "",
    val question: String = "",
    val answer: String = "",
    val latencyMs: Long? = null,
    val frame: Bitmap? = null,
    val error: String? = null,
    val model: String = DEFAULT_MODEL,
    val models: List<String> = FALLBACK_MODELS,
    val glassesMic: Boolean = true,
    val photoMode: Boolean = false,
    val wakeWord: String = "",
    val language: String = "ru-RU",
    val log: List<String> = emptyList(),
)

/**
 * The whole assistant: glasses session and camera, the listen → ask → speak loop, and the state the
 * screen shows. It outlives the activity so the conversation continues with the screen off.
 * Everything here runs on the main thread unless a block says otherwise.
 */
object Engine {
  private const val TAG = "TupoVision"
  private const val PREFS = "settings"
  private const val META_AI_PACKAGE = "com.facebook.stella"
  private const val MAX_HISTORY = 6
  private const val MAX_LOG = 200
  private const val PHOTO_TIMEOUT_MS = 3000L
  private const val STREAM_WAIT_MS = 10_000L

  val REQUIRED_PERMISSIONS: Array<String> =
      arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.RECORD_AUDIO)

  private lateinit var app: Application
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val _state = MutableStateFlow(UiState())
  val state: StateFlow<UiState> = _state.asStateFlow()

  private val logLines = ArrayDeque<String>()
  private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

  // Glasses
  private var wearablesReady = false
  private val selector: DeviceSelector by lazy { AutoDeviceSelector() }
  private var session: DeviceSession? = null
  private var camera: Camera? = null
  private var stream: Stream? = null
  private val sessionJobs = ArrayList<Job>()
  private var sessionStarted = false
  private var streaming = false
  private var serviceStarted = false

  // Voice
  private var stt: Stt? = null
  private var tts: Tts? = null
  private var voiceActive = false
  private var turnJob: Job? = null
  private var errorStreak = 0
  private val history = ArrayDeque<Turn>()

  // ---- setup ----

  fun attach(application: Application) {
    app = application
    val prefs = prefs()
    update {
      it.copy(
          signedIn = ChatGptAuth.isSignedIn(app),
          model = prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL,
          glassesMic = prefs.getBoolean("glasses_mic", true),
          photoMode = prefs.getBoolean("photo_mode", false),
          wakeWord = prefs.getString("wake_word", "") ?: "",
          language = prefs.getString("language", "ru-RU") ?: "ru-RU",
      )
    }
    refreshEnvironment()
    if (_state.value.signedIn) refreshModels()
  }

  /** Re-reads what can change while the app is in the background: installs and permissions. */
  fun refreshEnvironment() {
    val metaInstalled =
        runCatching { app.packageManager.getPackageInfo(META_AI_PACKAGE, 0) }.isSuccess
    val granted =
        REQUIRED_PERMISSIONS.all {
          app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    update { it.copy(metaInstalled = metaInstalled, permissionsGranted = granted) }
    if (granted) ensureWearables()
  }

  /** The SDK may only be touched after Bluetooth permission is granted. */
  private fun ensureWearables() {
    if (wearablesReady) return
    wearablesReady = true
    Wearables.initialize(app).onFailure { error, _ ->
      log("SDK очков не запустился: ${error.description}")
    }
    scope.launch {
      Wearables.registrationState.collect { value ->
        update {
          it.copy(
              registration = value.toString(),
              registered = value == RegistrationState.REGISTERED,
          )
        }
      }
    }
    scope.launch {
      Wearables.devices.collect { devices -> update { it.copy(deviceCount = devices.size) } }
    }
    scope.launch {
      selector.activeDeviceFlow().collect { device ->
        update { it.copy(hasActiveDevice = device != null) }
      }
    }
  }

  fun register(activity: Activity) {
    if (!wearablesReady) {
      fail("Сначала выдай разрешения Bluetooth и микрофона")
      return
    }
    Wearables.startRegistration(activity)
  }

  fun unregister(activity: Activity) {
    if (wearablesReady) Wearables.startUnregistration(activity)
  }

  // ---- ChatGPT ----

  private var loginJob: Job? = null

  /** A second call while a sign-in is pending drops it and starts a fresh one. */
  fun signIn(activity: Activity) {
    loginJob?.cancel()
    update { it.copy(loginInProgress = true, error = null) }
    val job =
        scope.launch(start = CoroutineStart.LAZY) {
          try {
            ChatGptAuth.login(app) { url -> openInBrowser(activity, url) }
            update { it.copy(signedIn = true) }
            log("ChatGPT подключён")
            bringToFront()
            refreshModels()
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            fail("Вход в ChatGPT: ${e.message}")
            bringToFront()
          } finally {
            if (loginJob === coroutineContext[Job]) update { it.copy(loginInProgress = false) }
          }
        }
    loginJob = job
    job.start()
  }

  fun signOut() {
    if (_state.value.running) stop()
    ChatGptAuth.signOut(app)
    update { it.copy(signedIn = false) }
    log("Вышел из ChatGPT. Доступ приложения можно отозвать в настройках ChatGPT.")
  }

  private fun openInBrowser(activity: Activity, url: Uri) {
    try {
      CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(activity, url)
    } catch (e: Exception) {
      activity.startActivity(Intent(Intent.ACTION_VIEW, url))
    }
  }

  /** Closes the sign-in tab by returning to the app's own screen. */
  private fun bringToFront() {
    runCatching {
      app.startActivity(
          Intent(app, MainActivity::class.java)
              .addFlags(
                  Intent.FLAG_ACTIVITY_NEW_TASK or
                      Intent.FLAG_ACTIVITY_CLEAR_TOP or
                      Intent.FLAG_ACTIVITY_SINGLE_TOP,
              ),
      )
    }
  }

  private fun refreshModels() {
    scope.launch {
      try {
        val token = ChatGptAuth.accessToken(app)
        val ids = Responses.listModels(token).filter { it.startsWith("gpt") }
        if (ids.isNotEmpty()) {
          update { state ->
            state.copy(
                models = ids,
                model = if (state.model in ids) state.model else pickDefault(ids),
            )
          }
          log("Моделей по подписке: ${ids.size}")
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log("Список моделей не получен: ${e.message}")
      }
    }
  }

  private fun pickDefault(ids: List<String>): String =
      ids.firstOrNull { it == DEFAULT_MODEL } ?: ids.firstOrNull { "sol" in it } ?: ids.first()

  // ---- settings ----

  fun setModel(value: String) {
    update { it.copy(model = value) }
    prefs().edit().putString("model", value).apply()
  }

  fun setGlassesMic(value: Boolean) {
    update { it.copy(glassesMic = value) }
    prefs().edit().putBoolean("glasses_mic", value).apply()
  }

  fun setPhotoMode(value: Boolean) {
    update { it.copy(photoMode = value) }
    prefs().edit().putBoolean("photo_mode", value).apply()
  }

  fun setWakeWord(value: String) {
    update { it.copy(wakeWord = value) }
    prefs().edit().putString("wake_word", value).apply()
  }

  fun setLanguage(value: String) {
    update { it.copy(language = value) }
    prefs().edit().putString("language", value).apply()
  }

  fun clearError() {
    update { it.copy(error = null) }
  }

  fun logText(): String = synchronized(logLines) { logLines.joinToString("\n") }

  // ---- conversation: glasses side ----

  /**
   * Starts the conversation. [requestPermission] opens the Meta AI app to ask for camera access,
   * which needs the activity, so the screen passes it in.
   */
  fun start(requestPermission: suspend (Permission) -> PermissionStatus) {
    if (_state.value.running) return
    if (!_state.value.signedIn) {
      fail("Сначала войди в ChatGPT")
      return
    }
    if (!wearablesReady) {
      fail("Сначала выдай разрешения Bluetooth и микрофона")
      return
    }
    update { it.copy(running = true, phase = Phase.CONNECTING, error = null) }
    scope.launch {
      var granted = false
      Wearables.checkPermissionStatus(Permission.CAMERA)
          .onSuccess { status -> granted = status == PermissionStatus.Granted }
          .onFailure { error, _ -> log("Проверка доступа к камере: ${error.description}") }
      if (!granted) {
        granted = requestPermission(Permission.CAMERA) == PermissionStatus.Granted
      }
      if (!_state.value.running) return@launch
      if (!granted) {
        fail("Meta AI не дал доступ к камере очков")
        shutdown()
        return@launch
      }
      try {
        KeepAliveService.start(app)
        serviceStarted = true
      } catch (e: Exception) {
        log("Фоновый режим не включился: ${e.message}")
      }
      openSession()
    }
  }

  fun stop() {
    if (!_state.value.running) return
    log("Остановлено")
    shutdown()
  }

  private fun openSession() {
    Wearables.createSession(selector)
        .fold(
            onSuccess = { created ->
              session = created
              // Subscribe before start() so no transition is missed.
              sessionJobs +=
                  scope.launch { created.state.collect { onSessionState(created, it) } }
              sessionJobs +=
                  scope.launch {
                    created.errors.collect { error ->
                      log("Ошибка сессии: ${error.description}")
                      update { it.copy(error = "Очки: ${error.description}") }
                    }
                  }
              created.start()
            },
            onFailure = { error, _ ->
              fail("Сессия с очками не создана: ${error.description}")
              shutdown()
            },
        )
  }

  private fun onSessionState(current: DeviceSession, value: DeviceSessionState) {
    update { it.copy(sessionState = value.toString()) }
    when (value) {
      DeviceSessionState.STARTED -> {
        sessionStarted = true
        if (camera == null) attachCamera(current) else resumeIfReady()
      }
      DeviceSessionState.PAUSED -> {
        sessionStarted = false
        pauseVoice("Очки поставили сессию на паузу")
      }
      DeviceSessionState.STOPPED -> {
        if (_state.value.running) {
          log("Очки завершили сессию")
          shutdown()
        }
      }
      else -> Unit
    }
  }

  private fun attachCamera(current: DeviceSession) {
    current
        .addCamera(StreamConfiguration(videoQuality = VideoQuality.HIGH, frameRate = 7))
        .fold(
            onSuccess = { added ->
              camera = added
              val video = added.stream
              stream = video
              sessionJobs +=
                  scope.launch(Dispatchers.Default) {
                    video.videoStream.collect { frame -> Frames.offer(frame) }
                  }
              sessionJobs += scope.launch { video.state.collect { onStreamState(it) } }
              sessionJobs +=
                  scope.launch {
                    video.errorStream.collect { error ->
                      log("Ошибка видеопотока: ${error.description}")
                    }
                  }
              video.start().onFailure { error, _ ->
                log("Видеопоток не запустился: ${error.description}")
              }
              // Voice still works if the picture never arrives; say so and carry on without it.
              sessionJobs +=
                  scope.launch {
                    delay(STREAM_WAIT_MS)
                    if (_state.value.running && !streaming && !voiceActive) {
                      log("Видео с очков не пошло за 10 секунд, работаю без картинки")
                      startVoice()
                    }
                  }
            },
            onFailure = { error, _ ->
              fail("Камера очков недоступна: ${error.description}")
              shutdown()
            },
        )
  }

  private fun onStreamState(value: StreamState) {
    update { it.copy(streamState = value.toString()) }
    when (value) {
      StreamState.STREAMING -> {
        streaming = true
        resumeIfReady()
      }
      // A single tap on the temple pauses the stream and a second one resumes it; the
      // conversation follows, which makes the tap a hands-free pause button.
      StreamState.PAUSED -> {
        streaming = false
        pauseVoice("Пауза. Тапни по дужке, чтобы продолжить")
      }
      StreamState.STOPPED,
      StreamState.CLOSED -> streaming = false
      else -> Unit
    }
  }

  private fun resumeIfReady() {
    if (_state.value.running && sessionStarted && streaming && !voiceActive) startVoice()
  }

  private fun shutdown() {
    voiceActive = false
    turnJob?.cancel()
    turnJob = null
    stt?.destroy()
    stt = null
    tts?.stop()
    AudioRouter.disable(app)
    sessionJobs.forEach { it.cancel() }
    sessionJobs.clear()
    runCatching { camera?.stop() }
    runCatching { session?.stop() }
    camera = null
    stream = null
    session = null
    sessionStarted = false
    streaming = false
    history.clear()
    Frames.reset()
    if (serviceStarted) {
      KeepAliveService.stop(app)
      serviceStarted = false
    }
    update {
      it.copy(
          running = false,
          phase = Phase.IDLE,
          partial = "",
          sessionState = "—",
          streamState = "—",
      )
    }
  }

  // ---- conversation: voice side ----

  private fun startVoice() {
    if (voiceActive) return
    voiceActive = true
    scope.launch {
      val settings = _state.value
      val routed = settings.glassesMic && AudioRouter.enable(app)
      if (settings.glassesMic && !routed) {
        log("Микрофон очков по Bluetooth недоступен, слушаю микрофоном телефона")
      }
      if (!voiceActive) return@launch

      val speech = tts ?: Tts(app) { ok -> if (!ok) log("Синтез речи не запустился") }.also { tts = it }
      withTimeoutOrNull(3000) { while (!speech.ready) delay(50) }
      if (!speech.configure(settings.language, routed)) {
        log("В синтезе речи телефона нет голоса для ${settings.language}")
      }

      val recognizer = stt ?: Stt(app, sttCallbacks).also { stt = it }
      if (!recognizer.isAvailable()) {
        fail("На телефоне нет сервиса распознавания речи")
        shutdown()
        return@launch
      }
      if (voiceActive) listen()
    }
  }

  private fun pauseVoice(reason: String) {
    if (!voiceActive) return
    voiceActive = false
    turnJob?.cancel()
    turnJob = null
    stt?.cancel()
    tts?.stop()
    // Free the hands-free link so music can play through the glasses while paused.
    AudioRouter.disable(app)
    log(reason)
    update { it.copy(phase = Phase.PAUSED, partial = "") }
  }

  private fun listen() {
    if (!voiceActive) return
    update { it.copy(phase = Phase.LISTENING, partial = "") }
    try {
      stt?.start(_state.value.language)
    } catch (e: Exception) {
      log("Распознавание не стартовало: ${e.message}")
      listenAfter(1000)
    }
  }

  private fun listenAfter(delayMs: Long) {
    scope.launch {
      delay(delayMs)
      if (voiceActive && turnJob?.isActive != true) listen()
    }
  }

  private val sttCallbacks =
      object : Stt.Callbacks {
        override fun onSpeechStart() {}

        override fun onPartial(text: String) {
          update { it.copy(partial = text) }
        }

        override fun onFinal(text: String) {
          errorStreak = 0
          onUtterance(text)
        }

        override fun onError(code: Int) {
          when (code) {
            // Nobody spoke: just listen again.
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> listenAfter(150)
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
              stt?.cancel()
              listenAfter(500)
            }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
              fail("Нет доступа к микрофону")
              shutdown()
            }
            else -> {
              errorStreak += 1
              log("Распознавание речи: ошибка $code")
              listenAfter(if (errorStreak > 5) 3000 else 800)
            }
          }
        }
      }

  private fun onUtterance(text: String) {
    val heard = text.trim()
    if (heard.isEmpty()) {
      listen()
      return
    }
    var question = heard
    val wakeWord = _state.value.wakeWord.trim()
    if (wakeWord.isNotEmpty()) {
      // With a wake word set, everything that does not contain it is ignored.
      val index = heard.lowercase().indexOf(wakeWord.lowercase())
      if (index < 0) {
        listen()
        return
      }
      val after = (index + wakeWord.length).coerceAtMost(heard.length)
      question = heard.substring(after).trim().trimStart(',', '.', '!', '?', ' ')
      if (question.isEmpty()) {
        listen()
        return
      }
    }
    turnJob = scope.launch { runTurn(question) }
  }

  private suspend fun runTurn(question: String) {
    val startedAt = SystemClock.elapsedRealtime()
    update {
      it.copy(
          phase = Phase.THINKING,
          question = question,
          answer = "",
          partial = "",
          latencyMs = null,
          error = null,
      )
    }

    val jpeg = withContext(Dispatchers.Default) { acquireFrame() }
    val preview = withContext(Dispatchers.Default) { jpeg?.let { Frames.preview(it) } }
    update { it.copy(frame = preview, frameInfo = Frames.info) }
    if (jpeg == null) log("Кадра с очков нет, отвечаю без картинки")

    val answer = StringBuilder()
    val chunker = SentenceChunker()
    var spoke = false
    fun say(sentence: String) {
      val clean = cleanForSpeech(sentence)
      if (clean.isEmpty()) return
      if (!spoke) {
        spoke = true
        val latency = SystemClock.elapsedRealtime() - startedAt
        update { it.copy(phase = Phase.SPEAKING, latencyMs = latency) }
      }
      tts?.speak(clean)
    }

    try {
      val settings = _state.value
      var forceRefresh = false
      var effort: String? = "low"
      var tries = 0
      while (true) {
        tries += 1
        try {
          val token = ChatGptAuth.accessToken(app, forceRefresh)
          val body =
              Responses.buildBody(
                  model = settings.model,
                  instructions = instructions(jpeg != null),
                  history = history.toList(),
                  question = question,
                  jpeg = jpeg,
                  reasoningEffort = effort,
              )
          Responses.stream(token, body).collect { delta ->
            answer.append(delta)
            update { it.copy(answer = answer.toString()) }
            chunker.push(delta).forEach { say(it) }
          }
          break
        } catch (e: ApiException) {
          val canRetry = tries < 3 && answer.isEmpty()
          if (canRetry && e.status == 401) {
            forceRefresh = true
            continue
          }
          // Not every model takes a reasoning effort; ask again without it.
          val aboutReasoning = (e.message ?: "").contains("reasoning", ignoreCase = true)
          if (canRetry && e.status == 400 && effort != null && aboutReasoning) {
            effort = null
            continue
          }
          throw e
        }
      }
      chunker.flush()?.let { say(it) }

      val text = answer.toString().trim()
      if (text.isNotEmpty()) {
        history.addLast(Turn(question, text))
        while (history.size > MAX_HISTORY) history.removeFirst()
      }
      awaitSpeechEnd()
    } catch (e: CancellationException) {
      throw e
    } catch (e: AuthException) {
      fail(e.message ?: "Нужно войти в ChatGPT")
      update { it.copy(signedIn = ChatGptAuth.isSignedIn(app)) }
      shutdown()
      return
    } catch (e: Exception) {
      val limit = e is ApiException && e.status == 429
      fail(
          if (limit) "Лимит подписки ChatGPT исчерпан. Смотри chatgpt.com/settings/usage"
          else "Запрос к ChatGPT: ${e.message}",
      )
      tts?.speak(if (limit) "Лимит подписки исчерпан." else "Ошибка запроса.")
      awaitSpeechEnd()
    }
    listen()
  }

  /** Listening must not start while the answer is still playing, or it would hear itself. */
  private suspend fun awaitSpeechEnd() {
    val speech = tts ?: return
    delay(150)
    while (speech.isSpeaking) delay(100)
    delay(250)
  }

  /** Runs off the main thread. Video frames are instant; a photo is sharper but takes longer. */
  private suspend fun acquireFrame(): ByteArray? {
    val photoFirst = _state.value.photoMode
    if (photoFirst) capturePhotoJpeg()?.let { return it }
    Frames.latestJpeg()?.let { return it }
    return if (photoFirst) null else capturePhotoJpeg()
  }

  private suspend fun capturePhotoJpeg(): ByteArray? {
    val video = stream ?: return null
    if (!streaming) return null
    var result: ByteArray? = null
    withTimeoutOrNull(PHOTO_TIMEOUT_MS) {
      video
          .capturePhoto()
          .onSuccess { photo ->
            result =
                when (photo) {
                  is PhotoData.Bitmap -> Frames.bitmapToJpeg(photo.bitmap)
                  is PhotoData.HEIC -> Frames.heicToJpeg(photo.data)
                }
          }
          .onFailure { error, _ -> log("Фото с очков: ${error.description}") }
    }
    return result
  }

  private fun instructions(hasImage: Boolean): String {
    val picture =
        if (hasImage) {
          "К вопросу приложен кадр с камеры очков: это то, что пользователь видит прямо сейчас. " +
              "Опирайся на кадр, когда вопрос про то, что перед глазами, и не описывай его без " +
              "просьбы. Если нужного на кадре не видно или не разобрать, так и скажи."
        } else {
          "Кадра с камеры сейчас нет. Если вопрос про то, что перед глазами, скажи, что не видишь."
        }
    return "Ты голосовой ассистент в умных очках. Пользователь говорит с тобой вслух, твой ответ " +
        "будет озвучен. Отвечай на языке вопроса, коротко и по делу: одно-три предложения, если " +
        "не просят подробнее. Без списков, разметки, эмодзи и ссылок. " +
        picture
  }

  private fun cleanForSpeech(text: String): String =
      text.replace(Regex("[*#`_>|]"), "").replace(Regex("\\s+"), " ").trim()

  // ---- plumbing ----

  private fun prefs(): SharedPreferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  private fun update(block: (UiState) -> UiState) {
    _state.update(block)
  }

  private fun fail(message: String) {
    log(message)
    update { it.copy(error = message) }
  }

  /** Safe from any thread. The log is on screen because there is no other way to see inside. */
  fun log(message: String) {
    Log.i(TAG, message)
    val snapshot =
        synchronized(logLines) {
          logLines.addLast("${clock.format(Date())}  $message")
          while (logLines.size > MAX_LOG) logLines.removeFirst()
          logLines.toList()
        }
    update { it.copy(log = snapshot) }
  }
}
