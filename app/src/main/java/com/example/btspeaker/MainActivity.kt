package com.example.btspeaker

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var player: SpeakerPlayer
    private lateinit var status: TextView
    private lateinit var btnAccess: Button
    private lateinit var volumeLabel: TextView
    private lateinit var swCalls: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)
        player = SpeakerPlayer(this)

        status = findViewById(R.id.status)
        btnAccess = findViewById(R.id.btnAccess)
        volumeLabel = findViewById(R.id.volumeLabel)
        swCalls = findViewById(R.id.swCalls)
        val swEnabled = findViewById<Switch>(R.id.swEnabled)
        val swWired = findViewById<Switch>(R.id.swWired)
        val seekVolume = findViewById<SeekBar>(R.id.seekVolume)

        btnAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        swEnabled.isChecked = prefs.enabled
        swEnabled.setOnCheckedChangeListener { _, checked -> prefs.enabled = checked }

        swCalls.isChecked = prefs.calls && hasPhonePermission()
        swCalls.setOnCheckedChangeListener { _, checked ->
            prefs.calls = checked
            if (checked && !hasPhonePermission()) {
                requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), REQ_PHONE)
            }
        }

        swWired.isChecked = prefs.wired
        swWired.setOnCheckedChangeListener { _, checked -> prefs.wired = checked }

        seekVolume.progress = prefs.volume
        updateVolumeLabel(prefs.volume)
        seekVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                updateVolumeLabel(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                prefs.volume = seekBar.progress
            }
        })

        findViewById<Button>(R.id.btnApps).setOnClickListener {
            startActivity(Intent(this, AppPickerActivity::class.java))
        }
        findViewById<Button>(R.id.btnTest).setOnClickListener {
            player.playNotification(defaultNotificationUri(this), null, prefs.volume)
        }
        findViewById<Button>(R.id.btnAutostart).setOnClickListener { openAutostart() }
        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        val hasAccess = hasListenerAccess()
        status.setText(if (hasAccess) R.string.access_ok else R.string.access_missing)
        btnAccess.visibility = if (hasAccess) View.GONE else View.VISIBLE

        if (hasAccess) {
            val service = NotificationSpeakerService.instance
            if (service == null) {
                // На HyperOS сервис иногда не поднимается сам после выдачи доступа
                try {
                    NotificationListenerService.requestRebind(
                        ComponentName(this, NotificationSpeakerService::class.java)
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "requestRebind failed", e)
                }
            } else {
                service.registerCallWatcher()
            }
        }
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PHONE) {
            val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                swCalls.isChecked = false
                Toast.makeText(this, R.string.calls_denied, Toast.LENGTH_LONG).show()
            }
            NotificationSpeakerService.instance?.registerCallWatcher()
        }
    }

    private fun updateVolumeLabel(percent: Int) {
        volumeLabel.text = getString(R.string.volume_label, percent)
    }

    private fun hasPhonePermission(): Boolean =
        checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    private fun hasListenerAccess(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?: return false
        return flat.split(":").any {
            ComponentName.unflattenFromString(it)?.packageName == packageName
        }
    }

    private fun openAutostart() {
        try {
            startActivity(
                Intent().setComponent(
                    ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"
                    )
                )
            )
        } catch (e: Exception) {
            Toast.makeText(this, R.string.autostart_fail, Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }
    }

    private companion object {
        const val TAG = "BtSpeakerNotify"
        const val REQ_PHONE = 1
    }
}
