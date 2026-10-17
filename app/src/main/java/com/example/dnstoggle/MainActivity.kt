package com.example.dnstoggle

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

object DnsHelper {
    private const val PREFS_NAME = "dns_prefs"
    private const val KEY_SERVER = "dns_server"
    const val DEFAULT_SERVER = "dns.adguard-dns.com"

    fun hasPermission(context: Context): Boolean {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED
    }

    fun getSavedServer(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_SERVER, DEFAULT_SERVER)?.trim()
        return if (saved.isNullOrEmpty()) DEFAULT_SERVER else saved
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

    fun getCurrentSystemServer(context: Context): String {
        return Settings.Global.getString(context.contentResolver, "private_dns_specifier") ?: ""
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
    private lateinit var toggleButton: Button
    private lateinit var adbInfoBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(64, 96, 64, 64)
        }

        val title = TextView(this).apply {
            text = "Персональный DNS"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 32)
        }

        statusText = TextView(this).apply {
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 48)
        }

        val labelInput = TextView(this).apply {
            text = "Адрес DNS-сервера (hostname):"
            textSize = 14f
        }

        serverInput = EditText(this).apply {
            hint = DnsHelper.DEFAULT_SERVER
            setText(DnsHelper.getSavedServer(this@MainActivity))
            setSingleLine(true)
        }

        val saveBtn = Button(this).apply {
            text = "Сохранить адрес"
            setOnClickListener {
                val addr = serverInput.text.toString().trim()
                DnsHelper.saveServer(this@MainActivity, addr)
                if (DnsHelper.isDnsEnabled(this@MainActivity) && DnsHelper.hasPermission(this@MainActivity)) {
                    DnsHelper.setDnsState(this@MainActivity, true, addr)
                }
                updateUi()
                Toast.makeText(this@MainActivity, "Адрес сохранен", Toast.LENGTH_SHORT).show()
            }
        }

        toggleButton = Button(this).apply {
            textSize = 18f
            setPadding(32, 32, 32, 32)
            setOnClickListener {
                val currentlyEnabled = DnsHelper.isDnsEnabled(this@MainActivity)
                val server = serverInput.text.toString().trim()
                val ok = DnsHelper.setDnsState(this@MainActivity, !currentlyEnabled, server)
                if (!ok) {
                    Toast.makeText(
                        this@MainActivity,
                        "Сначала выдайте разрешение через ADB (команда ниже)",
                        Toast.LENGTH_LONG
                    ).show()
                }
                updateUi()
            }
        }

        // Блок подсказки для выдачи разрешения (скрывается сам, когда разрешение получено)
        adbInfoBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 64, 0, 0)

            val adbCommand = "adb shell pm grant com.example.dnstoggle android.permission.WRITE_SECURE_SETTINGS"

            val infoText = TextView(this@MainActivity).apply {
                text = "⚠️ Разрешение еще не выдано.\nВыполните эту команду один раз через ПК (ADB) или Shizuku/LADB:\n\n$adbCommand"
                textSize = 13f
                setPadding(0, 0, 0, 24)
            }

            val copyBtn = Button(this@MainActivity).apply {
                text = "Скопировать команду ADB"
                setOnClickListener {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("ADB Command", adbCommand))
                    Toast.makeText(this@MainActivity, "Команда скопирована", Toast.LENGTH_SHORT).show()
                }
            }

            addView(infoText)
            addView(copyBtn)
        }

        root.addView(title)
        root.addView(statusText)
        root.addView(labelInput)
        root.addView(serverInput)
        root.addView(saveBtn)
        root.addView(View(this).apply { minimumHeight = 48 })
        root.addView(toggleButton)
        root.addView(adbInfoBox)

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        updateUi()
    }

    private fun updateUi() {
        val enabled = DnsHelper.isDnsEnabled(this)
        val hasPerm = DnsHelper.hasPermission(this)
        val activeServer = DnsHelper.getCurrentSystemServer(this).ifEmpty { DnsHelper.getSavedServer(this) }

        if (enabled) {
            statusText.text = "🟢 Статус: ВКЛЮЧЕН\n($activeServer)"
            statusText.setTextColor(Color.parseColor("#2E7D32"))
            toggleButton.text = "Выключить DNS"
        } else {
            statusText.text = "⚪ Статус: ВЫКЛЮЧЕН"
            statusText.setTextColor(Color.GRAY)
            toggleButton.text = "Включить DNS"
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
                "Откройте приложение DNS Switcher и выдайте разрешение через ADB",
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
                tile.subtitle = "Отключен"
            }
        }
        tile.updateTile()
    }
}
