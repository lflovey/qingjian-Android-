package com.qingjian.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CompoundButton
import android.widget.Switch
import android.widget.TextView
import java.io.File

/**
 * 最小设置页（method.xml 的 android:settingsActivity 指向本类）：
 * 版本/引擎数据状态展示 + 跳系统输入法设置 + 拉起输入法切换器。
 */
class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val version = try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (t: Throwable) {
            "unknown"
        }
        findViewById<TextView>(R.id.txtVersion).text = getString(R.string.settings_version, version)

        val dataDir = File(filesDir, "qingjian-data")
        val dictReady = File(dataDir, "dict.qj").let { it.exists() && it.length() > 0 }
        findViewById<TextView>(R.id.txtEngineStatus).text = if (dictReady) {
            getString(R.string.settings_engine_ready, dataDir.absolutePath)
        } else {
            getString(R.string.settings_engine_pending)
        }

        findViewById<Button>(R.id.btnEnableIme).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<Button>(R.id.btnSwitchIme).setOnClickListener {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
        // ★ 语音自动加句号开关（ITN）：true=识别后自动补句号等标点，false=原样上屏。
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val swPeriod = findViewById<Switch>(R.id.swVoicePeriod)
        swPeriod.isChecked = prefs.getBoolean(KEY_VOICE_AUTO_PERIOD, DEFAULT_AUTO_PERIOD)
        swPeriod.setOnCheckedChangeListener { _: CompoundButton?, checked: Boolean ->
            prefs.edit().putBoolean(KEY_VOICE_AUTO_PERIOD, checked).apply()
        }
    }
    companion object {
        const val PREFS_NAME = "qingjian_settings"
        const val KEY_VOICE_AUTO_PERIOD = "voice_auto_period"
        const val DEFAULT_AUTO_PERIOD = true
    }
}
