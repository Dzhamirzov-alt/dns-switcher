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
    private const val KEY_SELECTED_SERVER = "dns_server"
    private const val KEY_SERVER_LIST = "dns_server_list"
    const val DEFAULT_SERVER = "geohide.ru"

    fun hasPermission(context: Context): Boolean {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED
    }

    fun getCurrentSystemServer(context: Context): String {
        return Settings.Global.getString(context.contentResolver, "private_dns_specifier")?.trim() ?: ""
    }

    // Получить список всех сохраненных DNS-серверов
    fun getServerList(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_SERVER_LIST, null)

        if (raw == null) {
            // Первый запуск: формируем стартовый список + текущий сервер из системы
            val initial = mutableListOf("geohide.ru", "dns.adguard-dns.com")
            val sys = getCurrentSystemServer(context)
            if (sys.isNotEmpty() && !initial.contains(sys)) {
                initial.add(0, sys)
            }
            saveServerList(context, initial)
            return initial
        }

        return raw.split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun saveServerList(context: Context, list: List<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SERVER_LIST, list.distinct().joinToString("\n"))
            .apply()
    }

    // Добавить новый сервер в список
    fun addServerToList(context: Context, server: String): Boolean {
        val clean = server.trim().lowercase()
        if (clean.isEmpty()) return false
        val list = getServerList(context).toMutableList()
        if (!list.contains(clean)) {
            list.add(0, clean) // Добавляем в начало списка
            saveServerList(context, list)
        }
        saveSelectedServer(context, clean)
        return true
    }

    // Удалить сервер из списка
    fun removeServerFromList(context: Context, server: String) {
        val list = getServerList(context).toMutableList()
        list.remove(server)
        saveServerList(context, list)

        // Если удалили выбранный сервер, выбираем первый из оставшихся
        if (getSavedServer(context) == server) {
            val next = list.firstOrNull() ?: DEFAULT_SERVER
            saveSelectedServer(context, next)
        }
    }

    // Получить текущий выбранный сервер (для включения и для кнопки в шторке)
    fun getSavedServer(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_SELECTED_SERVER, null)?.trim()
        if (!saved.isNullOrEmpty()) return saved

        val sysServer = getCurrentSystemServer(context)
        if (sysServer.isNotEmpty()) return sysServer

        return getServerList(context).firstOrNull() ?: DEFAULT_SERVER
    }

    fun saveSelectedServer(context: Context, server: String) {
        val clean = server.trim().lowercase().ifEmpty { DEFAULT_SERVER }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SELECTED_SERVER, clean)
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
                val cleanServer = server.trim().lowercase().ifEmpty { getSavedServer(context) }
                addServerToList(context, cleanServer)
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
    private lateinit var btnEnable: Button
    private lateinit var btnDisable: Button
    private lateinit var serverListContainer: LinearLayout
    private lateinit var newServerInput: EditText
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
            textSize = 19f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(32, 36, 32, 36)
        }
        root.addView(statusText, params(bottomMargin = 32))

        // 2. Главные кнопки включения и выключения
        val mainButtonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        btnEnable = Button(this).apply {
            text = "✅ ВКЛЮЧИТЬ"
            textSize = 15f
            setPadding(16, 28, 16, 28)
            setOnClickListener {
                val server = DnsHelper.getSavedServer(this@MainActivity)
                if (DnsHelper.setDnsState(this@MainActivity, true, server)) {
                    Toast.makeText(this@MainActivity, "Подключен: $server", Toast.LENGTH_SHORT).show()
                } else {
                    showPermissionWarning()
                }
                updateUi()
            }
        }

        btnDisable = Button(this).apply {
            text = "⛔ ВЫКЛЮЧИТЬ"
            textSize = 15f
            setPadding(16, 28, 16, 28)
            setOnClickListener {
                if (DnsHelper.setDnsState(this@MainActivity, false, "")) {
                    Toast.makeText(this@MainActivity, "Персональный DNS выключен", Toast.LENGTH_SHORT).show()
                } else {
                    showPermissionWarning()
                }
                updateUi()
            }
        }

        mainButtonsRow.addView(btnEnable, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { rightMargin = 12 })
        mainButtonsRow.addView(btnDisable, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = 12 })
        root.addView(mainButtonsRow, params(bottomMargin = 48))

        // 3. Список сохраненных DNS-серверов
        val listTitle = TextView(this).apply {
            text = "Мои DNS-серверы (нажми для подключения):"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
        }
        root.addView(listTitle, params(bottomMargin = 16))

        serverListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(serverListContainer, params(bottomMargin = 40))

        // 4. Добавление нового сервера в память
        val addTitle = TextView(this).apply {
            text = "Добавить новый DNS-сервер в список:"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
        }
        root.addView(addTitle, params(bottomMargin = 8))

        newServerInput = EditText(this).apply {
            hint = "Например: one.one.one.one"
            setSingleLine(true)
        }
        root.addView(newServerInput, params(bottomMargin = 12))

        val addButtonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val btnAddOnly = Button(this).apply {
            text = "➕ В список"
            textSize = 14f
            setOnClickListener {
                val addr = newServerInput.text.toString().trim()
                if (DnsHelper.addServerToList(this@MainActivity, addr)) {
                    newServerInput.setText("")
                    Toast.makeText(this@MainActivity, "Добавлен: $addr", Toast.LENGTH_SHORT).show()
                    updateUi()
                } else {
                    Toast.makeText(this@MainActivity, "Введите адрес сервера", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val btnAddAndConnect = Button(this).apply {
            text = "⚡ Добавить и включить"
            textSize = 14f
            setOnClickListener {
                val addr = newServerInput.text.toString().trim()
                if (addr.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Введите адрес сервера", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                DnsHelper.addServerToList(this@MainActivity, addr)
                newServerInput.setText("")
                if (DnsHelper.setDnsState(this@MainActivity, true, addr)) {
                    Toast.makeText(this@MainActivity, "Подключен: $addr", Toast.LENGTH_SHORT).show()
                } else {
                    showPermissionWarning()
                }
                updateUi()
            }
        }

        addButtonsRow.addView(btnAddOnly, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { rightMargin = 8 })
        addButtonsRow.addView(btnAddAndConnect, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.3f).apply { leftMargin = 8 })
        root.addView(addButtonsRow, params(bottomMargin = 48))

        // 5. Блок инструкции ADB (автоматически скрывается после выдачи разрешения)
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

    private fun renderServerList(enabled: Boolean, activeServer: String, selectedServer: String) {
        serverListContainer.removeAllViews()
        val servers = DnsHelper.getServerList(this)

        if (servers.isEmpty()) {
            val emptyView = TextView(this).apply {
                text = "Список пуст. Добавьте первый сервер ниже."
                textSize = 14f
                setTextColor(Color.GRAY)
            }
            serverListContainer.addView(emptyView)
            return
        }

        for (server in servers) {
            val isCurrentlyActive = enabled && (activeServer.equals(server, ignoreCase = true))
            val isSelected = selectedServer.equals(server, ignoreCase = true)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val selectBtn = Button(this).apply {
                text = when {
                    isCurrentlyActive -> "🟢 $server  (Подключен)"
                    isSelected -> "🔘 $server  (Выбран)"
                    else -> "⚪ $server"
                }
                textSize = 14f
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(32, 24, 32, 24)
                setOnClickListener {
                    DnsHelper.saveSelectedServer(this@MainActivity, server)
                    if (DnsHelper.setDnsState(this@MainActivity, true, server)) {
                        Toast.makeText(this@MainActivity, "Подключен DNS: $server", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "Выбран $server (для авто-включения выдайте разрешение ADB)",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    updateUi()
                }
            }

            val deleteBtn = Button(this).apply {
                text = "✖"
                textSize = 14f
                setOnClickListener {
                    DnsHelper.removeServerFromList(this@MainActivity, server)
                    Toast.makeText(this@MainActivity, "Удален из списка: $server", Toast.LENGTH_SHORT).show()
                    updateUi()
                }
            }

            row.addView(selectBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { rightMargin = 12 })
            row.addView(deleteBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

            serverListContainer.addView(row, params(bottomMargin = 12))
        }
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
        val selectedServer = DnsHelper.getSavedServer(this)

        // Если в системе уже прописан какой-то сервер, убедимся, что он тоже сохранен в наш список
        if (sysServer.isNotEmpty()) {
            val currentList = DnsHelper.getServerList(this)
            if (!currentList.contains(sysServer)) {
                DnsHelper.addServerToList(this, sysServer)
            }
        }

        val activeServer = sysServer.ifEmpty { selectedServer }

        if (enabled) {
            statusText.text = "🟢 DNS ВКЛЮЧЕН\nАктивен: $activeServer"
            statusText.setTextColor(Color.parseColor("#66BB6A"))
            statusText.setBackgroundColor(Color.parseColor("#224CAF50"))
            btnEnable.isEnabled = !activeServer.equals(selectedServer, ignoreCase = true)
            btnDisable.isEnabled = true
        } else {
            statusText.text = "🔴 DNS ВЫКЛЮЧЕН\nВыбран: $selectedServer"
            statusText.setTextColor(Color.parseColor("#EF5350"))
            statusText.setBackgroundColor(Color.parseColor("#22F44336"))
            btnEnable.isEnabled = true
            btnDisable.isEnabled = false
        }

        renderServerList(enabled, activeServer, selectedServer)
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
