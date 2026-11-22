package com.example.btspeaker

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Главный выключатель */
    var enabled: Boolean
        get() = sp.getBoolean("enabled", true)
        set(value) = sp.edit().putBoolean("enabled", value).apply()

    /** Дублировать рингтон входящего звонка на динамик */
    var calls: Boolean
        get() = sp.getBoolean("calls", true)
        set(value) = sp.edit().putBoolean("calls", value).apply()

    /** Считать наушниками и проводные (3.5 мм / USB-C) */
    var wired: Boolean
        get() = sp.getBoolean("wired", false)
        set(value) = sp.edit().putBoolean("wired", value).apply()

    /** Сначала дать системе доиграть звук в наушниках, потом играть на динамике */
    var sequential: Boolean
        get() = sp.getBoolean("sequential", false)
        set(value) = sp.edit().putBoolean("sequential", value).apply()

    /** Громкость на динамике, 0..100 (множитель к системной громкости уведомлений) */
    var volume: Int
        get() = sp.getInt("volume", 100)
        set(value) = sp.edit().putInt("volume", value.coerceIn(0, 100)).apply()

    /** Пакеты приложений, для которых звук на динамик не дублируем */
    var excluded: Set<String>
        get() = sp.getStringSet("excluded", emptySet()) ?: emptySet()
        set(value) = sp.edit().putStringSet("excluded", HashSet(value)).apply()
}
