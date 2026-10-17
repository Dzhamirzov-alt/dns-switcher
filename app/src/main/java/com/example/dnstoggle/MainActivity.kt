package com.example.dnstoggle

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

object DnsHelper {
    private const val PREFS_NAME = "dns_prefs"
    private const val KEY_SERVER = "dns_server"
    const val DEFAULT_SERVER = "geohide.ru"

    fun hasPermission(context: Context): Boolean {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED
    }

    fun getCurrentSystemServer(context: Context): String {
        return Settings.Global.getString(context.contentResolver, "private_dns_specifier")?.trim() ?: ""
    }

    fun getSavedServer(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_SERVER, null)?.trim()
        if (!saved.isNullOrEmpty()) return saved

        // Если в приложении еще ничего не сохраняли, берем текущий сервер из системы
        val sysServer = getCurrentSystemServer(context)
        return if (sysServer.isNotEmpty()) sysServer else DEFAULT_SERVER
    }

    fun saveServer(context: Context, server: String) {
        val clean = server.trim().ifEmpty { DEFAULT_SERVER }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SERVER, clean)
            .apply()
    }

    fun isDnsEnabled(context: Context): Boolean {
        val mode = Settings.Global.getString(context.contentResolver, "private_dns_mode")
        return mode == "hostname"
    }

    fun setDnsState(context: Context, enable: Boolean, server: String): Boolean {
        return try {
            val resolver = context.contentResolver
            if (enable) {
                val cleanServer = server.trim().ifEmpty { DEFAULT_SERVER }
                saveServer(context, cleanServer)
                Settings.Global.putString(resolver, "private_dns_specifier", cleanServer)
                Settings.Global.putString(resolver, "private_dns_mode", "hostname")
            } else {
                Settings.Global.putString(resolver, "private_dns_mode", "off")
            }
            true
        } catch (e: SecurityException) {
            false
        }
    }
}

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var serverInput: EditText
    private lateinit var btnEnable: Button
    private lateinit var btnDisable: Button
    private lateinit var adbInfoBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 64)
        }

        // 1. Блок текущего статуса
        statusText = TextView(this).apply {
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(32, 40, 32, 40)
        }
        root.addView(statusText, params(bottomMargin = 40))

        // 2. Главные кнопки управления: ВКЛЮЧИТЬ и ВЫКЛЮЧИТЬ
        val controlsTitle = TextView(this).apply {
            text = "Управление DNS:"
            textSize = 15f
        }
        root.addView(controlsTitle, params(bottomMargin = 16))

        btnEnable = Button(this).apply {
            text = "✅ ВКЛЮЧИТЬ DNS"
            textSize = 17f
            setPadding(24, 32, 24, 32)
            setOnClickListener {
                val server = serverInput.text.toString().trim()
                if (DnsHelper.setDnsState(this@MainActivity, true, server)) {
                    Toast.makeText(this@MainActivity, "DNS включен: $server", Toast.LENGTH_SHORT).show()
                } else {
                    showPermissionWarning()
                }
                updateUi()
            }
        }
        root.addView(btnEnable, params(bottomMargin = 16))

        btnDisable = Button(this).apply {
            text = "⛔ ВЫКЛЮЧИТЬ DNS"
            textSize = 17f
            setPadding(24, 32, 24, 32)
            setOnClickListener {
                if (DnsHelper.setDnsState(this@MainActivity, false, "")) {
                    Toast.makeText(this@MainActivity, "Персональный DNS выключен", Toast.LENGTH_SHORT).show()
                } else {
                    showPermissionWarning()
                }
                updateUi()
            }
        }
        root.addView(btnDisable, params(bottomMargin = 56))

        // 3. Настройка адреса DNS-сервера
        val labelInput = TextView(this).apply {
            text = "Используемый адрес DNS-сервера:"
            textSize = 15f
        }
        root.addView(labelInput, params(bottomMargin = 8))

        serverInput = EditText(this).apply {
            hint = "Например, geohide.ru"
            setText(DnsHelper.getSavedServer(this@MainActivity))
            setSingleLine(true)
        }
        root.addView(serverInput, params(bottomMargin = 16))

        // Быстрые кнопки подстановки популярных серверов
        val presetsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val presetGeo = Button(this).apply {
            text = "geohide.ru"
            textSize = 12f
            setOnClickListener {
                serverInput.setText("geohide.ru")
                saveAndApplyServer()
            }
        }
        val presetAdguard = Button(this).apply {
            text = "AdGuard DNS"
            textSize = 12f
            setOnClickListener {
                serverInput.setText("dns.adguard-dns.com")
                saveAndApplyServer()
            }
        }
        presetsRow.addView(presetGeo, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { rightMargin = 8 })
        presetsRow.addView(presetAdguard, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = 8 })
        root.addView(presetsRow, params(bottomMargin = 16))

        val saveBtn = Button(this).apply {
            text = "💾 Сохранить этот адрес"
            setOnClickListener { saveAndApplyServer() }
        }
        root.addView(saveBtn, params(bottomMargin = 48))

        // 4. Блок инструкции ADB (показывается, только если еще не выдано разрешение)
        adbInfoBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.parseColor("#33FF9800"))

            val adbCommand = "adb shell pm grant com.example.dnstoggle android.permission.WRITE_SECURE_SETTINGS"

            val infoText = TextView(this@MainActivity).apply {
                text = "⚠️ Для переключения в 1 клик нужно один раз выдать разрешение через ПК (ADB) или приложение Shizuku / LADB:\n\n$adbCommand"
                textSize = 13f
            }
            addView(infoText, params(bottomMargin = 20))

            val copyBtn = Button(this@MainActivity).apply {
                text = "📋 Скопировать команду ADB"
                setOnClickListener {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("ADB", adbCommand))
                    Toast.makeText(this@MainActivity, "Команда скопирована", Toast.LENGTH_SHORT).show()
                }
            }
            addView(copyBtn, params(bottomMargin = 12))

            val openSettingsBtn = Button(this@MainActivity).apply {
                text = "⚙️ Открыть настройки сети телефона"
                setOnClickListener {
                    startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                }
            }
            addView(openSettingsBtn, params())
        }
        root.addView(adbInfoBox, params())

        scrollView.addView(root)
        setContentView(scrollView)
    }

    private fun saveAndApplyServer() {
        val addr = serverInput.text.toString().trim()
        DnsHelper.saveServer(this, addr)
        if (DnsHelper.isDnsEnabled(this) && DnsHelper.hasPermission(this)) {
            DnsHelper.setDnsState(this, true, addr)
            Toast.makeText(this, "Активный сервер изменен на $addr", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Адрес $addr сохранен для включения", Toast.LENGTH_SHORT).show()
        }
        updateUi()
    }

    private fun showPermissionWarning() {
        Toast.makeText(
            this,
            "Нет разрешения! Скопируйте команду ADB внизу экрана.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun params(topMargin: Int = 0, bottomMargin: Int = 0): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            this.topMargin = topMargin
            this.bottomMargin = bottomMargin
        }
    }

    override fun onResume() {
        super.onResume()
        updateUi()
    }

    private fun updateUi() {
        val enabled = DnsHelper.isDnsEnabled(this)
        val hasPerm = DnsHelper.hasPermission(this)
        val sysServer = DnsHelper.getCurrentSystemServer(this)
        val savedServer = DnsHelper.getSavedServer(this)

        if (enabled) {
            val active = sysServer.ifEmpty { savedServer }
            statusText.text = "🟢 DNS ВКЛЮЧЕН\nСервер: $active"
            statusText.setTextColor(Color.parseColor("#66BB6A"))
            statusText.setBackgroundColor(Color.parseColor("#224CAF50"))
            btnEnable.isEnabled = (active != serverInput.text.toString().trim())
            btnDisable.isEnabled = true
        } else {
            statusText.text = "🔴 DNS ВЫКЛЮЧЕН\n(Готов к включению: $savedServer)"
            statusText.setTextColor(Color.parseColor("#EF5350"))
            statusText.setBackgroundColor(Color.parseColor("#22F44336"))
            btnEnable.isEnabled = true
            btnDisable.isEnabled = false
        }

        adbInfoBox.visibility = if (hasPerm) View.GONE else View.VISIBLE
    }
}

class DnsTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val enabled = DnsHelper.isDnsEnabled(this)
        val server = DnsHelper.getSavedServer(this)
        val ok = DnsHelper.setDnsState(this, !enabled, server)

        if (!ok) {
            Toast.makeText(
                this,
                "Откройте DNS Switcher и выдайте разрешение ADB",
                Toast.LENGTH_LONG
            ).show()
        }
        refreshTile()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val enabled = DnsHelper.isDnsEnabled(this)

        if (enabled) {
            val server = DnsHelper.getCurrentSystemServer(this).ifEmpty { DnsHelper.getSavedServer(this) }
            tile.state = Tile.STATE_ACTIVE
            tile.label = "DNS: Вкл"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = server
            }
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "DNS: Выкл"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = DnsHelper.getSavedServer(this)
            }
        }
        tile.updateTile()
    }
}
