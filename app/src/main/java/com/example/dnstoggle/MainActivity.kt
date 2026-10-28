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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
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
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.HttpsURLConnection

data class DnsServer(
    var name: String,
    var host: String,
    var ping: Int = -1
)

// Универсальный контроллер тактильного виброотклика
object HapticUtil {
    fun vibrate(context: Context, isStrong: Boolean = false) {
        val prefs = context.getSharedPreferences("dns_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("haptic_enabled", true)) return

        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            if (!vibrator.hasVibrator()) return

            val duration = if (isStrong) 45L else 25L
            val amplitude = if (isStrong) 180 else 90

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(duration)
            }
        } catch (_: Exception) {}
    }
}

// Кастомный векторный отрисовщик щита
class ShieldDrawable(private var shieldColor: Int, private var isFilled: Boolean) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = if (isFilled) Paint.Style.FILL else Paint.Style.STROKE
        strokeWidth = 3.5f
        color = shieldColor
    }

    override fun draw(canvas: Canvas) {
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()

        val path = Path().apply {
            moveTo(width * 0.5f, height * 0.05f)
            lineTo(width * 0.9f, height * 0.2f)
            lineTo(width * 0.9f, height * 0.55f)
            quadTo(width * 0.9f, height * 0.85f, width * 0.5f, height * 0.95f)
            quadTo(width * 0.1f, height * 0.85f, width * 0.1f, height * 0.55f)
            lineTo(width * 0.1f, height * 0.2f)
            close()
        }
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private val serverList = mutableListOf<DnsServer>()
    private lateinit var rootScroll: ScrollView
    private lateinit var mainLayout: LinearLayout
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
    private lateinit var pingBtn: Button
    private lateinit var topBar: LinearLayout
    private lateinit var settingsBtn: TextView
    private lateinit var listHeader: TextView

    // Новые кнопки для Gemini (очистка кэша и проверка скрытия региона)
    private lateinit var checkGeminiRegionBtn: TextView
    private lateinit var clearGeminiCacheBtn: TextView

    // Элементы плашки возврата удаленного сервера (Undo)
    private lateinit var undoBanner: LinearLayout
    private lateinit var undoText: TextView
    private lateinit var undoActionBtn: TextView
    private var lastDeletedServer: DnsServer? = null
    private var lastDeletedIndex: Int = -1
    private val uiHandler = Handler(Looper.getMainLooper())
    private val hideUndoRunnable = Runnable {
        if (::undoBanner.isInitialized) {
            undoBanner.visibility = View.GONE
        }
    }

    private var backCallback: OnBackInvokedCallback? = null
    private var isUpdatingUi = false

    // Цветовые палитры и темы
    private var currentThemeKey = "green"
    private var isLightMode = false
    private var accentColorHex = "#00E676"
    private var accentCardBgHex = "#0D2318"

    // Динамические цвета для темной/светлой темы
    private var bgRootHex = "#08080A"
    private var bgCardHex = "#121418"
    private var bgCardStrokeHex = "#1E222B"
    private var textMainColorHex = "#FFFFFF"
    private var textSubColorHex = "#90A4AE"

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
        registerSystemBackVibrate()
    }

    private fun registerSystemBackVibrate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val callback = OnBackInvokedCallback {
                vibrateClick()
                finish()
            }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                callback
            )
            backCallback = callback
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(hideUndoRunnable)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backCallback?.let {
                try {
                    onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it)
                } catch (_: Exception) {}
            }
        }
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        } catch (_: Throwable) {}
    }

    override fun onResume() {
        super.onResume()
        refreshSystemState()
        checkNetworkAndTestAll()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        vibrateClick()
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun vibrateClick(strong: Boolean = false) {
        HapticUtil.vibrate(this, strong)
    }

    private fun setupDialogBackHandling(dialog: AlertDialog, onNavigateBack: (() -> Unit)? = null) {
        var handledByKey = false

        dialog.setOnKeyListener { d, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                handledByKey = true
                vibrateClick()
                d.dismiss()
                onNavigateBack?.invoke()
                true
            } else {
                false
            }
        }

        dialog.setOnCancelListener {
            if (!handledByKey) {
                vibrateClick()
                onNavigateBack?.invoke()
            }
        }
    }

    private fun loadThemePreferences() {
        currentThemeKey = prefs.getString("app_theme", "green") ?: "green"
        isLightMode = prefs.getBoolean("app_light_mode", false)

        when (currentThemeKey) {
            "green" -> {
                accentColorHex = "#00E676"
                accentCardBgHex = if (isLightMode) "#E8F8F0" else "#0D2318"
            }
            "blue" -> {
                accentColorHex = "#00B0FF"
                accentCardBgHex = if (isLightMode) "#E8F4FE" else "#0A1D2C"
            }
            "purple" -> {
                accentColorHex = "#E040FB"
                accentCardBgHex = if (isLightMode) "#FBE9FE" else "#230A2C"
            }
            "amber" -> {
                accentColorHex = "#FFAB00"
                accentCardBgHex = if (isLightMode) "#FFF8E1" else "#2C200A"
            }
        }

        if (isLightMode) {
            bgRootHex = "#F4F5F7"
            bgCardHex = "#FFFFFF"
            bgCardStrokeHex = "#E0E0E0"
            textMainColorHex = "#121212"
            textSubColorHex = "#546E7A"
        } else {
            bgRootHex = "#08080A"
            bgCardHex = "#121418"
            bgCardStrokeHex = "#1E222B"
            textMainColorHex = "#FFFFFF"
            textSubColorHex = "#90A4AE"
        }
    }

    private fun saveAndApplyTheme(themeKey: String, lightMode: Boolean) {
        currentThemeKey = themeKey
        isLightMode = lightMode
        prefs.edit()
            .putString("app_theme", themeKey)
            .putBoolean("app_light_mode", lightMode)
            .apply()

        loadThemePreferences()
        recreateUIColors()
        refreshSystemState()
        Toast.makeText(this, "Тема успешно обновлена!", Toast.LENGTH_SHORT).show()
    }

    private fun recreateUIColors() {
        rootScroll.setBackgroundColor(Color.parseColor(bgRootHex))
        titleApp.setTextColor(Color.parseColor(accentColorHex))
        settingsBtn.background = createRoundRect(bgCardHex, 20f, bgCardStrokeHex)
        settingsBtn.setTextColor(Color.parseColor(textMainColorHex))
        netStatusText.background = createRoundRect(bgCardHex, 22f, bgCardStrokeHex)
        netStatusText.setTextColor(Color.parseColor(textSubColorHex))
        checkGeminiRegionBtn.background = createRoundRect(accentCardBgHex, 22f, accentColorHex)
        checkGeminiRegionBtn.setTextColor(Color.parseColor(accentColorHex))
        clearGeminiCacheBtn.background = createRoundRect(bgCardHex, 22f, bgCardStrokeHex)
        clearGeminiCacheBtn.setTextColor(Color.parseColor(textMainColorHex))
        pingBtn.background = createRoundRect(accentCardBgHex, 20f, accentColorHex)
        pingBtn.setTextColor(Color.parseColor(accentColorHex))
        listHeader.setTextColor(Color.parseColor(textSubColorHex))
        undoBanner.background = createRoundRect(bgCardHex, 22f, accentColorHex)
        undoActionBtn.setTextColor(Color.parseColor(accentColorHex))
        refreshSystemState()
    }

    private fun hasPermission(): Boolean {
        return checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
    }

    private fun isShizukuReady(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    private fun runShizukuCommand(cmd: Array<String>): Boolean {
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(null, cmd, null, null) as Process
            process.waitFor() == 0
        } catch (_: Throwable) {
            false
        }
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
            val ok = runShizukuCommand(arrayOf("pm", "grant", packageName, android.Manifest.permission.WRITE_SECURE_SETTINGS))
            runOnUiThread {
                refreshSystemState()
                if (hasPermission() || ok) {
                    vibrateClick(true)
                    Toast.makeText(this, "⚡ Права успешно выданы через Shizuku!", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Не удалось выдать права через Shizuku", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun setupUI() {
        rootScroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(bgRootHex))
            isFillViewport = true
        }

        mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 40, 36, 40)
        }

        topBar = LinearLayout(this).apply {
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

        settingsBtn = TextView(this).apply {
            text = "⚙️"
            textSize = 20f
            setTextColor(Color.parseColor(textMainColorHex))
            gravity = Gravity.CENTER
            setPadding(22, 10, 22, 10)
            background = createRoundRect(bgCardHex, 20f, bgCardStrokeHex)
            setOnClickListener {
                vibrateClick()
                showSettingsDialog()
            }
        }

        topBar.addView(titleApp)
        topBar.addView(settingsBtn)
        mainLayout.addView(topBar)

        netStatusText = TextView(this).apply {
            text = "Инициализация сети..."
            textSize = 13f
            setTextColor(Color.parseColor(textSubColorHex))
            setPadding(28, 20, 28, 20)
            background = createRoundRect(bgCardHex, 22f, bgCardStrokeHex)
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 0, 0, 16)
            layoutParams = params
            setOnClickListener {
                vibrateClick()
                verifyActiveDnsInternet()
            }
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
            setOnClickListener {
                vibrateClick()
                showAdbDialog()
            }
        }
        mainLayout.addView(statusBanner)

        modeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 28, 32, 28)
            background = createRoundRect(bgCardHex, 30f, bgCardStrokeHex)
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 0, 0, 14)
            layoutParams = params
        }

        modeTitle = TextView(this).apply {
            text = "СИСТЕМНЫЙ РЕЖИМ"
            textSize = 12f
            setTextColor(Color.parseColor(textSubColorHex))
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
            vibrateClick(true)
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

        // Кнопка 1: Проверка скрытия местоположения для Google Gemini
        checkGeminiRegionBtn = TextView(this).apply {
            text = "🕵️ Проверить защиту и регион Gemini"
            textSize = 13f
            setTextColor(Color.parseColor(accentColorHex))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(24, 22, 24, 22)
            background = createRoundRect(accentCardBgHex, 22f, accentColorHex)
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 0, 0, 12)
            layoutParams = params
            setOnClickListener {
                vibrateClick(true)
                showGeminiRegionCheckDialog()
            }
        }
        mainLayout.addView(checkGeminiRegionBtn)

        // Кнопка 2: Очистка кэша для браузера и мобильного приложения Gemini
        clearGeminiCacheBtn = TextView(this).apply {
            text = "🧹 Очистить кэш Gemini и браузера (Сброс капчи)"
            textSize = 13f
            setTextColor(Color.parseColor(textMainColorHex))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(24, 22, 24, 22)
            background = createRoundRect(bgCardHex, 22f, bgCardStrokeHex)
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 0, 0, 22)
            layoutParams = params
            setOnClickListener {
                vibrateClick()
                showClearGeminiCacheDialog()
            }
        }
        mainLayout.addView(clearGeminiCacheBtn)

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 0, 0, 16)
            layoutParams = params
        }

        listHeader = TextView(this).apply {
            text = "ДОСТУПНЫЕ СЕРВЕРЫ"
            textSize = 12f
            setTextColor(Color.parseColor(textSubColorHex))
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        pingBtn = Button(this).apply {
            text = "📡 Проверка пинг (порт 853)"
            textSize = 11f
            setTextColor(Color.parseColor(accentColorHex))
            isAllCaps = false
            setPadding(22, 0, 22, 0)
            background = createRoundRect(accentCardBgHex, 20f, accentColorHex)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 88)
            setOnClickListener {
                vibrateClick()
                checkNetworkAndTestAll(false)
            }
        }

        headerRow.addView(listHeader)
        headerRow.addView(pingBtn)
        mainLayout.addView(headerRow)

        // Плашка отмены удаления (Undo Banner)
        undoBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(28, 20, 28, 20)
            background = createRoundRect(bgCardHex, 22f, accentColorHex)
            visibility = View.GONE
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 0, 0, 16)
            layoutParams = params
        }

        undoText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor(textMainColorHex))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        undoActionBtn = TextView(this).apply {
            text = "↩️ ВЕРНУТЬ"
            textSize = 13f
            setTextColor(Color.parseColor(accentColorHex))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(20, 8, 8, 8)
            setOnClickListener {
                vibrateClick(true)
                val restored = lastDeletedServer
                if (restored != null) {
                    val insertPos = lastDeletedIndex.coerceIn(0, serverList.size)
                    serverList.add(insertPos, restored)
                    lastDeletedServer = null
                    saveServers()
                    renderServerList()
                    undoBanner.visibility = View.GONE
                    uiHandler.removeCallbacks(hideUndoRunnable)
                    Toast.makeText(this@MainActivity, "✅ Сервер «${restored.name}» восстановлен!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        undoBanner.addView(undoText)
        undoBanner.addView(undoActionBtn)
        mainLayout.addView(undoBanner)

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        mainLayout.addView(listContainer)

        rootScroll.addView(mainLayout)
        setContentView(rootScroll)
    }

    // =========================================================================
    // ФУНКЦИЯ 1: ДИАГНОСТИКА СКРЫТИЯ МЕСТОПОЛОЖЕНИЯ ДЛЯ GOOGLE GEMINI
    // =========================================================================
    private fun isDirectGoogleIp(ip: String): Boolean {
        val directPrefixes = listOf(
            "142.250.", "142.251.", "172.217.", "172.253.",
            "216.58.", "74.125.", "173.194.", "108.177.",
            "64.233.", "209.85.", "216.239."
        )
        return directPrefixes.any { ip.startsWith(it) }
    }

    private fun showGeminiRegionCheckDialog(returnToSettings: Boolean = false) {
        var checkDialog: AlertDialog? = null

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 28, 36, 28)
            background = createRoundRect(if (isLightMode) "#FFFFFF" else "#0F1117", 36f, bgCardStrokeHex)
        }

        rootLayout.addView(createDialogHeaderTitle("🕵️ Защита и регион Gemini"))

        val verdictCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 22, 28, 22)
            background = createRoundRect(bgCardHex, 24f, accentColorHex)
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 0, 0, 14)
            layoutParams = params
        }

        val verdictTitle = TextView(this).apply {
            text = "⏳ Выполняется проверка маршрутизации..."
            textSize = 15f
            setTextColor(Color.parseColor(accentColorHex))
            typeface = Typeface.DEFAULT_BOLD
        }

        val verdictDetails = TextView(this).apply {
            text = "Проверяем DNS-перехват gemini.google.com, наличие утечки IPv6 и статус капчи Google..."
            textSize = 12.5f
            setTextColor(Color.parseColor(textSubColorHex))
            setPadding(0, 10, 0, 0)
        }

        verdictCard.addView(verdictTitle)
        verdictCard.addView(verdictDetails)
        rootLayout.addView(verdictCard)

        val switchNextBtn = TextView(this).apply {
            text = "🔄 Сменить DNS-сервер (Обойти капчу)"
            textSize = 14f
            setTextColor(Color.parseColor("#FFAB00"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            visibility = View.GONE
            background = createRoundRect("#261A00", 22f, "#FFAB00")
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 105)
            params.setMargins(0, 0, 0, 10)
            layoutParams = params
            setOnClickListener {
                vibrateClick(true)
                val activeHost = getActiveHost()
                val working = serverList.filter { it.ping != -3 && !it.host.equals(activeHost, ignoreCase = true) }
                val nextServer = if (working.isNotEmpty()) working[0] else serverList.firstOrNull { !it.host.equals(activeHost, ignoreCase = true) }
                if (nextServer != null) {
                    applyDnsMode("hostname", nextServer.host)
                    Toast.makeText(this@MainActivity, "⚡ Переключено на: ${nextServer.name}", Toast.LENGTH_SHORT).show()
                    checkDialog?.dismiss()
                    showGeminiRegionCheckDialog(returnToSettings)
                }
            }
        }
        rootLayout.addView(switchNextBtn)

        val retestBtn = TextView(this).apply {
            text = "🔄 Проверить ещё раз"
            textSize = 14f
            setTextColor(Color.parseColor(accentColorHex))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = createRoundRect(accentCardBgHex, 22f, accentColorHex)
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 105)
            params.setMargins(0, 0, 0, 10)
            layoutParams = params
        }
        rootLayout.addView(retestBtn)

        val openCacheBtn = TextView(this).apply {
            text = "🧹 Открыть меню очистки кэша Gemini"
            textSize = 14f
            setTextColor(Color.parseColor(textMainColorHex))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = createRoundRect(bgCardHex, 22f, bgCardStrokeHex)
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 105)
            params.setMargins(0, 0, 0, 10)
            layoutParams = params
            setOnClickListener {
                vibrateClick()
                checkDialog?.dismiss()
                showClearGeminiCacheDialog(returnToSettings)
            }
        }
        rootLayout.addView(openCacheBtn)

        val closeBtn = TextView(this).apply {
            text = if (returnToSettings) "Назад к настройкам" else "Закрыть"
            textSize = 15f
            setTextColor(Color.parseColor(textSubColorHex))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = createRoundRect(bgCardHex, 22f, bgCardStrokeHex)
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 105)
            layoutParams = params
            setOnClickListener {
                vibrateClick()
                checkDialog?.dismiss()
                if (returnToSettings) showSettingsDialog()
            }
        }
        rootLayout.addView(closeBtn)

        fun runDiagnostic() {
            verdictTitle.text = "⏳ Выполняется проверка маршрутизации..."
            verdictTitle.setTextColor(Color.parseColor(accentColorHex))
            verdictCard.background = createRoundRect(bgCardHex, 24f, accentColorHex)
            verdictDetails.text = "Опрашиваем DNS и сервер gemini.google.com..."
            switchNextBtn.visibility = View.GONE

            Thread {
                val mode = getActiveMode()
                val activeHost = getActiveHost()
                val dnsLabel = if (mode == "hostname" && activeHost.isNotEmpty()) activeHost else "Выключен ($mode)"

                var ipv4ProxyIp = ""
                var hasIpv6Leak = false
                var isInterceptedByProxy = false
                var proxyCountry = "Неизвестно"
                var proxyCountryCode = ""
                var proxyOrg = ""
                var httpStatus = -1
                var isCaptchaDetected = false
                var isRegionUnsupportedRedirect = false

                try {
                    val addresses = InetAddress.getAllByName("gemini.google.com")
                    val ipv4List = addresses.filterIsInstance<Inet4Address>().map { it.hostAddress ?: "" }
                    val ipv6List = addresses.filterIsInstance<Inet6Address>().map { it.hostAddress ?: "" }

                    if (ipv4List.isNotEmpty()) {
                        ipv4ProxyIp = ipv4List[0]
                        isInterceptedByProxy = !isDirectGoogleIp(ipv4ProxyIp)
                    }

                    // Если возвращаются стандартные IPv6 адреса Google (2a00:1450:*) при включенном мобильном IPv6 — возможна утечка региона
                    if (ipv6List.any { it.startsWith("2a00:1450:") || it.startsWith("2607:f8b0:") }) {
                        hasIpv6Leak = true
                    }

                    // Проверяем геолокацию IP, через который идет запрос к Gemini
                    if (ipv4ProxyIp.isNotEmpty()) {
                        try {
                            val geoUrl = URL("http://ip-api.com/json/$ipv4ProxyIp?fields=status,country,countryCode,org,isp&lang=ru")
                            val conn = (geoUrl.openConnection() as HttpURLConnection).apply {
                                connectTimeout = 3000
                                readTimeout = 3000
                            }
                            if (conn.responseCode == 200) {
                                val text = conn.inputStream.bufferedReader().use { it.readText() }
                                val json = JSONObject(text)
                                if (json.optString("status") == "success") {
                                    proxyCountry = json.optString("country", "Неизвестно")
                                    proxyCountryCode = json.optString("countryCode", "")
                                    proxyOrg = json.optString("org", json.optString("isp", ""))
                                }
                            }
                            conn.disconnect()
                        } catch (_: Exception) {}
                    }

                    // Прямая проверка ответа https://gemini.google.com/app
                    try {
                        val geminiUrl = URL("https://gemini.google.com/app")
                        val httpsConn = (geminiUrl.openConnection() as HttpsURLConnection).apply {
                            instanceFollowRedirects = false
                            connectTimeout = 4500
                            readTimeout = 4500
                            setRequestProperty(
                                "User-Agent",
                                "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
                            )
                        }
                        httpStatus = httpsConn.responseCode
                        val locationHeader = httpsConn.getHeaderField("Location") ?: ""

                        if (httpStatus == 429 || httpStatus == 503 || locationHeader.contains("/sorry/", ignoreCase = true) || locationHeader.contains("ipv4.google.com/sorry", ignoreCase = true)) {
                            isCaptchaDetected = true
                        } else if (locationHeader.contains("faq", ignoreCase = true) || locationHeader.contains("unsupported", ignoreCase = true)) {
                            isRegionUnsupportedRedirect = true
                        }
                        httpsConn.disconnect()
                    } catch (_: Exception) {}

                } catch (_: Exception) {}

                runOnUiThread {
                    val report = StringBuilder()
                    report.append("• Системный Private DNS: $dnsLabel\n")
                    report.append("• IP для gemini.google.com: ${if (ipv4ProxyIp.isEmpty()) "Нет ответа ❌" else ipv4ProxyIp}\n")
                    if (proxyCountryCode.isNotEmpty()) {
                        report.append("• Видимая страна для Gemini: $proxyCountry ($proxyCountryCode)\n")
                    }
                    if (proxyOrg.isNotEmpty()) {
                        report.append("• Узел маршрутизации: $proxyOrg\n")
                    }
                    report.append("• Защита от утечки по IPv6: ${if (hasIpv6Leak) "⚠️ Есть риск утечки (Google IPv6)" else "✅ Защищено (Только IPv4 прокси)"}\n")

                    when {
                        mode != "hostname" -> {
                            vibrateClick(true)
                            verdictTitle.text = "❌ Реальное местоположение НЕ скрыто!"
                            verdictTitle.setTextColor(Color.parseColor("#FF5252"))
                            verdictCard.background = createRoundRect(if (isLightMode) "#FFEBEE" else "#1F1214", 24f, "#FF5252")
                            report.append("\nПричина: Системный режим Private DNS отключен или стоит в режиме «Авто». Включите режим «Вкл» и выберите сервер (например, Comss.one или Malw.link).")
                        }
                        ipv4ProxyIp.isEmpty() -> {
                            vibrateClick(true)
                            verdictTitle.text = "⚠️ Ошибка соединения с DNS-сервером"
                            verdictTitle.setTextColor(Color.parseColor("#FF5252"))
                            verdictCard.background = createRoundRect(if (isLightMode) "#FFEBEE" else "#1F1214", 24f, "#FF5252")
                            report.append("\nНе удалось получить адрес gemini.google.com. Выберите другой рабочий сервер из списка.")
                            switchNextBtn.visibility = View.VISIBLE
                        }
                        !isInterceptedByProxy || proxyCountryCode.equals("RU", true) || proxyCountryCode.equals("BY", true) || isRegionUnsupportedRedirect -> {
                            vibrateClick(true)
                            verdictTitle.text = "❌ Местоположение НЕ скрыто от Gemini!"
                            verdictTitle.setTextColor(Color.parseColor("#FF5252"))
                            verdictCard.background = createRoundRect(if (isLightMode) "#FFEBEE" else "#1F1214", 24f, "#FF5252")
                            report.append("\nПричина: Текущий DNS ($activeHost) направляет запросы напрямую в Google без маскировки региона. Переключитесь на Comss.one, Malw.link или GeoHide!")
                            switchNextBtn.visibility = View.VISIBLE
                        }
                        isCaptchaDetected -> {
                            vibrateClick(true)
                            verdictTitle.text = "⚠️ Местоположение скрыто, но IP перегружен (Капча)"
                            verdictTitle.setTextColor(Color.parseColor("#FFAB00"))
                            verdictCard.background = createRoundRect("#261A00", 24f, "#FFAB00")
                            report.append("\nВаше реальное местоположение успешно скрыто ($proxyCountry), но Google временно выдал капчу «Подозрительный трафик» на IP $ipv4ProxyIp.\n\nРешение: Нажмите кнопку «Сменить DNS-сервер» ниже и очистите кэш браузера!")
                            switchNextBtn.visibility = View.VISIBLE
                        }
                        hasIpv6Leak -> {
                            vibrateClick(true)
                            verdictTitle.text = "⚠️ IPv4 скрыт ($proxyCountry), но есть утечка IPv6!"
                            verdictTitle.setTextColor(Color.parseColor("#FFAB00"))
                            verdictCard.background = createRoundRect("#261A00", 24f, "#FFAB00")
                            report.append("\nDNS-сервер подменяет IPv4, но пропускает прямой IPv6-адрес Google. Если Gemini пишет о недоступности региона — переключитесь на другой DNS-сервер и сбросьте кэш приложения Google.")
                            switchNextBtn.visibility = View.VISIBLE
                        }
                        else -> {
                            vibrateClick()
                            verdictTitle.text = "✅ Местоположение успешно скрыто!"
                            verdictTitle.setTextColor(Color.parseColor(accentColorHex))
                            verdictCard.background = createRoundRect(accentCardBgHex, 24f, accentColorHex)
                            report.append("\nОтлично! Google Gemini видит ваше подключение из региона «$proxyCountry ($proxyCountryCode)». Региональных блокировок и капчи не обнаружено.")
                        }
                    }

                    verdictDetails.text = report.toString()
                }
            }.start()
        }

        retestBtn.setOnClickListener {
            vibrateClick()
            runDiagnostic()
        }

        checkDialog = AlertDialog.Builder(this)
            .setView(rootLayout)
            .create()

        checkDialog?.let {
            setupDialogBackHandling(it) {
                if (returnToSettings) showSettingsDialog()
            }
        }
        checkDialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        checkDialog?.show()

        runDiagnostic()
    }

    // =========================================================================
    // ФУНКЦИЯ 2: ОЧИСТКА КЭША ДЛЯ МОБИЛЬНОГО ПРИЛОЖЕНИЯ GEMINI И БРАУЗЕРОВ
    // =========================================================================
    private fun openAppStorageSettings(pkgName: String, appTitle: String) {
        try {
            // Если Shizuku активен — предварительно останавливаем процесс приложения, чтобы сбросить активную сессию в ОЗУ
            if (isShizukuReady()) {
                Thread {
                    runShizukuCommand(arrayOf("am", "force-stop", pkgName))
                }.start()
            }
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", pkgName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Toast.makeText(
                this,
                "Нажмите «Память (Хранилище)» → «Очистить кэш» для $appTitle",
                Toast.LENGTH_LONG
            ).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Приложение «$appTitle» не установлено на телефоне", Toast.LENGTH_SHORT).show()
        }
    }

    private fun flushAndroidDnsAndStopGeminiApps() {
        Thread {
            val currentMode = getActiveMode()
            val currentHost = getActiveHost()

            // 1. Принудительный сброс системного DNS-кэша Android путём кратковременного переключения Private DNS
            if (hasPermission() && currentMode == "hostname" && currentHost.isNotEmpty()) {
                try {
                    Settings.Global.putString(contentResolver, "private_dns_mode", "off")
                    Thread.sleep(350)
                    Settings.Global.putString(contentResolver, "private_dns_mode", "hostname")
                    Settings.Global.putString(contentResolver, "private_dns_specifier", currentHost)
                } catch (_: Exception) {}
            }

            // 2. Если активен Shizuku — принудительно останавливаем фоновые процессы Gemini, приложения Google и браузеров
            var stoppedViaShizuku = false
            if (isShizukuReady()) {
                val targetPackages = listOf(
                    "com.google.android.apps.bard",
                    "com.google.android.googlequicksearchbox",
                    "com.sec.android.app.sbrowser",
                    "com.android.chrome",
                    "com.yandex.browser",
                    "com.brave.browser"
                )
                for (pkg in targetPackages) {
                    runShizukuCommand(arrayOf("am", "force-stop", pkg))
                }
                stoppedViaShizuku = true
            }

            runOnUiThread {
                vibrateClick(true)
                refreshSystemState()
                val msg = if (stoppedViaShizuku) {
                    "⚡ DNS-кэш сброшен, фоновые сессии Gemini и браузеров перезапущены через Shizuku!"
                } else {
                    "✅ Системный DNS-кэш и старые соединения успешно сброшены!"
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun showClearGeminiCacheDialog(returnToSettings: Boolean = false) {
        var cacheDialog: AlertDialog? = null

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 28, 36, 28)
            background = createRoundRect(if (isLightMode) "#FFFFFF" else "#0F1117", 36f, bgCardStrokeHex)
        }

        rootLayout.addView(createDialogHeaderTitle("🧹 Очистка кэша Gemini и браузера"))

        val hintBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundRect(bgCardHex, 22f, bgCardStrokeHex)
            setPadding(24, 18, 24, 18)
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 0, 0, 14)
            layoutParams = params
        }

        val hintTv = TextView(this).apply {
            text = "💡 На Android приложение Gemini хранит метку вашего региона внутри главного приложения «Google», а при входе через браузер — в кэше и куки браузера."
            textSize = 12f
            setTextColor(Color.parseColor(textSubColorHex))
        }
        hintBox.addView(hintTv)
        rootLayout.addView(hintBox)

        // Кнопка мгновенного сброса сессии и DNS-кэша в 1 клик
        val quickFlushBtn = TextView(this).apply {
            text = "⚡ Сбросить DNS-кэш и фоновые сессии (1 клик)"
            textSize = 13.5f
            setTextColor(Color.parseColor(accentColorHex))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(20, 0, 20, 0)
            background = createRoundRect(accentCardBgHex, 22f, accentColorHex)
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 105)
            params.setMargins(0, 0, 0, 14)
            layoutParams = params
            setOnClickListener {
                flushAndroidDnsAndStopGeminiApps()
            }
        }
        rootLayout.addView(quickFlushBtn)

        val scrollView = ScrollView(this).apply {
            val maxHeight = (resources.displayMetrics.heightPixels * 0.42).toInt()
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxHeight)
        }

        val appListBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundRect(bgCardHex, 22f, bgCardStrokeHex)
            setPadding(16, 8, 16, 8)
        }

        val targetApps = listOf(
            Triple("✨ Приложение Google Gemini", "Очистить кэш мобильного клиента Gemini", "com.google.android.apps.bard"),
            Triple("🔍 Приложение Google (Обязательно!)", "Хранит регион аккаунта для приложения Gemini", "com.google.android.googlequicksearchbox"),
            Triple("🌐 Samsung Internet", "Очистить кэш и куки gemini.google.com", "com.sec.android.app.sbrowser"),
            Triple("🌐 Google Chrome", "Очистить кэш и данные сайта в Chrome", "com.android.chrome"),
            Triple("🌐 Яндекс Браузер", "Очистить кэш и сессию в Яндекс Браузере", "com.yandex.browser"),
            Triple("🌐 Brave / Opera / Firefox", "Открыть хранилище дополнительного браузера", "com.brave.browser")
        )

        for ((index, appItem) in targetApps.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 20, 20, 20)
                setOnClickListener {
                    vibrateClick()
                    if (index == 5) {
                        // Проверяем альтернативные браузеры по очереди
                        val altPackages = listOf("com.brave.browser", "com.opera.browser", "org.mozilla.firefox", "com.microsoft.emmx")
                        var opened = false
                        for (altPkg in altPackages) {
                            try {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", altPkg, null)
                                }
                                startActivity(intent)
                                opened = true
                                break
                            } catch (_: Exception) {}
                        }
                        if (!opened) {
                            Toast.makeText(this@MainActivity, "Дополнительные браузеры не найдены", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        openAppStorageSettings(appItem.third, appItem.first)
                    }
                }
            }

            val titleTv = TextView(this).apply {
                text = appItem.first
                textSize = 14.5f
                setTextColor(Color.parseColor(textMainColorHex))
                typeface = Typeface.DEFAULT_BOLD
            }

            val subTv = TextView(this).apply {
                text = "${appItem.second} → Нажмите «Хранилище» → «Очистить кэш»"
                textSize = 11.5f
                setTextColor(Color.parseColor(textSubColorHex))
                setPadding(0, 4, 0, 0)
            }

            row.addView(titleTv)
            row.addView(subTv)
            appListBox.addView(row)

            if (index < targetApps.size - 1) {
                appListBox.addView(createHorizontalDivider())
            }
        }

        scrollView.addView(appListBox)
        rootLayout.addView(scrollView)

        val closeBtn = TextView(this).apply {
            text = if (returnToSettings) "Назад к настройкам" else "Закрыть"
            textSize = 15f
            setTextColor(Color.parseColor(textSubColorHex))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = createRoundRect(bgCardHex, 22f, bgCardStrokeHex)
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 105)
            params.setMargins(0, 14, 0, 0)
            layoutParams = params
            setOnClickListener {
                vibrateClick()
                cacheDialog?.dismiss()
                if (returnToSettings) showSettingsDialog()
            }
        }
        rootLayout.addView(closeBtn)

        cacheDialog = AlertDialog.Builder(this)
            .setView(rootLayout)
            .create()

        cacheDialog?.let {
            setupDialogBackHandling(it) {
                if (returnToSettings) showSettingsDialog()
            }
        }
        cacheDialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        cacheDialog?.show()
    }

    private fun verifyActiveDnsInternet() {
        netStatusText.text = "⏳ Проверка интернет-отклика и DNS-резолвинга..."
        Thread {
            val start = System.currentTimeMillis()
            val isResolving = try {
                val addresses = InetAddress.getAllByName("google.com")
                addresses != null && addresses.isNotEmpty()
            } catch (_: Exception) {
                false
            }
            val elapsed = System.currentTimeMillis() - start
            runOnUiThread {
                val workingCount = serverList.count { it.ping > 0 }
                if (isResolving) {
                    vibrateClick()
                    netStatusText.text = "🌐 DNS резолвинг: OK (${elapsed} мс) ✅ • DoT: $workingCount/${serverList.size}"
                    Toast.makeText(this, "✅ DNS успешно разрешает домены ($elapsed мс)", Toast.LENGTH_SHORT).show()
                } else {
                    vibrateClick(true)
                    netStatusText.text = "⚠️ Сбой разрешения доменов ❌ • DoT: $workingCount/${serverList.size}"
                    Toast.makeText(this, "❌ Ошибка резолвинга! Проверьте выбранный DNS или сеть", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun createDialogHeaderTitle(titleText: String): TextView {
        return TextView(this).apply {
            text = titleText
            textSize = 18f
            setTextColor(Color.parseColor(textMainColorHex))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(12, 6, 12, 24)
        }
    }

    private fun showServerActionsDialog(index: Int) {
        if (index !in serverList.indices) return
        val server = serverList[index]
        var actionDialog: AlertDialog? = null

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 28, 36, 28)
            background = createRoundRect(if (isLightMode) "#FFFFFF" else "#0F1117", 36f, bgCardStrokeHex)
        }

        val titleView = TextView(this).apply {
            text = server.name
            textSize = 18f
            setTextColor(Color.parseColor(textMainColorHex))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(12, 4, 12, 4)
        }

        val subTitleView = TextView(this).apply {
            text = server.host
            textSize = 13f
            setTextColor(Color.parseColor(accentColorHex))
            gravity = Gravity.CENTER
            setPadding(12, 0, 12, 22)
        }

        rootLayout.addView(titleView)
        rootLayout.addView(subTitleView)

        val editCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(28, 22, 28, 22)
            background = createRoundRect(bgCardHex, 22f, bgCardStrokeHex)
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 0, 0, 12)
            layoutParams = params
            setOnClickListener {
                vibrateClick()
                actionDialog?.dismiss()
                showEditServerDialog(index, returnToActions = true)
            }
        }

        val editTitle = TextView(this).apply {
            text = "✏️ Редактировать"
            textSize = 15f
            setTextColor(Color.parseColor(textMainColorHex))
            typeface = Typeface.DEFAULT_BOLD
        }

        val editSub = TextView(this).apply {
            text = "Изменить название или адрес хоста"
            textSize = 12f
            setTextColor(Color.parseColor(textSubColorHex))
            setPadding(0, 4, 0, 0)
        }

        editCard.addView(editTitle)
        editCard.addView(editSub)
        rootLayout.addView(editCard)

        val deleteCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(28, 22, 28, 22)
            background = createRoundRect(if (isLightMode) "#FFEBEE" else "#1F1214", 22f, "#FF5252")
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 0, 0, 16)
            layoutParams = params
            setOnClickListener {
                vibrateClick(true)
                actionDialog?.dismiss()
                showDeleteConfirmDialog(index)
            }
        }

        val deleteTitle = TextView(this).apply {
            text = "🗑️ Удалить"
            textSize = 15f
            setTextColor(Color.parseColor("#FF5252"))
            typeface = Typeface.DEFAULT_BOLD
        }

        val deleteSub = TextView(this).apply {
            text = "Удалить сервер из вашего списка"
            textSize = 12f
            setTextColor(Color.parseColor("#E57373"))
            setPadding(0, 4, 0, 0)
        }

        deleteCard.addView(deleteTitle)
        deleteCard.addView(deleteSub)
        rootLayout.addView(deleteCard)

        val closeBtn = TextView(this).apply {
            text = "Отмена"
            textSize = 15f
            setTextColor(Color.parseColor(accentColorHex))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = createRoundRect(bgCardHex, 22f, bgCardStrokeHex)
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 105)
            layoutParams = params
            setOnClickListener {
                vibrateClick()
                actionDialog?.dismiss()
            }
        }
        rootLayout.addView(closeBtn)

        actionDialog = AlertDialog.Builder(this)
            .setView(rootLayout)
            .create()

        actionDialog?.let { setupDialogBackHandling(it) }
        actionDialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        actionDialog?.show()
    }

    private fun showDeleteConfirmDialog(index: Int) {
        if (index !in serverList.indices) return
        val server = serverList[index]
        var confirmDialog: AlertDialog? = null

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 28, 36, 28)
            background = createRoundRect(if (isLightMode) "#FFFFFF" else "#0F1117", 36f, "#FF5252")
        }

        rootLayout.addView(createDialogHeaderTitle("🗑️ Удаление сервера"))

        val msgView = TextView(this).apply {
            text = "Удалить «${server.name}» (${server.host}) из вашего списка?"
            textSize = 14f
            setTextColor(Color.parseColor(textSubColorHex))
            gravity = Gravity.CENTER
            setPadding(12, 0, 12, 24)
        }
        rootLayout.addView(msgView)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }

        val cancelBtn = TextView(this).apply {
            text = "Назад"
            textSize = 14f
            setTextColor(Color.parseColor(textMainColorHex))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = createRoundRect(bgCardHex, 20f, bgCardStrokeHex)
            val params = LinearLayout.LayoutParams(0, 100, 1f)
            params.setMargins(0, 0, 10, 0)
            layoutParams = params
            setOnClickListener {
                vibrateClick()
                confirmDialog?.dismiss()
                showServerActionsDialog(index)
            }
        }

        val deleteBtn = TextView(this).apply {
            text = "Удалить"
            textSize = 14f
            setTextColor(Color.parseColor("#FF5252"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = createRoundRect("#2A1215", 20f, "#FF5252")
            val params = LinearLayout.LayoutParams(0, 100, 1f)
            params.setMargins(10, 0, 0, 0)
            layoutParams = params
            setOnClickListener {
                vibrateClick(true)
                confirmDialog?.dismiss()
                performRemoveWithUndo(index)
            }
        }

        btnRow.addView(cancelBtn)
        btnRow.addView(deleteBtn)
        rootLayout.addView(btnRow)

        confirmDialog = AlertDialog.Builder(this)
            .setView(rootLayout)
            .create()

        confirmDialog?.let {
            setupDialogBackHandling(it) {
                showServerActionsDialog(index)
            }
        }
        confirmDialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        confirmDialog?.show()
    }

    private fun performRemoveWithUndo(index: Int) {
        if (index !in serverList.indices) return
        lastDeletedServer = serverList[index]
        lastDeletedIndex = index
        serverList.removeAt(index)
        saveServers()
        renderServerList()

        undoText.text = "Удален «${lastDeletedServer?.name}»"
        undoBanner.visibility = View.VISIBLE
        uiHandler.removeCallbacks(hideUndoRunnable)
        uiHandler.postDelayed(hideUndoRunnable, 6000)
    }

    private fun createHorizontalDivider(): View {
        return View(this).apply {
            setBackgroundColor(Color.parseColor(if (isLightMode) "#E0E0E0" else "#262B36"))
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
            background = createRoundRect(if (isLightMode) "#FFFFFF" else "#0F1117", 36f, bgCardStrokeHex)
        }

        rootLayout.addView(createDialogHeaderTitle("⚙️ Настройки приложения"))

        val scrollView = ScrollView(this).apply {
            val maxHeight = (resources.displayMetrics.heightPixels * 0.62).toInt()
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxHeight)
        }

        val optionsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val options = listOf(
            Pair("➕ Добавить свой сервер", "Ручной ввод хоста и имени"),
            Pair("📚 Популярные DNS-серверы", "Каталог проверенных серверов"),
            Pair("📊 Ping Benchmark (Топ серверов)", "Анализ скорости и автовыбор лидера"),
            Pair("🕵️ Проверить защиту и регион Gemini", "Тест скрытия местоположения, IPv6 и капчи"),
            Pair("🧹 Очистить кэш Gemini и браузеров", "Сброс сессии, куки и региональной метки"),
            Pair("💾 Бэкап и сброс настроек", "Экспорт/импорт JSON, сброс списка"),
            Pair("✨ Кастомизация", "Тема, светлый/темный режим и стиль")
        )

        for ((index, item) in options.withIndex()) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(28, 22, 28, 22)
                background = createRoundRect(bgCardHex, 22f, bgCardStrokeHex)
                val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                params.setMargins(0, 0, 0, 14)
                layoutParams = params

                setOnClickListener {
                    vibrateClick()
                    dialogInstance?.dismiss()
                    when (index) {
                        0 -> showEditServerDialog(null, returnToSettings = true)
                        1 -> showPresetsDialog()
                        2 -> showBenchmarkDialog()
                        3 -> showGeminiRegionCheckDialog(returnToSettings = true)
                        4 -> showClearGeminiCacheDialog(returnToSettings = true)
                        5 -> showBackupDialog()
                        6 -> showCustomizationDialog()
                    }
                }
            }

            val titleTv = TextView(this).apply {
                text = item.first
                textSize = 15f
                setTextColor(Color.parseColor(textMainColorHex))
                typeface = Typeface.DEFAULT_BOLD
            }

            val subTv = TextView(this).apply {
                text = item.second
                textSize = 12f
                setTextColor(Color.parseColor(textSubColorHex))
                setPadding(0, 4, 0, 0)
            }

            card.addView(titleTv)
            card.addView(subTv)
            optionsContainer.addView(card)
        }

        scrollView.addView(optionsContainer)
        rootLayout.addView(scrollView)

        val closeBtn = TextView(this).apply {
            text = "Закрыть"
            textSize = 15f
            setTextColor(Color.parseColor(accentColorHex))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = createRoundRect(bgCardHex, 22f, bgCardStrokeHex)
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 110)
            params.setMargins(0, 10, 0, 0)
            layoutParams = params
            setOnClickListener {
                vibrateClick()
                dialogInstance?.dismiss()
            }
        }
        rootLayout.addView(closeBtn)

        dialogInstance = AlertDialog.Builder(this)
            .setView(rootLayout)
            .create()

        dialogInstance?.let { setupDialogBackHandling(it) }
        dialogInstance?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialogInstance?.show()
    }

    private fun showCustomizationDialog() {
        var customDialog: AlertDialog? = null

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 28, 36, 28)
            background = createRoundRect(if (isLightMode) "#FFFFFF" else "#0F1117", 36f, bgCardStrokeHex)
        }

        rootLayout.addView(createDialogHeaderTitle("✨ Кастомизация"))

        val optionsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun renderCustomizationOptions() {
            optionsContainer.removeAllViews()
            val isHapticOn = prefs.getBoolean("haptic_enabled", true)
            val isSimpleToggle = prefs.getBoolean("tile_simple_toggle", true)
            val isAutoSortOn = prefs.getBoolean("auto_sort_ping", true)

            val customOptions = listOf(
                Pair("🎨 Выбор темы и акцента", "Цветовая палитра и янтарный стиль"),
                Pair("☀️ Режим интерфейса", if (isLightMode) "Сейчас: Светлая тема" else "Сейчас: Темная тема"),
                Pair("🔀 Автосортировка по пингу", if (isAutoSortOn) "Сейчас: Включена (быстрые сверху)" else "Сейчас: Отключена (фиксированный порядок)"),
                Pair("🎛️ Режим шторки", if (isSimpleToggle) "Сейчас: Обычный тумблер (Вкл/Выкл)" else "Сейчас: Циклический перебор"),
                Pair("📳 Виброотклик при нажатиях", if (isHapticOn) "Сейчас: Включен" else "Сейчас: Отключен")
            )

            for ((index, item) in customOptions.withIndex()) {
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(28, 22, 28, 22)
                    background = createRoundRect(bgCardHex, 22f, bgCardStrokeHex)
                    val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    params.setMargins(0, 0, 0, 14)
                    layoutParams = params

                    setOnClickListener {
                        vibrateClick()
                        when (index) {
                            0 -> {
                                customDialog?.dismiss()
                                showThemeChooserDialog()
                            }
                            1 -> {
                                val newLightMode = !isLightMode
                                saveAndApplyTheme(currentThemeKey, newLightMode)
                                customDialog?.dismiss()
                                showCustomizationDialog()
                            }
                            2 -> {
                                val newSort = !isAutoSortOn
                                prefs.edit().putBoolean("auto_sort_ping", newSort).apply()
                                if (newSort) {
                                    serverList.sortWith(Comparator { a, b ->
                                        val pA = if (a.ping > 0) a.ping else Int.MAX_VALUE
                                        val pB = if (b.ping > 0) b.ping else Int.MAX_VALUE
                                        pA.compareTo(pB)
                                    })
                                    saveServers()
                                    renderServerList()
                                }
                                renderCustomizationOptions()
                                val msg = if (newSort) "Автосортировка включена" else "Порядок серверов зафиксирован"
                                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                            }
                            3 -> {
                                prefs.edit().putBoolean("tile_simple_toggle", !isSimpleToggle).apply()
                                renderCustomizationOptions()
                                Toast.makeText(this@MainActivity, "Режим шторки изменен", Toast.LENGTH_SHORT).show()
                            }
                            4 -> {
                                val newState = !isHapticOn
                                prefs.edit().putBoolean("haptic_enabled", newState).apply()
                                if (newState) HapticUtil.vibrate(this@MainActivity, true)
                                renderCustomizationOptions()
                                val msg = if (newState) "Виброотклик включен" else "Виброотклик отключен"
                                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }

                val titleTv = TextView(this).apply {
                    text = item.first
                    textSize = 15f
                    setTextColor(Color.parseColor(textMainColorHex))
                    typeface = Typeface.DEFAULT_BOLD
                }

                val subTv = TextView(this).apply {
                    text = item.second
                    textSize = 12f
                    setTextColor(Color.parseColor(textSubColorHex))
                    setPadding(0, 4, 0, 0)
                }

                card.addView(titleTv)
                card.addView(subTv)
                optionsContainer.addView(card)
            }
        }

        renderCustomizationOptions()
        rootLayout.addView(optionsContainer)

        val backBtn = TextView(this).apply {
            text = "Назад к настройкам"
            textSize = 15f
            setTextColor(Color.parseColor(accentColorHex))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = createRoundRect(bgCardHex, 22f, bgCardStrokeHex)
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 110)
            params.setMargins(0, 10, 0, 0)
            layoutParams = params
            setOnClickListener {
                vibrateClick()
                customDialog?.dismiss()
                showSettingsDialog()
            }
        }
        rootLayout.addView(backBtn)

        customDialog = AlertDialog.Builder(this)
            .setView(rootLayout)
            .create()

        customDialog?.let {
            setupDialogBackHandling(it) {
                showSettingsDialog()
            }
        }
        customDialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        customDialog?.show()
    }

    private fun showBenchmarkDialog() {
        var benchDialog: AlertDialog? = null

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 28, 36, 28)
            background = createRoundRect(if (isLightMode) "#FFFFFF" else "#0F1117", 36f, bgCardStrokeHex)
        }

        rootLayout.addView(createDialogHeaderTitle("📊 Ping Benchmark — Топ серверов"))

        val scrollView = ScrollView(this).apply {
            val maxHeight = (resources.displayMetrics.heightPixels * 0.46).toInt()
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxHeight)
        }

        val listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundRect(bgCardHex, 22f, bgCardStrokeHex)
            setPadding(16, 8, 16, 8)
        }

        fun populateBenchmarkList() {
            listBox.removeAllViews()
            val sortedList = serverList.sortedWith(Comparator { a, b ->
                val pA = if (a.ping > 0) a.ping else Int.MAX_VALUE
                val pB = if (b.ping > 0) b.ping else Int.MAX_VALUE
                pA.compareTo(pB)
            })

            if (sortedList.isEmpty()) {
                val emptyTv = TextView(this).apply {
                    text = "Список серверов пуст"
                    textSize = 14f
                    setTextColor(Color.parseColor(textSubColorHex))
                    gravity = Gravity.CENTER
                    setPadding(20, 30, 20, 30)
                }
                listBox.addView(emptyTv)
            } else {
                for ((index, server) in sortedList.withIndex()) {
                    val rank = when (index) {
                        0 -> "🥇 #1"
                        1 -> "🥈 #2"
                        2 -> "🥉 #3"
                        else -> "   #${index + 1}"
                    }

                    val itemLayout = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(16, 22, 16, 22)
                        setOnClickListener {
                            vibrateClick(true)
                            benchDialog?.dismiss()
                            applyDnsMode("hostname", server.host)
                            Toast.makeText(this@MainActivity, "Подключен: ${server.name}", Toast.LENGTH_SHORT).show()
                        }
                        setOnLongClickListener {
                            vibrateClick(true)
                            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cb.setPrimaryClip(ClipData.newPlainText("dns_host", server.host))
                            Toast.makeText(this@MainActivity, "📋 Скопировано: ${server.host}", Toast.LENGTH_SHORT).show()
                            true
                        }
                    }

                    val rankTv = TextView(this).apply {
                        text = rank
                        textSize = 14f
                        setTextColor(Color.parseColor(accentColorHex))
                        typeface = Typeface.DEFAULT_BOLD
                        val params = LinearLayout.LayoutParams(95, ViewGroup.LayoutParams.WRAP_CONTENT)
                        layoutParams = params
                    }

                    val detailsLayout = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }

                    val nameTv = TextView(this).apply {
                        text = server.name
                        textSize = 14f
                        setTextColor(Color.parseColor(textMainColorHex))
                        typeface = Typeface.DEFAULT_BOLD
                    }

                    val pingStr = when (server.ping) {
                        -1 -> "Не проверен"
                        -2 -> "⏳ Тестирование..."
                        -3 -> "❌ Заблокирован (порт 853)"
                        else -> "⚡ ${server.ping} мс"
                    }

                    val pingTv = TextView(this).apply {
                        text = "$pingStr (${server.host})"
                        textSize = 12f
                        setTextColor(if (server.ping > 0) Color.parseColor(textSubColorHex) else Color.parseColor("#FF5252"))
                        setPadding(0, 4, 0, 0)
                    }

                    detailsLayout.addView(nameTv)
                    detailsLayout.addView(pingTv)

                    itemLayout.addView(rankTv)
                    itemLayout.addView(detailsLayout)
                    listBox.addView(itemLayout)

                    if (index < sortedList.size - 1) {
                        listBox.addView(createHorizontalDivider())
                    }
                }
            }
        }

        populateBenchmarkList()
        scrollView.addView(listBox)
        rootLayout.addView(scrollView)

        val connectBestBtn = TextView(this).apply {
            text = "⚡ Автовыбор (Подключить лучший #1)"
            textSize = 14f
            setTextColor(Color.parseColor(accentColorHex))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = createRoundRect(accentCardBgHex, 22f, accentColorHex)
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 105)
            params.setMargins(0, 16, 0, 10)
            layoutParams = params
            setOnClickListener {
                vibrateClick(true)
                val best = serverList.filter { it.ping > 0 }.minByOrNull { it.ping }
                if (best != null) {
                    benchDialog?.dismiss()
                    applyDnsMode("hostname", best.host)
                    Toast.makeText(this@MainActivity, "⚡ Подключен лидер: ${best.name} (${best.ping} мс)", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@MainActivity, "⏳ Запускаем экспресс-тест для выбора лидера...", Toast.LENGTH_SHORT).show()
                    benchDialog?.dismiss()
                    checkNetworkAndTestAll(autoConnectBest = true)
                }
            }
        }
        rootLayout.addView(connectBestBtn)

        val runTestBtn = TextView(this).apply {
            text = "🔄 Запустить тест скорости"
            textSize = 14f
            setTextColor(Color.parseColor(textMainColorHex))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = createRoundRect(bgCardHex, 22f, bgCardStrokeHex)
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 105)
            params.setMargins(0, 0, 0, 10)
            layoutParams = params
            setOnClickListener {
                vibrateClick()
                text = "⏳ Идет сканирование..."
                for (s in serverList) s.ping = -2
                populateBenchmarkList()

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

                        runOnUiThread {
                            populateBenchmarkList()
                        }
                    }

                    runOnUiThread {
                        if (prefs.getBoolean("auto_sort_ping", true)) {
                            serverList.sortWith(Comparator { a, b ->
                                val pA = if (a.ping > 0) a.ping else Int.MAX_VALUE
                                val pB = if (b.ping > 0) b.ping else Int.MAX_VALUE
                                pA.compareTo(pB)
                            })
                        }
                        saveServers()
                        text = "🔄 Запустить тест скорости"
                        populateBenchmarkList()
                        renderServerList()
                        vibrateClick(true)
                        Toast.makeText(this@MainActivity, "✅ Тест скорости завершен!", Toast.LENGTH_SHORT).show()
                    }
                }.start()
            }
        }
        rootLayout.addView(runTestBtn)

        val backBtn = TextView(this).apply {
            text = "Назад к настройкам"
            textSize = 15f
            setTextColor(Color.parseColor(textSubColorHex))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = createRoundRect(bgCardHex, 22f, bgCardStrokeHex)
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 105)
            layoutParams = params
            setOnClickListener {
                vibrateClick()
                benchDialog?.dismiss()
                showSettingsDialog()
            }
        }
        rootLayout.addView(backBtn)

        benchDialog = AlertDialog.Builder(this)
            .setView(rootLayout)
            .create()

        benchDialog?.let {
            setupDialogBackHandling(it) {
                showSettingsDialog()
            }
        }
        benchDialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        benchDialog?.show()
    }

    private fun showThemeChooserDialog() {
        var themeDialog: AlertDialog? = null

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 28, 36, 28)
            background = createRoundRect(if (isLightMode) "#FFFFFF" else "#0F1117", 36f, bgCardStrokeHex)
        }

        rootLayout.addView(createDialogHeaderTitle("🎨 Выбор темы оформления"))

        val listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundRect(bgCardHex, 22f, bgCardStrokeHex)
            setPadding(16, 8, 16, 8)
        }

        val themes = listOf(
            Triple("green", "🟢 Neon Green (Классический неон)", "#00E676"),
            Triple("blue", "🔵 Cyber Blue (Неоновый голубой)", "#00B0FF"),
            Triple("purple", "🟣 Synthwave Purple (Фиолетовый кибер)", "#E040FB"),
            Triple("amber", "🟠 Amber Glow (Янтарный премиум)", "#FFAB00")
        )

        for ((index, item) in themes.withIndex()) {
            val isSelected = (currentThemeKey == item.first)

            val themeTv = TextView(this).apply {
                text = item.second
                textSize = 15f
                setTextColor(if (isSelected) Color.parseColor(item.third) else Color.parseColor(textMainColorHex))
                typeface = if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setPadding(20, 26, 20, 26)
                setOnClickListener {
                    vibrateClick(true)
                    themeDialog?.dismiss()
                    saveAndApplyTheme(item.first, isLightMode)
                    showCustomizationDialog()
                }
            }
            listBox.addView(themeTv)

            if (index < themes.size - 1) {
                listBox.addView(createHorizontalDivider())
            }
        }

        rootLayout.addView(listBox)

        val backBtn = TextView(this).apply {
            text = "Назад к кастомизации"
            textSize = 15f
            setTextColor(Color.parseColor(accentColorHex))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = createRoundRect(bgCardHex, 22f, bgCardStrokeHex)
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 105)
            params.setMargins(0, 16, 0, 0)
            layoutParams = params
            setOnClickListener {
                vibrateClick()
                themeDialog?.dismiss()
                showCustomizationDialog()
            }
        }
        rootLayout.addView(backBtn)

        themeDialog = AlertDialog.Builder(this)
            .setView(rootLayout)
            .create()

        themeDialog?.let {
            setupDialogBackHandling(it) {
                showCustomizationDialog()
            }
        }
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

        val inactiveColor = Color.parseColor(textSubColorHex)
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

                modeCard.background = createRoundRect(if (isLightMode) "#FFEBEE" else "#1F1214", 30f, "#FF5252")
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

                modeCard.background = createRoundRect(if (isLightMode) "#FFEBEE" else "#1F1214", 30f, "#FF5252")
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
                setPadding(28, 24, 20, 24)
                background = if (isSelected) {
                    createRoundRect(accentCardBgHex, 26f, accentColorHex)
                } else {
                    createRoundRect(bgCardHex, 26f, bgCardStrokeHex)
                }
                val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                params.setMargins(0, 0, 0, 14)
                layoutParams = params

                setOnClickListener {
                    vibrateClick(true)
                    applyDnsMode("hostname", server.host)
                    Toast.makeText(this@MainActivity, "Подключен: ${server.name}", Toast.LENGTH_SHORT).show()
                }

                setOnLongClickListener {
                    vibrateClick(true)
                    val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cb.setPrimaryClip(ClipData.newPlainText("dns_host", server.host))
                    Toast.makeText(this@MainActivity, "📋 Адрес скопирован: ${server.host}", Toast.LENGTH_SHORT).show()
                    true
                }
            }

            val shieldView = View(this).apply {
                val sizeInPx = (24 * resources.displayMetrics.density).toInt()
                val params = LinearLayout.LayoutParams(sizeInPx, sizeInPx)
                params.setMargins(0, 0, 16, 0)
                layoutParams = params

                val shieldColor = if (isSelected) Color.parseColor(accentColorHex) else Color.parseColor(textSubColorHex)
                background = ShieldDrawable(shieldColor, isFilled = isSelected)
            }
            card.addView(shieldView)

            val infoLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val nameRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val nameTv = TextView(this).apply {
                text = server.name
                textSize = 16f
                setTextColor(Color.parseColor(textMainColorHex))
                typeface = Typeface.DEFAULT_BOLD
            }
            nameRow.addView(nameTv)

            if (isSelected) {
                val onlineTag = TextView(this).apply {
                    text = "ONLINE"
                    textSize = 9f
                    setTextColor(Color.parseColor(accentColorHex))
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(14, 4, 14, 4)
                    background = createRoundRect(if (isLightMode) "#FFFFFF" else "#08080A", 12f, accentColorHex)
                    val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    params.setMargins(16, 0, 0, 0)
                    layoutParams = params
                }
                nameRow.addView(onlineTag)
            }

            val pingPart = when (server.ping) {
                -1 -> ""
                -2 -> " • ⏳..."
                -3 -> " • ❌ Блок (853)"
                else -> " • ⚡ ${server.ping} мс"
            }

            val subTv = TextView(this).apply {
                text = "${server.host}$pingPart"
                textSize = 13f
                setTextColor(
                    if (server.ping == -3) Color.parseColor("#FF5252")
                    else Color.parseColor(textSubColorHex)
                )
                setPadding(0, 6, 0, 0)
            }

            infoLayout.addView(nameRow)
            infoLayout.addView(subTv)
            card.addView(infoLayout)

            val moreBtn = TextView(this).apply {
                text = "⋮"
                textSize = 22f
                setTextColor(Color.parseColor(textSubColorHex))
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = createRoundRect(if (isLightMode) "#EFEFF1" else "#181B22", 18f, bgCardStrokeHex)
                val params = LinearLayout.LayoutParams(88, 88)
                params.setMargins(12, 0, 4, 0)
                layoutParams = params
                setOnClickListener {
                    vibrateClick()
                    showServerActionsDialog(index)
                }
            }

            card.addView(moreBtn)
            listContainer.addView(card)
        }
    }

    private fun checkNetworkAndTestAll(autoConnectBest: Boolean = false) {
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
        netStatusText.text = "$netType • Проверка порта 853 и DNS..."

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

            val dnsResolves = try {
                val res = InetAddress.getAllByName("google.com")
                res != null && res.isNotEmpty()
            } catch (_: Exception) {
                false
            }

            runOnUiThread {
                if (prefs.getBoolean("auto_sort_ping", true)) {
                    serverList.sortWith(Comparator { a, b ->
                        val pA = if (a.ping > 0) a.ping else Int.MAX_VALUE
                        val pB = if (b.ping > 0) b.ping else Int.MAX_VALUE
                        pA.compareTo(pB)
                    })
                }
                saveServers()
                val workingCount = serverList.count { it.ping > 0 }
                val resolveBadge = if (dnsResolves) "DNS: OK ✅" else "DNS: Сбой ⚠️"
                netStatusText.text = "$netType • DoT: $workingCount/${serverList.size} • $resolveBadge"
                renderServerList()

                if (autoConnectBest) {
                    val best = serverList.filter { it.ping > 0 }.minByOrNull { it.ping }
                    if (best != null) {
                        vibrateClick(true)
                        applyDnsMode("hostname", best.host)
                        Toast.makeText(this, "⚡ Автовыбор: подключен ${best.name} (${best.ping} мс)", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "❌ Нет доступных серверов по порту 853", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }.start()
    }

    private fun showEditServerDialog(
        editIndex: Int?,
        returnToSettings: Boolean = false,
        returnToActions: Boolean = false
    ) {
        val idx = editIndex ?: -1
        val isEdit = idx >= 0
        val current = if (isEdit) serverList[idx] else null
        var editDialog: AlertDialog? = null

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 28, 36, 28)
            background = createRoundRect(if (isLightMode) "#FFFFFF" else "#0F1117", 36f, bgCardStrokeHex)
        }

        rootLayout.addView(createDialogHeaderTitle(if (isEdit) "✏️ Редактировать сервер" else "➕ Добавить свой сервер"))

        val nameInput = EditText(this).apply {
            hint = "Название (например, Comss.one)"
            setText(current?.name ?: "")
            setTextColor(Color.parseColor(textMainColorHex))
            setHintTextColor(Color.parseColor(textSubColorHex))
            setPadding(28, 24, 28, 24)
            background = createRoundRect(bgCardHex, 20f, bgCardStrokeHex)
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 0, 0, 14)
            layoutParams = params
        }

        val hostInput = EditText(this).apply {
            hint = "Адрес хоста (например, dns.comss.one)"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(current?.host ?: "")
            setTextColor(Color.parseColor(textMainColorHex))
            setHintTextColor(Color.parseColor(textSubColorHex))
            setPadding(28, 24, 28, 24)
            background = createRoundRect(bgCardHex, 20f, bgCardStrokeHex)
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 0, 0, 12)
            layoutParams = params
        }

        val noteTv = TextView(this).apply {
            text = "ℹ️ Префиксы https:// или tls:// очищаются автоматически."
            textSize = 12f
            setTextColor(Color.parseColor(textSubColorHex))
            setPadding(8, 4, 8, 20)
        }

        rootLayout.addView(nameInput)
        rootLayout.addView(hostInput)
        rootLayout.addView(noteTv)

        val saveBtn = TextView(this).apply {
            text = "Сохранить"
            textSize = 15f
            setTextColor(Color.parseColor(accentColorHex))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = createRoundRect(accentCardBgHex, 22f, accentColorHex)
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 105)
            params.setMargins(0, 0, 0, 10)
            layoutParams = params
            setOnClickListener {
                vibrateClick(true)
                val cleanHost = sanitizeHost(hostInput.text.toString())
                val rawName = nameInput.text.toString().trim()
                val cleanName = if (rawName.isEmpty()) cleanHost else rawName

                if (cleanHost.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Введите адрес сервера", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                if (isEdit) {
                    serverList[idx].name = cleanName
                    serverList[idx].host = cleanHost
                } else {
                    serverList.add(DnsServer(cleanName, cleanHost))
                }
                saveServers()
                editDialog?.dismiss()
                checkNetworkAndTestAll()
            }
        }

        val cancelBtn = TextView(this).apply {
            text = "Назад"
            textSize = 15f
            setTextColor(Color.parseColor(textSubColorHex))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = createRoundRect(bgCardHex, 22f, bgCardStrokeHex)
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 105)
            layoutParams = params
            setOnClickListener {
                vibrateClick()
                editDialog?.dismiss()
                if (returnToSettings) showSettingsDialog()
                else if (returnToActions && idx >= 0) showServerActionsDialog(idx)
            }
        }

        rootLayout.addView(saveBtn)
        rootLayout.addView(cancelBtn)

        editDialog = AlertDialog.Builder(this)
            .setView(rootLayout)
            .create()

        editDialog?.let {
            setupDialogBackHandling(it) {
                if (returnToSettings) showSettingsDialog()
                else if (returnToActions && idx >= 0) showServerActionsDialog(idx)
            }
        }
        editDialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        editDialog?.show()
    }

    private fun showPresetsDialog() {
        var dialogInstance: AlertDialog? = null

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 28, 36, 28)
            background = createRoundRect(if (isLightMode) "#FFFFFF" else "#0F1117", 36f, bgCardStrokeHex)
        }

        rootLayout.addView(createDialogHeaderTitle("📚 Популярные DNS-серверы"))

        val scrollView = ScrollView(this).apply {
            val maxHeight = (resources.displayMetrics.heightPixels * 0.50).toInt()
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxHeight)
        }

        val listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundRect(bgCardHex, 22f, bgCardStrokeHex)
            setPadding(16, 8, 16, 8)
        }

        for ((index, chosen) in popularCatalog.withIndex()) {
            val itemLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 22, 20, 22)
                setOnClickListener {
                    vibrateClick()
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
                setTextColor(Color.parseColor(textMainColorHex))
                typeface = Typeface.DEFAULT_BOLD
            }

            val hostTv = TextView(this).apply {
                text = chosen.host
                textSize = 13f
                setTextColor(Color.parseColor(textSubColorHex))
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
            background = createRoundRect(bgCardHex, 22f, bgCardStrokeHex)
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 105)
            params.setMargins(0, 16, 0, 10)
            layoutParams = params
            setOnClickListener {
                vibrateClick(true)
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

        val backBtn = TextView(this).apply {
            text = "Назад к настройкам"
            textSize = 15f
            setTextColor(Color.parseColor(textSubColorHex))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = createRoundRect(bgCardHex, 22f, bgCardStrokeHex)
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 105)
            layoutParams = params
            setOnClickListener {
                vibrateClick()
                dialogInstance?.dismiss()
                showSettingsDialog()
            }
        }
        rootLayout.addView(backBtn)

        dialogInstance = AlertDialog.Builder(this)
            .setView(rootLayout)
            .create()

        dialogInstance?.let {
            setupDialogBackHandling(it) {
                showSettingsDialog()
            }
        }
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
            background = createRoundRect(if (isLightMode) "#FFFFFF" else "#0F1117", 36f, bgCardStrokeHex)
        }

        rootLayout.addView(createDialogHeaderTitle("💾 Бэкап и восстановление"))

        val listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundRect(bgCardHex, 22f, bgCardStrokeHex)
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
                setTextColor(Color.parseColor(textMainColorHex))
                setPadding(20, 26, 20, 26)
                setOnClickListener {
                    vibrateClick()
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

        val backBtn = TextView(this).apply {
            text = "Назад к настройкам"
            textSize = 15f
            setTextColor(Color.parseColor(textSubColorHex))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = createRoundRect(bgCardHex, 22f, bgCardStrokeHex)
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 105)
            params.setMargins(0, 16, 0, 0)
            layoutParams = params
            setOnClickListener {
                vibrateClick()
                dialogInstance?.dismiss()
                showSettingsDialog()
            }
        }
        rootLayout.addView(backBtn)

        dialogInstance = AlertDialog.Builder(this)
            .setView(rootLayout)
            .create()

        dialogInstance?.let {
            setupDialogBackHandling(it) {
                showSettingsDialog()
            }
        }
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
            background = createRoundRect(if (isLightMode) "#FFFFFF" else "#0F1117", 36f, bgCardStrokeHex)
        }

        rootLayout.addView(createDialogHeaderTitle("🔐 Системное разрешение"))

        val infoBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundRect(bgCardHex, 22f, bgCardStrokeHex)
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
            setTextColor(Color.parseColor(textSubColorHex))
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
                vibrateClick(true)
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
            background = createRoundRect(if (isLightMode) "#E3F2FD" else "#101A24", 22f, "#00B0FF")
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 115)
            params.setMargins(0, 0, 0, 12)
            layoutParams = params
            setOnClickListener {
                vibrateClick()
                dialogInstance?.dismiss()
                showShizukuGuideDialog()
            }
        }
        rootLayout.addView(guideBtn)

        val copyAdbBtn = TextView(this).apply {
            text = "📋 Скопировать команду ADB"
            textSize = 14f
            setTextColor(Color.parseColor(textMainColorHex))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = createRoundRect(bgCardHex, 22f, bgCardStrokeHex)
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 105)
            params.setMargins(0, 0, 0, 12)
            layoutParams = params
            setOnClickListener {
                vibrateClick()
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("adb", adbCommand))
                Toast.makeText(this@MainActivity, "Команда скопирована", Toast.LENGTH_SHORT).show()
            }
        }
        rootLayout.addView(copyAdbBtn)

        val closeBtn = TextView(this).apply {
            text = "Закрыть"
            textSize = 15f
            setTextColor(Color.parseColor(textSubColorHex))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = createRoundRect(bgCardHex, 22f, bgCardStrokeHex)
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 105)
            layoutParams = params
            setOnClickListener {
                vibrateClick()
                dialogInstance?.dismiss()
            }
        }
        rootLayout.addView(closeBtn)

        dialogInstance = AlertDialog.Builder(this)
            .setView(rootLayout)
            .create()

        dialogInstance?.let { setupDialogBackHandling(it) }
        dialogInstance?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialogInstance?.show()
    }

    private fun showShizukuGuideDialog() {
        var guideDialog: AlertDialog? = null

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 28, 36, 28)
            background = createRoundRect(if (isLightMode) "#FFFFFF" else "#0F1117", 36f, bgCardStrokeHex)
        }

        rootLayout.addView(createDialogHeaderTitle("📖 Инструкция по Shizuku"))

        val scrollView = ScrollView(this).apply {
            val maxHeight = (resources.displayMetrics.heightPixels * 0.55).toInt()
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxHeight)
        }

        val stepsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundRect(bgCardHex, 22f, bgCardStrokeHex)
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
                setTextColor(Color.parseColor(textSubColorHex))
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
            background = createRoundRect(bgCardHex, 20f, bgCardStrokeHex)
            val params = LinearLayout.LayoutParams(0, 95, 1f)
            params.setMargins(0, 0, 8, 0)
            layoutParams = params
            setOnClickListener {
                vibrateClick()
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api")))
                } catch (_: Exception) {}
            }
        }

        val githubBtn = TextView(this).apply {
            text = "📦 GitHub (APK)"
            textSize = 13f
            setTextColor(Color.parseColor("#00B0FF"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = createRoundRect(bgCardHex, 20f, bgCardStrokeHex)
            val params = LinearLayout.LayoutParams(0, 95, 1f)
            params.setMargins(8, 0, 0, 0)
            layoutParams = params
            setOnClickListener {
                vibrateClick()
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
            background = createRoundRect(bgCardHex, 22f, bgCardStrokeHex)
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 105)
            layoutParams = params
            setOnClickListener {
                vibrateClick()
                guideDialog?.dismiss()
                showAdbDialog()
            }
        }
        rootLayout.addView(backBtn)

        guideDialog = AlertDialog.Builder(this)
            .setView(rootLayout)
            .create()

        guideDialog?.let {
            setupDialogBackHandling(it) {
                showAdbDialog()
            }
        }
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
        HapticUtil.vibrate(this, true)

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
