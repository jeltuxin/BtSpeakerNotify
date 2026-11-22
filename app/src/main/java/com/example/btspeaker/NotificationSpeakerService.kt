package com.example.btspeaker

import android.Manifest
import android.annotation.TargetApi
import android.app.Notification
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Слушает уведомления системы. Когда приходит уведомление со звуком и подключены
 * Bluetooth-наушники, дополнительно проигрывает этот же звук через динамик телефона.
 * Заодно следит за входящими звонками и дублирует рингтон на динамик.
 */
class NotificationSpeakerService : NotificationListenerService() {

    private lateinit var prefs: Prefs
    private lateinit var audio: AudioManager
    private lateinit var player: SpeakerPlayer

    private var telephony: TelephonyManager? = null
    private var telephonyCallback: Any? = null
    private var legacyListener: Any? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        audio = getSystemService(AudioManager::class.java)
        player = SpeakerPlayer.get(this)
    }

    override fun onListenerConnected() {
        instance = this
        registerCallWatcher()
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        unregisterCallWatcher()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        unregisterCallWatcher()
        player.release()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- уведомления

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        try {
            handlePosted(sbn, rankingMap)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to handle notification", e)
        }
    }

    private fun handlePosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        if (!prefs.enabled) return

        val pkg = sbn.packageName
        if (pkg == packageName || pkg in prefs.excluded) return

        val n = sbn.notification
        val flags = n.flags

        // Постоянные/служебные уведомления звука не издают
        if (flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return

        // Сводка группы звучит только если приложение так решило
        val isSummary = flags and Notification.FLAG_GROUP_SUMMARY != 0
        val groupBehavior = n.groupAlertBehavior
        if (isSummary && groupBehavior != Notification.GROUP_ALERT_SUMMARY) return
        if (!isSummary && n.group != null && groupBehavior == Notification.GROUP_ALERT_SUMMARY) return

        // «Беззвучные» каналы, режим «Не беспокоить»
        val ranking = Ranking()
        if (!rankingMap.getRanking(sbn.key, ranking)) return
        if (ranking.importance < NotificationManager.IMPORTANCE_DEFAULT) return
        if (!ranking.matchesInterruptionFilter()) return

        // Какой звук у канала уведомлений
        val channel = ranking.channel
        val sound: Uri? = if (channel != null) channel.sound else defaultNotificationUri(this)
        if (sound == null) return // канал без звука

        if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return

        // ---- защита от повторов: приложения часто присылают одно и то же уведомление
        // несколько раз подряд (обновление, удаление + повторная публикация, сводка группы)
        val now = SystemClock.elapsedRealtime()
        if (now - lastAnyPlay < MIN_GAP_MS) return

        val keyTime = recentKeys[sbn.key]
        if (keyTime != null) {
            if (now - keyTime < SAME_KEY_GAP_MS) return
            if (flags and Notification.FLAG_ONLY_ALERT_ONCE != 0 && now - keyTime < ALERT_ONCE_MS) return
        }

        val extras = n.extras
        val signature = sbn.key + "|" +
                extras?.getCharSequence(Notification.EXTRA_TITLE) + "|" +
                extras?.getCharSequence(Notification.EXTRA_TEXT)
        val signatureTime = recentSignatures[signature]
        if (signatureTime != null && now - signatureTime < SAME_TEXT_GAP_MS) return

        if (!player.headphonesConnected(prefs.wired)) return

        lastAnyPlay = now
        recentKeys[sbn.key] = now
        recentSignatures[signature] = now
        if (recentKeys.size > 100) recentKeys.entries.removeAll { now - it.value > ALERT_ONCE_MS }
        if (recentSignatures.size > 100) recentSignatures.entries.removeAll { now - it.value > SAME_TEXT_GAP_MS }

        player.playNotification(sound, defaultNotificationUri(this), prefs.volume, prefs.sequential)
    }

    // ---------------------------------------------------------------- звонки

    /** Безопасно вызывать повторно: если уже подписаны или нет разрешения — ничего не делает. */
    fun registerCallWatcher() {
        if (telephony != null) return
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return
        val tm = getSystemService(TelephonyManager::class.java) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) registerModern(tm) else registerLegacy(tm)
            telephony = tm
        } catch (e: Exception) {
            Log.w(TAG, "Cannot watch call state", e)
        }
    }

    @TargetApi(Build.VERSION_CODES.S)
    private fun registerModern(tm: TelephonyManager) {
        val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                handleCallState(state)
            }
        }
        telephonyCallback = cb
        tm.registerTelephonyCallback(mainExecutor, cb)
    }

    @Suppress("DEPRECATION")
    private fun registerLegacy(tm: TelephonyManager) {
        val listener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                handleCallState(state)
            }
        }
        legacyListener = listener
        tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
    }

    @Suppress("DEPRECATION")
    private fun unregisterCallWatcher() {
        val tm = telephony ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (telephonyCallback as? TelephonyCallback)?.let { tm.unregisterTelephonyCallback(it) }
            } else {
                (legacyListener as? PhoneStateListener)?.let { tm.listen(it, PhoneStateListener.LISTEN_NONE) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot stop watching call state", e)
        }
        telephonyCallback = null
        legacyListener = null
        telephony = null
        player.stopRing()
    }

    private fun handleCallState(state: Int) {
        if (state == TelephonyManager.CALL_STATE_RINGING) {
            if (prefs.enabled && prefs.calls &&
                audio.ringerMode == AudioManager.RINGER_MODE_NORMAL &&
                player.headphonesConnected(prefs.wired)
            ) {
                player.playRingtone(defaultRingtoneUri(this), prefs.volume)
            }
        } else {
            player.stopRing()
        }
    }

    companion object {
        @Volatile
        var instance: NotificationSpeakerService? = null

        private const val TAG = "BtSpeakerNotify"

        // Общее состояние на весь процесс: даже если система создаст сервис дважды,
        // повторы всё равно будут отфильтрованы.
        private val recentKeys = HashMap<String, Long>()
        private val recentSignatures = HashMap<String, Long>()
        private var lastAnyPlay = 0L

        private const val MIN_GAP_MS = 700L        // любые два звука подряд
        private const val SAME_KEY_GAP_MS = 1500L  // то же самое уведомление
        private const val SAME_TEXT_GAP_MS = 8000L // то же уведомление с тем же текстом
        private const val ALERT_ONCE_MS = 60_000L  // «звук только один раз»
    }
}
