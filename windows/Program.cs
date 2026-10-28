using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Microsoft.Win32;
using Forms = System.Windows.Forms;
using MediaBrush = System.Windows.Media.Brush;
using MediaBrushes = System.Windows.Media.Brushes;
using MediaFontFamily = System.Windows.Media.FontFamily;

namespace DnsSwitcherPro
{
    public class DnsServer
    {
        public string name { get; set; } = "";
        public string host { get; set; } = "";
        public int ping { get; set; } = -1;
    }

    public class BrowserConflictInfo
    {
        public string BrowserName { get; set; } = "";
        public string LocalStatePath { get; set; } = "";
        public string PolicyRegistryPath { get; set; } = "";
        public string CurrentTemplate { get; set; } = "";
    }

    public class AppConfig
    {
        public string Theme { get; set; } = "green";
        public bool LightMode { get; set; } = false;
        public bool AutoSortPing { get; set; } = true;
        public bool EnableDohEncryption { get; set; } = true;
        public bool BlockIpv6 { get; set; } = true;
        public bool AutoFixBrowserDns { get; set; } = false;
        public bool EnableDpiBypass { get; set; } = false;
        public int DpiStrategyProfile { get; set; } = 1;
        public bool StartWithWindows { get; set; } = true;
        public bool FirstRunCompleted { get; set; } = false;
        public string CurrentMode { get; set; } = "off";
        public string LastActiveHost { get; set; } = "dns.comss.one";
        public List<DnsServer> Servers { get; set; } = new();
    }

    public class Program
    {
        [STAThread]
        public static void Main(string[] args)
        {
            bool startMinimized = args.Any(a => a.Equals("--minimized", StringComparison.OrdinalIgnoreCase));
            var app = new Application();
            var mainWindow = new MainWindow(startMinimized);
            app.Run(mainWindow);
        }
    }

    public class MainWindow : Window
    {
        private AppConfig config = new();
        private readonly string configPath;
        private readonly bool launchMinimized;
        private List<DnsServer> serverList = new();
        private const string AutoStartTaskName = "DnsSwitcherProAutoStart";
        private const string HostsBlockStartMarker = "# >>> DNS_SWITCHER_PRO_GEMINI_GUARD_START >>>";
        private const string HostsBlockEndMarker = "# <<< DNS_SWITCHER_PRO_GEMINI_GUARD_END <<<";
        private static readonly UTF8Encoding Utf8NoBom = new UTF8Encoding(false);

        private Process? dpiProcess = null;
        private bool isStartingDpi = false;

        private ScrollViewer rootScroll = null!;
        private StackPanel mainLayout = null!;
        private StackPanel listContainer = null!;
        private Border netStatusBorder = null!;
        private TextBlock netStatusText = null!;
        private Border modeCard = null!;
        private TextBlock modeTitle = null!;
        private RadioButton rbOff = null!;
        private RadioButton rbAuto = null!;
        private RadioButton rbOn = null!;
        private TextBlock titleApp = null!;
        private Border settingsBtn = null!;
        private TextBlock settingsGearText = null!;
        private TextBlock listHeader = null!;
        private Border pingBtnBorder = null!;
        private TextBlock pingBtnText = null!;
        private Border checkGeminiRegionBtn = null!;
        private TextBlock checkGeminiRegionText = null!;
        private Border clearGeminiCacheBtn = null!;
        private Border dpiBypassBtn = null!;
        private TextBlock dpiBypassText = null!;

        private Border browserWarningBanner = null!;
        private TextBlock browserWarningText = null!;

        private Border undoBanner = null!;
        private TextBlock undoText = null!;
        private TextBlock undoActionBtn = null!;
        private DnsServer? lastDeletedServer = null;
        private int lastDeletedIndex = -1;
        private DispatcherTimer undoTimer = null!;

        private DispatcherTimer ipv6GuardTimer = null!;
        private DateTime lastGeminiBlockNotify = DateTime.MinValue;
        private bool? lastKnownHostsBlockState = null;
        private bool browserWarningShownThisSession = false;

        private Forms.NotifyIcon trayIcon = null!;
        private bool isUpdatingUi = false;
        private bool isRecalculatingHeight = false;

        private string accentColorHex = "#00E676";
        private string accentCardBgHex = "#0D2318";
        private string bgRootHex = "#08080A";
        private string bgCardHex = "#121418";
        private string bgCardStrokeHex = "#1E222B";
        private string textMainColorHex = "#CBD5E1";
        private string textSubColorHex = "#8A99AD";
        private string gearIconColorHex = "#F1F5F9";
        private string gearBgHex = "#1E2430";

        private readonly Dictionary<string, string[]> knownIps = new(StringComparer.OrdinalIgnoreCase)
        {
            { "dns.comss.one", new[] { "83.220.169.155", "212.109.195.93" } },
            { "comss.dns.controld.com", new[] { "76.76.2.22", "76.76.10.22" } },
            { "dns.malw.link", new[] { "193.23.209.189", "45.95.233.23" } },
            { "geohide.ru", new[] { "5.35.91.161" } },
            { "dns.astracat.network", new[] { "185.236.231.114" } },
            { "xbox-dns.ru", new[] { "176.99.11.77", "80.78.247.254" } },
            { "dns.quad9.net", new[] { "9.9.9.9", "149.112.112.112" } },
            { "dns.adguard-dns.com", new[] { "94.140.14.14", "94.140.15.15" } },
            { "family.adguard-dns.com", new[] { "94.140.14.15", "94.140.15.16" } },
            { "one.one.one.one", new[] { "1.1.1.1", "1.0.0.1" } },
            { "security.cloudflare-dns.com", new[] { "1.1.1.2", "1.0.0.2" } },
            { "dns.google", new[] { "8.8.8.8", "8.8.4.4" } },
            { "freedns.controld.com", new[] { "76.76.2.0", "76.76.10.0" } },
            { "dns.mullvad.net", new[] { "194.242.2.2" } },
            { "security-filter-dns.cleanbrowsing.org", new[] { "185.228.168.9", "185.228.169.9" } }
        };

        private readonly List<DnsServer> defaultServers = new()
        {
            new DnsServer { name = "Comss.one", host = "dns.comss.one" },
            new DnsServer { name = "Мобильная -1", host = "comss.dns.controld.com" },
            new DnsServer { name = "📌 Malw.link", host = "dns.malw.link" },
            new DnsServer { name = "🌍 GeoHide", host = "geohide.ru" },
            new DnsServer { name = "Мобильная -2", host = "dns.astracat.network" },
            new DnsServer { name = "Xbox", host = "xbox-dns.ru" },
            new DnsServer { name = "Quad9", host = "dns.quad9.net" }
        };

        private readonly List<DnsServer> popularCatalog = new()
        {
            new DnsServer { name = "AdGuard DNS (Без рекламы)", host = "dns.adguard-dns.com" },
            new DnsServer { name = "AdGuard Семейный", host = "family.adguard-dns.com" },
            new DnsServer { name = "Cloudflare (Быстрый)", host = "one.one.one.one" },
            new DnsServer { name = "Cloudflare Malware Block", host = "security.cloudflare-dns.com" },
            new DnsServer { name = "Google Public DNS", host = "dns.google" },
            new DnsServer { name = "Control D (Без рекламы)", host = "freedns.controld.com" },
            new DnsServer { name = "Mullvad DNS (Приватность)", host = "dns.mullvad.net" },
            new DnsServer { name = "CleanBrowsing (Фильтр)", host = "security-filter-dns.cleanbrowsing.org" },
            new DnsServer { name = "Comss.one", host = "dns.comss.one" },
            new DnsServer { name = "Мобильная -1", host = "comss.dns.controld.com" },
            new DnsServer { name = "📌 Malw.link", host = "dns.malw.link" },
            new DnsServer { name = "🌍 GeoHide", host = "geohide.ru" },
            new DnsServer { name = "Мобильная -2", host = "dns.astracat.network" },
            new DnsServer { name = "Xbox", host = "xbox-dns.ru" },
            new DnsServer { name = "Quad9", host = "dns.quad9.net" }
        };

        public MainWindow(bool startMinimized)
        {
            launchMinimized = startMinimized;
            string appData = Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData);
            string dir = Path.Combine(appData, "DnsSwitcherPro");
            Directory.CreateDirectory(dir);
            configPath = Path.Combine(dir, "config.json");

            Title = "DNS Switcher Pro";
            Width = 440;
            MinWidth = 400;
            MinHeight = 440;
            ResizeMode = ResizeMode.CanResize;
            WindowStartupLocation = WindowStartupLocation.CenterScreen;

            LoadConfig();
            ApplyThemePalette();
            ApplyCustomWindowAndTrayIcon();
            SetupUI();
            SetupTrayIcon();

            StateChanged += (s, e) =>
            {
                if (WindowState == WindowState.Maximized)
                {
                    SizeToContent = SizeToContent.Manual;
                    UpdateScrollBarVisibility();
                }
                else if (WindowState == WindowState.Normal)
                {
                    RecalculateWindowHeight();
                }
            };

            undoTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(6) };
            undoTimer.Tick += (s, e) =>
            {
                undoBanner.Visibility = Visibility.Collapsed;
                RecalculateWindowHeight();
                undoTimer.Stop();
            };

            ipv6GuardTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(2500) };
            ipv6GuardTimer.Tick += (s, e) =>
            {
                EnforceGeminiIpv6Guard();
                MonitorBrowserDnsSettings(showPopupOnConflict: false);
                EnsureDpiProcessAlive();
            };

            Loaded += async (s, e) =>
            {
                RecalculateWindowHeight();

                if (config.StartWithWindows)
                {
                    await Task.Run(() => SetAutoStartTask(true));
                }

                if (config.EnableDpiBypass)
                {
                    await Task.Run(() => StartDpiBypass(silent: true));
                }

                await Task.Run(() => EnforceIpv6Policy());

                DetectInitialSystemMode();
                RefreshSystemState();
                CheckNetworkAndTestAll();

                ipv6GuardTimer.Start();
                MonitorBrowserDnsSettings(showPopupOnConflict: !launchMinimized);

                if (launchMinimized)
                {
                    Hide();
                    trayIcon.ShowBalloonTip(
                        2000,
                        "⚡ DNS Switcher Pro активен",
                        "Защита DNS, IPv4 Only и обход DPI работают в фоновом режиме.",
                        Forms.ToolTipIcon.Info);
                }
            };

            Closing += (s, e) =>
            {
                e.Cancel = true;
                Hide();
                trayIcon.ShowBalloonTip(
                    1500,
                    "DNS Switcher Pro",
                    "Приложение работает в фоне. Правый клик по иконке — быстрое переключение DNS.",
                    Forms.ToolTipIcon.Info);
            };
        }

        // =========================================================================
        // ДИАГНОСТИКА СКРЫТИЯ МЕСТОПОЛОЖЕНИЯ ДЛЯ GOOGLE GEMINI (WINDOWS)
        // =========================================================================
        private bool IsDirectGoogleIp(string ip)
        {
            string[] directPrefixes = new[]
            {
                "142.250.", "142.251.", "172.217.", "172.253.",
                "216.58.", "74.125.", "173.194.", "108.177.",
                "64.233.", "209.85.", "216.239."
            };
            return directPrefixes.Any(p => ip.StartsWith(p, StringComparison.OrdinalIgnoreCase));
        }

        private void ShowGeminiRegionCheckDialog(bool returnToSettings = false, bool returnToNetworkSettings = false)
        {
            var dlg = CreateModalDialog("Защита и регион Gemini", () =>
            {
                if (returnToNetworkSettings) ShowNetworkSettingsDialog();
                else if (returnToSettings) ShowSettingsDialog();
            });

            var root = new StackPanel();
            root.Children.Add(CreateDialogHeader("🕵️ Защита и регион Gemini"));

            var verdictTitle = new TextBlock
            {
                Text = "⏳ Выполняется проверка маршрутизации...",
                FontSize = 14,
                FontWeight = FontWeights.Bold,
                Foreground = BrushFromHex(accentColorHex),
                TextWrapping = TextWrapping.Wrap
            };

            var verdictDetails = new TextBlock
            {
                Text = "Проверяем перехват домена gemini.google.com, геолокацию прокси-узла, защиту IPv6 и статус капчи Google...",
                FontSize = 12,
                Foreground = BrushFromHex(textSubColorHex),
                Margin = new Thickness(0, 8, 0, 0),
                TextWrapping = TextWrapping.Wrap
            };

            var verdictStack = new StackPanel();
            verdictStack.Children.Add(verdictTitle);
            verdictStack.Children.Add(verdictDetails);

            var verdictCard = new Border
            {
                Background = BrushFromHex(bgCardHex),
                BorderBrush = BrushFromHex(accentColorHex),
                BorderThickness = new Thickness(1.5),
                CornerRadius = new CornerRadius(14),
                Padding = new Thickness(14, 12, 14, 12),
                Margin = new Thickness(0, 0, 0, 10),
                Child = verdictStack
            };
            root.Children.Add(verdictCard);

            Border switchNextBtn = null!;
            switchNextBtn = CreateDialogButton("🔄 Сменить DNS-сервер (Обойти капчу / блок)", "#FFAB00", () =>
            {
                string currentHost = config.LastActiveHost;
                var candidates = serverList
                    .Where(s => s.ping != -3 && !s.host.Equals(currentHost, StringComparison.OrdinalIgnoreCase))
                    .ToList();
                var nextServer = candidates.FirstOrDefault()
                                 ?? serverList.FirstOrDefault(s => !s.host.Equals(currentHost, StringComparison.OrdinalIgnoreCase));
                if (nextServer != null)
                {
                    ApplyWindowsDnsServer(nextServer.host);
                    dlg.Close();
                    ShowGeminiRegionCheckDialog(returnToSettings, returnToNetworkSettings);
                }
            }, "#261A00", "#FFAB00");
            switchNextBtn.Visibility = Visibility.Collapsed;
            root.Children.Add(switchNextBtn);

            async void RunDiagnosticAsync()
            {
                verdictTitle.Text = "⏳ Выполняется проверка маршрутизации...";
                verdictTitle.Foreground = BrushFromHex(accentColorHex);
                verdictCard.BorderBrush = BrushFromHex(accentColorHex);
                verdictCard.Background = BrushFromHex(bgCardHex);
                verdictDetails.Text = "Опрашиваем системный DNS и сервер gemini.google.com...";
                switchNextBtn.Visibility = Visibility.Collapsed;

                string mode = config.CurrentMode;
                string activeHost = config.LastActiveHost;
                string dnsLabel = mode == "on" && !string.IsNullOrEmpty(activeHost) ? activeHost : $"Выключен ({mode})";

                string ipv4ProxyIp = "";
                bool hasIpv6Leak = !config.BlockIpv6;
                bool isInterceptedByProxy = false;
                string proxyCountry = "Неизвестно";
                string proxyCountryCode = "";
                string proxyOrg = "";
                bool isCaptchaDetected = false;
                bool isRegionUnsupportedRedirect = false;
                var browserConflicts = CheckBrowserDnsConflicts();

                await Task.Run(async () =>
                {
                    try
                    {
                        RunCommand("ipconfig /flushdns");
                        var addresses = Dns.GetHostAddresses("gemini.google.com");
                        var ipv4List = addresses
                            .Where(a => a.AddressFamily == AddressFamily.InterNetwork)
                            .Select(a => a.ToString())
                            .Where(ip => ip != "0.0.0.0" && ip != "127.0.0.1")
                            .ToList();

                        var ipv6List = addresses
                            .Where(a => a.AddressFamily == AddressFamily.InterNetworkV6)
                            .Select(a => a.ToString())
                            .ToList();

                        if (ipv4List.Count > 0)
                        {
                            ipv4ProxyIp = ipv4List[0];
                            isInterceptedByProxy = !IsDirectGoogleIp(ipv4ProxyIp);
                        }

                        if (ipv6List.Any(ip => ip.StartsWith("2a00:1450:", StringComparison.OrdinalIgnoreCase) ||
                                               ip.StartsWith("2607:f8b0:", StringComparison.OrdinalIgnoreCase)))
                        {
                            hasIpv6Leak = true;
                        }

                        using var handler = new HttpClientHandler { AllowAutoRedirect = false };
                        using var http = new HttpClient(handler) { Timeout = TimeSpan.FromMilliseconds(4500) };
                        http.DefaultRequestHeaders.UserAgent.ParseAdd(
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36");

                        if (!string.IsNullOrEmpty(ipv4ProxyIp))
                        {
                            try
                            {
                                string geoJson = await http.GetStringAsync(
                                    $"http://ip-api.com/json/{ipv4ProxyIp}?fields=status,country,countryCode,org,isp&lang=ru");
                                var node = JsonNode.Parse(geoJson);
                                if (node?["status"]?.GetValue<string>() == "success")
                                {
                                    proxyCountry = node["country"]?.GetValue<string>() ?? "Неизвестно";
                                    proxyCountryCode = node["countryCode"]?.GetValue<string>() ?? "";
                                    proxyOrg = node["org"]?.GetValue<string>() ?? node["isp"]?.GetValue<string>() ?? "";
                                }
                            }
                            catch { }
                        }

                        try
                        {
                            using var resp = await http.GetAsync("https://gemini.google.com/app");
                            int code = (int)resp.StatusCode;
                            string loc = resp.Headers.Location?.ToString() ?? "";

                            if (code == 429 || code == 503 ||
                                loc.Contains("/sorry/", StringComparison.OrdinalIgnoreCase) ||
                                loc.Contains("ipv4.google.com/sorry", StringComparison.OrdinalIgnoreCase))
                            {
                                isCaptchaDetected = true;
                            }
                            else if (loc.Contains("faq", StringComparison.OrdinalIgnoreCase) ||
                                     loc.Contains("unsupported", StringComparison.OrdinalIgnoreCase))
                            {
                                isRegionUnsupportedRedirect = true;
                            }
                        }
                        catch { }
                    }
                    catch { }
                });

                var report = new StringBuilder();
                report.AppendLine($"• Системный DNS: {dnsLabel}");
                report.AppendLine($"• IP для gemini.google.com: {(string.IsNullOrEmpty(ipv4ProxyIp) ? "Нет ответа ❌" : ipv4ProxyIp)}");
                if (!string.IsNullOrEmpty(proxyCountryCode))
                {
                    report.AppendLine($"• Видимая страна для Gemini: {proxyCountry} ({proxyCountryCode})");
                }
                if (!string.IsNullOrEmpty(proxyOrg))
                {
                    report.AppendLine($"• Узел маршрутизации: {proxyOrg}");
                }
                report.AppendLine($"• Защита от утечки IPv6: {(hasIpv6Leak ? "⚠️ IPv6 включён (риск утечки региона)" : "✅ Защищено (IPv4 Only)")}");
                report.AppendLine($"• Браузеры: {(browserConflicts.Count > 0 ? $"⚠️ Конфликт в {string.Join(", ", browserConflicts.Select(c => c.BrowserName))}" : "✅ Подчиняются системе")}");

                if (mode != "on")
                {
                    verdictTitle.Text = "❌ Реальное местоположение НЕ скрыто!";
                    verdictTitle.Foreground = BrushFromHex("#FF5252");
                    verdictCard.BorderBrush = BrushFromHex("#FF5252");
                    verdictCard.Background = BrushFromHex(config.LightMode ? "#FFEBEE" : "#1F1214");
                    report.Append("\nПричина: Системный режим DNS отключен или стоит в режиме «Авто». Включите режим «Вкл» и выберите сервер (например, Comss.one или Malw.link).");
                    switchNextBtn.Visibility = Visibility.Visible;
                }
                else if (string.IsNullOrEmpty(ipv4ProxyIp))
                {
                    verdictTitle.Text = "⚠️ Нет ответа от текущего DNS-сервера";
                    verdictTitle.Foreground = BrushFromHex("#FF5252");
                    verdictCard.BorderBrush = BrushFromHex("#FF5252");
                    verdictCard.Background = BrushFromHex(config.LightMode ? "#FFEBEE" : "#1F1214");
                    report.Append("\nНе удалось разрешить домен gemini.google.com. Нажмите кнопку ниже для переключения на другой сервер.");
                    switchNextBtn.Visibility = Visibility.Visible;
                }
                else if (!isInterceptedByProxy ||
                         proxyCountryCode.Equals("RU", StringComparison.OrdinalIgnoreCase) ||
                         proxyCountryCode.Equals("BY", StringComparison.OrdinalIgnoreCase) ||
                         isRegionUnsupportedRedirect)
                {
                    verdictTitle.Text = "❌ Местоположение НЕ скрыто от Gemini!";
                    verdictTitle.Foreground = BrushFromHex("#FF5252");
                    verdictCard.BorderBrush = BrushFromHex("#FF5252");
                    verdictCard.Background = BrushFromHex(config.LightMode ? "#FFEBEE" : "#1F1214");
                    report.Append($"\nПричина: Сервер ({activeHost}) направляет трафик напрямую в Google без маскировки региона. Переключитесь на Comss.one, Malw.link или GeoHide!");
                    switchNextBtn.Visibility = Visibility.Visible;
                }
                else if (isCaptchaDetected)
                {
                    verdictTitle.Text = "⚠️ Местоположение скрыто, но IP перегружен (Капча)";
                    verdictTitle.Foreground = BrushFromHex("#FFAB00");
                    verdictCard.BorderBrush = BrushFromHex("#FFAB00");
                    verdictCard.Background = BrushFromHex("#261A00");
                    report.Append($"\nВаше местоположение скрыто ({proxyCountry}), но Google временно выдал капчу «Подозрительный трафик» на прокси-IP {ipv4ProxyIp}.\n\nРешение: Нажмите «Сменить DNS-сервер» ниже и очистите кэш Gemini!");
                    switchNextBtn.Visibility = Visibility.Visible;
                }
                else if (browserConflicts.Count > 0)
                {
                    verdictTitle.Text = "⚠️ DNS настроен, но браузер обходит защиту!";
                    verdictTitle.Foreground = BrushFromHex("#FFAB00");
                    verdictCard.BorderBrush = BrushFromHex("#FFAB00");
                    verdictCard.Background = BrushFromHex("#261A00");
                    report.Append($"\nСистема направляет Gemini через «{proxyCountry}», но в браузере ({string.Join(", ", browserConflicts.Select(c => c.BrowserName))}) включён сторонний DNS, который выдаёт ваш реальный регион.");
                }
                else
                {
                    verdictTitle.Text = "✅ Местоположение успешно скрыто!";
                    verdictTitle.Foreground = BrushFromHex(accentColorHex);
                    verdictCard.BorderBrush = BrushFromHex(accentColorHex);
                    verdictCard.Background = BrushFromHex(accentCardBgHex);
                    report.Append($"\nОтлично! Google Gemini видит ваше подключение из региона «{proxyCountry} ({proxyCountryCode})». Региональных ограничений и капчи не обнаружено.");
                }

                verdictDetails.Text = report.ToString();
            }

            root.Children.Add(CreateDialogButton("🔄 Проверить ещё раз", accentColorHex, () =>
            {
                RunDiagnosticAsync();
            }, accentCardBgHex, accentColorHex));

            root.Children.Add(CreateDialogButton("🧹 Очистить кэш и куки Gemini", textMainColorHex, () =>
            {
                ClearGeminiCookiesAndCache();
            }));

            root.Children.Add(CreateDialogButton("Закрыть", textSubColorHex, () =>
            {
                dlg.Close();
                if (returnToNetworkSettings) ShowNetworkSettingsDialog();
                else if (returnToSettings) ShowSettingsDialog();
            }));

            dlg.Content = new Border
            {
                Background = BrushFromHex(config.LightMode ? "#FFFFFF" : "#0F1117"),
                BorderBrush = BrushFromHex(bgCardStrokeHex),
                BorderThickness = new Thickness(2),
                CornerRadius = new CornerRadius(20),
                Padding = new Thickness(22, 18, 22, 18),
                Child = root
            };

            dlg.Loaded += (s, e) => RunDiagnosticAsync();
            dlg.ShowDialog();
        }

        private void EnsureDpiProcessAlive()
        {
            if (!config.EnableDpiBypass || isStartingDpi) return;
            try
            {
                if (Process.GetProcessesByName("winws").Length == 0)
                {
                    StartDpiBypass(silent: true);
                }
            }
            catch { }
        }

        private string? FindDpiAsset(string dpiDir, string fileName)
        {
            string direct = Path.Combine(dpiDir, fileName);
            if (File.Exists(direct)) return direct;
            string inLists = Path.Combine(dpiDir, "lists", fileName);
            if (File.Exists(inLists)) return inLists;
            return null;
        }

        private string BuildExcludeHostlistFile(string dpiDir)
        {
            var excludeDomains = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
            {
                "gemini.google.com",
                "www.gemini.google.com",
                "aistudio.google.com",
                "makersuite.google.com",
                "alkalimakersuite-pa.clients6.google.com",
                "generativelanguage.googleapis.com",
                "proactivebackend-pa.googleapis.com",
                "notebooklm.google.com",
                "bard.google.com",
                "deepmind.google",
                "labs.google",
                "accounts.google.com",
                "ogs.google.com",
                "apis.google.com",
                "clients6.google.com",
                "gstatic.com",
                "www.gstatic.com",
                "ssl.gstatic.com",
                "chatgpt.com",
                "openai.com",
                "auth.openai.com",
                "oaistatic.com",
                "oaiusercontent.com",
                "claude.ai",
                "anthropic.com",
                "copilot.microsoft.com",
                "sydney.bing.com",
                "bing.com",
                "edgeservices.bing.com",
                "githubcopilot.com",
                "grok.com",
                "x.ai",
                "perplexity.ai",
                "elevenlabs.io",
                "midjourney.com",
                "spotify.com",
                "scdn.co",
                "xboxlive.com",
                "xbox.com",
                "microsoft.com",
                "live.com",
                "dns.comss.one",
                "comss.one",
                "comss.dns.controld.com",
                "controld.com",
                "freedns.controld.com",
                "dns.malw.link",
                "malw.link",
                "geohide.ru",
                "dns.astracat.network",
                "astracat.network",
                "xbox-dns.ru",
                "dns.quad9.net",
                "quad9.net",
                "dns.adguard-dns.com",
                "family.adguard-dns.com",
                "adguard-dns.com",
                "one.one.one.one",
                "cloudflare-dns.com",
                "security.cloudflare-dns.com",
                "dns.google",
                "dns.mullvad.net",
                "mullvad.net",
                "cleanbrowsing.org",
                "security-filter-dns.cleanbrowsing.org"
            };

            foreach (var s in serverList)
            {
                if (!string.IsNullOrWhiteSpace(s.host))
                {
                    excludeDomains.Add(s.host.Trim());
                }
            }

            string? flowsealExclude = FindDpiAsset(dpiDir, "list-exclude.txt");
            if (flowsealExclude != null && File.Exists(flowsealExclude))
            {
                try
                {
                    foreach (var rawLine in File.ReadAllLines(flowsealExclude, Encoding.UTF8))
                    {
                        string line = rawLine.Trim().TrimStart('\uFEFF');
                        if (!string.IsNullOrEmpty(line) && !line.StartsWith("#"))
                        {
                            excludeDomains.Add(line);
                        }
                    }
                }
                catch { }
            }

            string excludePath = Path.Combine(dpiDir, "list-exclude-gemini-dns.txt");
            try
            {
                File.WriteAllLines(excludePath, excludeDomains, Utf8NoBom);
            }
            catch { }

            return excludePath;
        }

        private string BuildCombinedHostlistFile(string dpiDir, HashSet<string> excludedSet)
        {
            var domains = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
            {
                "youtube.com",
                "www.youtube.com",
                "m.youtube.com",
                "music.youtube.com",
                "youtu.be",
                "googlevideo.com",
                "ytimg.com",
                "i.ytimg.com",
                "s.ytimg.com",
                "ggpht.com",
                "yt3.ggpht.com",
                "yt4.ggpht.com",
                "youtubei.googleapis.com",
                "youtubeembeddedplayer.googleapis.com",
                "ytimg.l.google.com",
                "jnn-pa.googleapis.com",
                "wide-youtube.l.google.com",
                "nhacmp3youtube.com",
                "discord.com",
                "www.discord.com",
                "discordapp.com",
                "discordapp.net",
                "discord.gg",
                "discord.media",
                "discordcdn.com",
                "discordstatus.com",
                "dis.gd",
                "discord-attachments-uploads-prd.storage.googleapis.com"
            };

            string[] sourceFiles = new[] { "list-general.txt", "list-google.txt", "russia-youtube.txt", "russia-discord.txt" };
            foreach (var srcName in sourceFiles)
            {
                string? path = FindDpiAsset(dpiDir, srcName);
                if (path != null && File.Exists(path))
                {
                    try
                    {
                        foreach (var rawLine in File.ReadAllLines(path, Encoding.UTF8))
                        {
                            string line = rawLine.Trim().TrimStart('\uFEFF');
                            if (!string.IsNullOrEmpty(line) && !line.StartsWith("#") && !excludedSet.Contains(line))
                            {
                                domains.Add(line);
                            }
                        }
                    }
                    catch { }
                }
            }

            domains.ExceptWith(excludedSet);

            string combinedPath = Path.Combine(dpiDir, "list-combined.txt");
            try
            {
                File.WriteAllLines(combinedPath, domains, Utf8NoBom);
            }
            catch { }

            return combinedPath;
        }

        private void SetBrowserDpiOptimizationPolicies(bool enableOptimization)
        {
            foreach (var b in GetInstalledBrowsersTargetList())
            {
                try
                {
                    using var polKey = Registry.LocalMachine.CreateSubKey(b.PolicyPath, true);
                    if (enableOptimization)
                    {
                        polKey?.SetValue("QuicAllowed", 0, RegistryValueKind.DWord);
                        polKey?.SetValue("PostQuantumKeyAgreementEnabled", 0, RegistryValueKind.DWord);
                        polKey?.SetValue("TLS13HardeningForLocalAnchorsEnabled", 0, RegistryValueKind.DWord);
                    }
                    else
                    {
                        polKey?.DeleteValue("QuicAllowed", false);
                        polKey?.DeleteValue("PostQuantumKeyAgreementEnabled", false);
                        polKey?.DeleteValue("TLS13HardeningForLocalAnchorsEnabled", false);
                    }
                }
                catch { }
            }

            if (enableOptimization)
            {
                ClearBrowserBrokenQuicState();
            }
        }

        private void ClearBrowserBrokenQuicState()
        {
            try
            {
                string local = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
                string[] userDataRoots = new[]
                {
                    Path.Combine(local, @"Yandex\YandexBrowser\User Data"),
                    Path.Combine(local, @"Google\Chrome\User Data"),
                    Path.Combine(local, @"Microsoft\Edge\User Data"),
                    Path.Combine(local, @"BraveSoftware\Brave-Browser\User Data")
                };

                foreach (var root in userDataRoots)
                {
                    if (!Directory.Exists(root)) continue;
                    foreach (var profileDir in Directory.GetDirectories(root))
                    {
                        string netStateFile = Path.Combine(profileDir, "Network", "Network Persistent State");
                        string netStateOld = Path.Combine(profileDir, "Network Persistent State");
                        try { if (File.Exists(netStateFile)) File.Delete(netStateFile); } catch { }
                        try { if (File.Exists(netStateOld)) File.Delete(netStateOld); } catch { }
                    }
                }
            }
            catch { }
        }

        private void FlushSystemNetworkSockets()
        {
            RunCommand("ipconfig /flushdns");
            RunCommand("arp -d *");
            RunCommand("netsh int tcp set global timestamps=enabled");
        }

        private string BuildZapretArguments(string dpiDir)
        {
            string? quicBin = FindDpiAsset(dpiDir, "quic_initial_www_google_com.bin");
            string? tlsBin = FindDpiAsset(dpiDir, "tls_clienthello_www_google_com.bin");

            string excludeListPath = BuildExcludeHostlistFile(dpiDir);
            var excludedSet = new HashSet<string>(
                File.Exists(excludeListPath) ? File.ReadAllLines(excludeListPath, Encoding.UTF8) : Array.Empty<string>(),
                StringComparer.OrdinalIgnoreCase);

            string combinedListPath = BuildCombinedHostlistFile(dpiDir, excludedSet);

            string hostListArg = File.Exists(combinedListPath) ? $" --hostlist=\"{combinedListPath}\"" : "";
            string excludeArg = File.Exists(excludeListPath) ? $" --hostlist-exclude=\"{excludeListPath}\"" : "";

            string discordVoicePart =
                "--filter-udp=50000-50099 --filter-l7=discord,stun --dpi-desync=fake --dpi-desync-repeats=6 --new ";

            string udpQuicPart = quicBin != null
                ? $"--filter-udp=443{hostListArg}{excludeArg} --dpi-desync=fake --dpi-desync-repeats=6 --dpi-desync-fake-quic=\"{quicBin}\" --new "
                : $"--filter-udp=443{hostListArg}{excludeArg} --dpi-desync=fake --dpi-desync-repeats=6 --new ";

            int profile = config.DpiStrategyProfile;

            if (profile == 2)
            {
                string patternArg = tlsBin != null ? $" --dpi-desync-split-seqovl-pattern=\"{tlsBin}\"" : "";
                return $"--wf-l3=ipv4 --wf-tcp=80,443 --wf-udp=443,50000-50099 " +
                       $"{discordVoicePart}" +
                       $"{udpQuicPart}" +
                       $"--filter-tcp=80,443{hostListArg}{excludeArg} --dpi-desync=multisplit --dpi-desync-split-pos=1,midsld --dpi-desync-split-seqovl=2{patternArg}";
            }
            else if (profile == 3)
            {
                return $"--wf-l3=ipv4 --wf-tcp=80,443 --wf-udp=443,50000-50099 " +
                       $"{discordVoicePart}" +
                       $"{udpQuicPart}" +
                       $"--filter-tcp=80,443{excludeArg} --dpi-desync=multidisorder --dpi-desync-split-pos=1,midsld";
            }
            else
            {
                return $"--wf-l3=ipv4 --wf-tcp=80,443 --wf-udp=443,50000-50099 " +
                       $"{discordVoicePart}" +
                       $"{udpQuicPart}" +
                       $"--filter-tcp=80,443{hostListArg}{excludeArg} --dpi-desync=multidisorder --dpi-desync-split-pos=1,midsld";
            }
        }

        private string GetDpiProfileShortName()
        {
            return config.DpiStrategyProfile switch
            {
                2 => "Профиль #2 (Multisplit + Исключения)",
                3 => "Профиль #3 (Глобальный + Исключения Gemini)",
                _ => "Профиль #1 (YouTube/Discord + Защита Gemini)"
            };
        }

        private bool StartDpiBypass(bool silent = false)
        {
            if (isStartingDpi) return false;
            isStartingDpi = true;

            try
            {
                StopDpiBypass(restoreBrowserPolicies: false);

                string baseDir = AppDomain.CurrentDomain.BaseDirectory;
                string dpiDir = Path.Combine(baseDir, "dpi");
                string winwsPath = Path.Combine(dpiDir, "winws.exe");

                if (!File.Exists(winwsPath))
                {
                    config.EnableDpiBypass = false;
                    SaveConfig();
                    Dispatcher.Invoke(UpdateDpiButtonVisualState);
                    if (!silent)
                    {
                        Dispatcher.Invoke(() => MessageBox.Show(
                            $"Файл движка не найден по пути:\n{winwsPath}\n\nПереустановите программу из свежего установщика.",
                            "Ошибка запуска Zapret",
                            MessageBoxButton.OK,
                            MessageBoxImage.Warning));
                    }
                    return false;
                }

                SetBrowserDpiOptimizationPolicies(enableOptimization: true);
                FlushSystemNetworkSockets();

                string args = BuildZapretArguments(dpiDir);
                var psi = new ProcessStartInfo(winwsPath, args)
                {
                    CreateNoWindow = true,
                    UseShellExecute = false,
                    WindowStyle = ProcessWindowStyle.Hidden,
                    WorkingDirectory = dpiDir
                };

                dpiProcess = Process.Start(psi);
                Task.Delay(350).Wait();

                if (dpiProcess == null || dpiProcess.HasExited)
                {
                    string excludeListPath = BuildExcludeHostlistFile(dpiDir);
                    string excludeArg = File.Exists(excludeListPath) ? $" --hostlist-exclude=\"{excludeListPath}\"" : "";

                    string fallbackArgs =
                        "--wf-l3=ipv4 --wf-tcp=80,443 --wf-udp=443,50000-50099 " +
                        "--filter-udp=50000-50099 --dpi-desync=fake --dpi-desync-any-protocol=1 --dpi-desync-repeats=6 --new " +
                        $"--filter-udp=443{excludeArg} --dpi-desync=fake --dpi-desync-repeats=6 --new " +
                        $"--filter-tcp=80,443{excludeArg} --dpi-desync=disorder2 --dpi-desync-split-pos=1";

                    var fallbackPsi = new ProcessStartInfo(winwsPath, fallbackArgs)
                    {
                        CreateNoWindow = true,
                        UseShellExecute = false,
                        WindowStyle = ProcessWindowStyle.Hidden,
                        WorkingDirectory = dpiDir
                    };
                    dpiProcess = Process.Start(fallbackPsi);
                    Task.Delay(300).Wait();
                }

                if (dpiProcess == null || dpiProcess.HasExited)
                {
                    config.EnableDpiBypass = false;
                    SaveConfig();
                    Dispatcher.Invoke(UpdateDpiButtonVisualState);
                    if (!silent)
                    {
                        Dispatcher.Invoke(() => MessageBox.Show(
                            "Процесс winws.exe завершился сразу после старта.\nПроверьте, что папка программы добавлена в исключения антивируса (для драйвера WinDivert64.sys).",
                            "Обход DPI не запустился",
                            MessageBoxButton.OK,
                            MessageBoxImage.Warning));
                    }
                    return false;
                }

                config.EnableDpiBypass = true;
                SaveConfig();
                Dispatcher.Invoke(() =>
                {
                    UpdateDpiButtonVisualState();
                    UpdateTrayMenu();
                });
                return true;
            }
            catch (Exception ex)
            {
                config.EnableDpiBypass = false;
                SaveConfig();
                Dispatcher.Invoke(UpdateDpiButtonVisualState);
                if (!silent)
                {
                    Dispatcher.Invoke(() => MessageBox.Show($"Ошибка при запуске обхода DPI: {ex.Message}", "Ошибка", MessageBoxButton.OK, MessageBoxImage.Warning));
                }
                return false;
            }
            finally
            {
                isStartingDpi = false;
            }
        }

        private void StopDpiBypass(bool restoreBrowserPolicies = true)
        {
            try
            {
                if (dpiProcess != null && !dpiProcess.HasExited)
                {
                    dpiProcess.Kill();
                    dpiProcess.Dispose();
                }
                dpiProcess = null;

                foreach (var p in Process.GetProcessesByName("winws"))
                {
                    try
                    {
                        p.Kill();
                        p.WaitForExit(1200);
                    }
                    catch { }
                    finally
                    {
                        p.Dispose();
                    }
                }

                if (restoreBrowserPolicies)
                {
                    SetBrowserDpiOptimizationPolicies(enableOptimization: false);
                }

                RunCommand("sc stop WinDivert");
                RunCommand("sc stop WinDivert14");
                RunCommand("ipconfig /flushdns");
            }
            catch { }
        }

        private void ToggleDpiBypass()
        {
            if (config.EnableDpiBypass)
            {
                StopDpiBypass(restoreBrowserPolicies: true);
                config.EnableDpiBypass = false;
                SaveConfig();
                UpdateDpiButtonVisualState();
                UpdateTrayMenu();
            }
            else
            {
                StartDpiBypass(silent: false);
            }
        }

        private void UpdateDpiButtonVisualState()
        {
            if (dpiBypassText == null || dpiBypassBtn == null) return;
            if (config.EnableDpiBypass)
            {
                dpiBypassText.Text = $"🛡️ Обход DPI: ВКЛЮЧЕН • {GetDpiProfileShortName()}";
                dpiBypassText.Foreground = BrushFromHex(accentColorHex);
                dpiBypassBtn.Background = BrushFromHex(accentCardBgHex);
                dpiBypassBtn.BorderBrush = BrushFromHex(accentColorHex);
            }
            else
            {
                dpiBypassText.Text = "🛡️ Обход DPI: ОТКЛЮЧЕН (ЛКМ — Вкл, ПКМ — Профиль)";
                dpiBypassText.Foreground = BrushFromHex(textSubColorHex);
                dpiBypassBtn.Background = BrushFromHex(bgCardHex);
                dpiBypassBtn.BorderBrush = BrushFromHex(bgCardStrokeHex);
            }
        }

        private void RecalculateWindowHeight()
        {
            if (mainLayout == null || rootScroll == null) return;

            if (WindowState == WindowState.Maximized)
            {
                SizeToContent = SizeToContent.Manual;
                UpdateScrollBarVisibility();
                return;
            }

            isRecalculatingHeight = true;
            try
            {
                double currentWidth = ActualWidth > 0 ? ActualWidth : Width;
                double measureWidth = Math.Max(360, currentWidth - 20);

                mainLayout.Measure(new System.Windows.Size(measureWidth, double.PositiveInfinity));

                double desiredWindowHeight = mainLayout.DesiredSize.Height + 42;
                double maxWorkAreaHeight = SystemParameters.WorkArea.Height - 20;

                if (desiredWindowHeight <= maxWorkAreaHeight)
                {
                    rootScroll.VerticalScrollBarVisibility = ScrollBarVisibility.Hidden;
                    SizeToContent = SizeToContent.Manual;
                    SizeToContent = SizeToContent.Height;
                }
                else
                {
                    SizeToContent = SizeToContent.Manual;
                    Height = maxWorkAreaHeight;
                    rootScroll.VerticalScrollBarVisibility = ScrollBarVisibility.Visible;

                    if (Top + Height > SystemParameters.WorkArea.Bottom)
                    {
                        Top = Math.Max(SystemParameters.WorkArea.Top + 6, SystemParameters.WorkArea.Bottom - Height);
                    }
                }
            }
            finally
            {
                isRecalculatingHeight = false;
            }
        }

        private void UpdateScrollBarVisibility()
        {
            if (rootScroll == null || isRecalculatingHeight) return;

            bool needsScroll = rootScroll.ExtentHeight > rootScroll.ViewportHeight + 4;
            var desiredVisibility = needsScroll ? ScrollBarVisibility.Visible : ScrollBarVisibility.Hidden;

            if (rootScroll.VerticalScrollBarVisibility != desiredVisibility)
            {
                rootScroll.VerticalScrollBarVisibility = desiredVisibility;
            }
        }

        private Icon LoadAppCustomIcon()
        {
            try
            {
                string baseDir = AppDomain.CurrentDomain.BaseDirectory;
                string icoPath = Path.Combine(baseDir, "icon.ico");
                if (File.Exists(icoPath)) return new Icon(icoPath);

                string exePath = Process.GetCurrentProcess().MainModule?.FileName ?? "";
                if (!string.IsNullOrEmpty(exePath) && File.Exists(exePath))
                {
                    var extracted = System.Drawing.Icon.ExtractAssociatedIcon(exePath);
                    if (extracted != null) return extracted;
                }
            }
            catch { }

            return SystemIcons.Shield;
        }

        private void ApplyCustomWindowAndTrayIcon()
        {
            try
            {
                using var sysIcon = LoadAppCustomIcon();
                Icon = Imaging.CreateBitmapSourceFromHIcon(
                    sysIcon.Handle,
                    Int32Rect.Empty,
                    BitmapSizeOptions.FromEmptyOptions());
                if (trayIcon != null)
                {
                    trayIcon.Icon = LoadAppCustomIcon();
                }
            }
            catch { }
        }

        private List<(string Name, string LocalStatePath, string PolicyPath)> GetInstalledBrowsersTargetList()
        {
            string local = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            string roaming = Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData);

            return new List<(string, string, string)>
            {
                ("Google Chrome", Path.Combine(local, @"Google\Chrome\User Data\Local State"), @"SOFTWARE\Policies\Google\Chrome"),
                ("Microsoft Edge", Path.Combine(local, @"Microsoft\Edge\User Data\Local State"), @"SOFTWARE\Policies\Microsoft\Edge"),
                ("Brave Browser", Path.Combine(local, @"BraveSoftware\Brave-Browser\User Data\Local State"), @"SOFTWARE\Policies\BraveSoftware\Brave"),
                ("Яндекс Браузер", Path.Combine(local, @"Yandex\YandexBrowser\User Data\Local State"), @"SOFTWARE\Policies\Yandex\YandexBrowser"),
                ("Opera", Path.Combine(roaming, @"Opera Software\Opera Stable\Local State"), @"SOFTWARE\Policies\Opera Software"),
                ("Opera GX", Path.Combine(roaming, @"Opera Software\Opera GX Stable\Local State"), @"SOFTWARE\Policies\Opera Software")
            };
        }

        private List<BrowserConflictInfo> CheckBrowserDnsConflicts()
        {
            var conflicts = new List<BrowserConflictInfo>();

            foreach (var b in GetInstalledBrowsersTargetList())
            {
                if (!File.Exists(b.LocalStatePath)) continue;

                bool policyEnforced = false;
                try
                {
                    using var key = Registry.LocalMachine.OpenSubKey(b.PolicyPath, false);
                    string? polMode = key?.GetValue("DnsOverHttpsMode")?.ToString();
                    string? polTemplates = key?.GetValue("DnsOverHttpsTemplates")?.ToString();
                    if (polMode == "automatic" && string.IsNullOrEmpty(polTemplates))
                    {
                        policyEnforced = true;
                    }
                }
                catch { }

                try
                {
                    using var fs = new FileStream(b.LocalStatePath, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
                    using var sr = new StreamReader(fs, Encoding.UTF8);
                    string jsonText = sr.ReadToEnd();

                    var rootNode = JsonNode.Parse(jsonText);
                    var dohNode = rootNode?["dns_over_https"];
                    if (dohNode != null)
                    {
                        string mode = dohNode["mode"]?.GetValue<string>() ?? "";
                        string templates = dohNode["templates"]?.GetValue<string>() ?? "";

                        if ((mode.Equals("secure", StringComparison.OrdinalIgnoreCase) || !string.IsNullOrWhiteSpace(templates)) && !policyEnforced)
                        {
                            conflicts.Add(new BrowserConflictInfo
                            {
                                BrowserName = b.Name,
                                LocalStatePath = b.LocalStatePath,
                                PolicyRegistryPath = b.PolicyPath,
                                CurrentTemplate = string.IsNullOrWhiteSpace(templates) ? "Кастомный провайдер (mode: secure)" : templates
                            });
                        }
                    }
                }
                catch { }
            }

            return conflicts;
        }

        private void MonitorBrowserDnsSettings(bool showPopupOnConflict)
        {
            if (config.AutoFixBrowserDns)
            {
                var found = CheckBrowserDnsConflicts();
                if (found.Count > 0) ApplyBrowserDnsAutoFix(silent: true);
                browserWarningBanner.Visibility = Visibility.Collapsed;
                return;
            }

            var conflicts = CheckBrowserDnsConflicts();
            if (conflicts.Count > 0)
            {
                string names = string.Join(", ", conflicts.Select(c => c.BrowserName));
                browserWarningText.Text = $"⚠️ Конфликт DNS в браузере ({names})! Нажмите для исправления";
                if (browserWarningBanner.Visibility != Visibility.Visible)
                {
                    browserWarningBanner.Visibility = Visibility.Visible;
                    RecalculateWindowHeight();
                }

                if (showPopupOnConflict && !browserWarningShownThisSession)
                {
                    browserWarningShownThisSession = true;
                    ShowBrowserDnsWarningDialog(conflicts);
                }
            }
            else
            {
                if (browserWarningBanner.Visibility != Visibility.Collapsed)
                {
                    browserWarningBanner.Visibility = Visibility.Collapsed;
                    RecalculateWindowHeight();
                }
            }
        }

        private void ApplyBrowserDnsAutoFix(bool silent = false)
        {
            int fixedCount = 0;

            foreach (var b in GetInstalledBrowsersTargetList())
            {
                if (!File.Exists(b.LocalStatePath)) continue;

                try
                {
                    using var polKey = Registry.LocalMachine.CreateSubKey(b.PolicyPath, true);
                    polKey?.SetValue("DnsOverHttpsMode", "automatic", RegistryValueKind.String);
                    polKey?.DeleteValue("DnsOverHttpsTemplates", false);
                    fixedCount++;
                }
                catch { }

                try
                {
                    string jsonText;
                    using (var fs = new FileStream(b.LocalStatePath, FileMode.Open, FileAccess.Read, FileShare.ReadWrite))
                    using (var sr = new StreamReader(fs, Encoding.UTF8))
                    {
                        jsonText = sr.ReadToEnd();
                    }

                    var rootNode = JsonNode.Parse(jsonText);
                    if (rootNode != null)
                    {
                        rootNode["dns_over_https"] = new JsonObject
                        {
                            ["mode"] = "automatic",
                            ["templates"] = ""
                        };
                        File.WriteAllText(b.LocalStatePath, rootNode.ToJsonString(), Utf8NoBom);
                    }
                }
                catch { }
            }

            config.AutoFixBrowserDns = true;
            SaveConfig();
            browserWarningBanner.Visibility = Visibility.Collapsed;
            RecalculateWindowHeight();

            if (!silent)
            {
                MessageBox.Show(
                    $"✅ Настройки браузеров ({fixedCount} шт.) успешно синхронизированы!\n\n" +
                    "Теперь браузеры используют режим «С вашим текущим поставщиком услуг» и полностью подчиняются DNS Switcher Pro.\n" +
                    "Если браузер сейчас открыт — перезапустите его для применения.",
                    "Автонастройка браузеров",
                    MessageBoxButton.OK,
                    MessageBoxImage.Information);
            }
        }

        private void RemoveBrowserDnsPolicies()
        {
            foreach (var b in GetInstalledBrowsersTargetList())
            {
                try
                {
                    using var polKey = Registry.LocalMachine.OpenSubKey(b.PolicyPath, true);
                    polKey?.DeleteValue("DnsOverHttpsMode", false);
                    polKey?.DeleteValue("DnsOverHttpsTemplates", false);
                }
                catch { }
            }
            config.AutoFixBrowserDns = false;
            SaveConfig();
            MonitorBrowserDnsSettings(showPopupOnConflict: false);
        }

        private void ShowBrowserDnsWarningDialog(List<BrowserConflictInfo> conflicts)
        {
            var dlg = CreateModalDialog("Конфликт DNS в браузере");
            var root = new StackPanel();
            root.Children.Add(CreateDialogHeader("⚠️ Конфликт DNS в браузере"));

            var sb = new StringBuilder();
            sb.AppendLine("Обнаружено, что в вашем браузере включён сторонний «Безопасный DNS», который игнорирует настройки Windows и обращается к серверам напрямую:\n");
            foreach (var c in conflicts)
            {
                sb.AppendLine($"• {c.BrowserName}: {c.CurrentTemplate}");
            }
            sb.Append("\nИз-за этого Gemini может определять ваш реальный регион. Как вы хотите решить проблему?");

            var infoBox = new Border
            {
                Background = BrushFromHex(bgCardHex),
                BorderBrush = BrushFromHex("#FFAB00"),
                BorderThickness = new Thickness(1.5),
                CornerRadius = new CornerRadius(12),
                Padding = new Thickness(14, 12, 14, 12),
                Margin = new Thickness(0, 0, 0, 12),
                Child = new TextBlock
                {
                    Text = sb.ToString(),
                    FontSize = 12.5,
                    Foreground = BrushFromHex(textMainColorHex),
                    TextWrapping = TextWrapping.Wrap
                }
            };
            root.Children.Add(infoBox);

            root.Children.Add(CreateDialogButton("⚡ Исправить автоматически (в 1 клик)", accentColorHex, () =>
            {
                dlg.Close();
                ApplyBrowserDnsAutoFix(silent: false);
            }, accentCardBgHex, accentColorHex));

            root.Children.Add(CreateDialogButton("📖 Инструкция (Как исправить вручную)", "#00B0FF", () =>
            {
                dlg.Close();
                ShowBrowserManualGuideDialog();
            }, config.LightMode ? "#E3F2FD" : "#101A24", "#00B0FF"));

            root.Children.Add(CreateDialogButton("Напомнить позже", textSubColorHex, () => dlg.Close()));

            dlg.Content = new Border
            {
                Background = BrushFromHex(config.LightMode ? "#FFFFFF" : "#0F1117"),
                BorderBrush = BrushFromHex("#FFAB00"),
                BorderThickness = new Thickness(2),
                CornerRadius = new CornerRadius(20),
                Padding = new Thickness(22, 18, 22, 18),
                Child = root
            };
            dlg.ShowDialog();
        }

        private void ShowBrowserManualGuideDialog(bool returnToNetworkSettings = false)
        {
            var dlg = CreateModalDialog("Инструкция для браузера", () =>
            {
                if (returnToNetworkSettings) ShowNetworkSettingsDialog();
            });
            var root = new StackPanel();
            root.Children.Add(CreateDialogHeader("📖 Настройка DNS в браузере"));

            string guideText =
                "Чтобы браузер не обходил DNS Switcher Pro своими путями:\n\n" +
                "1. Откройте раздел «Безопасность» в настройках браузера (нажмите на кнопку вашего браузера ниже, чтобы скопировать быструю ссылку, и вставьте её в адресную строку).\n\n" +
                "2. Прокрутите вниз до пункта «Использовать безопасный DNS-сервер» (Use secure DNS).\n\n" +
                "3. Выберите переключатель «С вашим текущим поставщиком услуг» (With your current service provider) либо совсем отключите этот тумблер.\n\n" +
                "⚠️ Не выбирайте внутри браузера стандартные пункты «Google (Public DNS)» или «Cloudflare (1.1.1.1)»!";

            var guideBox = new Border
            {
                Background = BrushFromHex(bgCardHex),
                BorderBrush = BrushFromHex(bgCardStrokeHex),
                BorderThickness = new Thickness(1.5),
                CornerRadius = new CornerRadius(12),
                Padding = new Thickness(14, 12, 14, 12),
                Margin = new Thickness(0, 0, 0, 12),
                Child = new TextBlock
                {
                    Text = guideText,
                    FontSize = 12.5,
                    Foreground = BrushFromHex(textMainColorHex),
                    TextWrapping = TextWrapping.Wrap
                }
            };
            root.Children.Add(guideBox);

            var links = new (string Label, string Url)[]
            {
                ("📋 Скопировать для Chrome: chrome://settings/security", "chrome://settings/security"),
                ("📋 Скопировать для Edge: edge://settings/privacy", "edge://settings/privacy"),
                ("📋 Скопировать для Brave: brave://settings/security", "brave://settings/security"),
                ("📋 Скопировать для Яндекс: browser://settings/security", "browser://settings/security")
            };

            foreach (var l in links)
            {
                root.Children.Add(CreateDialogButton(l.Label, textMainColorHex, () =>
                {
                    Clipboard.SetText(l.Url);
                    MessageBox.Show($"Ссылка {l.Url} скопирована!\nВставьте её (Ctrl+V) в адресную строку браузера и нажмите Enter.", "Скопировано", MessageBoxButton.OK, MessageBoxImage.Information);
                }));
            }

            root.Children.Add(CreateDialogButton("⚡ Или исправить автоматически в 1 клик", accentColorHex, () =>
            {
                dlg.Close();
                ApplyBrowserDnsAutoFix(silent: false);
            }, accentCardBgHex, accentColorHex));

            root.Children.Add(CreateDialogButton("Закрыть", textSubColorHex, () =>
            {
                dlg.Close();
                if (returnToNetworkSettings) ShowNetworkSettingsDialog();
            }));

            dlg.Content = new Border
            {
                Background = BrushFromHex(config.LightMode ? "#FFFFFF" : "#0F1117"),
                BorderBrush = BrushFromHex(bgCardStrokeHex),
                BorderThickness = new Thickness(2),
                CornerRadius = new CornerRadius(20),
                Padding = new Thickness(22, 18, 22, 18),
                Child = root
            };
            dlg.ShowDialog();
        }

        private void ClearGeminiCookiesAndCache()
        {
            try
            {
                string localAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
                string[] browserPaths = new[]
                {
                    Path.Combine(localAppData, @"Google\Chrome\User Data\Default"),
                    Path.Combine(localAppData, @"Microsoft\Edge\User Data\Default"),
                    Path.Combine(localAppData, @"BraveSoftware\Brave-Browser\User Data\Default"),
                    Path.Combine(localAppData, @"Yandex\YandexBrowser\User Data\Default")
                };

                int cleanedCount = 0;
                foreach (string bPath in browserPaths)
                {
                    if (!Directory.Exists(bPath)) continue;

                    string cookiesFile = Path.Combine(bPath, "Network", "Cookies");
                    if (File.Exists(cookiesFile))
                    {
                        try { File.Delete(cookiesFile); cleanedCount++; } catch { }
                    }

                    string storageDir = Path.Combine(bPath, "Local Storage", "leveldb");
                    if (Directory.Exists(storageDir))
                    {
                        foreach (var file in Directory.GetFiles(storageDir))
                        {
                            try { File.Delete(file); cleanedCount++; } catch { }
                        }
                    }
                }

                RunCommand("ipconfig /flushdns");

                MessageBox.Show(
                    cleanedCount > 0
                        ? "✅ Кэш, куки Google/Gemini и системный DNS-кэш успешно очищены!\nПерезапустите браузер или вкладку Gemini."
                        : "✅ Системный DNS-кэш сброшен!\nℹ️ Чтобы очистить также файлы куки браузера — закройте браузер перед нажатием этой кнопки.",
                    "Очистка Gemini Cache",
                    MessageBoxButton.OK,
                    MessageBoxImage.Information);
            }
            catch (Exception ex)
            {
                MessageBox.Show($"Ошибка при очистке кэша: {ex.Message}", "Ошибка", MessageBoxButton.OK, MessageBoxImage.Warning);
            }
        }

        private bool IsIpv6CurrentlyEnabled()
        {
            return !config.BlockIpv6;
        }

        private void EnforceGeminiIpv6Guard()
        {
            bool ipv6Enabled = IsIpv6CurrentlyEnabled();

            if (lastKnownHostsBlockState == null || ipv6Enabled != lastKnownHostsBlockState.Value)
            {
                UpdateGeminiHostsRule(blockCompletely: ipv6Enabled);
                lastKnownHostsBlockState = ipv6Enabled;
            }

            if (!ipv6Enabled) return;

            try
            {
                foreach (var proc in Process.GetProcesses())
                {
                    try
                    {
                        string pName = proc.ProcessName;
                        string wTitle = proc.MainWindowTitle ?? "";

                        bool isGeminiProcess = pName.Equals("Gemini", StringComparison.OrdinalIgnoreCase) ||
                                               pName.Equals("GoogleGemini", StringComparison.OrdinalIgnoreCase) ||
                                               pName.Equals("GeminiApp", StringComparison.OrdinalIgnoreCase);

                        bool isGeminiWindow = !string.IsNullOrEmpty(wTitle) &&
                                              (wTitle.Equals("Gemini", StringComparison.OrdinalIgnoreCase) ||
                                               wTitle.StartsWith("Gemini -", StringComparison.OrdinalIgnoreCase) ||
                                               wTitle.StartsWith("Gemini —", StringComparison.OrdinalIgnoreCase) ||
                                               wTitle.Contains("Google Gemini", StringComparison.OrdinalIgnoreCase) ||
                                               wTitle.Contains("gemini.google.com", StringComparison.OrdinalIgnoreCase));

                        if (isGeminiProcess)
                        {
                            proc.Kill();
                            NotifyGeminiBlocked();
                        }
                        else if (isGeminiWindow)
                        {
                            proc.CloseMainWindow();
                            NotifyGeminiBlocked();
                        }
                    }
                    catch { }
                    finally
                    {
                        proc.Dispose();
                    }
                }
            }
            catch { }
        }

        private void NotifyGeminiBlocked()
        {
            if ((DateTime.Now - lastGeminiBlockNotify).TotalSeconds < 6) return;
            lastGeminiBlockNotify = DateTime.Now;

            trayIcon?.ShowBalloonTip(
                2500,
                "🚫 Gemini заблокирован (Включён IPv6)",
                "При включённом IPv6 приложение и сайт Gemini заблокированы. Включите блокировку IPv6 (IPv4 Only) в сетевых настройках.",
                Forms.ToolTipIcon.Warning);
        }

        private void UpdateGeminiHostsRule(bool blockCompletely)
        {
            try
            {
                string hostsPath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System), @"drivers\etc\hosts");
                if (!File.Exists(hostsPath)) return;

                var lines = File.ReadAllLines(hostsPath, Encoding.UTF8).ToList();
                var cleaned = new List<string>();
                bool insideBlock = false;

                foreach (var line in lines)
                {
                    string trimmed = line.Trim();
                    if (trimmed == HostsBlockStartMarker) { insideBlock = true; continue; }
                    if (trimmed == HostsBlockEndMarker) { insideBlock = false; continue; }
                    if (trimmed.Contains("gemini.google.com", StringComparison.OrdinalIgnoreCase) ||
                        trimmed.Contains("aistudio.google.com", StringComparison.OrdinalIgnoreCase))
                    {
                        continue;
                    }
                    if (!insideBlock) cleaned.Add(line);
                }

                if (blockCompletely)
                {
                    cleaned.Add(HostsBlockStartMarker);
                    cleaned.Add("0.0.0.0 gemini.google.com");
                    cleaned.Add("0.0.0.0 www.gemini.google.com");
                    cleaned.Add("0.0.0.0 aistudio.google.com");
                    cleaned.Add(HostsBlockEndMarker);
                }

                File.WriteAllLines(hostsPath, cleaned, Utf8NoBom);
                RunCommand("ipconfig /flushdns");
            }
            catch { }
        }

        private void EnforceIpv6Policy()
        {
            var nic = GetActiveNetworkInterface();
            string? ifaceName = nic?.Name;

            if (config.BlockIpv6)
            {
                if (!string.IsNullOrEmpty(ifaceName))
                {
                    RunCommand($"netsh interface ipv6 set dnsservers name=\"{ifaceName}\" static ::1 primary validate=no");
                    RunPowerShell($"Disable-NetAdapterBinding -Name '{ifaceName.Replace("'", "''")}' -ComponentID ms_tcpip6 -ErrorAction SilentlyContinue");
                }

                RunCommand("netsh interface teredo set state disabled");
                RunCommand("netsh interface ipv6 6to4 set state state=disabled");
                RunCommand("netsh interface ipv6 isatap set state state=disabled");

                try
                {
                    using var tcp6Key = Registry.LocalMachine.CreateSubKey(
                        @"SYSTEM\CurrentControlSet\Services\Tcpip6\Parameters", true);
                    tcp6Key?.SetValue("DisabledComponents", 0x20, RegistryValueKind.DWord);
                }
                catch { }

                UpdateGeminiHostsRule(blockCompletely: false);
                lastKnownHostsBlockState = false;
            }
            else
            {
                if (!string.IsNullOrEmpty(ifaceName))
                {
                    RunPowerShell($"Enable-NetAdapterBinding -Name '{ifaceName.Replace("'", "''")}' -ComponentID ms_tcpip6 -ErrorAction SilentlyContinue");
                    RunCommand($"netsh interface ipv6 set dnsservers name=\"{ifaceName}\" source=dhcp");
                }

                try
                {
                    using var tcp6Key = Registry.LocalMachine.CreateSubKey(
                        @"SYSTEM\CurrentControlSet\Services\Tcpip6\Parameters", true);
                    tcp6Key?.SetValue("DisabledComponents", 0x0, RegistryValueKind.DWord);
                }
                catch { }

                UpdateGeminiHostsRule(blockCompletely: true);
                lastKnownHostsBlockState = true;
                Dispatcher.Invoke(EnforceGeminiIpv6Guard);
            }
        }

        private bool IsAutoStartEnabled()
        {
            try
            {
                var psi = new ProcessStartInfo("schtasks.exe", $"/Query /TN \"{AutoStartTaskName}\"")
                {
                    CreateNoWindow = true,
                    UseShellExecute = false,
                    WindowStyle = ProcessWindowStyle.Hidden
                };
                using var p = Process.Start(psi);
                p?.WaitForExit(2000);
                return p?.ExitCode == 0;
            }
            catch { return false; }
        }

        private void SetAutoStartTask(bool enable)
        {
            try
            {
                string exePath = Process.GetCurrentProcess().MainModule?.FileName ?? "";
                if (string.IsNullOrEmpty(exePath)) return;

                if (enable)
                {
                    string safeExe = exePath.Replace("'", "''");
                    string psScript =
                        $"$act = New-ScheduledTaskAction -Execute '{safeExe}'; " +
                        $"$trig = New-ScheduledTaskTrigger -AtLogOn; " +
                        $"$prin = New-ScheduledTaskPrincipal -GroupId 'S-1-5-32-544' -RunLevel Highest; " +
                        $"$set = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero); " +
                        $"Register-ScheduledTask -TaskName '{AutoStartTaskName}' -Action $act -Trigger $trig -Principal $prin -Settings $set -Force -ErrorAction SilentlyContinue";

                    RunPowerShell(psScript);
                }
                else
                {
                    RunCommand($"schtasks /Delete /TN \"{AutoStartTaskName}\" /F");
                }
                config.StartWithWindows = enable;
                SaveConfig();
            }
            catch { }
        }

        private MediaBrush BrushFromHex(string hex)
        {
            return (SolidColorBrush)new BrushConverter().ConvertFromString(hex)!;
        }

        private UIElement CreateShieldIcon(string colorHex, bool isFilled)
        {
            return new System.Windows.Shapes.Path
            {
                Width = 22,
                Height = 22,
                Stretch = Stretch.Uniform,
                Data = Geometry.Parse("M 12,2 L 21,6 L 21,13 C 21,18.5 17.2,23.2 12,24.5 C 6.8,23.2 3,18.5 3,13 L 3,6 Z"),
                Stroke = BrushFromHex(colorHex),
                StrokeThickness = 2.0,
                Fill = isFilled ? BrushFromHex(colorHex) : MediaBrushes.Transparent,
                VerticalAlignment = VerticalAlignment.Center,
                Margin = new Thickness(0, 0, 14, 0)
            };
        }

        private void SetupUI()
        {
            rootScroll = new ScrollViewer
            {
                VerticalScrollBarVisibility = ScrollBarVisibility.Hidden,
                HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
                Background = BrushFromHex(bgRootHex)
            };

            rootScroll.ScrollChanged += (s, e) => UpdateScrollBarVisibility();

            mainLayout = new StackPanel { Margin = new Thickness(18, 14, 18, 14) };

            var topGrid = new Grid { Margin = new Thickness(0, 0, 0, 10) };
            topGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            topGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            titleApp = new TextBlock
            {
                Text = "⚡ DNS SWITCHER PRO",
                FontSize = 19,
                FontWeight = FontWeights.Bold,
                Foreground = BrushFromHex(accentColorHex),
                VerticalAlignment = VerticalAlignment.Center
            };

            settingsGearText = new TextBlock
            {
                Text = "\u2699\uFE0E",
                FontFamily = new MediaFontFamily("Segoe UI Symbol"),
                FontSize = 20,
                FontWeight = FontWeights.Bold,
                Foreground = BrushFromHex(gearIconColorHex),
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center
            };

            settingsBtn = new Border
            {
                Background = BrushFromHex(gearBgHex),
                BorderBrush = BrushFromHex(accentColorHex),
                BorderThickness = new Thickness(1.2),
                CornerRadius = new CornerRadius(12),
                Padding = new Thickness(12, 5, 12, 6),
                Cursor = Cursors.Hand,
                Child = settingsGearText
            };
            settingsBtn.MouseLeftButtonUp += (s, e) => ShowSettingsDialog();

            Grid.SetColumn(titleApp, 0);
            Grid.SetColumn(settingsBtn, 1);
            topGrid.Children.Add(titleApp);
            topGrid.Children.Add(settingsBtn);
            mainLayout.Children.Add(topGrid);

            netStatusText = new TextBlock
            {
                Text = "Инициализация сети...",
                FontSize = 12.5,
                Foreground = BrushFromHex(textSubColorHex),
                TextWrapping = TextWrapping.Wrap
            };

            netStatusBorder = new Border
            {
                Background = BrushFromHex(bgCardHex),
                BorderBrush = BrushFromHex(bgCardStrokeHex),
                BorderThickness = new Thickness(1.5),
                CornerRadius = new CornerRadius(12),
                Padding = new Thickness(14, 9, 14, 9),
                Margin = new Thickness(0, 0, 0, 8),
                Cursor = Cursors.Hand,
                Child = netStatusText
            };
            netStatusBorder.MouseLeftButtonUp += (s, e) => VerifyActiveDnsInternet();
            mainLayout.Children.Add(netStatusBorder);

            browserWarningText = new TextBlock
            {
                Text = "⚠️ Конфликт Безопасного DNS в браузере! Нажмите для решения",
                FontSize = 12,
                FontWeight = FontWeights.SemiBold,
                Foreground = BrushFromHex("#FFD54F"),
                TextWrapping = TextWrapping.Wrap
            };

            browserWarningBanner = new Border
            {
                Background = BrushFromHex("#261A00"),
                BorderBrush = BrushFromHex("#FFAB00"),
                BorderThickness = new Thickness(1.5),
                CornerRadius = new CornerRadius(12),
                Padding = new Thickness(14, 8, 14, 8),
                Margin = new Thickness(0, 0, 0, 8),
                Visibility = Visibility.Collapsed,
                Cursor = Cursors.Hand,
                Child = browserWarningText
            };
            browserWarningBanner.MouseLeftButtonUp += (s, e) =>
            {
                var currentConflicts = CheckBrowserDnsConflicts();
                if (currentConflicts.Count > 0) ShowBrowserDnsWarningDialog(currentConflicts);
                else ShowBrowserManualGuideDialog();
            };
            mainLayout.Children.Add(browserWarningBanner);

            var modeInner = new StackPanel();
            modeTitle = new TextBlock
            {
                Text = "СИСТЕМНЫЙ РЕЖИМ",
                FontSize = 11.5,
                FontWeight = FontWeights.Bold,
                Foreground = BrushFromHex(textSubColorHex),
                Margin = new Thickness(0, 0, 0, 8)
            };
            modeInner.Children.Add(modeTitle);

            var radioGrid = new Grid();
            radioGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            radioGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            radioGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

            rbOff = new RadioButton { Content = "Выкл", FontSize = 14, Foreground = BrushFromHex(textSubColorHex), VerticalContentAlignment = VerticalAlignment.Center, Cursor = Cursors.Hand };
            rbAuto = new RadioButton { Content = "Авто", FontSize = 14, Foreground = BrushFromHex(textSubColorHex), VerticalContentAlignment = VerticalAlignment.Center, Cursor = Cursors.Hand };
            rbOn = new RadioButton { Content = "Вкл", FontSize = 14, Foreground = BrushFromHex(textSubColorHex), VerticalContentAlignment = VerticalAlignment.Center, Cursor = Cursors.Hand };

            rbOff.Checked += (s, e) => { if (!isUpdatingUi) ResetWindowsDnsToDhcp(enableAutoEncryption: false); };
            rbAuto.Checked += (s, e) => { if (!isUpdatingUi) ResetWindowsDnsToDhcp(enableAutoEncryption: true); };
            rbOn.Checked += (s, e) =>
            {
                if (isUpdatingUi) return;
                string target = !string.IsNullOrEmpty(config.LastActiveHost)
                    ? config.LastActiveHost
                    : (serverList.Count > 0 ? serverList[0].host : "dns.comss.one");
                ApplyWindowsDnsServer(target);
            };

            Grid.SetColumn(rbOff, 0);
            Grid.SetColumn(rbAuto, 1);
            Grid.SetColumn(rbOn, 2);
            radioGrid.Children.Add(rbOff);
            radioGrid.Children.Add(rbAuto);
            radioGrid.Children.Add(rbOn);
            modeInner.Children.Add(radioGrid);

            modeCard = new Border
            {
                Background = BrushFromHex(bgCardHex),
                BorderBrush = BrushFromHex(bgCardStrokeHex),
                BorderThickness = new Thickness(1.5),
                CornerRadius = new CornerRadius(16),
                Padding = new Thickness(16, 11, 16, 11),
                Margin = new Thickness(0, 0, 0, 8),
                Child = modeInner
            };
            mainLayout.Children.Add(modeCard);

            // Кнопка 1: Проверка защиты и скрытия региона от Google Gemini
            checkGeminiRegionText = new TextBlock
            {
                Text = "🕵️ Проверить защиту и регион Gemini (Тест IP и капчи)",
                FontSize = 12,
                FontWeight = FontWeights.SemiBold,
                Foreground = BrushFromHex(accentColorHex),
                HorizontalAlignment = HorizontalAlignment.Center
            };

            checkGeminiRegionBtn = new Border
            {
                Background = BrushFromHex(accentCardBgHex),
                BorderBrush = BrushFromHex(accentColorHex),
                BorderThickness = new Thickness(1.5),
                CornerRadius = new CornerRadius(12),
                Padding = new Thickness(14, 8, 14, 8),
                Margin = new Thickness(0, 0, 0, 7),
                Cursor = Cursors.Hand,
                Child = checkGeminiRegionText
            };
            checkGeminiRegionBtn.MouseLeftButtonUp += (s, e) => ShowGeminiRegionCheckDialog();
            mainLayout.Children.Add(checkGeminiRegionBtn);

            // Кнопка 2: Очистка кэша и куки Gemini
            var clearGeminiText = new TextBlock
            {
                Text = "🧹 Очистить кэш и куки Gemini (Сбросить старый регион)",
                FontSize = 12,
                FontWeight = FontWeights.SemiBold,
                Foreground = BrushFromHex(textMainColorHex),
                HorizontalAlignment = HorizontalAlignment.Center
            };

            clearGeminiCacheBtn = new Border
            {
                Background = BrushFromHex(bgCardHex),
                BorderBrush = BrushFromHex(bgCardStrokeHex),
                BorderThickness = new Thickness(1.5),
                CornerRadius = new CornerRadius(12),
                Padding = new Thickness(14, 8, 14, 8),
                Margin = new Thickness(0, 0, 0, 7),
                Cursor = Cursors.Hand,
                Child = clearGeminiText
            };
            clearGeminiCacheBtn.MouseLeftButtonUp += (s, e) => ClearGeminiCookiesAndCache();
            mainLayout.Children.Add(clearGeminiCacheBtn);

            // Кнопка 3: Обход DPI (Zapret)
            dpiBypassText = new TextBlock
            {
                FontSize = 12,
                FontWeight = FontWeights.SemiBold,
                HorizontalAlignment = HorizontalAlignment.Center
            };

            dpiBypassBtn = new Border
            {
                CornerRadius = new CornerRadius(12),
                BorderThickness = new Thickness(1.5),
                Padding = new Thickness(14, 8, 14, 8),
                Margin = new Thickness(0, 0, 0, 12),
                Cursor = Cursors.Hand,
                Child = dpiBypassText
            };
            dpiBypassBtn.MouseLeftButtonUp += (s, e) => ToggleDpiBypass();
            dpiBypassBtn.MouseRightButtonUp += (s, e) => ShowDpiProfileDialog(returnToNetworkSettings: false);
            UpdateDpiButtonVisualState();
            mainLayout.Children.Add(dpiBypassBtn);

            var headerGrid = new Grid { Margin = new Thickness(0, 0, 0, 8) };
            headerGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            headerGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            listHeader = new TextBlock
            {
                Text = "ДОСТУПНЫЕ СЕРВЕРЫ",
                FontSize = 11.5,
                FontWeight = FontWeights.Bold,
                Foreground = BrushFromHex(textSubColorHex),
                VerticalAlignment = VerticalAlignment.Center
            };

            pingBtnText = new TextBlock
            {
                Text = "📡 Проверка пинг (порт 853)",
                FontSize = 11.5,
                FontWeight = FontWeights.SemiBold,
                Foreground = BrushFromHex(accentColorHex)
            };

            pingBtnBorder = new Border
            {
                Background = BrushFromHex(accentCardBgHex),
                BorderBrush = BrushFromHex(accentColorHex),
                BorderThickness = new Thickness(1.5),
                CornerRadius = new CornerRadius(11),
                Padding = new Thickness(12, 5, 12, 5),
                Cursor = Cursors.Hand,
                Child = pingBtnText
            };
            pingBtnBorder.MouseLeftButtonUp += (s, e) => CheckNetworkAndTestAll(false);

            Grid.SetColumn(listHeader, 0);
            Grid.SetColumn(pingBtnBorder, 1);
            headerGrid.Children.Add(listHeader);
            headerGrid.Children.Add(pingBtnBorder);
            mainLayout.Children.Add(headerGrid);

            var undoGrid = new Grid();
            undoGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            undoGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            undoText = new TextBlock
            {
                FontSize = 12.5,
                Foreground = BrushFromHex(textMainColorHex),
                VerticalAlignment = VerticalAlignment.Center
            };

            undoActionBtn = new TextBlock
            {
                Text = "↩️ ВЕРНУТЬ",
                FontSize = 12.5,
                FontWeight = FontWeights.Bold,
                Foreground = BrushFromHex(accentColorHex),
                Cursor = Cursors.Hand,
                Padding = new Thickness(10, 4, 4, 4)
            };
            undoActionBtn.MouseLeftButtonUp += (s, e) =>
            {
                if (lastDeletedServer != null)
                {
                    int pos = Math.Clamp(lastDeletedIndex, 0, serverList.Count);
                    serverList.Insert(pos, lastDeletedServer);
                    lastDeletedServer = null;
                    SaveConfig();
                    RenderServerList();
                    undoBanner.Visibility = Visibility.Collapsed;
                    RecalculateWindowHeight();
                    undoTimer.Stop();
                }
            };

            Grid.SetColumn(undoText, 0);
            Grid.SetColumn(undoActionBtn, 1);
            undoGrid.Children.Add(undoText);
            undoGrid.Children.Add(undoActionBtn);

            undoBanner = new Border
            {
                Background = BrushFromHex(bgCardHex),
                BorderBrush = BrushFromHex(accentColorHex),
                BorderThickness = new Thickness(1.5),
                CornerRadius = new CornerRadius(12),
                Padding = new Thickness(14, 8, 14, 8),
                Margin = new Thickness(0, 0, 0, 8),
                Visibility = Visibility.Collapsed,
                Child = undoGrid
            };
            mainLayout.Children.Add(undoBanner);

            listContainer = new StackPanel();
            mainLayout.Children.Add(listContainer);

            rootScroll.Content = mainLayout;
            Content = rootScroll;
        }

        private void ApplyThemePalette()
        {
            switch (config.Theme)
            {
                case "green":
                    accentColorHex = "#00E676";
                    accentCardBgHex = config.LightMode ? "#E8F8F0" : "#0D2318";
                    break;
                case "blue":
                    accentColorHex = "#00B0FF";
                    accentCardBgHex = config.LightMode ? "#E8F4FE" : "#0A1D2C";
                    break;
                case "purple":
                    accentColorHex = "#E040FB";
                    accentCardBgHex = config.LightMode ? "#FBE9FE" : "#230A2C";
                    break;
                case "amber":
                    accentColorHex = "#FFAB00";
                    accentCardBgHex = config.LightMode ? "#FFF8E1" : "#2C200A";
                    break;
            }

            if (config.LightMode)
            {
                bgRootHex = "#F4F5F7";
                bgCardHex = "#FFFFFF";
                bgCardStrokeHex = "#D8DEE9";
                textMainColorHex = "#334155";
                textSubColorHex = "#64748B";
                gearIconColorHex = "#334155";
                gearBgHex = "#E2E8F0";
            }
            else
            {
                bgRootHex = "#08080A";
                bgCardHex = "#121418";
                bgCardStrokeHex = "#1E222B";
                textMainColorHex = "#CBD5E1";
                textSubColorHex = "#8A99AD";
                gearIconColorHex = "#F1F5F9";
                gearBgHex = "#1E2430";
            }
        }

        private void UpdateUIColors()
        {
            ApplyThemePalette();
            rootScroll.Background = BrushFromHex(bgRootHex);
            titleApp.Foreground = BrushFromHex(accentColorHex);
            settingsBtn.Background = BrushFromHex(gearBgHex);
            settingsBtn.BorderBrush = BrushFromHex(accentColorHex);
            settingsGearText.Foreground = BrushFromHex(gearIconColorHex);
            netStatusBorder.Background = BrushFromHex(bgCardHex);
            netStatusBorder.BorderBrush = BrushFromHex(bgCardStrokeHex);
            netStatusText.Foreground = BrushFromHex(textSubColorHex);
            listHeader.Foreground = BrushFromHex(textSubColorHex);
            pingBtnBorder.Background = BrushFromHex(accentCardBgHex);
            pingBtnBorder.BorderBrush = BrushFromHex(accentColorHex);
            pingBtnText.Foreground = BrushFromHex(accentColorHex);
            checkGeminiRegionBtn.Background = BrushFromHex(accentCardBgHex);
            checkGeminiRegionBtn.BorderBrush = BrushFromHex(accentColorHex);
            checkGeminiRegionText.Foreground = BrushFromHex(accentColorHex);
            clearGeminiCacheBtn.Background = BrushFromHex(bgCardHex);
            clearGeminiCacheBtn.BorderBrush = BrushFromHex(bgCardStrokeHex);
            ((TextBlock)clearGeminiCacheBtn.Child).Foreground = BrushFromHex(textMainColorHex);
            UpdateDpiButtonVisualState();
            undoBanner.Background = BrushFromHex(bgCardHex);
            undoBanner.BorderBrush = BrushFromHex(accentColorHex);
            undoText.Foreground = BrushFromHex(textMainColorHex);
            undoActionBtn.Foreground = BrushFromHex(accentColorHex);
            RefreshSystemState();
        }

        private NetworkInterface? GetActiveNetworkInterface()
        {
            return NetworkInterface.GetAllNetworkInterfaces()
                .Where(n => n.OperationalStatus == OperationalStatus.Up &&
                            n.NetworkInterfaceType != NetworkInterfaceType.Loopback &&
                            !n.Description.Contains("Virtual", StringComparison.OrdinalIgnoreCase))
                .OrderByDescending(n => n.GetIPProperties().GatewayAddresses.Count)
                .FirstOrDefault();
        }

        private void DetectInitialSystemMode()
        {
            var nic = GetActiveNetworkInterface();
            if (nic == null) return;

            var dnsAddresses = nic.GetIPProperties().DnsAddresses
                .Where(ip => ip.AddressFamily == AddressFamily.InterNetwork)
                .Select(ip => ip.ToString())
                .ToList();

            foreach (var kv in knownIps)
            {
                if (dnsAddresses.Any(d => kv.Value.Contains(d)))
                {
                    config.CurrentMode = "on";
                    config.LastActiveHost = kv.Key;
                    return;
                }
            }
        }

        private string[] ResolveHostToIpv4(string host)
        {
            try
            {
                var ips = Dns.GetHostAddresses(host)
                    .Where(ip => ip.AddressFamily == AddressFamily.InterNetwork)
                    .Select(ip => ip.ToString())
                    .Distinct()
                    .ToArray();
                if (ips.Length > 0)
                {
                    knownIps[host] = ips;
                    return ips;
                }
            }
            catch { }

            return knownIps.TryGetValue(host, out var fallback) ? fallback : Array.Empty<string>();
        }

        private void ConfigureWindowsDohForServer(string ifaceGuid, string host, string[] ips)
        {
            string dohTemplate = $"https://{host}/dns-query";

            foreach (var ip in ips)
            {
                RunCommand($"netsh dns add encryption server={ip} dohtemplate={dohTemplate} autoupgrade=yes udpfallback=no");

                try
                {
                    using var wellKnownKey = Registry.LocalMachine.CreateSubKey(
                        $@"SYSTEM\CurrentControlSet\Services\Dnscache\Parameters\DohWellKnownServers\{ip}", true);
                    wellKnownKey?.SetValue("Template", dohTemplate, RegistryValueKind.String);

                    using var ifaceDohKey = Registry.LocalMachine.CreateSubKey(
                        $@"SYSTEM\CurrentControlSet\Services\Dnscache\InterfaceSpecificParameters\{ifaceGuid}\DohInterfaceSettings\Doh\{ip}", true);
                    ifaceDohKey?.SetValue("DohFlags", 1L, RegistryValueKind.QWord);
                    ifaceDohKey?.SetValue("DohTemplate", dohTemplate, RegistryValueKind.String);
                }
                catch { }
            }
        }

        private void ClearInterfaceDohRegistry(string ifaceGuid)
        {
            try
            {
                Registry.LocalMachine.DeleteSubKeyTree(
                    $@"SYSTEM\CurrentControlSet\Services\Dnscache\InterfaceSpecificParameters\{ifaceGuid}\DohInterfaceSettings\Doh", false);
            }
            catch { }
        }

        private void ApplyWindowsDnsServer(string host)
        {
            var nic = GetActiveNetworkInterface();
            if (nic == null)
            {
                MessageBox.Show("Не найден активный сетевой адаптер!", "Ошибка сети", MessageBoxButton.OK, MessageBoxImage.Warning);
                return;
            }

            var ips = ResolveHostToIpv4(host);
            if (ips.Length == 0)
            {
                MessageBox.Show($"Не удалось определить IPv4-адрес для {host}. Проверьте подключение к сети.", "Ошибка резолвинга", MessageBoxButton.OK, MessageBoxImage.Warning);
                return;
            }

            string ifaceName = nic.Name;
            string ifaceGuid = nic.Id;

            EnforceIpv6Policy();

            RunCommand($"netsh interface ipv4 set dnsservers name=\"{ifaceName}\" static {ips[0]} primary");
            if (ips.Length > 1)
            {
                RunCommand($"netsh interface ipv4 add dnsservers name=\"{ifaceName}\" {ips[1]} index=2");
            }

            if (config.EnableDohEncryption) ConfigureWindowsDohForServer(ifaceGuid, host, ips);
            else ClearInterfaceDohRegistry(ifaceGuid);

            RunCommand("ipconfig /flushdns");

            config.CurrentMode = "on";
            config.LastActiveHost = host;
            SaveConfig();
            RefreshSystemState();
            UpdateTrayMenu();
        }

        private void ResetWindowsDnsToDhcp(bool enableAutoEncryption)
        {
            var nic = GetActiveNetworkInterface();
            if (nic != null)
            {
                EnforceIpv6Policy();
                RunCommand($"netsh interface ipv4 set dnsservers name=\"{nic.Name}\" source=dhcp");
                ClearInterfaceDohRegistry(nic.Id);

                if (enableAutoEncryption)
                {
                    var currentDns = nic.GetIPProperties().DnsAddresses
                        .Where(ip => ip.AddressFamily == AddressFamily.InterNetwork)
                        .Select(ip => ip.ToString());
                    foreach (var ip in currentDns)
                    {
                        try
                        {
                            using var ifaceDohKey = Registry.LocalMachine.CreateSubKey(
                                $@"SYSTEM\CurrentControlSet\Services\Dnscache\InterfaceSpecificParameters\{nic.Id}\DohInterfaceSettings\Doh\{ip}", true);
                            ifaceDohKey?.SetValue("DohFlags", 1L, RegistryValueKind.QWord);
                        }
                        catch { }
                    }
                }

                RunCommand("ipconfig /flushdns");
            }

            config.CurrentMode = enableAutoEncryption ? "auto" : "off";
            SaveConfig();
            RefreshSystemState();
            UpdateTrayMenu();
        }

        private void RunCommand(string cmd)
        {
            try
            {
                var psi = new ProcessStartInfo("cmd.exe", "/c " + cmd)
                {
                    CreateNoWindow = true,
                    UseShellExecute = false,
                    WindowStyle = ProcessWindowStyle.Hidden
                };
                using var p = Process.Start(psi);
                p?.WaitForExit(3000);
            }
            catch { }
        }

        private void RunPowerShell(string script)
        {
            try
            {
                var psi = new ProcessStartInfo("powershell.exe", $"-NoProfile -NonInteractive -WindowStyle Hidden -Command \"{script}\"")
                {
                    CreateNoWindow = true,
                    UseShellExecute = false,
                    WindowStyle = ProcessWindowStyle.Hidden
                };
                using var p = Process.Start(psi);
                p?.WaitForExit(4500);
            }
            catch { }
        }

        private void RefreshSystemState()
        {
            isUpdatingUi = true;

            rbOff.Foreground = BrushFromHex(textSubColorHex);
            rbOff.FontWeight = FontWeights.Normal;
            rbAuto.Foreground = BrushFromHex(textSubColorHex);
            rbAuto.FontWeight = FontWeights.Normal;
            rbOn.Foreground = BrushFromHex(textSubColorHex);
            rbOn.FontWeight = FontWeights.Normal;

            string ipv4Tag = config.BlockIpv6 ? " • IPv4 ONLY" : " • IPv6 ВКЛ (GEMINI БЛОК)";

            switch (config.CurrentMode)
            {
                case "on":
                    rbOn.IsChecked = true;
                    rbOn.Foreground = BrushFromHex(accentColorHex);
                    rbOn.FontWeight = FontWeights.Bold;
                    modeCard.Background = BrushFromHex(accentCardBgHex);
                    modeCard.BorderBrush = BrushFromHex(accentColorHex);
                    string dohStatus = config.EnableDohEncryption ? " (DoH)" : "";
                    modeTitle.Text = $"СИСТЕМНЫЙ РЕЖИМ: ВКЛЮЧЕН{dohStatus}{ipv4Tag}";
                    modeTitle.Foreground = BrushFromHex(accentColorHex);
                    break;

                case "auto":
                    rbAuto.IsChecked = true;
                    rbAuto.Foreground = BrushFromHex(accentColorHex);
                    rbAuto.FontWeight = FontWeights.Bold;
                    modeCard.Background = BrushFromHex(accentCardBgHex);
                    modeCard.BorderBrush = BrushFromHex(accentColorHex);
                    modeTitle.Text = $"СИСТЕМНЫЙ РЕЖИМ: АВТО{ipv4Tag}";
                    modeTitle.Foreground = BrushFromHex(accentColorHex);
                    break;

                default:
                    rbOff.IsChecked = true;
                    rbOff.Foreground = BrushFromHex("#FF5252");
                    rbOff.FontWeight = FontWeights.Bold;
                    modeCard.Background = BrushFromHex(config.LightMode ? "#FFEBEE" : "#1F1214");
                    modeCard.BorderBrush = BrushFromHex("#FF5252");
                    modeTitle.Text = $"СИСТЕМНЫЙ РЕЖИМ: ОТКЛЮЧЕН{ipv4Tag}";
                    modeTitle.Foreground = BrushFromHex("#FF5252");
                    break;
            }

            isUpdatingUi = false;
            RenderServerList();
        }

        private void RenderServerList()
        {
            listContainer.Children.Clear();
            string? activeHost = config.CurrentMode == "on" ? config.LastActiveHost : null;

            for (int i = 0; i < serverList.Count; i++)
            {
                int index = i;
                var server = serverList[i];
                bool isSelected = !string.IsNullOrEmpty(activeHost) &&
                                  string.Equals(activeHost, server.host, StringComparison.OrdinalIgnoreCase);

                var cardGrid = new Grid();
                cardGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                cardGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                cardGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

                var shieldIcon = CreateShieldIcon(isSelected ? accentColorHex : textSubColorHex, isSelected);
                Grid.SetColumn(shieldIcon, 0);
                cardGrid.Children.Add(shieldIcon);

                var infoStack = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
                var nameRow = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };

                nameRow.Children.Add(new TextBlock
                {
                    Text = server.name,
                    FontSize = 14.5,
                    FontWeight = FontWeights.SemiBold,
                    Foreground = BrushFromHex(textMainColorHex),
                    VerticalAlignment = VerticalAlignment.Center
                });

                if (isSelected)
                {
                    nameRow.Children.Add(new Border
                    {
                        Background = BrushFromHex(config.LightMode ? "#FFFFFF" : "#08080A"),
                        BorderBrush = BrushFromHex(accentColorHex),
                        BorderThickness = new Thickness(1),
                        CornerRadius = new CornerRadius(6),
                        Padding = new Thickness(6, 1, 6, 1),
                        Margin = new Thickness(8, 0, 0, 0),
                        Child = new TextBlock
                        {
                            Text = "ONLINE",
                            FontSize = 9,
                            FontWeight = FontWeights.Bold,
                            Foreground = BrushFromHex(accentColorHex)
                        }
                    });
                }

                string pingPart = server.ping switch
                {
                    -1 => "",
                    -2 => " • ⏳...",
                    -3 => " • ❌ Блок (853)",
                    _ => $" • ⚡ {server.ping} мс"
                };

                infoStack.Children.Add(nameRow);
                infoStack.Children.Add(new TextBlock
                {
                    Text = $"{server.host}{pingPart}",
                    FontSize = 12,
                    Foreground = BrushFromHex(server.ping == -3 ? "#FF5252" : textSubColorHex),
                    Margin = new Thickness(0, 2, 0, 0)
                });
                Grid.SetColumn(infoStack, 1);
                cardGrid.Children.Add(infoStack);

                var moreBtn = new Border
                {
                    Width = 34,
                    Height = 34,
                    Background = BrushFromHex(config.LightMode ? "#EFEFF1" : "#181B22"),
                    BorderBrush = BrushFromHex(bgCardStrokeHex),
                    BorderThickness = new Thickness(1),
                    CornerRadius = new CornerRadius(10),
                    Margin = new Thickness(8, 0, 0, 0),
                    Cursor = Cursors.Hand,
                    Child = new TextBlock
                    {
                        Text = "⋮",
                        FontSize = 17,
                        FontWeight = FontWeights.Bold,
                        Foreground = BrushFromHex(textSubColorHex),
                        HorizontalAlignment = HorizontalAlignment.Center,
                        VerticalAlignment = VerticalAlignment.Center
                    }
                };
                moreBtn.MouseLeftButtonUp += (s, e) =>
                {
                    e.Handled = true;
                    ShowServerActionsDialog(index);
                };
                Grid.SetColumn(moreBtn, 2);
                cardGrid.Children.Add(moreBtn);

                var cardBorder = new Border
                {
                    Background = BrushFromHex(isSelected ? accentCardBgHex : bgCardHex),
                    BorderBrush = BrushFromHex(isSelected ? accentColorHex : bgCardStrokeHex),
                    BorderThickness = new Thickness(1.5),
                    CornerRadius = new CornerRadius(14),
                    Padding = new Thickness(14, 8, 12, 8),
                    Margin = new Thickness(0, 0, 0, i == serverList.Count - 1 ? 0 : 6),
                    Cursor = Cursors.Hand,
                    Child = cardGrid
                };

                cardBorder.MouseLeftButtonUp += (s, e) =>
                {
                    if (!e.Handled) ApplyWindowsDnsServer(server.host);
                };

                cardBorder.MouseRightButtonUp += (s, e) =>
                {
                    Clipboard.SetText(server.host);
                    netStatusText.Text = $"📋 Скопировано в буфер: {server.host}";
                };

                listContainer.Children.Add(cardBorder);
            }

            RecalculateWindowHeight();
        }

        private async void CheckNetworkAndTestAll(bool autoConnectBest = false)
        {
            var nic = GetActiveNetworkInterface();
            string netType = nic != null ? $"{nic.Name} 🌐" : "Нет сети ❌";
            netStatusText.Text = $"{netType} • Проверка порта 853 (IPv4)...";

            foreach (var s in serverList) s.ping = -2;
            RenderServerList();

            await Task.Run(() =>
            {
                foreach (var server in serverList.ToList())
                {
                    var sw = Stopwatch.StartNew();
                    int resultPing = -3;
                    try
                    {
                        var ipv4List = ResolveHostToIpv4(server.host);
                        if (ipv4List.Length > 0 && IPAddress.TryParse(ipv4List[0], out var targetIp))
                        {
                            using var client = new TcpClient(AddressFamily.InterNetwork);
                            var task = client.ConnectAsync(targetIp, 853);
                            if (task.Wait(2200) && client.Connected)
                            {
                                sw.Stop();
                                resultPing = (int)sw.ElapsedMilliseconds;
                            }
                        }
                    }
                    catch { resultPing = -3; }
                    server.ping = resultPing;
                }
            });

            bool dnsResolves = await Task.Run(() =>
            {
                try
                {
                    return Dns.GetHostAddresses("google.com")
                        .Any(ip => ip.AddressFamily == AddressFamily.InterNetwork);
                }
                catch { return false; }
            });

            if (config.AutoSortPing)
            {
                serverList = serverList.OrderBy(s => s.ping > 0 ? s.ping : int.MaxValue).ToList();
            }
            SaveConfig();

            int workingCount = serverList.Count(s => s.ping > 0);
            string resolveBadge = dnsResolves ? "IPv4 DNS: OK ✅" : "IPv4 DNS: Сбой ⚠️";
            netStatusText.Text = $"{netType} • DoT: {workingCount}/{serverList.Count} • {resolveBadge}";
            RenderServerList();

            if (autoConnectBest)
            {
                var best = serverList.Where(s => s.ping > 0).OrderBy(s => s.ping).FirstOrDefault();
                if (best != null) ApplyWindowsDnsServer(best.host);
            }
        }

        private async void VerifyActiveDnsInternet()
        {
            netStatusText.Text = "⏳ Проверка IPv4 интернет-отклика и DNS-резолвинга...";
            var sw = Stopwatch.StartNew();
            bool ok = await Task.Run(() =>
            {
                try
                {
                    return Dns.GetHostAddresses("google.com")
                        .Any(ip => ip.AddressFamily == AddressFamily.InterNetwork);
                }
                catch { return false; }
            });
            sw.Stop();
            int workingCount = serverList.Count(s => s.ping > 0);
            netStatusText.Text = ok
                ? $"🌐 IPv4 резолвинг: OK ({sw.ElapsedMilliseconds} мс) ✅ • DoT: {workingCount}/{serverList.Count}"
                : $"⚠️ Сбой разрешения доменов (IPv4) ❌ • DoT: {workingCount}/{serverList.Count}";
        }

        private Window CreateModalDialog(string title, Action? onBackAction = null)
        {
            var win = new Window
            {
                Title = title,
                Width = 405,
                SizeToContent = SizeToContent.Height,
                WindowStartupLocation = WindowStartupLocation.CenterOwner,
                Owner = this,
                ResizeMode = ResizeMode.NoResize,
                WindowStyle = WindowStyle.None,
                AllowsTransparency = true,
                Background = MediaBrushes.Transparent
            };

            win.PreviewKeyDown += (s, e) =>
            {
                if (e.Key == Key.Escape)
                {
                    e.Handled = true;
                    win.Close();
                    onBackAction?.Invoke();
                }
            };

            return win;
        }

        private TextBlock CreateDialogHeader(string text)
        {
            return new TextBlock
            {
                Text = text,
                FontSize = 17,
                FontWeight = FontWeights.SemiBold,
                Foreground = BrushFromHex(textMainColorHex),
                HorizontalAlignment = HorizontalAlignment.Center,
                Margin = new Thickness(8, 4, 8, 16)
            };
        }

        private Border CreateMenuCard(string title, string subtitle, Action onClick, string? customStroke = null, string? customTitleColor = null)
        {
            var stack = new StackPanel();
            stack.Children.Add(new TextBlock
            {
                Text = title,
                FontSize = 14,
                FontWeight = FontWeights.SemiBold,
                Foreground = BrushFromHex(customTitleColor ?? textMainColorHex)
            });
            stack.Children.Add(new TextBlock
            {
                Text = subtitle,
                FontSize = 11.5,
                Foreground = BrushFromHex(textSubColorHex),
                Margin = new Thickness(0, 3, 0, 0),
                TextWrapping = TextWrapping.Wrap
            });

            var border = new Border
            {
                Background = BrushFromHex(bgCardHex),
                BorderBrush = BrushFromHex(customStroke ?? bgCardStrokeHex),
                BorderThickness = new Thickness(1.5),
                CornerRadius = new CornerRadius(12),
                Padding = new Thickness(14, 10, 14, 10),
                Margin = new Thickness(0, 0, 0, 9),
                Cursor = Cursors.Hand,
                Child = stack
            };
            border.MouseLeftButtonUp += (s, e) => onClick();
            return border;
        }

        private Border CreateDialogButton(string text, string textColorHex, Action onClick, string? bgHex = null, string? strokeHex = null)
        {
            var btn = new Border
            {
                Background = BrushFromHex(bgHex ?? bgCardHex),
                BorderBrush = BrushFromHex(strokeHex ?? bgCardStrokeHex),
                BorderThickness = new Thickness(1.5),
                CornerRadius = new CornerRadius(12),
                Padding = new Thickness(14, 10, 14, 10),
                Margin = new Thickness(0, 6, 0, 0),
                Cursor = Cursors.Hand,
                Child = new TextBlock
                {
                    Text = text,
                    FontSize = 13.5,
                    FontWeight = FontWeights.Bold,
                    Foreground = BrushFromHex(textColorHex),
                    HorizontalAlignment = HorizontalAlignment.Center,
                    TextWrapping = TextWrapping.Wrap
                }
            };
            btn.MouseLeftButtonUp += (s, e) => onClick();
            return btn;
        }

        private void ShowSettingsDialog()
        {
            var dlg = CreateModalDialog("Настройки");
            var root = new StackPanel();
            root.Children.Add(CreateDialogHeader("⚙️ Настройки приложения"));

            root.Children.Add(CreateMenuCard("➕ Добавить свой сервер", "Ручной ввод хоста и имени", () =>
            {
                dlg.Close();
                ShowEditServerDialog(null, returnToSettings: true);
            }));

            root.Children.Add(CreateMenuCard("📚 Популярные DNS-серверы", "Каталог проверенных серверов", () =>
            {
                dlg.Close();
                ShowPresetsDialog();
            }));

            root.Children.Add(CreateMenuCard("📊 Ping Benchmark (Топ серверов)", "Анализ скорости и автовыбор лидера", () =>
            {
                dlg.Close();
                ShowBenchmarkDialog();
            }));

            root.Children.Add(CreateMenuCard("🕵️ Проверить защиту и регион Gemini", "Тест скрытия местоположения, прокси-страны и капчи", () =>
            {
                dlg.Close();
                ShowGeminiRegionCheckDialog(returnToSettings: true);
            }, accentColorHex));

            root.Children.Add(CreateMenuCard("🌐 Сетевые настройки", "Обход DPI (Zapret), профиль DPI, браузеры, IPv6, DoH", () =>
            {
                dlg.Close();
                ShowNetworkSettingsDialog();
            }));

            root.Children.Add(CreateMenuCard("🎨 Кастомизация и система", "Выбор темы, светлый/темный режим, автозапуск", () =>
            {
                dlg.Close();
                ShowCustomizationDialog();
            }));

            root.Children.Add(CreateMenuCard("💾 Бэкап и сброс настроек", "Экспорт/импорт JSON, сброс списка", () =>
            {
                dlg.Close();
                ShowBackupDialog();
            }));

            root.Children.Add(CreateDialogButton("Закрыть", accentColorHex, () => dlg.Close()));

            dlg.Content = new Border
            {
                Background = BrushFromHex(config.LightMode ? "#FFFFFF" : "#0F1117"),
                BorderBrush = BrushFromHex(bgCardStrokeHex),
                BorderThickness = new Thickness(2),
                CornerRadius = new CornerRadius(20),
                Padding = new Thickness(22, 18, 22, 18),
                Child = root
            };
            dlg.ShowDialog();
        }

        private void ShowBenchmarkDialog()
        {
            var dlg = CreateModalDialog("Ping Benchmark", () => ShowSettingsDialog());
            var root = new StackPanel();
            root.Children.Add(CreateDialogHeader("📊 Ping Benchmark — Топ серверов"));

            var listBox = new StackPanel();
            var sorted = serverList.OrderBy(s => s.ping > 0 ? s.ping : int.MaxValue).ToList();

            for (int i = 0; i < sorted.Count; i++)
            {
                var srv = sorted[i];
                string rank = i switch { 0 => "🥇 #1", 1 => "🥈 #2", 2 => "🥉 #3", _ => $"   #{i + 1}" };
                string pingText = srv.ping switch
                {
                    -1 => "Не проверен",
                    -2 => "⏳ Тестирование...",
                    -3 => "❌ Заблокирован (порт 853)",
                    _ => $"⚡ {srv.ping} мс"
                };

                listBox.Children.Add(CreateMenuCard($"{rank}  {srv.name}", $"{pingText} ({srv.host})", () =>
                {
                    dlg.Close();
                    ApplyWindowsDnsServer(srv.host);
                }));
            }

            root.Children.Add(new ScrollViewer
            {
                MaxHeight = 290,
                VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
                Content = listBox
            });

            root.Children.Add(CreateDialogButton("⚡ Автовыбор (Подключить лучший #1)", accentColorHex, () =>
            {
                dlg.Close();
                var best = serverList.Where(s => s.ping > 0).OrderBy(s => s.ping).FirstOrDefault();
                if (best != null) ApplyWindowsDnsServer(best.host);
                else CheckNetworkAndTestAll(true);
            }, accentCardBgHex, accentColorHex));

            root.Children.Add(CreateDialogButton("🔄 Запустить тест скорости", textMainColorHex, () =>
            {
                dlg.Close();
                CheckNetworkAndTestAll(false);
            }));

            root.Children.Add(CreateDialogButton("Назад к настройкам", textSubColorHex, () =>
            {
                dlg.Close();
                ShowSettingsDialog();
            }));

            dlg.Content = new Border
            {
                Background = BrushFromHex(config.LightMode ? "#FFFFFF" : "#0F1117"),
                BorderBrush = BrushFromHex(bgCardStrokeHex),
                BorderThickness = new Thickness(2),
                CornerRadius = new CornerRadius(20),
                Padding = new Thickness(22, 18, 22, 18),
                Child = root
            };
            dlg.ShowDialog();
        }

        private void ShowNetworkSettingsDialog()
        {
            var dlg = CreateModalDialog("Сетевые настройки", () => ShowSettingsDialog());
            var root = new StackPanel();
            root.Children.Add(CreateDialogHeader("🌐 Сетевые настройки"));

            var browserConflicts = CheckBrowserDnsConflicts();

            string browserStatusSub = config.AutoFixBrowserDns
                ? "Сейчас: Автоподчинение ВКЛ (браузеры берут системный DNS)"
                : (browserConflicts.Count > 0
                    ? $"⚠️ Обнаружен конфликт ({string.Join(", ", browserConflicts.Select(c => c.BrowserName))})! Нажмите для решения"
                    : "Сейчас: Мониторинг активен (конфликтов не обнаружено)");

            root.Children.Add(CreateMenuCard("🕵️ Проверить защиту и регион Gemini",
                "Проверить видимую страну для Gemini, IP-прокси и наличие капчи Google", () =>
            {
                dlg.Close();
                ShowGeminiRegionCheckDialog(returnToSettings: false, returnToNetworkSettings: true);
            }, accentColorHex));

            root.Children.Add(CreateMenuCard("🛡️ Обход DPI (Zapret) — Вкл / Выкл",
                config.EnableDpiBypass
                    ? $"Сейчас: ВКЛЮЧЕН • {GetDpiProfileShortName()}"
                    : "Сейчас: Отключен", () =>
            {
                ToggleDpiBypass();
                dlg.Close();
                ShowNetworkSettingsDialog();
            }, config.EnableDpiBypass ? accentColorHex : null));

            root.Children.Add(CreateMenuCard("⚙️ Выбор профиля обхода DPI",
                $"Текущий режим: {GetDpiProfileShortName()} (нажмите для смены метода)", () =>
            {
                dlg.Close();
                ShowDpiProfileDialog(returnToNetworkSettings: true);
            }));

            root.Children.Add(CreateMenuCard("🌐 Контроль DNS в браузерах (Chrome / Edge / Brave / Яндекс)", browserStatusSub, () =>
            {
                dlg.Close();
                ShowBrowserControlMenuDialog();
            }, browserConflicts.Count > 0 ? "#FFAB00" : (config.AutoFixBrowserDns ? accentColorHex : null)));

            root.Children.Add(CreateMenuCard("🚫 Блокировка IPv6 (Защита Gemini)",
                config.BlockIpv6
                    ? "Сейчас: IPv6 заблокирован (hosts чист, Gemini работает по IPv4)"
                    : "Сейчас: IPv6 включён (Приложение и сайт Gemini ЗАБЛОКИРОВАНЫ)", () =>
            {
                config.BlockIpv6 = !config.BlockIpv6;
                SaveConfig();
                EnforceIpv6Policy();
                RefreshSystemState();
                dlg.Close();
                ShowNetworkSettingsDialog();
            }, config.BlockIpv6 ? accentColorHex : "#FF5252"));

            root.Children.Add(CreateMenuCard("🔒 Нативное шифрование DoH (Windows 11)",
                config.EnableDohEncryption ? "Сейчас: Включено (https://.../dns-query)" : "Сейчас: Отключено (только классический IPv4)", () =>
            {
                config.EnableDohEncryption = !config.EnableDohEncryption;
                SaveConfig();
                if (config.CurrentMode == "on" && !string.IsNullOrEmpty(config.LastActiveHost))
                {
                    ApplyWindowsDnsServer(config.LastActiveHost);
                }
                dlg.Close();
                ShowNetworkSettingsDialog();
            }));

            root.Children.Add(CreateMenuCard("🔀 Автосортировка серверов по пингу",
                config.AutoSortPing ? "Сейчас: Включена (быстрые серверы поднимаются наверх)" : "Сейчас: Отключена", () =>
            {
                config.AutoSortPing = !config.AutoSortPing;
                SaveConfig();
                if (config.AutoSortPing)
                {
                    serverList = serverList.OrderBy(s => s.ping > 0 ? s.ping : int.MaxValue).ToList();
                    RenderServerList();
                }
                dlg.Close();
                ShowNetworkSettingsDialog();
            }));

            root.Children.Add(CreateDialogButton("Назад к настройкам", accentColorHex, () =>
            {
                dlg.Close();
                ShowSettingsDialog();
            }));

            dlg.Content = new Border
            {
                Background = BrushFromHex(config.LightMode ? "#FFFFFF" : "#0F1117"),
                BorderBrush = BrushFromHex(bgCardStrokeHex),
                BorderThickness = new Thickness(2),
                CornerRadius = new CornerRadius(20),
                Padding = new Thickness(22, 18, 22, 18),
                Child = root
            };
            dlg.ShowDialog();
        }

        private void ShowDpiProfileDialog(bool returnToNetworkSettings)
        {
            var dlg = CreateModalDialog("Профиль обхода DPI", () =>
            {
                if (returnToNetworkSettings) ShowNetworkSettingsDialog();
            });

            var root = new StackPanel();
            root.Children.Add(CreateDialogHeader("🛡️ Профиль обхода DPI (Zapret)"));

            var profiles = new (int id, string title, string desc)[]
            {
                (1, "Профиль #1 — YouTube/Discord + Исключение Gemini/DNS",
                    "Разрезает пакеты (1,midsld) строго для YouTube и Discord. Полностью исключает трафик Gemini, ChatGPT и всех DNS-серверов."),
                (2, "Профиль #2 — Multisplit + SeqOvl=2 (С исключением Gemini)",
                    "Наложение 2 байт для YouTube и Discord со списком исключений для прокси DNS и ИИ-сервисов."),
                (3, "Профиль #3 — Глобальный + Исключение Gemini и DNS",
                    "Применяет обход ко всем сайтам и видеосерверам, кроме списка исключений (Gemini, ИИ, Comss, Malw, GeoHide).")
            };

            foreach (var p in profiles)
            {
                bool isSel = config.DpiStrategyProfile == p.id;
                root.Children.Add(CreateMenuCard(p.title, p.desc, () =>
                {
                    config.DpiStrategyProfile = p.id;
                    SaveConfig();
                    if (config.EnableDpiBypass) StartDpiBypass(silent: false);
                    else UpdateDpiButtonVisualState();
                    dlg.Close();
                    if (returnToNetworkSettings) ShowNetworkSettingsDialog();
                }, isSel ? accentColorHex : null, isSel ? accentColorHex : null));
            }

            root.Children.Add(CreateDialogButton("Назад", textSubColorHex, () =>
            {
                dlg.Close();
                if (returnToNetworkSettings) ShowNetworkSettingsDialog();
            }));

            dlg.Content = new Border
            {
                Background = BrushFromHex(config.LightMode ? "#FFFFFF" : "#0F1117"),
                BorderBrush = BrushFromHex(bgCardStrokeHex),
                BorderThickness = new Thickness(2),
                CornerRadius = new CornerRadius(20),
                Padding = new Thickness(22, 18, 22, 18),
                Child = root
            };
            dlg.ShowDialog();
        }

        private void ShowCustomizationDialog()
        {
            var dlg = CreateModalDialog("Кастомизация и система", () => ShowSettingsDialog());
            var root = new StackPanel();
            root.Children.Add(CreateDialogHeader("🎨 Кастомизация и система"));

            bool autoStartActive = IsAutoStartEnabled();

            root.Children.Add(CreateMenuCard("🎨 Выбор темы и неонового акцента", "Зеленый, Синий, Фиолетовый, Янтарный", () =>
            {
                dlg.Close();
                ShowThemeChooserDialog();
            }));

            root.Children.Add(CreateMenuCard("☀️ Режим оформления интерфейса",
                config.LightMode ? "Сейчас: Светлая тема" : "Сейчас: Темная тема", () =>
            {
                config.LightMode = !config.LightMode;
                SaveConfig();
                UpdateUIColors();
                dlg.Close();
                ShowCustomizationDialog();
            }));

            root.Children.Add(CreateMenuCard("🚀 Автозапуск вместе с Windows",
                autoStartActive ? "Сейчас: Включен (автостарт при входе в систему)" : "Сейчас: Отключен", () =>
            {
                SetAutoStartTask(!autoStartActive);
                dlg.Close();
                ShowCustomizationDialog();
            }));

            root.Children.Add(CreateDialogButton("Назад к настройкам", accentColorHex, () =>
            {
                dlg.Close();
                ShowSettingsDialog();
            }));

            dlg.Content = new Border
            {
                Background = BrushFromHex(config.LightMode ? "#FFFFFF" : "#0F1117"),
                BorderBrush = BrushFromHex(bgCardStrokeHex),
                BorderThickness = new Thickness(2),
                CornerRadius = new CornerRadius(20),
                Padding = new Thickness(22, 18, 22, 18),
                Child = root
            };
            dlg.ShowDialog();
        }

        private void ShowBrowserControlMenuDialog()
        {
            var dlg = CreateModalDialog("Контроль DNS в браузерах", () => ShowNetworkSettingsDialog());
            var root = new StackPanel();
            root.Children.Add(CreateDialogHeader("🌐 Контроль DNS в браузерах"));

            var conflicts = CheckBrowserDnsConflicts();
            string statusMsg = conflicts.Count > 0
                ? $"⚠️ Обнаружен сторонний DNS в: {string.Join(", ", conflicts.Select(c => c.BrowserName))}."
                : "✅ Все обнаруженные браузеры подчиняются системному DNS Windows.";

            root.Children.Add(CreateMenuCard("🔍 Статус проверки браузеров", statusMsg, () => { },
                conflicts.Count > 0 ? "#FFAB00" : accentColorHex));

            root.Children.Add(CreateMenuCard("⚡ Автоматически подчинить браузеры системе",
                "Перевести Chrome, Edge, Brave и Яндекс в режим «С вашим текущим поставщиком услуг»", () =>
            {
                dlg.Close();
                ApplyBrowserDnsAutoFix(silent: false);
                ShowNetworkSettingsDialog();
            }, accentColorHex));

            root.Children.Add(CreateMenuCard("📖 Открыть инструкцию (Как настроить вручную)",
                "Пошаговое описание и быстрые ссылки для адресной строки", () =>
            {
                dlg.Close();
                ShowBrowserManualGuideDialog(returnToNetworkSettings: true);
            }));

            if (config.AutoFixBrowserDns)
            {
                root.Children.Add(CreateMenuCard("🔓 Отключить принудительную политику браузеров",
                    "Разрешить ручное изменение DNS внутри настроек браузера", () =>
                {
                    RemoveBrowserDnsPolicies();
                    dlg.Close();
                    ShowNetworkSettingsDialog();
                }));
            }

            root.Children.Add(CreateDialogButton("Назад к сетевым настройкам", textSubColorHex, () =>
            {
                dlg.Close();
                ShowNetworkSettingsDialog();
            }));

            dlg.Content = new Border
            {
                Background = BrushFromHex(config.LightMode ? "#FFFFFF" : "#0F1117"),
                BorderBrush = BrushFromHex(bgCardStrokeHex),
                BorderThickness = new Thickness(2),
                CornerRadius = new CornerRadius(20),
                Padding = new Thickness(22, 18, 22, 18),
                Child = root
            };
            dlg.ShowDialog();
        }

        private void ShowThemeChooserDialog()
        {
            var dlg = CreateModalDialog("Выбор темы", () => ShowCustomizationDialog());
            var root = new StackPanel();
            root.Children.Add(CreateDialogHeader("🎨 Выбор темы оформления"));

            var themes = new (string key, string title, string color)[]
            {
                ("green", "🟢 Neon Green (Классический неон)", "#00E676"),
                ("blue", "🔵 Cyber Blue (Неоновый голубой)", "#00B0FF"),
                ("purple", "🟣 Synthwave Purple (Фиолетовый кибер)", "#E040FB"),
                ("amber", "🟠 Amber Glow (Янтарный премиум)", "#FFAB00")
            };

            foreach (var t in themes)
            {
                bool isSel = config.Theme == t.key;
                root.Children.Add(CreateMenuCard(t.title, isSel ? "Активная тема" : "Нажмите для применения", () =>
                {
                    config.Theme = t.key;
                    SaveConfig();
                    UpdateUIColors();
                    dlg.Close();
                    ShowCustomizationDialog();
                }, isSel ? t.color : null, isSel ? t.color : null));
            }

            root.Children.Add(CreateDialogButton("Назад к кастомизации", accentColorHex, () =>
            {
                dlg.Close();
                ShowCustomizationDialog();
            }));

            dlg.Content = new Border
            {
                Background = BrushFromHex(config.LightMode ? "#FFFFFF" : "#0F1117"),
                BorderBrush = BrushFromHex(bgCardStrokeHex),
                BorderThickness = new Thickness(2),
                CornerRadius = new CornerRadius(20),
                Padding = new Thickness(22, 18, 22, 18),
                Child = root
            };
            dlg.ShowDialog();
        }

        private void ShowServerActionsDialog(int index)
        {
            if (index < 0 || index >= serverList.Count) return;
            var server = serverList[index];
            var dlg = CreateModalDialog(server.name);
            var root = new StackPanel();

            root.Children.Add(CreateDialogHeader(server.name));

            root.Children.Add(CreateMenuCard("✏️ Редактировать", "Изменить название или адрес хоста", () =>
            {
                dlg.Close();
                ShowEditServerDialog(index, returnToSettings: false);
            }));

            root.Children.Add(CreateMenuCard("🗑️ Удалить", "Удалить сервер из вашего списка", () =>
            {
                dlg.Close();
                lastDeletedServer = serverList[index];
                lastDeletedIndex = index;
                serverList.RemoveAt(index);
                SaveConfig();
                RenderServerList();

                undoText.Text = $"Удален «{lastDeletedServer.name}»";
                undoBanner.Visibility = Visibility.Visible;
                RecalculateWindowHeight();
                undoTimer.Stop();
                undoTimer.Start();
            }, "#FF5252", "#FF5252"));

            root.Children.Add(CreateDialogButton("Отмена", accentColorHex, () => dlg.Close()));

            dlg.Content = new Border
            {
                Background = BrushFromHex(config.LightMode ? "#FFFFFF" : "#0F1117"),
                BorderBrush = BrushFromHex(bgCardStrokeHex),
                BorderThickness = new Thickness(2),
                CornerRadius = new CornerRadius(20),
                Padding = new Thickness(22, 18, 22, 18),
                Child = root
            };
            dlg.ShowDialog();
        }

        private void ShowEditServerDialog(int? editIndex, bool returnToSettings)
        {
            bool isEdit = editIndex.HasValue && editIndex.Value >= 0;
            var current = isEdit ? serverList[editIndex!.Value] : null;

            var dlg = CreateModalDialog("Сервер", () =>
            {
                if (returnToSettings) ShowSettingsDialog();
            });

            var root = new StackPanel();
            root.Children.Add(CreateDialogHeader(isEdit ? "✏️ Редактировать сервер" : "➕ Добавить свой сервер"));

            var nameBox = new TextBox
            {
                Text = current?.name ?? "",
                FontSize = 14,
                Foreground = BrushFromHex(textMainColorHex),
                Background = BrushFromHex(bgCardHex),
                BorderBrush = BrushFromHex(bgCardStrokeHex),
                Padding = new Thickness(10, 8, 10, 8),
                Margin = new Thickness(0, 0, 0, 10)
            };

            var hostBox = new TextBox
            {
                Text = current?.host ?? "",
                FontSize = 14,
                Foreground = BrushFromHex(textMainColorHex),
                Background = BrushFromHex(bgCardHex),
                BorderBrush = BrushFromHex(bgCardStrokeHex),
                Padding = new Thickness(10, 8, 10, 8),
                Margin = new Thickness(0, 0, 0, 14)
            };

            root.Children.Add(new TextBlock { Text = "Название:", Foreground = BrushFromHex(textSubColorHex), Margin = new Thickness(0, 0, 0, 4) });
            root.Children.Add(nameBox);
            root.Children.Add(new TextBlock { Text = "Хост (например, dns.comss.one):", Foreground = BrushFromHex(textSubColorHex), Margin = new Thickness(0, 0, 0, 4) });
            root.Children.Add(hostBox);

            root.Children.Add(CreateDialogButton("Сохранить", accentColorHex, () =>
            {
                string cleanHost = hostBox.Text.Trim().ToLowerInvariant()
                    .Replace("https://", "").Replace("http://", "").Replace("tls://", "");
                if (cleanHost.Contains('/')) cleanHost = cleanHost.Split('/')[0];
                if (cleanHost.Contains(':')) cleanHost = cleanHost.Split(':')[0];

                if (string.IsNullOrEmpty(cleanHost)) return;
                string cleanName = string.IsNullOrWhiteSpace(nameBox.Text) ? cleanHost : nameBox.Text.Trim();

                if (isEdit)
                {
                    serverList[editIndex!.Value].name = cleanName;
                    serverList[editIndex!.Value].host = cleanHost;
                }
                else
                {
                    serverList.Add(new DnsServer { name = cleanName, host = cleanHost });
                }
                SaveConfig();
                dlg.Close();
                CheckNetworkAndTestAll();
            }, accentCardBgHex, accentColorHex));

            root.Children.Add(CreateDialogButton("Назад", textSubColorHex, () =>
            {
                dlg.Close();
                if (returnToSettings) ShowSettingsDialog();
            }));

            dlg.Content = new Border
            {
                Background = BrushFromHex(config.LightMode ? "#FFFFFF" : "#0F1117"),
                BorderBrush = BrushFromHex(bgCardStrokeHex),
                BorderThickness = new Thickness(2),
                CornerRadius = new CornerRadius(20),
                Padding = new Thickness(22, 18, 22, 18),
                Child = root
            };
            dlg.ShowDialog();
        }

        private void ShowPresetsDialog()
        {
            var dlg = CreateModalDialog("Популярные серверы", () => ShowSettingsDialog());
            var root = new StackPanel();
            root.Children.Add(CreateDialogHeader("📚 Популярные DNS-серверы"));

            var listStack = new StackPanel();
            foreach (var item in popularCatalog)
            {
                listStack.Children.Add(CreateMenuCard(item.name, item.host, () =>
                {
                    if (!serverList.Any(s => string.Equals(s.host, item.host, StringComparison.OrdinalIgnoreCase)))
                    {
                        serverList.Add(new DnsServer { name = item.name, host = item.host });
                        SaveConfig();
                        CheckNetworkAndTestAll();
                    }
                    dlg.Close();
                }));
            }

            root.Children.Add(new ScrollViewer
            {
                MaxHeight = 310,
                VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
                Content = listStack
            });

            root.Children.Add(CreateDialogButton("➕ Добавить все недостающие", accentColorHex, () =>
            {
                foreach (var p in popularCatalog)
                {
                    if (!serverList.Any(s => string.Equals(s.host, p.host, StringComparison.OrdinalIgnoreCase)))
                    {
                        serverList.Add(new DnsServer { name = p.name, host = p.host });
                    }
                }
                SaveConfig();
                dlg.Close();
                CheckNetworkAndTestAll();
            }));

            root.Children.Add(CreateDialogButton("Назад к настройкам", textSubColorHex, () =>
            {
                dlg.Close();
                ShowSettingsDialog();
            }));

            dlg.Content = new Border
            {
                Background = BrushFromHex(config.LightMode ? "#FFFFFF" : "#0F1117"),
                BorderBrush = BrushFromHex(bgCardStrokeHex),
                BorderThickness = new Thickness(2),
                CornerRadius = new CornerRadius(20),
                Padding = new Thickness(22, 18, 22, 18),
                Child = root
            };
            dlg.ShowDialog();
        }

        private void ShowBackupDialog()
        {
            var dlg = CreateModalDialog("Бэкап", () => ShowSettingsDialog());
            var root = new StackPanel();
            root.Children.Add(CreateDialogHeader("💾 Бэкап и восстановление"));

            root.Children.Add(CreateMenuCard("💾 Сохранить бэкап в файл (.json)", "Полная совместимость с Android-версией", () =>
            {
                var sfd = new SaveFileDialog
                {
                    FileName = "dns_switcher_backup.json",
                    Filter = "JSON Files (*.json)|*.json"
                };
                if (sfd.ShowDialog() == true)
                {
                    string json = JsonSerializer.Serialize(serverList, new JsonSerializerOptions { WriteIndented = true });
                    File.WriteAllText(sfd.FileName, json, Utf8NoBom);
                }
                dlg.Close();
            }));

            root.Children.Add(CreateMenuCard("📂 Загрузить бэкап из файла (.json)", "Импорт серверов с телефона или ПК", () =>
            {
                var ofd = new OpenFileDialog
                {
                    Filter = "JSON Files (*.json)|*.json|All files (*.*)|*.*"
                };
                if (ofd.ShowDialog() == true)
                {
                    try
                    {
                        string raw = File.ReadAllText(ofd.FileName, Encoding.UTF8);
                        var imported = JsonSerializer.Deserialize<List<DnsServer>>(raw);
                        if (imported != null)
                        {
                            foreach (var s in imported)
                            {
                                if (!string.IsNullOrEmpty(s.host) && !serverList.Any(x => x.host.Equals(s.host, StringComparison.OrdinalIgnoreCase)))
                                {
                                    serverList.Add(s);
                                }
                            }
                            SaveConfig();
                            CheckNetworkAndTestAll();
                        }
                    }
                    catch { }
                }
                dlg.Close();
            }));

            root.Children.Add(CreateMenuCard("🔄 Сбросить к стандартным 7 серверам", "Восстановить базовый список", () =>
            {
                serverList = defaultServers.Select(d => new DnsServer { name = d.name, host = d.host }).ToList();
                SaveConfig();
                dlg.Close();
                CheckNetworkAndTestAll();
            }));

            root.Children.Add(CreateDialogButton("Назад к настройкам", accentColorHex, () =>
            {
                dlg.Close();
                ShowSettingsDialog();
            }));

            dlg.Content = new Border
            {
                Background = BrushFromHex(config.LightMode ? "#FFFFFF" : "#0F1117"),
                BorderBrush = BrushFromHex(bgCardStrokeHex),
                BorderThickness = new Thickness(2),
                CornerRadius = new CornerRadius(20),
                Padding = new Thickness(22, 18, 22, 18),
                Child = root
            };
            dlg.ShowDialog();
        }

        private void SetupTrayIcon()
        {
            trayIcon = new Forms.NotifyIcon
            {
                Icon = LoadAppCustomIcon(),
                Visible = true,
                Text = "DNS Switcher Pro (IPv4 Only)"
            };

            trayIcon.DoubleClick += (s, e) =>
            {
                Show();
                if (WindowState == WindowState.Minimized) WindowState = WindowState.Normal;
                RecalculateWindowHeight();
                Activate();
            };

            UpdateTrayMenu();
        }

        private void UpdateTrayMenu()
        {
            var menu = new Forms.ContextMenuStrip();
            menu.Items.Add("⚡ Открыть DNS Switcher Pro", null, (s, e) =>
            {
                Show();
                if (WindowState == WindowState.Minimized) WindowState = WindowState.Normal;
                RecalculateWindowHeight();
                Activate();
            });
            menu.Items.Add("🕵️ Проверить регион Gemini", null, (s, e) =>
            {
                Show();
                if (WindowState == WindowState.Minimized) WindowState = WindowState.Normal;
                Activate();
                ShowGeminiRegionCheckDialog();
            });
            menu.Items.Add(new Forms.ToolStripSeparator());

            string dpiLabel = config.EnableDpiBypass
                ? $"✔ 🛡️ Обход DPI: ВКЛ ({GetDpiProfileShortName()})"
                : "    🛡️ Обход DPI: ВЫКЛ";
            menu.Items.Add(dpiLabel, null, (s, e) => ToggleDpiBypass());

            menu.Items.Add(new Forms.ToolStripSeparator());

            foreach (var srv in serverList)
            {
                bool isCurrent = config.CurrentMode == "on" &&
                                 string.Equals(config.LastActiveHost, srv.host, StringComparison.OrdinalIgnoreCase);
                string prefix = isCurrent ? "✔ " : "    ";
                menu.Items.Add($"{prefix}{srv.name}", null, (s, e) => ApplyWindowsDnsServer(srv.host));
            }

            menu.Items.Add(new Forms.ToolStripSeparator());
            menu.Items.Add("Режим: Авто (DHCP + DoH)", null, (s, e) => ResetWindowsDnsToDhcp(true));
            menu.Items.Add("Режим: Выкл (Стандартный DHCP)", null, (s, e) => ResetWindowsDnsToDhcp(false));
            menu.Items.Add(new Forms.ToolStripSeparator());
            menu.Items.Add("Выход", null, (s, e) =>
            {
                StopDpiBypass(restoreBrowserPolicies: true);
                trayIcon.Visible = false;
                Environment.Exit(0);
            });

            trayIcon.ContextMenuStrip = menu;
        }

        private void LoadConfig()
        {
            if (File.Exists(configPath))
            {
                try
                {
                    string json = File.ReadAllText(configPath, Encoding.UTF8);
                    var loaded = JsonSerializer.Deserialize<AppConfig>(json);
                    if (loaded != null)
                    {
                        config = loaded;
                        if (config.DpiStrategyProfile < 1 || config.DpiStrategyProfile > 3)
                        {
                            config.DpiStrategyProfile = 1;
                        }
                        serverList = config.Servers ?? new List<DnsServer>();
                    }
                }
                catch { }
            }

            if (serverList.Count == 0)
            {
                serverList = defaultServers.Select(d => new DnsServer { name = d.name, host = d.host }).ToList();
                SaveConfig();
            }
        }

        private void SaveConfig()
        {
            try
            {
                config.Servers = serverList;
                string json = JsonSerializer.Serialize(config, new JsonSerializerOptions { WriteIndented = true });
                File.WriteAllText(configPath, json, Utf8NoBom);
            }
            catch { }
        }
    }
}
