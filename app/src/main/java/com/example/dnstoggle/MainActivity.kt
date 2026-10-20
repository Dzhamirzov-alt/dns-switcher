package com.example.dnstoggle

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

data class DnsServer(
    var name: String,
    var host: String,
    var ping: Int = -1
)

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private val serverList = mutableListOf<DnsServer>()
    private lateinit var listContainer: LinearLayout
    private lateinit var statusBanner: TextView
    private lateinit var netStatusText: TextView
    private lateinit var modeCard: LinearLayout
    private lateinit var modeGroup: RadioGroup
    private lateinit var rbOff: RadioButton
    private lateinit var rbAuto: RadioButton
    private lateinit var rbOn: RadioButton
    private lateinit var modeTitle: TextView
    private lateinit var titleApp: TextView
    private var isUpdatingUi = false

    // Конфигурация цветовых тем
    private var currentThemeKey = "green"
    private var accentColorHex = "#00E676"
    private var accentCardBgHex = "#0D2318"

    companion object {
        private const val REQ_EXPORT_FILE = 1001
        private const val REQ_IMPORT_FILE = 1002
        private const val REQ_SHIZUKU_PERMISSION = 1003
    }

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQ_SHIZUKU_PERMISSION) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                grantPermissionViaShizuku()
            } else {
                runOnUiThread {
                    Toast.makeText(this, "Доступ к Shizuku отклонён", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val defaultServers = listOf(
        DnsServer("Comss.one", "dns.comss.one"),
        DnsServer("Мобильная -1", "comss.dns.controld.com"),
        DnsServer("📌 Malw.link", "dns.malw.link"),
        DnsServer("🌍 GeoHide", "geohide.ru"),
        DnsServer("Мобильная -2", "dns.astracat.network"),
        DnsServer("Xbox", "xbox-dns.ru"),
        DnsServer("Quad9", "dns.quad9.net")
    )

    private val popularCatalog = listOf(
        DnsServer("AdGuard DNS (Без рекламы)", "dns.adguard-dns.com"),
        DnsServer("AdGuard Семейный", "family.adguard-dns.com"),
        DnsServer("Cloudflare (Быстрый)", "one.one.one.one"),
        DnsServer("Cloudflare Malware Block", "security.cloudflare-dns.com"),
        DnsServer("Google Public DNS", "dns.google"),
        DnsServer("Control D (Без рекламы)", "freedns.controld.com"),
        DnsServer("Mullvad DNS (Приватность)", "dns.mullvad.net"),
        DnsServer("CleanBrowsing (Фильтр)", "security-filter-dns.cleanbrowsing.org"),
        DnsServer("Comss.one", "dns.comss.one"),
        DnsServer("Мобильная -1", "comss.dns.controld.com"),
        DnsServer("📌 Malw.link", "dns.malw.link"),
        DnsServer("🌍 GeoHide", "geohide.ru"),
        DnsServer("Мобильная -2", "dns.astracat.network"),
        DnsServer("Xbox", "xbox-dns.ru"),
        DnsServer("Quad9", "dns.quad9.net")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("dns_prefs", Context.MODE_PRIVATE)
        loadThemePreferences()
        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        } catch (_: Throwable) {}
        loadServers()
        setupUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        } catch (_: Throwable) {}
    }

    override fun onResume() {
        super.onResume()
        refreshSystemState()
        checkNetworkAndTestAll()
    }

    private fun loadThemePreferences() {
        currentThemeKey = prefs.getString("app_theme", "green") ?: "green"
        when (currentThemeKey) {
            "green" -> {
                accentColorHex = "#00E676"
                accentCardBgHex = "#0D2318"
            }
            "blue" -> {
                accentColorHex = "#00B0FF"
                accentCardBgHex = "#0A1D2C"
            }
            "purple" -> {
                accentColorHex = "#E040FB"
                accentCardBgHex = "#230A2C"
            }
        }
    }

    private fun saveAndApplyTheme(themeKey: String) {
        currentThemeKey = themeKey
        prefs.edit().putString("app_theme", themeKey).apply()
        loadThemePreferences()
        titleApp.setTextColor(Color.parseColor(accentColorHex))
        refreshSystemState()
        Toast.makeText(this, "Тема успешно изменена!", Toast.LENGTH_SHORT).show()
    }

    private fun hasPermission(): Boolean {
        return checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestShizukuOneClick() {
        try {
            if (!Shizuku.pingBinder()) {
                Toast.makeText(this, "Shizuku не запущен! Откройте инструкцию для настройки.", Toast.LENGTH_LONG).show()
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                grantPermissionViaShizuku()
            } else {
                Shizuku.requestPermission(REQ_SHIZUKU_PERMISSION)
            }
        } catch (e: Throwable) {
            Toast.makeText(this, "Ошибка Shizuku: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun grantPermissionViaShizuku() {
        Thread {
            try {
                val method = Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                method.isAccessible = true
                val cmd = arrayOf("pm", "grant", packageName, android.Manifest.permission.WRITE_SECURE_SETTINGS)
                val process = method.invoke(null, cmd, null, null) as Process
                val exitCode = process.waitFor()

                runOnUiThread {
                    refreshSystemState()
                    if (hasPermission()) {
                        Toast.makeText(this, "⚡ Права успешно выданы через Shizuku!", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "Не удалось выдать права (код: $exitCode)", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "Ошибка Shizuku: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun setupUI() {
        val rootScroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#08080A"))
            isFillViewport = true
        }

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 40, 36, 40)
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 0, 0, 16)
            layoutParams = params
        }

        titleApp = TextView(this).apply {
            text = "⚡ DNS SWITCHER PRO"
            textSize = 20f
            setTextColor(Color.parseColor(accentColorHex))
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val settingsBtn = TextView(this).apply {
            text = "⚙️"
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(20, 10, 20, 10)
            background = createRoundRect("#121418", 20f, "#1E222B")
            setOnClickListener { showSettingsDialog() }
        }

        topBar.addView(titleApp)
        topBar.addView(settingsBtn)
        mainLayout.addView(topBar)

        netStatusText = TextView(this).apply {
            text = "Инициализация сети..."
            textSize = 13f
            setTextColor(Color.parseColor("#B0BEC5"))
            setPadding(28, 20, 28, 20)
            background = createRoundRect("#121418", 22f, "#1E222B")
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 0, 0, 16)
            layoutParams = params
        }
        mainLayout.addView(netStatusText)

        statusBanner = TextView(this).apply {
            textSize = 13f
            setPadding(30, 24, 30, 24)
            background = createRoundRect("#261A00", 24f, "#FFB300")
            setTextColor(Color.parseColor("#FFD54F"))
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 0, 0, 20)
            layoutParams = params
            setOnClickListener { showAdbDialog() }
        }
        mainLayout.addView(statusBanner)

        modeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 28, 32, 28)
            background = createRoundRect("#121418", 30f, "#1E222B")
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 0, 0, 24)
            layoutParams = params
        }

        modeTitle = TextView(this).apply {
            text = "СИСТЕМНЫЙ РЕЖИМ"
            textSize = 12f
            setTextColor(Color.parseColor("#90A4AE"))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 14)
        }
        modeCard.addView(modeTitle)

        modeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            weightSum = 3f
        }

        rbOff = RadioButton(this).apply {
            text = "Выкл"
            textSize = 15f
            setPadding(12, 0, 0, 0)
            layoutParams = RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        rbAuto = RadioButton(this).apply {
            text = "Авто"
            textSize = 15f
            setPadding(12, 0, 0, 0)
            layoutParams = RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        rbOn = RadioButton(this).apply {
            text = "Вкл"
            textSize = 15f
            setPadding(12, 0, 0, 0)
            layoutParams = RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        modeGroup.addView(rbOff)
        modeGroup.addView(rbAuto)
        modeGroup.addView(rbOn)

        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            if (isUpdatingUi) return@setOnCheckedChangeListener
            when (checkedId) {
                rbOff.id -> applyDnsMode("off", null)
                rbAuto.id -> applyDnsMode("opportunistic", null)
                rbOn.id -> {
                    val currentHost = getActiveHost()
                    val lastSaved = prefs.getString("last_active_host", null)
                    val target = when {
                        currentHost.isNotEmpty() -> currentHost
                        !lastSaved.isNullOrEmpty() -> lastSaved
                        else -> if (serverList.isNotEmpty()) serverList[0].host else "dns.comss.one"
                    }
                    applyDnsMode("hostname", target)
                }
            }
        }
        modeCard.addView(modeGroup)
        mainLayout.addView(modeCard)

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 0, 0, 16)
            layoutParams = params
        }

        val listHeader = TextView(this).apply {
            text = "ДОСТУПНЫЕ СЕРВЕРЫ"
            textSize = 12f
            setTextColor(Color.parseColor("#78909C"))
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val pingBtn = Button(this).apply {
            text = "📡 Проверка пинг (порт 853)"
            textSize = 11f
            setTextColor(Color.WHITE)
            isAllCaps = false
            setPadding(22, 0, 22, 0)
            background = createRoundRect("#00796B", 20f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 88)
            setOnClickListener { checkNetworkAndTestAll() }
        }

        headerRow.addView(listHeader)
        headerRow.addView(pingBtn)
        mainLayout.addView(headerRow)

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        mainLayout.addView(listContainer)

        rootScroll.addView(mainLayout)
        setContentView(rootScroll)
    }

    private fun createHorizontalDivider(): View {
        return View(this).apply {
            setBackgroundColor(Color.parseColor("#262B36"))
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 3)
            params.setMargins(12, 4, 12, 4)
            layoutParams = params
        }
    }

    private fun showSettingsDialog() {
        var dialogInstance: AlertDialog? = null

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 28, 36, 28)
            background = createRoundRect("#0F1117", 36f, "#1E222B")
        }

        val headerBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = createRoundRect("#121418", 22f, "#1E222B")
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 115)
            params.setMargins(0, 0, 0, 24)
            layoutParams = params
        }

        val titleView = TextView(this).apply {
            text = "⚙️ Настройки приложения"
            textSize = 17f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        headerBox.addView(titleView)
        rootLayout.addView(headerBox)

        val options = listOf(
            Pair("➕ Добавить свой сервер", "Ручной ввод хоста и имени"),
            Pair("📚 Популярные DNS-серверы", "Каталог проверенных серверов"),
            Pair("💾 Бэкап и сброс настроек", "Экспорт/импорт JSON, сброс списка"),
            Pair("🎨 Выбор темы оформления", "Сменить неоновый стиль интерфейса"),
            Pair("🎛️ Режим шторки", if (prefs.getBoolean("tile_simple_toggle", true)) "Сейчас: Обычный тумблер (Вкл/Выкл)" else "Сейчас: Циклический перебор")
        )

        for ((index, item) in options.withIndex()) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(28, 22, 28, 22)
                background = createRoundRect("#121418", 22f, "#1E222B")
                val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                params.setMargins(0, 0, 0, 14)
                layoutParams = params

                setOnClickListener {
                    dialogInstance?.dismiss()
                    when (index) {
                        0 -> showEditServerDialog(null)
                        1 -> showPresetsDialog()
                        2 -> showBackupDialog()
                        3 -> showThemeChooserDialog()
                        4 -> {
                            val isToggle = prefs.getBoolean("tile_simple_toggle", true)
                            prefs.edit().putBoolean("tile_simple_toggle", !isToggle).apply()
                            Toast.makeText(this@MainActivity, "Режим шторки изменен", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            val titleTv = TextView(this).apply {
                text = item.first
                textSize = 15f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
            }

            val subTv = TextView(this).apply {
                text = item.second
                textSize = 12f
                setTextColor(Color.parseColor("#90A4AE"))
                setPadding(0, 4, 0, 0)
            }

            card.addView(titleTv)
            card.addView(subTv)
            rootLayout.addView(card)
        }

        val closeBtn = TextView(this).apply {
            text = "Закрыть"
            textSize = 15f
            setTextColor(Color.parseColor(accentColorHex))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = createRoundRect("#121418", 22f, "#1E222B")
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 110)
            params.setMargins(0, 10, 0, 0)
            layoutParams = params
            setOnClickListener { dialogInstance?.dismiss() }
        }
        rootLayout.addView(closeBtn)

        dialogInstance = AlertDialog.Builder(this)
            .setView(rootLayout)
            .create()

        dialogInstance?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialogInstance?.show()
    }

    // Диалог выбора цветовой темы
    private fun showThemeChooserDialog() {
        var themeDialog: AlertDialog? = null

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 28, 36, 28)
            background = createRoundRect("#0F1117", 36f, "#1E222B")
        }

        val headerBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = createRoundRect("#121418", 22f, "#1E222B")
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 110)
            params.setMargins(0, 0, 0, 16)
            layoutParams = params
        }

        val titleView = TextView(this).apply {
            text = "🎨 Выбор темы оформления"
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        headerBox.addView(titleView)
        rootLayout.addView(headerBox)

        val listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundRect("#121418", 22f, "#1E222B")
            setPadding(16, 8, 16, 8)
        }

        val themes = listOf(
            Triple("green", "🟢 Neon Green (Классический неон)", "#00E676"),
            Triple("blue", "🔵 Cyber Blue (Неоновый голубой)", "#00B0FF"),
            Triple("purple", "🟣 Synthwave Purple (Фиолетовый кибер)", "#E040FB")
        )

        for ((index, item) in themes.withIndex()) {
            val isSelected = (currentThemeKey == item.first)
            val prefix = if (isSelected) "➔ " else ""

            val themeTv = TextView(this).apply {
                text = "$prefix${item.second}"
                textSize = 15f
                setTextColor(if (isSelected) Color.parseColor(item.third) else Color.WHITE)
                typeface = if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setPadding(20, 26, 20, 26)
                setOnClickListener {
                    themeDialog?.dismiss()
                    saveAndApplyTheme(item.first)
                }
            }
            listBox.addView(themeTv)

            if (index < themes.size - 1) {
                listBox.addView(createHorizontalDivider())
            }
        }

        rootLayout.addView(listBox)

        val backBtn = TextView(this).apply {
            text = "Назад к настройкам"
            textSize = 15f
            setTextColor(Color.parseColor(accentColorHex))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = createRoundRect("#121418", 22f, "#1E222B")
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 105)
            params.setMargins(0, 16, 0, 0)
            layoutParams = params
            setOnClickListener {
                themeDialog?.dismiss()
                showSettingsDialog()
            }
        }
        rootLayout.addView(backBtn)

        themeDialog = AlertDialog.Builder(this)
            .setView(rootLayout)
            .create()

        themeDialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        themeDialog?.show()
    }

    private fun createRoundRect(colorHex: String, radius: Float, strokeColor: String? = null): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(colorHex))
            cornerRadius = radius
            if (strokeColor != null) {
                setStroke(3, Color.parseColor(strokeColor))
            }
        }
    }

    private fun sanitizeHost(raw: String): String {
        var clean = raw.trim().lowercase()
        val prefixes = listOf("https://", "http://", "tls://", "quic://", "sdns://")
        for (p in prefixes) {
            if (clean.startsWith(p)) clean = clean.removePrefix(p)
        }
        if (clean.contains("/")) clean = clean.substringBefore("/")
        if (clean.contains(":")) clean = clean.substringBefore(":")
        return clean.trim()
    }

    private fun getActiveMode(): String {
        return Settings.Global.getString(contentResolver, "private_dns_mode") ?: "off"
    }

    private fun getActiveHost(): String {
        return Settings.Global.getString(contentResolver, "private_dns_specifier") ?: ""
    }

    private fun applyDnsMode(mode: String, host: String?) {
        if (!hasPermission()) {
            showAdbDialog()
            refreshSystemState()
            return
        }
        try {
            Settings.Global.putString(contentResolver, "private_dns_mode", mode)
            if (mode == "hostname" && !host.isNullOrEmpty()) {
                Settings.Global.putString(contentResolver, "private_dns_specifier", host)
                prefs.edit().putString("last_active_host", host).apply()
            }
            refreshSystemState()
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshSystemState() {
        isUpdatingUi = true
        val mode = getActiveMode()

        if (hasPermission()) {
            statusBanner.visibility = View.GONE
        } else {
            statusBanner.visibility = View.VISIBLE
            statusBanner.text = "⚠️ Требуется разрешение WRITE_SECURE_SETTINGS.\nНажмите здесь для выдачи через Shizuku в 1 клик."
        }

        val inactiveColor = Color.parseColor("#78909C")
        val redColor = Color.parseColor("#FF5252")
        val activeAccentColor = Color.parseColor(accentColorHex)

        rbOff.setTextColor(inactiveColor)
        rbOff.typeface = Typeface.DEFAULT
        rbOff.buttonTintList = ColorStateList.valueOf(inactiveColor)

        rbAuto.setTextColor(inactiveColor)
        rbAuto.typeface = Typeface.DEFAULT
        rbAuto.buttonTintList = ColorStateList.valueOf(inactiveColor)

        rbOn.setTextColor(inactiveColor)
        rbOn.typeface = Typeface.DEFAULT
        rbOn.buttonTintList = ColorStateList.valueOf(inactiveColor)

        when (mode) {
            "off" -> {
                rbOff.isChecked = true
                rbOff.setTextColor(redColor)
                rbOff.typeface = Typeface.DEFAULT_BOLD
                rbOff.buttonTintList = ColorStateList.valueOf(redColor)

                modeCard.background = createRoundRect("#1F1214", 30f, "#FF5252")
                modeTitle.text = "СИСТЕМНЫЙ РЕЖИМ: ОТКЛЮЧЕН"
                modeTitle.setTextColor(redColor)
            }
            "opportunistic" -> {
                rbAuto.isChecked = true
                rbAuto.setTextColor(activeAccentColor)
                rbAuto.typeface = Typeface.DEFAULT_BOLD
                rbAuto.buttonTintList = ColorStateList.valueOf(activeAccentColor)

                modeCard.background = createRoundRect(accentCardBgHex, 30f, accentColorHex)
                modeTitle.text = "СИСТЕМНЫЙ РЕЖИМ: АВТО"
                modeTitle.setTextColor(activeAccentColor)
            }
            "hostname" -> {
                rbOn.isChecked = true
                rbOn.setTextColor(activeAccentColor)
                rbOn.typeface = Typeface.DEFAULT_BOLD
                rbOn.buttonTintList = ColorStateList.valueOf(activeAccentColor)

                modeCard.background = createRoundRect(accentCardBgHex, 30f, accentColorHex)
                modeTitle.text = "СИСТЕМНЫЙ РЕЖИМ: ВКЛЮЧЕН"
                modeTitle.setTextColor(activeAccentColor)
            }
            else -> {
                rbOff.isChecked = true
                rbOff.setTextColor(redColor)
                rbOff.typeface = Typeface.DEFAULT_BOLD
                rbOff.buttonTintList = ColorStateList.valueOf(redColor)

                modeCard.background = createRoundRect("#1F1214", 30f, "#FF5252")
                modeTitle.text = "СИСТЕМНЫЙ РЕЖИМ: ОТКЛЮЧЕН"
                modeTitle.setTextColor(redColor)
            }
        }

        isUpdatingUi = false
        renderServerList()
    }

    private fun renderServerList() {
        listContainer.removeAllViews()
        val activeMode = getActiveMode()
        val activeHost = getActiveHost()

        for ((index, server) in serverList.withIndex()) {
            val isSelected = (activeMode == "hostname" && activeHost.equals(server.host, ignoreCase = true))

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(32, 26, 20, 26)
                background = if (isSelected) {
                    createRoundRect(accentCardBgHex, 26f, accentColorHex)
                } else {
                    createRoundRect("#121418", 26f, "#1E222B")
                }
                val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                params.setMargins(0, 0, 0, 14)
                layoutParams = params

                setOnClickListener {
                    applyDnsMode("hostname", server.host)
                    Toast.makeText(this@MainActivity, "Подключен: ${server.name}", Toast.LENGTH_SHORT).show()
                }
            }

            val infoLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val indicator = if (isSelected) "🟢" else "⚪"
            val nameTv = TextView(this).apply {
                text = "$indicator ${server.name}"
                textSize = 16f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
            }

            val statusSuffix = if (isSelected) " (Активен)" else ""
            val pingPart = when (server.ping) {
                -1 -> ""
                -2 -> " • ⏳..."
                -3 -> " • ❌ Блок (853)"
                else -> " • ⚡ ${server.ping} мс"
            }

            val subTv = TextView(this).apply {
                text = "${server.host}$statusSuffix$pingPart"
                textSize = 13f
                setTextColor(
                    if (server.ping == -3) Color.parseColor("#FF5252")
                    else Color.parseColor("#90A4AE")
                )
                setPadding(0, 6, 0, 0)
            }

            infoLayout.addView(nameTv)
            infoLayout.addView(subTv)
            card.addView(infoLayout)

            val editBtn = TextView(this).apply {
                text = "✏️"
                textSize = 15f
                gravity = Gravity.CENTER
                val params = LinearLayout.LayoutParams(88, 88)
                params.setMargins(6, 0, 6, 0)
                layoutParams = params
                setOnClickListener { showEditServerDialog(index) }
            }

            val delBtn = TextView(this).apply {
                text = "✖️"
                textSize = 14f
                setTextColor(Color.parseColor("#FF5252"))
                gravity = Gravity.CENTER
                val params = LinearLayout.LayoutParams(88, 88)
                layoutParams = params
                setOnClickListener {
                    serverList.removeAt(index)
                    saveServers()
                    renderServerList()
                }
            }

            card.addView(editBtn)
            card.addView(delBtn)
            listContainer.addView(card)
        }
    }

    private fun checkNetworkAndTestAll() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(net)

        val netType = when {
            caps == null -> "Нет сети ❌"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi 📶"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Мобильная сеть 📱"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN 🔒"
            else -> "Сеть активна 🌐"
        }
        netStatusText.text = "$netType • Проверка порта 853..."

        for (s in serverList) s.ping = -2
        renderServerList()

        Thread {
            val snapshot = serverList.toList()
            for (server in snapshot) {
                val startTime = System.currentTimeMillis()
                var resultPing = -3
                val socket = Socket()
                try {
                    socket.connect(InetSocketAddress(server.host, 853), 2500)
                    resultPing = (System.currentTimeMillis() - startTime).toInt()
                } catch (_: Exception) {
                    resultPing = -3
                } finally {
                    try {
                        socket.close()
                    } catch (_: Exception) {}
                }
                server.ping = resultPing
            }

            runOnUiThread {
                serverList.sortWith(Comparator { a, b ->
                    val pA = if (a.ping > 0) a.ping else Int.MAX_VALUE
                    val pB = if (b.ping > 0) b.ping else Int.MAX_VALUE
                    pA.compareTo(pB)
                })
                saveServers()
                val workingCount = serverList.count { it.ping > 0 }
                netStatusText.text = "$netType • Доступно DoT (853): $workingCount из ${serverList.size}"
                renderServerList()
            }
        }.start()
    }

    private fun showEditServerDialog(editIndex: Int?) {
        val idx = editIndex ?: -1
        val isEdit = idx >= 0
        val current = if (isEdit) serverList[idx] else null

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 20)
        }

        val nameInput = EditText(this).apply {
            hint = "Название (например, Comss.one)"
            setText(current?.name ?: "")
        }

        val hostInput = EditText(this).apply {
            hint = "Адрес хоста (например, dns.comss.one)"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(current?.host ?: "")
        }

        val noteTv = TextView(this).apply {
            text = "💡 Префиксы https:// или tls:// очищаются автоматически."
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, 15, 0, 0)
        }

        layout.addView(nameInput)
        layout.addView(hostInput)
        layout.addView(noteTv)

        AlertDialog.Builder(this)
            .setTitle(if (isEdit) "Редактировать сервер" else "Добавить новый сервер")
            .setView(layout)
            .setPositiveButton("Сохранить") { _, _ ->
                val cleanHost = sanitizeHost(hostInput.text.toString())
                val rawName = nameInput.text.toString().trim()
                val cleanName = if (rawName.isEmpty()) cleanHost else rawName

                if (cleanHost.isEmpty()) {
                    Toast.makeText(this, "Введите адрес сервера", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (isEdit) {
                    serverList[idx].name = cleanName
                    serverList[idx].host = cleanHost
                } else {
                    serverList.add(DnsServer(cleanName, cleanHost))
                }
                saveServers()
                checkNetworkAndTestAll()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showPresetsDialog() {
        var dialogInstance: AlertDialog? = null

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 28, 36, 28)
            background = createRoundRect("#0F1117", 36f, "#1E222B")
        }

        val headerBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = createRoundRect("#121418", 22f, "#1E222B")
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 110)
            params.setMargins(0, 0, 0, 16)
            layoutParams = params
        }

        val titleView = TextView(this).apply {
            text = "📚 Популярные DNS-серверы"
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        headerBox.addView(titleView)
        rootLayout.addView(headerBox)

        val scrollView = ScrollView(this).apply {
            val maxHeight = (resources.displayMetrics.heightPixels * 0.50).toInt()
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxHeight)
        }

        val listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundRect("#121418", 22f, "#1E222B")
            setPadding(16, 8, 16, 8)
        }

        for ((index, chosen) in popularCatalog.withIndex()) {
            val itemLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 22, 20, 22)
                setOnClickListener {
                    if (serverList.any { it.host.equals(chosen.host, ignoreCase = true) }) {
                        Toast.makeText(this@MainActivity, "Уже есть в вашем списке", Toast.LENGTH_SHORT).show()
                    } else {
                        serverList.add(DnsServer(chosen.name, chosen.host))
                        saveServers()
                        checkNetworkAndTestAll()
                        Toast.makeText(this@MainActivity, "Добавлено: ${chosen.name}", Toast.LENGTH_SHORT).show()
                        dialogInstance?.dismiss()
                    }
                }
            }

            val nameTv = TextView(this).apply {
                text = chosen.name
                textSize = 15f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
            }

            val hostTv = TextView(this).apply {
                text = chosen.host
                textSize = 13f
                setTextColor(Color.parseColor("#90A4AE"))
                setPadding(0, 4, 0, 0)
            }

            itemLayout.addView(nameTv)
            itemLayout.addView(hostTv)
            listBox.addView(itemLayout)

            if (index < popularCatalog.size - 1) {
                listBox.addView(createHorizontalDivider())
            }
        }

        scrollView.addView(listBox)
        rootLayout.addView(scrollView)

        val addAllBtn = TextView(this).apply {
            text = "➕ Добавить все недостающие"
            textSize = 14f
            setTextColor(Color.parseColor(accentColorHex))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = createRoundRect("#121418", 22f, "#1E222B")
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 105)
            params.setMargins(0, 16, 0, 10)
            layoutParams = params
            setOnClickListener {
                var added = 0
                for (p in popularCatalog) {
                    if (serverList.none { it.host.equals(p.host, ignoreCase = true) }) {
                        serverList.add(DnsServer(p.name, p.host))
                        added++
                    }
                }
                saveServers()
                checkNetworkAndTestAll()
                Toast.makeText(this@MainActivity, "Добавлено серверов: $added", Toast.LENGTH_LONG).show()
                dialogInstance?.dismiss()
            }
        }
        rootLayout.addView(addAllBtn)

        val closeBtn = TextView(this).apply {
            text = "Закрыть"
            textSize = 15f
            setTextColor(Color.parseColor(accentColorHex))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = createRoundRect("#121418", 22f, "#1E222B")
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 105)
            layoutParams = params
            setOnClickListener { dialogInstance?.dismiss() }
        }
        rootLayout.addView(closeBtn)

        dialogInstance = AlertDialog.Builder(this)
            .setView(rootLayout)
            .create()

        dialogInstance?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialogInstance?.show()
    }

    private fun buildBackupJsonString(): String {
        val jsonArray = JSONArray()
        for (s in serverList) {
            val obj = JSONObject()
            obj.put("name", s.name)
            obj.put("host", s.host)
            jsonArray.put(obj)
        }
        return jsonArray.toString(2)
    }

    private fun importServersFromJsonString(jsonText: String) {
        try {
            val arr = JSONArray(jsonText)
            var imported = 0
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val name = obj.optString("name")
                val host = sanitizeHost(obj.optString("host"))
                if (host.isNotEmpty() && serverList.none { it.host.equals(host, true) }) {
                    val finalName = if (name.isEmpty()) host else name
                    serverList.add(DnsServer(finalName, host))
                    imported++
                }
            }
            saveServers()
            checkNetworkAndTestAll()
            Toast.makeText(this, "✅ Импортировано серверов: $imported", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Ошибка формата JSON!", Toast.LENGTH_LONG).show()
        }
    }

    private fun exportBackupToFile() {
        try {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "dns_switcher_backup.json")
            }
            startActivityForResult(intent, REQ_EXPORT_FILE)
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun importBackupFromFile() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(intent, REQ_IMPORT_FILE)
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        val uri: Uri = data.data ?: return

        if (requestCode == REQ_EXPORT_FILE) {
            try {
                val jsonStr = buildBackupJsonString()
                val outStream = contentResolver.openOutputStream(uri)
                if (outStream != null) {
                    outStream.write(jsonStr.toByteArray(Charsets.UTF_8))
                    outStream.flush()
                    outStream.close()
                    Toast.makeText(this, "✅ Бэкап сохранён в файл!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else if (requestCode == REQ_IMPORT_FILE) {
            try {
                val inStream = contentResolver.openInputStream(uri)
                if (inStream != null) {
                    val reader = BufferedReader(InputStreamReader(inStream, Charsets.UTF_8))
                    val sb = StringBuilder()
                    var line: String? = reader.readLine()
                    while (line != null) {
                        sb.append(line).append("\n")
                        line = reader.readLine()
                    }
                    reader.close()
                    inStream.close()
                    importServersFromJsonString(sb.toString())
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showBackupDialog() {
        var dialogInstance: AlertDialog? = null

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 28, 36, 28)
            background = createRoundRect("#0F1117", 36f, "#1E222B")
        }

        val headerBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = createRoundRect("#121418", 22f, "#1E222B")
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 110)
            params.setMargins(0, 0, 0, 16)
            layoutParams = params
        }

        val titleView = TextView(this).apply {
            text = "💾 Бэкап и восстановление"
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        headerBox.addView(titleView)
        rootLayout.addView(headerBox)

        val listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundRect("#121418", 22f, "#1E222B")
            setPadding(16, 8, 16, 8)
        }

        val options = listOf(
            "💾 Сохранить бэкап в файл (.json)",
            "📂 Загрузить бэкап из файла (.json)",
            "📤 Скопировать JSON в буфер",
            "📥 Вставить JSON из буфера",
            "🔄 Сбросить к стандартным 7 серверам"
        )

        for ((which, label) in options.withIndex()) {
            val itemTv = TextView(this).apply {
                text = label
                textSize = 15f
                setTextColor(Color.WHITE)
                setPadding(20, 26, 20, 26)
                setOnClickListener {
                    dialogInstance?.dismiss()
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    when (which) {
                        0 -> exportBackupToFile()
                        1 -> importBackupFromFile()
                        2 -> {
                            clipboard.setPrimaryClip(ClipData.newPlainText("dns_backup", buildBackupJsonString()))
                            Toast.makeText(this@MainActivity, "Скопировано в буфер!", Toast.LENGTH_LONG).show()
                        }
                        3 -> {
                            val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                            if (clipText.isEmpty()) {
                                Toast.makeText(this@MainActivity, "Буфер пуст!", Toast.LENGTH_SHORT).show()
                            } else {
                                importServersFromJsonString(clipText)
                            }
                        }
                        4 -> {
                            serverList.clear()
                            for (d in defaultServers) {
                                serverList.add(DnsServer(d.name, d.host))
                            }
                            saveServers()
                            checkNetworkAndTestAll()
                            Toast.makeText(this@MainActivity, "Список сброшен!", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            listBox.addView(itemTv)

            if (which < options.size - 1) {
                listBox.addView(createHorizontalDivider())
            }
        }

        rootLayout.addView(listBox)

        val closeBtn = TextView(this).apply {
            text = "Отмена"
            textSize = 15f
            setTextColor(Color.parseColor(accentColorHex))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = createRoundRect("#121418", 22f, "#1E222B")
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 105)
            params.setMargins(0, 16, 0, 0)
            layoutParams = params
            setOnClickListener { dialogInstance?.dismiss() }
        }
        rootLayout.addView(closeBtn)

        dialogInstance = AlertDialog.Builder(this)
            .setView(rootLayout)
            .create()

        dialogInstance?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialogInstance?.show()
    }

    private fun showAdbDialog() {
        var dialogInstance: AlertDialog? = null

        val adbCommand = "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"
        val shizukuCommand = "pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 28, 36, 28)
            background = createRoundRect("#0F1117", 36f, "#1E222B")
        }

        val headerBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = createRoundRect("#121418", 22f, "#1E222B")
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 110)
            params.setMargins(0, 0, 0, 16)
            layoutParams = params
        }

        val titleView = TextView(this).apply {
            text = "🔑 Системное разрешение"
            textSize = 17f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        headerBox.addView(titleView)
        rootLayout.addView(headerBox)

        val infoBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundRect("#121418", 22f, "#1E222B")
            setPadding(28, 22, 28, 22)
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 0, 0, 16)
            layoutParams = params
        }

        val msgTv = TextView(this).apply {
            text = "Вы можете выдать разрешение в 1 клик через Shizuku или через консоль:\n\n" +
                    "ADB:\n$adbCommand\n\n" +
                    "Терминал (aShell/LADB):\n$shizukuCommand"
            textSize = 13f
            setTextColor(Color.parseColor("#CFD8DC"))
        }
        infoBox.addView(msgTv)
        rootLayout.addView(infoBox)

        val shizukuGrantBtn = TextView(this).apply {
            text = "⚡ Выдать через Shizuku (1 клик)"
            textSize = 14f
            setTextColor(Color.parseColor(accentColorHex))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = createRoundRect(accentCardBgHex, 22f, accentColorHex)
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 105)
            params.setMargins(0, 0, 0, 12)
            layoutParams = params
            setOnClickListener {
                dialogInstance?.dismiss()
                requestShizukuOneClick()
            }
        }
        rootLayout.addView(shizukuGrantBtn)

        val guideBtn = TextView(this).apply {
            text = "📖 Инструкция по скачиванию и настройке Shizuku"
            textSize = 13f
            setTextColor(Color.parseColor("#00B0FF"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(20, 0, 20, 0)
            background = createRoundRect("#101A24", 22f, "#00B0FF")
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 115)
            params.setMargins(0, 0, 0, 12)
            layoutParams = params
            setOnClickListener {
                dialogInstance?.dismiss()
                showShizukuGuideDialog()
            }
        }
        rootLayout.addView(guideBtn)

        val copyAdbBtn = TextView(this).apply {
            text = "📋 Скопировать команду ADB"
            textSize = 14f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = createRoundRect("#121418", 22f, "#1E222B")
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 105)
            params.setMargins(0, 0, 0, 12)
            layoutParams = params
            setOnClickListener {
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("adb", adbCommand))
                Toast.makeText(this@MainActivity, "Команда скопирована", Toast.LENGTH_SHORT).show()
            }
        }
        rootLayout.addView(copyAdbBtn)

        val closeBtn = TextView(this).apply {
            text = "Закрыть"
            textSize = 15f
            setTextColor(Color.parseColor("#90A4AE"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = createRoundRect("#121418", 22f, "#1E222B")
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 105)
            layoutParams = params
            setOnClickListener { dialogInstance?.dismiss() }
        }
        rootLayout.addView(closeBtn)

        dialogInstance = AlertDialog.Builder(this)
            .setView(rootLayout)
            .create()

        dialogInstance?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialogInstance?.show()
    }

    private fun showShizukuGuideDialog() {
        var guideDialog: AlertDialog? = null

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 28, 36, 28)
            background = createRoundRect("#0F1117", 36f, "#1E222B")
        }

        val headerBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = createRoundRect("#121418", 22f, "#1E222B")
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 110)
            params.setMargins(0, 0, 0, 16)
            layoutParams = params
        }

        val titleView = TextView(this).apply {
            text = "📖 Инструкция по Shizuku"
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        headerBox.addView(titleView)
        rootLayout.addView(headerBox)

        val scrollView = ScrollView(this).apply {
            val maxHeight = (resources.displayMetrics.heightPixels * 0.55).toInt()
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxHeight)
        }

        val stepsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundRect("#121418", 22f, "#1E222B")
            setPadding(24, 20, 24, 20)
        }

        val steps = listOf(
            Pair(
                "ШАГ 1. Скачивание и установка",
                "Установите приложение Shizuku из Google Play или с официального GitHub (кнопки для скачивания находятся внизу этого окна)."
            ),
            Pair(
                "ШАГ 2. Включение режима разработчика",
                "1. Откройте «Настройки» телефона → «О телефоне» (или «Сведения о ПО»).\n" +
                "2. Быстро нажмите 7 раз подряд на пункт «Номер сборки» (на Xiaomi — на «Версия MIUI/HyperOS»), пока не появится сообщение «Вы стали разработчиком!»."
            ),
            Pair(
                "ШАГ 3. Настройка отладки по Wi-Fi",
                "1. Подключите телефон к любой сети Wi-Fi.\n" +
                "2. Перейдите в «Настройки» → «Система» (или «Расширенные настройки») → «Для разработчиков».\n" +
                "3. Включите тумблер «Отладка по USB».\n" +
                "4. Ниже включите тумблер «Отладка по Wi-Fi» (на Xiaomi также включите пункт «Отладка по USB (Настройки безопасности)»)."
            ),
            Pair(
                "ШАГ 4. Сопряжение Shizuku с телефоном",
                "1. Откройте приложение Shizuku и в разделе «Запуск через отладку по Wi-Fi» нажмите кнопку «Сопряжение» (Pairing).\n" +
                "2. Разрешите Shizuku отправку уведомлений.\n" +
                "3. Нажмите «Параметры разработчика» → выберите пункт «Отладка по Wi-Fi» → нажмите «Подключить устройство с помощью кода сопряжения».\n" +
                "4. На экране появится 6-значный код. Протяните шторку уведомлений сверху и введите этот 6-значный код прямо в уведомление от Shizuku."
            ),
            Pair(
                "ШАГ 5. Запуск сервиса Shizuku",
                "Вернитесь в приложение Shizuku и нажмите кнопку «Запустить» (Start). Подождите 3–5 секунд, пока статус вверху не сменится на «Shizuku запущен»."
            ),
            Pair(
                "ШАГ 6. Выдача прав в 1 клик",
                "Вернитесь в DNS Switcher Pro, нажмите на жёлтый баннер разрешения → «⚡ Выдать через Shizuku (1 клик)» и нажмите «Разрешить всегда» во всплывающем окне. Готово! Перезагружать телефон не нужно, а права сохранятся навсегда."
            )
        )

        for ((index, step) in steps.withIndex()) {
            val stepTitle = TextView(this).apply {
                text = step.first
                textSize = 14f
                setTextColor(Color.parseColor(accentColorHex))
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, if (index == 0) 0 else 12, 0, 6)
            }

            val stepDesc = TextView(this).apply {
                text = step.second
                textSize = 13f
                setTextColor(Color.parseColor("#CFD8DC"))
                setPadding(0, 0, 0, 12)
            }

            stepsBox.addView(stepTitle)
            stepsBox.addView(stepDesc)

            if (index < steps.size - 1) {
                stepsBox.addView(createHorizontalDivider())
            }
        }

        scrollView.addView(stepsBox)
        rootLayout.addView(scrollView)

        val dlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 14, 0, 10)
            layoutParams = params
        }

        val playBtn = TextView(this).apply {
            text = "📥 Google Play"
            textSize = 13f
            setTextColor(Color.parseColor("#00B0FF"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = createRoundRect("#121418", 20f, "#1E222B")
            val params = LinearLayout.LayoutParams(0, 95, 1f)
            params.setMargins(0, 0, 8, 0)
            layoutParams = params
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api")))
                } catch (_: Exception) {}
            }
        }

        val githubBtn = TextView(this).apply {
            text = "🌐 GitHub (APK)"
            textSize = 13f
            setTextColor(Color.parseColor("#00B0FF"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = createRoundRect("#121418", 20f, "#1E222B")
            val params = LinearLayout.LayoutParams(0, 95, 1f)
            params.setMargins(8, 0, 0, 0)
            layoutParams = params
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/RikkaApps/Shizuku/releases")))
                } catch (_: Exception) {}
            }
        }

        dlRow.addView(playBtn)
        dlRow.addView(githubBtn)
        rootLayout.addView(dlRow)

        val backBtn = TextView(this).apply {
            text = "Назад к выдаче прав"
            textSize = 15f
            setTextColor(Color.parseColor(accentColorHex))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = createRoundRect("#121418", 22f, "#1E222B")
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 105)
            layoutParams = params
            setOnClickListener {
                guideDialog?.dismiss()
                showAdbDialog()
            }
        }
        rootLayout.addView(backBtn)

        guideDialog = AlertDialog.Builder(this)
            .setView(rootLayout)
            .create()

        guideDialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        guideDialog?.show()
    }

    private fun loadServers() {
        serverList.clear()
        val raw = prefs.getString("servers_json", null)
        if (raw.isNullOrEmpty()) {
            for (d in defaultServers) {
                serverList.add(DnsServer(d.name, d.host))
            }
            saveServers()
            return
        }
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                serverList.add(DnsServer(obj.getString("name"), obj.getString("host")))
            }
        } catch (_: Exception) {
            for (d in defaultServers) {
                serverList.add(DnsServer(d.name, d.host))
            }
        }
    }

    private fun saveServers() {
        val arr = JSONArray()
        for (s in serverList) {
            val obj = JSONObject()
            obj.put("name", s.name)
            obj.put("host", s.host)
            arr.put(obj)
        }
        prefs.edit().putString("servers_json", arr.toString()).apply()
    }
}

class DnsTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Сначала выдайте разрешение в приложении", Toast.LENGTH_LONG).show()
            return
        }

        val prefs = getSharedPreferences("dns_prefs", Context.MODE_PRIVATE)
        val isSimpleToggle = prefs.getBoolean("tile_simple_toggle", true)

        val raw = prefs.getString("servers_json", null)
        val hosts = mutableListOf<Pair<String, String>>()
        if (!raw.isNullOrEmpty()) {
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    hosts.add(Pair(o.getString("name"), o.getString("host")))
                }
            } catch (_: Exception) {}
        }
        if (hosts.isEmpty()) {
            hosts.add(Pair("Comss.one", "dns.comss.one"))
        }

        val currentMode = Settings.Global.getString(contentResolver, "private_dns_mode") ?: "off"
        val currentHost = Settings.Global.getString(contentResolver, "private_dns_specifier") ?: ""

        if (isSimpleToggle) {
            if (currentMode == "off") {
                val savedHost = prefs.getString("last_active_host", null)
                val targetHost = when {
                    !savedHost.isNullOrEmpty() -> savedHost
                    currentHost.isNotEmpty() -> currentHost
                    else -> hosts[0].second
                }
                Settings.Global.putString(contentResolver, "private_dns_mode", "hostname")
                Settings.Global.putString(contentResolver, "private_dns_specifier", targetHost)
            } else {
                if (currentMode == "hostname" && currentHost.isNotEmpty()) {
                    prefs.edit().putString("last_active_host", currentHost).apply()
                }
                Settings.Global.putString(contentResolver, "private_dns_mode", "off")
            }
        } else {
            when (currentMode) {
                "off" -> {
                    Settings.Global.putString(contentResolver, "private_dns_mode", "opportunistic")
                }
                "opportunistic" -> {
                    Settings.Global.putString(contentResolver, "private_dns_mode", "hostname")
                    Settings.Global.putString(contentResolver, "private_dns_specifier", hosts[0].second)
                    prefs.edit().putString("last_active_host", hosts[0].second).apply()
                }
                "hostname" -> {
                    val idx = hosts.indexOfFirst { it.second.equals(currentHost, ignoreCase = true) }
                    if (idx != -1 && idx + 1 < hosts.size) {
                        val nextHost = hosts[idx + 1].second
                        Settings.Global.putString(contentResolver, "private_dns_specifier", nextHost)
                        prefs.edit().putString("last_active_host", nextHost).apply()
                    } else {
                        Settings.Global.putString(contentResolver, "private_dns_mode", "off")
                    }
                }
                else -> {
                    Settings.Global.putString(contentResolver, "private_dns_mode", "off")
                }
            }
        }
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val mode = Settings.Global.getString(contentResolver, "private_dns_mode") ?: "off"
        val host = Settings.Global.getString(contentResolver, "private_dns_specifier") ?: ""

        val prefs = getSharedPreferences("dns_prefs", Context.MODE_PRIVATE)
        val raw = prefs.getString("servers_json", null)
        var matchedName: String? = null
        if (!raw.isNullOrEmpty()) {
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.getString("host").equals(host, ignoreCase = true)) {
                        matchedName = o.getString("name")
                        break
                    }
                }
            } catch (_: Exception) {}
        }

        when (mode) {
            "off" -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "DNS: Выкл"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Отключен"
                }
            }
            "opportunistic" -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "DNS: Авто"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Автоматический"
                }
            }
            "hostname" -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = matchedName ?: "Private DNS"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = if (host.isEmpty()) "Включен" else host
                }
            }
        }
        tile.updateTile()
    }
}
