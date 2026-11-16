package com.keonyrus.tupovision

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService

/**
 * Exists only so the user can grant this app "notification access", which Android requires before
 * an app may see other apps' media sessions. It reads no notifications.
 */
class MediaWatchService : NotificationListenerService()

/**
 * Standby: start and stop the conversation from the glasses, phone in a pocket.
 *
 * The glasses cannot launch this app, but their tap-and-hold gesture can be assigned to "Music"
 * in the Meta AI app, which makes them ask a music app on the phone to play. Standby uses a music
 * app the user does not otherwise listen to as a tripwire: when that app shows any sign of life,
 * it is paused and the conversation toggles.
 *
 * Every media session change is logged as package and state, never titles, because what the
 * glasses actually make the music app do is only known by trying.
 */
object MediaWatch {
  const val DEFAULT_TRIGGER = "com.amazon.mp3"
  // One gesture causes a burst of session changes; it must toggle once.
  private const val DEBOUNCE_MS = 4000L

  private val handler = Handler(Looper.getMainLooper())
  private var manager: MediaSessionManager? = null
  private var component: ComponentName? = null
  private var triggerPackage = DEFAULT_TRIGGER
  private var armed = false
  private var lastTriggerAt = 0L
  private val watched = HashMap<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()

  private val sessionsListener =
      MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        onSessions(controllers.orEmpty())
      }

  val isOn: Boolean
    get() = manager != null

  fun hasAccess(context: Context): Boolean {
    val enabled =
        Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?: return false
    return enabled.split(":").any {
      ComponentName.unflattenFromString(it)?.packageName == context.packageName
    }
  }

  /** Call on the main thread. Returns false when notification access is missing. */
  fun start(context: Context, trigger: String): Boolean {
    if (manager != null) return true
    val sessions = context.getSystemService(MediaSessionManager::class.java)
    val listener = ComponentName(context, MediaWatchService::class.java)
    return try {
      sessions.addOnActiveSessionsChangedListener(sessionsListener, listener, handler)
      manager = sessions
      component = listener
      triggerPackage = trigger.ifBlank { DEFAULT_TRIGGER }
      // Sessions that already exist are the baseline, not a gesture.
      armed = false
      onSessions(sessions.getActiveSessions(listener))
      armed = true
      true
    } catch (e: SecurityException) {
      false
    }
  }

  fun stop() {
    val sessions = manager ?: return
    manager = null
    armed = false
    runCatching { sessions.removeOnActiveSessionsChangedListener(sessionsListener) }
    watched.values.forEach { (controller, callback) ->
      runCatching { controller.unregisterCallback(callback) }
    }
    watched.clear()
  }

  private fun onSessions(controllers: List<MediaController>) {
    val current = controllers.map { it.sessionToken }.toSet()
    for (token in watched.keys.filter { it !in current }) {
      watched.remove(token)?.let { (controller, callback) ->
        runCatching { controller.unregisterCallback(callback) }
        if (armed) Engine.log("Media: ${controller.packageName} session ended")
      }
    }
    for (controller in controllers) {
      if (controller.sessionToken in watched) continue
      val callback =
          object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
              onState(controller, state)
            }
          }
      controller.registerCallback(callback, handler)
      watched[controller.sessionToken] = controller to callback
      if (armed) {
        Engine.log(
            "Media: ${controller.packageName} session appeared, " +
                stateName(controller.playbackState),
        )
        if (controller.packageName == triggerPackage) trigger(controller)
      }
    }
  }

  private fun onState(controller: MediaController, state: PlaybackState?) {
    if (!armed) return
    Engine.log("Media: ${controller.packageName} is ${stateName(state)}")
    if (controller.packageName != triggerPackage || state == null) return
    // Asked to play: it either starts, or fails because nobody is signed in. Both count.
    val asked =
        state.state == PlaybackState.STATE_PLAYING ||
            state.state == PlaybackState.STATE_BUFFERING ||
            state.state == PlaybackState.STATE_CONNECTING ||
            state.state == PlaybackState.STATE_ERROR
    if (asked) trigger(controller)
  }

  private fun trigger(controller: MediaController) {
    val now = SystemClock.elapsedRealtime()
    if (now - lastTriggerAt < DEBOUNCE_MS) return
    lastTriggerAt = now
    // The music was only a signal; nobody wants to hear it.
    runCatching { controller.transportControls.pause() }
    Engine.toggleFromGesture()
  }

  private fun stateName(state: PlaybackState?): String =
      when (state?.state) {
        null -> "no state"
        PlaybackState.STATE_NONE -> "none"
        PlaybackState.STATE_STOPPED -> "stopped"
        PlaybackState.STATE_PAUSED -> "paused"
        PlaybackState.STATE_PLAYING -> "playing"
        PlaybackState.STATE_BUFFERING -> "buffering"
        PlaybackState.STATE_CONNECTING -> "connecting"
        PlaybackState.STATE_ERROR -> "error"
        else -> "state ${state.state}"
      }

  /** A short tone in the glasses: high when the conversation starts, low when it stops. */
  fun beep(starting: Boolean) {
    Thread {
          runCatching {
            val tones = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
            val tone = if (starting) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_NACK
            tones.startTone(tone, 250)
            Thread.sleep(400)
            tones.release()
          }
        }
        .start()
  }
}
