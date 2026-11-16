package com.keonyrus.tupovision

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.ToneGenerator
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import android.view.KeyEvent

/**
 * Standby: lets a gesture on the glasses start and stop the conversation while the phone stays in
 * a pocket.
 *
 * To the phone the glasses are a Bluetooth headset, and their touchpad gestures arrive as media
 * buttons: tap is play/pause, double tap is next, triple tap is previous. Android hands media
 * buttons to the app that played sound last and has an active media session, so standby opens a
 * session and plays a moment of silence to claim them. Starting music in another app hands the
 * buttons to that app, which is the right thing to happen.
 *
 * Every button that arrives is written to the log, because which gestures the glasses forward,
 * and when, is only known by trying.
 */
object Standby {
  private const val SILENCE_RATE = 16000
  // A held button repeats; one gesture must toggle once.
  private const val REPEAT_GUARD_MS = 800L

  private var session: MediaSession? = null
  private var lastToggleAt = 0L

  val isOn: Boolean
    get() = session != null

  /** Call on the main thread. */
  fun enable(context: Context) {
    if (session != null) return
    val created = MediaSession(context.applicationContext, "TupoVisionStandby")
    created.setCallback(
        object : MediaSession.Callback() {
          override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
            @Suppress("DEPRECATION")
            val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
            if (event != null && event.action == KeyEvent.ACTION_DOWN) onButton(event.keyCode)
            return true
          }
        },
    )
    created.setPlaybackState(
        PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS,
            )
            .setState(PlaybackState.STATE_PAUSED, 0L, 0f)
            .build(),
    )
    created.isActive = true
    session = created
    playSilence()
  }

  fun disable() {
    val current = session ?: return
    session = null
    runCatching {
      current.isActive = false
      current.release()
    }
  }

  private fun onButton(keyCode: Int) {
    Engine.log("Glasses button: " + KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_"))
    if (keyCode != KeyEvent.KEYCODE_MEDIA_PREVIOUS) return
    val now = SystemClock.elapsedRealtime()
    if (now - lastToggleAt < REPEAT_GUARD_MS) return
    lastToggleAt = now
    Engine.toggleFromGesture()
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

  /** Half a second of silent media playback, which makes this app the one media buttons go to. */
  private fun playSilence() {
    Thread {
          runCatching {
            val samples = ByteArray(SILENCE_RATE)
            val track =
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(SILENCE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(samples.size)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
            track.write(samples, 0, samples.size)
            track.play()
            Thread.sleep(700)
            track.stop()
            track.release()
          }
        }
        .start()
  }
}
