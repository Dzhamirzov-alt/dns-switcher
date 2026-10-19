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
import android.graphics.drawable.GradientDrawable
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
import java.net.InetSocketAddress
import java.net.Socket

data class DnsEntry(val host: String, val label: String)

object DnsHelper {
    private const val PREFS_NAME = "dns_prefs"
    private const val KEY_SELECTED_SERVER = "dns_server"
    private const val KEY_SERVER_LIST = "dns_server_entries"
    private const val KEY_TILE_CYCLE_MODE = "tile_cycle_mode"
    const val DEFAULT_SERVER = "geohide.ru"

    fun hasPermission(context: Context): Boolean {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED
    }

    // Режимы: "hostname" (Вкл), "opportunistic" (Авто), "off" (Выкл)
    fun getDnsMode(context: Context): String {
        return Settings.Global.getString(context.contentResolver, "private_dns_mode") ?: "off"
    }

    fun getCurrentSystemServer(context: Context): String {
        return Settings.Global.getString(context.contentResolver, "private_dns_specifier")?.trim() ?: ""
    }

    fun getServerEntries(context: Context): List<DnsEntry> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_SERVER_LIST, null)

        if (raw == null) {
            val initial = mutableListOf(
                DnsEntry("geohide.ru", "🌍 GeoHide (Обход)"),
                DnsEntry("dns.adguard-dns.com", "🛡️ AdGuard (Без рекламы)"),
                DnsEntry("one.one.one.one", "⚡ Cloudflare (Быстрый)")
            )
            val sys = getCurrentSystemServer(context)
            if (sys.isNotEmpty() && initial.none { it.host.equals(sys, true) }) {
                initial.add(0, DnsEntry(sys, "📌 Мой DNS"))
            }
            saveServerEntries(context, initial)
            return initial
        }

        return raw.split("\n")
            .mapNotNull { line ->
                val parts = line.split("|")
                val host = parts.getOrNull(0)?.trim() ?: ""
                val label = parts.getOrNull(1)?.trim() ?: ""
                if (host.isNotEmpty()) DnsEntry(host, label) else null
            }
            .distinctBy { it.host.lowercase() }
    }

    private fun saveServerEntries(context: Context, list: List<DnsEntry>) {
        val serialized = list.distinctBy { it.host.lowercase() }
            .joinToString("\n") { "${it.host}|${it.label}" }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SERVER_LIST, serialized)
            .apply()
    }

    fun addServerToList(context: Context, host: String, label: String): Boolean {
        val cleanHost = host.trim().lowercase()
        val cleanLabel = label.trim()
        if (cleanHost.isEmpty()) return false

        val list = getServerEntries(context).toMutableList()
        val existingIndex = list.indexOfFirst { it.host.equals(cleanHost, true) }
        if (existingIndex >= 0) {
            val oldLabel = list[existingIndex].label
            list[existingIndex] = DnsEntry(cleanHost, cleanLabel.ifEmpty { oldLabel })
        } else {
            list.add(0, DnsEntry(cleanHost, cleanLabel))
        }
        saveServerEntries(context, list)
        saveSelectedServer(context, cleanHost)
        return true
    }

    fun removeServerFromList(context: Context, host: String) {
        val list = getServerEntries(context).toMutableList()
        list.removeAll { it.host.equals(host, true) }
        saveServerEntries(context, list)

        if (getSavedServer(context).equals(host, true)) {
            val next = list.firstOrNull()?.host ?: DEFAULT_SERVER
            saveSelectedServer(context, next)
        }
    }

    fun getSavedServer(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_SELECTED_SERVER, null)?.trim()
        if (!saved.isNullOrEmpty()) return saved

        val sysServer = getCurrentSystemServer(context)
        if (sysServer.isNotEmpty()) return sysServer

        return getServerEntries(context).firstOrNull()?.host ?: DEFAULT_SERVER
    }

    fun saveSelectedServer(context: Context, server: String) {
        val clean = server.trim().lowercase().ifEmpty { DEFAULT_SERVER }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SELECTED_SERVER, clean)
            .apply()
    }

    fun getLabelForHost(context: Context, host: String): String {
        val entry = getServerEntries(context).find { it.host.equals(host, true) }
        return if (entry != null && entry.label.isNotEmpty()) "${entry.label} (${entry.host})" else host
    }

    fun isTileCycleMode(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_TILE_CYCLE_MODE, false)
    }

    fun setTileCycleMode(context: Context, cycle: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_TILE_CYCLE_MODE, cycle)
            .apply()
    }

    // Установка любого из 3 режимов: "hostname", "opportunistic", "off"
    fun setDnsMode(context: Context, mode: String, server: String = ""): Boolean {
        return try {
            val resolver = context.contentResolver
            when (mode) {
                "hostname" -> {
                    val cleanServer = server.trim().lowercase().ifEmpty { getSavedServer(context) }
                    saveSelectedServer(context, cleanServer)
                    Settings.Global.putString(resolver, "private_dns_specifier", cleanServer)
                    Settings.Global.putString(resolver, "private_dns_mode", "hostname")
                }
                "opportunistic" -> {
                    Settings.Global.putString(resolver, "private_dns_mode", "opportunistic")
                }
                else -> {
                    Settings.Global.putString(resolver, "private_dns_mode", "off")
                }
            }
            true
        } catch (e: SecurityException) {
            false
        }
    }

    // Проверка пинга по порту 853 (DNS-over-TLS) или 443
    fun measurePingMs(host: String): Long {
        for (port in intArrayOf(853, 443)) {
            try {
                val start = System.currentTimeMillis()
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 2000)
                }
                return System.currentTimeMillis() - start
            } catch (_: Exception) {
            }
        }
        return -1L
    }
}

class MainActivity : Activity() {
    private lateinit var statusCard: LinearLayout
    private lateinit var statusTitle: TextView
    private lateinit var statusSubtitle: TextView
    private lateinit var btnModeOn: Button
    private lateinit var btnModeAuto: Button
    private lateinit var btnModeOff: Button
    private lateinit var btnTileBehavior: Button
    private lateinit var serverListContainer: LinearLayout
    private lateinit var newServerHostInput: EditText
    private lateinit var newServerLabelInput: EditText
    private lateinit var adbInfoBox: LinearLayout

    // Кэш результатов пинга: host -> мс (-1 = ошибка, -2 = проверяется)
    private val pingResults = mutableMapOf<String, Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.parseColor("#121212"))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(44, 44, 44, 72)
        }

        // 1. Карточка статуса со скругленными углами
        statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(40, 44, 40, 44)
        }
        statusTitle = TextView(this).apply {
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        statusSubtitle = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 0)
        }
        statusCard.addView(statusTitle)
        statusCard.addView(statusSubtitle)
        root.addView(statusCard, params(bottomMargin = 36))

        // 2. Три кнопки режима: ВКЛ / АВТО / ВЫКЛ
        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        btnModeOn = createStyledButton("✅ ВКЛ", "#1E88E5").apply {
            setOnClickListener {
                val srv = DnsHelper.getSavedServer(this@MainActivity)
                if (DnsHelper.setDnsMode(this@MainActivity, "hostname", srv)) {
                    Toast.makeText(this@MainActivity, "DNS включен: $srv", Toast.LENGTH_SHORT).show()
                } else showPermissionWarning()
                updateUi()
            }
        }

        btnModeAuto = createStyledButton("🔄 АВТО", "#546E7A").apply {
            setOnClickListener {
                if (DnsHelper.setDnsMode(this@MainActivity, "opportunistic")) {
                    Toast.makeText(this@MainActivity, "Режим: Автоматически", Toast.LENGTH_SHORT).show()
                } else showPermissionWarning()
                updateUi()
            }
        }

        btnModeOff = createStyledButton("⛔ ВЫКЛ", "#E53935").apply {
            setOnClickListener {
                if (DnsHelper.setDnsMode(this@MainActivity, "off")) {
                    Toast.makeText(this@MainActivity, "Персональный DNS выключен", Toast.LENGTH_SHORT).show()
                } else showPermissionWarning()
                updateUi()
            }
        }

        modeRow.addView(btnModeOn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { rightMargin = 10 })
        modeRow.addView(btnModeAuto, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = 5; rightMargin = 5 })
        modeRow.addView(btnModeOff, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = 10 })
        root.addView(modeRow, params(bottomMargin = 28))

        // 3. Настройка поведения кнопки в шторке уведомлений
        btnTileBehavior = createStyledButton("", "#263238").apply {
            textSize = 13f
            setOnClickListener {
                val next = !DnsHelper.isTileCycleMode(this@MainActivity)
                DnsHelper.setTileCycleMode(this@MainActivity, next)
                updateUi()
            }
        }
        root.addView(btnTileBehavior, params(bottomMargin = 40))

        // 4. Заголовок списка серверов + кнопка проверки пинга
        val listHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val listTitle = TextView(this).apply {
            text = "Мои DNS-серверы:"
            textSize = 17f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        }
        val btnPingAll = createStyledButton("📡 Проверить пинг", "#00897B").apply {
            textSize = 13f
            setPadding(28, 18, 28, 18)
            setOnClickListener { pingAllServers() }
        }
        listHeaderRow.addView(listTitle, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        listHeaderRow.addView(btnPingAll, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        root.addView(listHeaderRow, params(bottomMargin = 20))

        serverListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(serverListContainer, params(bottomMargin = 40))

        // 5. Карточка добавления нового сервера с именем
        val addCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 36, 36, 36)
            background = roundedBg("#1E1E1E", "#333333")
        }

        val addTitle = TextView(this).apply {
            text = "Добавить новый сервер"
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        }
        addCard.addView(addTitle, params(bottomMargin = 16))

        newServerLabelInput = EditText(this).apply {
            hint = "Название (например: Мой AdGuard)"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setSingleLine(true)
        }
        addCard.addView(newServerLabelInput, params(bottomMargin = 12))

        newServerHostInput = EditText(this).apply {
            hint = "Адрес (например: dns.adguard-dns.com)"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setSingleLine(true)
        }
        addCard.addView(newServerHostInput, params(bottomMargin = 20))

        val addButtonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val btnAddOnly = createStyledButton("➕ В список", "#37474F").apply {
            textSize = 13f
            setOnClickListener {
                val host = newServerHostInput.text.toString().trim()
                val label = newServerLabelInput.text.toString().trim()
                if (DnsHelper.addServerToList(this@MainActivity, host, label)) {
                    newServerHostInput.setText("")
                    newServerLabelInput.setText("")
                    Toast.makeText(this@MainActivity, "Сохранено: $host", Toast.LENGTH_SHORT).show()
                    updateUi()
                } else {
                    Toast.makeText(this@MainActivity, "Введите адрес сервера", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val btnAddAndConnect = createStyledButton("⚡ Добавить и включить", "#1E88E5").apply {
            textSize = 13f
            setOnClickListener {
                val host = newServerHostInput.text.toString().trim()
                val label = newServerLabelInput.text.toString().trim()
                if (!DnsHelper.addServerToList(this@MainActivity, host, label)) {
                    Toast.makeText(this@MainActivity, "Введите адрес сервера", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                newServerHostInput.setText("")
                newServerLabelInput.setText("")
                if (DnsHelper.setDnsMode(this@MainActivity, "hostname", host)) {
                    Toast.makeText(this@MainActivity, "Подключен: $host", Toast.LENGTH_SHORT).show()
                } else showPermissionWarning()
                updateUi()
            }
        }

        addButtonsRow.addView(btnAddOnly, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { rightMargin = 10 })
        addButtonsRow.addView(btnAddAndConnect, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.3f).apply { leftMargin = 10 })
        addCard.addView(addButtonsRow)
        root.addView(addCard, params(bottomMargin = 40))

        // 6. Блок инструкции ADB
        adbInfoBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 36, 36, 36)
            background = roundedBg("#2C221E", "#FF9800")

            val adbCommand = "adb shell pm grant com.example.dnstoggle android.permission.WRITE_SECURE_SETTINGS"

            val infoText = TextView(this@MainActivity).apply {
                text = "⚠️ Выдайте разрешение один раз через ПК или Brevent:\n\n$adbCommand"
                textSize = 13f
                setTextColor(Color.parseColor("#FFE0B2"))
            }
            addView(infoText, params(bottomMargin = 20))

            val copyBtn = createStyledButton("📋 Скопировать команду ADB", "#F57C00").apply {
                setOnClickListener {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("ADB", adbCommand))
                    Toast.makeText(this@MainActivity, "Команда скопирована", Toast.LENGTH_SHORT).show()
                }
            }
            addView(copyBtn, params(bottomMargin = 12))

            val openSettingsBtn = createStyledButton("⚙️ Открыть настройки сети", "#455A64").apply {
                setOnClickListener {
                    startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                }
            }
            addView(openSettingsBtn)
        }
        root.addView(adbInfoBox, params())

        scrollView.addView(root)
        setContentView(scrollView)
    }

    private fun pingAllServers() {
        val entries = DnsHelper.getServerEntries(this)
        for (entry in entries) {
            pingResults[entry.host] = -2L // Статус "Проверяется..."
        }
        updateUi()

        for (entry in entries) {
            Thread {
                val ms = DnsHelper.measurePingMs(entry.host)
                runOnUiThread {
                    pingResults[entry.host] = ms
                    updateUi()
                }
            }.start()
        }
    }

    private fun renderServerList(mode: String, activeServer: String, selectedServer: String) {
        serverListContainer.removeAllViews()
        val entries = DnsHelper.getServerEntries(this)

        for (entry in entries) {
            val isCurrentlyActive = (mode == "hostname") && activeServer.equals(entry.host, true)
            val isSelected = selectedServer.equals(entry.host, true)

            val cardBgColor = when {
                isCurrentlyActive -> "#1B3B2B"
                isSelected -> "#1A2B3C"
                else -> "#1E1E1E"
            }
            val cardStrokeColor = when {
                isCurrentlyActive -> "#4CAF50"
                isSelected -> "#42A5F5"
                else -> "#333333"
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(32, 26, 24, 26)
                background = roundedBg(cardBgColor, cardStrokeColor)
                isClickable = true
                setOnClickListener {
                    DnsHelper.saveSelectedServer(this@MainActivity, entry.host)
                    if (DnsHelper.setDnsMode(this@MainActivity, "hostname", entry.host)) {
                        Toast.makeText(this@MainActivity, "Подключен: ${entry.host}", Toast.LENGTH_SHORT).show()
                    } else showPermissionWarning()
                    updateUi()
                }
            }

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }

            val statusIcon = when {
                isCurrentlyActive -> "🟢 "
                isSelected -> "🔘 "
                else -> "⚪ "
            }

            val titleLine = if (entry.label.isNotEmpty()) "$statusIcon${entry.label}" else "$statusIcon${entry.host}"
            val tvTitle = TextView(this).apply {
                text = titleLine
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
            }
            textCol.addView(tvTitle)

            val pingState = pingResults[entry.host]
            val pingText = when (pingState) {
                null -> ""
                -2L -> "  •  ⏳ проверка..."
                -1L -> "  •  ❌ недоступен"
                else -> "  •  ⚡ $pingState мс"
            }

            val subLine = buildString {
                if (entry.label.isNotEmpty()) append(entry.host)
                if (isCurrentlyActive) append(" (Подключен)")
                else if (isSelected) append(" (Выбран)")
                append(pingText)
            }

            if (subLine.isNotEmpty()) {
                val tvSub = TextView(this).apply {
                    text = subLine
                    textSize = 13f
                    setTextColor(Color.parseColor("#B0BEC5"))
                    setPadding(0, 6, 0, 0)
                }
                textCol.addView(tvSub)
            }

            val deleteBtn = Button(this).apply {
                text = "✖"
                textSize = 14f
                setTextColor(Color.parseColor("#EF5350"))
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener {
                    DnsHelper.removeServerFromList(this@MainActivity, entry.host)
                    Toast.makeText(this@MainActivity, "Удален: ${entry.host}", Toast.LENGTH_SHORT).show()
                    updateUi()
                }
            }

            row.addView(textCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            row.addView(deleteBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

            serverListContainer.addView(row, params(bottomMargin = 16))
        }
    }

    private fun createStyledButton(textStr: String, bgHex: String): Button {
        return Button(this).apply {
            text = textStr
            setTextColor(Color.WHITE)
            isAllCaps = false
            setPadding(24, 28, 24, 28)
            background = roundedBg(bgHex, null, 28f)
        }
    }

    private fun roundedBg(fillHex: String, strokeHex: String?, radius: Float = 32f): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = radius
            setColor(Color.parseColor(fillHex))
            if (strokeHex != null) {
                setStroke(3, Color.parseColor(strokeHex))
            }
        }
    }

    private fun showPermissionWarning() {
        Toast.makeText(this, "Выдайте разрешение ADB (инструкция внизу)", Toast.LENGTH_LONG).show()
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
        val mode = DnsHelper.getDnsMode(this)
        val hasPerm = DnsHelper.hasPermission(this)
        val sysServer = DnsHelper.getCurrentSystemServer(this)
        val selectedServer = DnsHelper.getSavedServer(this)

        if (sysServer.isNotEmpty()) {
            val currentList = DnsHelper.getServerEntries(this)
            if (currentList.none { it.host.equals(sysServer, true) }) {
                DnsHelper.addServerToList(this, sysServer, "📌 Системный")
            }
        }

        val activeServer = sysServer.ifEmpty { selectedServer }

        when (mode) {
            "hostname" -> {
                statusTitle.text = "🟢 ПЕРСОНАЛЬНЫЙ DNS ВКЛЮЧЕН"
                statusTitle.setTextColor(Color.parseColor("#81C784"))
                statusSubtitle.text = DnsHelper.getLabelForHost(this, activeServer)
                statusSubtitle.setTextColor(Color.parseColor("#E8F5E9"))
                statusCard.background = roundedBg("#1B3B2B", "#4CAF50", 40f)
            }
            "opportunistic" -> {
                statusTitle.text = "🔄 РЕЖИМ: АВТОМАТИЧЕСКИ"
                statusTitle.setTextColor(Color.parseColor("#64B5F6"))
                statusSubtitle.text = "Системный авто-выбор DNS (выбран: $selectedServer)"
                statusSubtitle.setTextColor(Color.parseColor("#E3F2FD"))
                statusCard.background = roundedBg("#1A2B3C", "#42A5F5", 40f)
            }
            else -> {
                statusTitle.text = "🔴 ПЕРСОНАЛЬНЫЙ DNS ВЫКЛЮЧЕН"
                statusTitle.setTextColor(Color.parseColor("#E57373"))
                statusSubtitle.text = "Готов к включению: $selectedServer"
                statusSubtitle.setTextColor(Color.parseColor("#FFEBEE"))
                statusCard.background = roundedBg("#3B1B1B", "#EF5350", 40f)
            }
        }

        val isCycle = DnsHelper.isTileCycleMode(this)
        btnTileBehavior.text = if (isCycle) {
            "🎛️ Кнопка в шторке: По кругу (Сервер 1 → Сервер 2 → Выкл)"
        } else {
            "🎛️ Кнопка в шторке: Обычный тумблер (Вкл ↔ Выкл)"
        }

        renderServerList(mode, activeServer, selectedServer)
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
        val mode = DnsHelper.getDnsMode(this)
        val isCycle = DnsHelper.isTileCycleMode(this)

        val ok = if (!isCycle) {
            // Обычный режим: Вкл <-> Выкл
            val enable = (mode != "hostname")
            DnsHelper.setDnsMode(this, if (enable) "hostname" else "off", DnsHelper.getSavedServer(this))
        } else {
            // Циклический режим по всем серверам из списка -> Выкл
            val entries = DnsHelper.getServerEntries(this)
            if (entries.isEmpty()) {
                DnsHelper.setDnsMode(this, if (mode == "hostname") "off" else "hostname")
            } else if (mode != "hostname") {
                val first = entries.first().host
                DnsHelper.setDnsMode(this, "hostname", first)
            } else {
                val current = DnsHelper.getCurrentSystemServer(this)
                val idx = entries.indexOfFirst { it.host.equals(current, true) }
                if (idx in 0 until entries.size - 1) {
                    val nextHost = entries[idx + 1].host
                    DnsHelper.setDnsMode(this, "hostname", nextHost)
                } else {
                    DnsHelper.setDnsMode(this, "off")
                }
            }
        }

        if (!ok) {
            Toast.makeText(this, "Выдайте разрешение ADB в приложении DNS Switcher", Toast.LENGTH_LONG).show()
        }
        refreshTile()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val mode = DnsHelper.getDnsMode(this)

        when (mode) {
            "hostname" -> {
                val server = DnsHelper.getCurrentSystemServer(this).ifEmpty { DnsHelper.getSavedServer(this) }
                val entry = DnsHelper.getServerEntries(this).find { it.host.equals(server, true) }
                val shortLabel = if (entry != null && entry.label.isNotEmpty()) entry.label else server
                tile.state = Tile.STATE_ACTIVE
                tile.label = "DNS: Вкл"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = shortLabel
                }
            }
            "opportunistic" -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "DNS: Авто"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Автоматически"
                }
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "DNS: Выкл"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = DnsHelper.getSavedServer(this)
                }
            }
        }
        tile.updateTile()
    }
}
