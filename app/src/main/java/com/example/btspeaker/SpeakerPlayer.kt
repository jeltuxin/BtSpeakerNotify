package com.example.btspeaker

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log

fun defaultNotificationUri(context: Context): Uri =
    RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_NOTIFICATION)
        ?: Settings.System.DEFAULT_NOTIFICATION_URI

fun defaultRingtoneUri(context: Context): Uri =
    RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
        ?: Settings.System.DEFAULT_RINGTONE_URI

/**
 * Проигрывает звук принудительно через встроенный динамик телефона,
 * даже если подключены Bluetooth-наушники.
 * Все методы нужно вызывать из главного потока.
 */
class SpeakerPlayer(context: Context) {

    private val ctx = context.applicationContext
    private val audio: AudioManager = ctx.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val stopTask = Runnable { stopInternal() }

    private var player: MediaPlayer? = null
    private var ringing = false

    /** Подключены ли сейчас Bluetooth-наушники (и, по желанию, проводные) */
    fun headphonesConnected(includeWired: Boolean): Boolean {
        return audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { d ->
            when (d.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER -> true

                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_USB_HEADSET -> includeWired

                else -> false
            }
        }
    }

    /** Короткий звук уведомления. Если [uri] не открылся — пробуем [fallback]. */
    fun playNotification(uri: Uri, fallback: Uri?, volumePercent: Int) {
        handler.post {
            if (ringing) return@post // не перебиваем звонок
            start(
                uri, fallback, volumePercent,
                AudioAttributes.USAGE_NOTIFICATION,
                AudioAttributes.CONTENT_TYPE_SONIFICATION,
                false, NOTIFICATION_MAX_MS
            )
        }
    }

    /** Рингтон входящего звонка по кругу, пока не вызван [stopRing]. */
    fun playRingtone(uri: Uri, volumePercent: Int) {
        handler.post {
            if (ringing) return@post
            start(
                uri, null, volumePercent,
                AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
                AudioAttributes.CONTENT_TYPE_SONIFICATION,
                true, RINGTONE_MAX_MS
            )
        }
    }

    fun stopRing() {
        handler.post { if (ringing) stopInternal() }
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        stopInternal()
    }

    private fun speakerDevice(): AudioDeviceInfo? =
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

    private fun start(
        uri: Uri,
        fallback: Uri?,
        volumePercent: Int,
        usage: Int,
        contentType: Int,
        loop: Boolean,
        maxMs: Long
    ) {
        stopInternal()
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(contentType)
                    .build()
            )
            mp.setDataSource(ctx, uri)
            mp.isLooping = loop
            val v = volumePercent.coerceIn(0, 100) / 100f
            mp.setVolume(v, v)
            // Ключевая строка: направляем звук именно на динамик телефона
            speakerDevice()?.let { mp.setPreferredDevice(it) }

            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener { stopInternal() }
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "MediaPlayer error $what/$extra for $uri")
                stopInternal()
                if (fallback != null && fallback != uri) {
                    start(fallback, null, volumePercent, usage, contentType, loop, maxMs)
                }
                true
            }
            mp.prepareAsync()

            player = mp
            ringing = loop
            handler.postDelayed(stopTask, maxMs)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot play $uri", e)
            mp.release()
            player = null
            ringing = false
            if (fallback != null && fallback != uri) {
                start(fallback, null, volumePercent, usage, contentType, loop, maxMs)
            }
        }
    }

    private fun stopInternal() {
        handler.removeCallbacks(stopTask)
        val mp = player
        player = null
        ringing = false
        if (mp != null) {
            try {
                mp.setOnPreparedListener(null)
                mp.setOnCompletionListener(null)
                mp.setOnErrorListener(null)
                if (mp.isPlaying) mp.stop()
            } catch (e: Exception) {
                // плеер мог быть в неподходящем состоянии — это нормально
            }
            mp.release()
        }
    }

    private companion object {
        const val TAG = "SpeakerPlayer"
        const val NOTIFICATION_MAX_MS = 8_000L
        const val RINGTONE_MAX_MS = 60_000L
    }
}
