package com.example.dnstoggle

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

data class DnsServer(
    var name: String,
    var host: String,
    var ping: Int = -1 // -1: не проверен, -2: проверка..., -3: недоступен
)

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private val serverList = mutableListOf<DnsServer>()
    private lateinit var listContainer: LinearLayout
    private lateinit var statusBanner: TextView
    private lateinit var netStatusText: TextView
    private lateinit var tileModeBtn: Button
    private lateinit var modeGroup: RadioGroup
    private lateinit var rbOff: RadioButton
    private lateinit var rbAuto: RadioButton
    private lateinit var rbOn: RadioButton
    private var isUpdatingUi = false

    // Стандартный список серверов по умолчанию (со скриншота)
    private val defaultServers = listOf(
        DnsServer("Comss.one", "dns.comss.one"),
        DnsServer("Мобильная -1", "comss.dns.controld.com"),
        DnsServer("📌 Malw.link", "dns.malw.link"),
        DnsServer("🌍 GeoHide", "geohide.ru"),
        DnsServer("Мобильная -2", "dns.astracat.network"),
        DnsServer("Xbox", "xbox-dns.ru"),
        DnsServer("Quad9", "dns.quad9.net")
    )

    // Дополнительный каталог для кнопки "Популярные"
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
        loadServers()
        setupUI()
    }

    override fun onResume() {
        super.onResume()
        refreshSystemState()
        checkNetworkAndTestAll()
    }

    private fun hasPermission(): Boolean {
        return checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
    }

    private fun setupUI() {
        val rootScroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F0F0F"))
            isFillViewport = true
        }

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 36, 36, 40)
        }

        // Заголовок
        val title = TextView(this).apply {
            text = "DNS Switcher"
            textSize = 24f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 8)
        }
        mainLayout.addView(title)

        // Статус текущей сети и порта 853
        netStatusText = TextView(this).apply {
            text = "Проверка сети..."
            textSize = 13f
            setTextColor(Color.parseColor("#9E9E9E"))
            setPadding(0, 0, 0, 20)
        }
        mainLayout.addView(netStatusText)

        // Баннер проверки прав ADB / Shizuku
        statusBanner = TextView(this).apply {
            textSize = 13f
            setPadding(30, 24, 30, 24)
            background = createRoundRect("#332200", 24f, "#FF9800")
            setTextColor(Color.parseColor("#FFCC00"))
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 0, 0, 20)
            layoutParams = params
            setOnClickListener { showAdbDialog() }
        }
        mainLayout.addView(statusBanner)

        // Карточка выбора системного режима
        val modeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 26, 32, 26)
            background = createRoundRect("#1C1C1E", 28f, "#2C2C2E")
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 0, 0, 18)
            layoutParams = params
        }

        val modeTitle = TextView(this).apply {
            text = "Режим Private DNS:"
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 12)
        }
        modeCard.addView(modeTitle)

        modeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            weightSum = 3f
        }

        rbOff = RadioButton(this).apply {
            text = "Выкл"
            setTextColor(Color.WHITE)
            layoutParams = RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        rbAuto = RadioButton(this).apply {
            text = "Авто"
            setTextColor(Color.WHITE)
            layoutParams = RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        rbOn = RadioButton(this).apply {
            text = "Вкл (DNS)"
            setTextColor(Color.WHITE)
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
                        else -> serverList.firstOrNull()?.host ?: "dns.comss.one"
                    }
                    applyDnsMode("hostname", target)
                }
            }
        }
        modeCard.addView(modeGroup)
        mainLayout.addView(modeCard)

        // Переключатель режима работы плитки в шторке
        tileModeBtn = Button(this).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = createRoundRect("#1C1C1E", 24f, "#2C2C2E")
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 115)
            params.setMargins(0, 0, 0, 26)
            layoutParams = params
            setOnClickListener {
                val isToggle = prefs.getBoolean("tile_simple_toggle", true)
                prefs.edit().putBoolean("tile_simple_toggle", !isToggle).apply()
                updateTileModeButtonText()
            }
        }
        updateTileModeButtonText()
        mainLayout.addView(tileModeBtn)

        // Шапка списка: "Мои DNS-серверы:" + кнопка "📡 Проверить пинг"
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 0, 0, 20)
            layoutParams = params
        }

        val listHeader = TextView(this).apply {
            text = "Мои DNS-серверы:"
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val pingBtn = Button(this).apply {
            text = "📡 Проверить пинг"
            textSize = 13f
            setTextColor(Color.WHITE)
            isAllCaps = false
            setPadding(32, 0, 32, 0)
            background = createRoundRect("#00897B", 22f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 96)
            setOnClickListener { checkNetworkAndTestAll() }
        }

        headerRow.addView(listHeader)
        headerRow.addView(pingBtn)
        mainLayout.addView(headerRow)

        // Контейнер для карточек серверов
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        mainLayout.addView(listContainer)

        // Нижняя кнопка "Добавить новый сервер" (как на скриншоте)
        val addServerBtn = Button(this).apply {
            text = "Добавить новый сервер"
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(40, 0, 40, 0)
            background = createRoundRect("#1C1C1E", 26f, "#2C2C2E")
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 130)
            params.setMargins(0, 10, 0, 18)
            layoutParams = params
            setOnClickListener { showEditServerDialog(null) }
        }
        mainLayout.addView(addServerBtn)

        // Дополнительный ряд кнопок: "📚 Популярные" и "💾 Бэкап / Сброс"
        val bottomActionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 0, 0, 20)
            layoutParams = params
        }

        val popularBtn = Button(this).apply {
            text = "📚 Популярные"
            textSize = 14f
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = createRoundRect("#1565C0", 24f)
            val params = LinearLayout.LayoutParams(0, 120, 1f)
            params.setMargins(0, 0, 10, 0)
            layoutParams = params
            setOnClickListener { showPresetsDialog() }
        }

        val backupBtn = Button(this).apply {
            text = "💾 Бэкап / Сброс"
            textSize = 14f
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = createRoundRect("#2C2C2E", 24f)
            val params = LinearLayout.LayoutParams(0, 120, 1f)
            params.setMargins(10, 0, 0, 0)
            layoutParams = params
            setOnClickListener { showBackupDialog() }
        }

        bottomActionsRow.addView(popularBtn)
        bottomActionsRow.addView(backupBtn)
        mainLayout.addView(bottomActionsRow)

        rootScroll.addView(mainLayout)
        setContentView(rootScroll)
    }

    private fun updateTileModeButtonText() {
        val isSimpleToggle = prefs.getBoolean("tile_simple_toggle", true)
        tileModeBtn.text = if (isSimpleToggle) {
            "🎛️ Кнопка в шторке: Обычный тумблер (ВКЛ ↔ ВЫКЛ)"
        } else {
            "🎛️ Кнопка в шторке: Перебор серверов по кругу"
        }
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
            statusBanner.text = "⚠️ Нет разрешения WRITE_SECURE_SETTINGS.\nНажми сюда, чтобы скопировать команду для ADB или Shizuku."
        }

        when (mode) {
            "off" -> rbOff.isChecked = true
            "opportunistic" -> rbAuto.isChecked = true
            "hostname" -> rbOn.isChecked = true
            else -> rbOff.isChecked = true
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
                setPadding(36, 30, 24, 30)
                background = if (isSelected) {
                    createRoundRect("#193823", 28f, "#2E7D32")
                } else {
                    createRoundRect("#1C1C1E", 28f, "#2C2C2E")
                }
                val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                params.setMargins(0, 0, 0, 18)
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
                text = "$indicator${server.name}"
                textSize = 17f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
            }

            val statusSuffix = if (isSelected) " (Подключен)" else ""
            val pingPart = when (server.ping) {
                -1 -> ""
                -2 -> " • ⏳..."
                -3 -> " • ❌ Недоступен (853)"
                else -> " • ⚡ ${server.ping} мс"
            }

            val subTv = TextView(this).apply {
                text = "${server.host}$statusSuffix$pingPart"
                textSize = 14f
                setTextColor(
                    if (server.ping == -3) Color.parseColor("#EF5350")
                    else Color.parseColor("#9E9E9E")
                )
                setPadding(0, 8, 0, 0)
            }

            infoLayout.addView(nameTv)
            infoLayout.addView(subTv)
            card.addView(infoLayout)

            // Иконка редактирования (карандаш)
            val editBtn = TextView(this).apply {
                text = "✏️"
                textSize = 16f
                gravity = Gravity.CENTER
                val params = LinearLayout.LayoutParams(95, 95)
                params.setMargins(8, 0, 8, 0)
                layoutParams = params
                setOnClickListener { showEditServerDialog(index) }
            }

            // Иконка удаления (крестик)
            val delBtn = TextView(this).apply {
                text = "✖️"
                textSize = 16f
                setTextColor(Color.parseColor("#EF5350"))
                gravity = Gravity.CENTER
                val params = LinearLayout.LayoutParams(95, 95)
                layoutParams = params
                setOnClickListener {
                    serverList.removeAt(
