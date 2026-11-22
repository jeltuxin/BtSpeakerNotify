package com.example.btspeaker

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView

/** Список приложений: отмеченные галочкой — для них звук на динамик НЕ дублируется. */
class AppPickerActivity : Activity() {

    private data class AppItem(val label: String, val pkg: String)

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.pick_title)

        val prefs = Prefs(this)
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        val apps = pm.queryIntentActivities(launcher, 0)
            .map { AppItem(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .filter { it.pkg != packageName }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }

        val list = ListView(this)
        list.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        list.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_multiple_choice,
            apps.map { it.label }
        )

        val excluded = prefs.excluded
        apps.forEachIndexed { index, app -> list.setItemChecked(index, app.pkg in excluded) }

        list.setOnItemClickListener { _, _, _, _ ->
            val shownPackages = apps.map { it.pkg }.toSet()
            // Сохраняем исключения для приложений, которых нет в списке (например, удалённых)
            val kept = prefs.excluded - shownPackages
            val checked = apps.filterIndexed { index, _ -> list.isItemChecked(index) }.map { it.pkg }
            prefs.excluded = kept + checked
        }

        setContentView(list)
    }
}
