using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Net;
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
        public int DpiStrategyProfile { get; set; } = 1; // 1 = ALT11, 2 = FAKE TLS AUTO, 3 = General Multisplit, 4 = Simple Fake
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

        private Process? dpiProcess = null;

        // Элементы интерфейса
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
        private Border clearGeminiCacheBtn = null!;
        private Border dpiBypassBtn = null!;
        private TextBlock dpiBypassText = null!;

        // Плашка предупреждения о конфликте DNS в браузере
        private Border browserWarningBanner = null!;
        private TextBlock browserWarningText = null!;

        // Плашка Undo
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

            ipv6GuardTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(2000) };
            ipv6GuardTimer.Tick += (s, e) =>
            {
                EnforceGeminiIpv6Guard();
                MonitorBrowserDnsSettings(showPopupOnConflict: false);
            };

            Loaded += async (s, e) =>
            {
                RecalculateWindowHeight();

                if (config.StartWithWindows)
                {
                    await Task.Run(() => SetAutoStartTask(true));
                }

                await Task.Run(() => EnforceIpv6Policy());

                DetectInitialSystemMode();

                if (config.EnableDpiBypass)
                {
                    StartDpiBypass(silent: true);
                }

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

        // Гарантируем наличие списков доменов YouTube и Discord на случай их отсутствия
        private void EnsureZapretListsExist(string dpiDir, out string listGooglePath, out string listGeneralPath)
        {
            string listsDir = Path.Combine(dpiDir, "lists");
            Directory.CreateDirectory(listsDir);

            listGooglePath = Path.Combine(listsDir, "list-google.txt");
            listGeneralPath = Path.Combine(listsDir, "list-general.txt");

            string[] googleDomains = new[]
            {
                "youtube.com",
                "www.youtube.com",
                "m.youtube.com",
                "youtu.be",
                "googlevideo.com",
                "ytimg.com",
                "i.ytimg.com",
                "ggpht.com",
                "yt3.ggpht.com",
                "youtubei.googleapis.com",
                "youtubeembeddedplayer.googleapis.com",
                "ytimg.l.google.com",
                "jnn-pa.googleapis.com",
                "wide-youtube.l.google.com"
            };

            string[] discordAndGeneralDomains = new[]
            {
                "discord.com",
                "www.discord.com",
                "discordapp.com",
                "discordapp.net",
                "media.discordapp.net",
                "cdn.discordapp.com",
                "images-ext-1.discordapp.net",
                "images-ext-2.discordapp.net",
                "discord.gg",
                "discord.media",
                "discordstatus.com",
                "dis.gd",
                "discord-attachments-uploads-prd.storage.googleapis.com",
                "rutracker.org",
                "ntc.party"
            };

            if (!File.Exists(listGooglePath) || new FileInfo(listGooglePath).Length < 20)
            {
                File.WriteAllLines(listGooglePath, googleDomains, Encoding.UTF8);
            }

            if (!File.Exists(listGeneralPath) || new FileInfo(listGeneralPath).Length < 20)
            {
                File.WriteAllLines(listGeneralPath, discordAndGeneralDomains.Concat(googleDomains).Distinct(), Encoding.UTF8);
            }
        }

        // Формирование аргументов winws.exe по архитектуре Flowseal/zapret-discord-youtube
        private string BuildZapretArguments(string dpiDir)
        {
            EnsureZapretListsExist(dpiDir, out string listGoogle, out string listGeneral);

            string quicBin = Path.Combine(dpiDir, "quic_initial_www_google_com.bin");
            string tlsGoogleBin = Path.Combine(dpiDir, "tls_clienthello_www_google_com.bin");
            string tlsMaxBin = Path.Combine(dpiDir, "tls_clienthello_max_ru.bin");
            if (!File.Exists(tlsMaxBin)) tlsMaxBin = tlsGoogleBin;

            bool hasQuic = File.Exists(quicBin);
            bool hasTls = File.Exists(tlsGoogleBin);

            // Перехватываем только нужные порты HTTP/HTTPS и спецпорты Discord (шлюзы 2053..8443 и голос UDP 19294..50100)
            string wfFilter = "--wf-l3=ipv4 --wf-tcp=80,443,2053,2083,2087,2096,8443 --wf-udp=443,19294-19344,50000-50100";

            // 1. Обход QUIC (UDP 443) для YouTube и сайтов из списка
            string udpQuicPart = hasQuic
                ? $"--filter-udp=443 --hostlist=\"{listGeneral}\" --dpi-desync=fake --dpi-desync-repeats=6 --dpi-desync-fake-quic=\"{quicBin}\" --new "
                : $"--filter-udp=443 --hostlist=\"{listGeneral}\" --dpi-desync=fake --dpi-desync-repeats=6 --new ";

            // 2. Обход голосовых каналов и подключения Discord (UDP 19294-19344, 50000-50100 + протоколы discord,stun)
            string udpDiscordVoicePart =
                "--filter-udp=19294-19344,50000-50100 --filter-l7=discord,stun --dpi-desync=fake --dpi-desync-repeats=6 --new ";

            // 3. Обход медиа-шлюзов Discord по нестандартным TCP-портам
            string tcpDiscordMediaPart = hasTls
                ? $"--filter-tcp=2053,2083,2087,2096,8443 --hostlist-domains=discord.media --dpi-desync=fake,multisplit --dpi-desync-split-seqovl=681 --dpi-desync-split-pos=1 --dpi-desync-fooling=ts --dpi-desync-repeats=8 --dpi-desync-split-seqovl-pattern=\"{tlsGoogleBin}\" --dpi-desync-fake-tls=\"{tlsGoogleBin}\" --new "
                : "--filter-tcp=2053,2083,2087,2096,8443 --hostlist-domains=discord.media --dpi-desync=fake,multisplit --dpi-desync-split-pos=1 --dpi-desync-fooling=ts --dpi-desync-repeats=8 --new ";

            int profile = config.DpiStrategyProfile;
            string tcpYoutubeAndGeneralPart;

            if (profile == 2)
            {
                // Профиль #2: FAKE TLS AUTO (multidisorder + динамическая модуляция SNI www.google.com + badseq)
                tcpYoutubeAndGeneralPart =
                    $"--filter-tcp=443 --hostlist=\"{listGoogle}\" --ip-id=zero --dpi-desync=fake,multidisorder --dpi-desync-split-pos=1,midsld --dpi-desync-repeats=11 --dpi-desync-fooling=badseq --dpi-desync-fake-tls-mod=rnd,dupsid,sni=www.google.com --new " +
                    $"--filter-tcp=80,443 --hostlist=\"{listGeneral}\" --dpi-desync=fake,multidisorder --dpi-desync-split-pos=1,midsld --dpi-desync-repeats=6 --dpi-desync-fooling=badseq --dpi-desync-fake-tls-mod=rnd,dupsid,sni=www.google.com";
            }
            else if (profile == 3)
            {
                // Профиль #3: General (multisplit seqovl=681 + badseq + tls_clienthello_max_ru.bin)
                tcpYoutubeAndGeneralPart = hasTls
                    ? $"--filter-tcp=443 --hostlist=\"{listGoogle}\" --ip-id=zero --dpi-desync=fake,multisplit --dpi-desync-split-seqovl=681 --dpi-desync-split-pos=1 --dpi-desync-fooling=badseq --dpi-desync-repeats=8 --dpi-desync-split-seqovl-pattern=\"{tlsGoogleBin}\" --dpi-desync-fake-tls=\"{tlsMaxBin}\" --new " +
                      $"--filter-tcp=80,443 --hostlist=\"{listGeneral}\" --dpi-desync=fake,multisplit --dpi-desync-split-seqovl=681 --dpi-desync-split-pos=1 --dpi-desync-fooling=badseq --dpi-desync-repeats=8 --dpi-desync-split-seqovl-pattern=\"{tlsGoogleBin}\" --dpi-desync-fake-tls=\"{tlsMaxBin}\""
                    : $"--filter-tcp=80,443 --hostlist=\"{listGeneral}\" --dpi-desync=fake,multisplit --dpi-desync-split-pos=1 --dpi-desync-fooling=badseq --dpi-desync-repeats=8";
            }
            else if (profile == 4)
            {
                // Профиль #4: Simple Fake (fake,split2 + ts)
                tcpYoutubeAndGeneralPart = hasTls
                    ? $"--filter-tcp=443 --hostlist=\"{listGoogle}\" --dpi-desync=fake,split2 --dpi-desync-repeats=6 --dpi-desync-fooling=ts --dpi-desync-fake-tls=\"{tlsGoogleBin}\" --new " +
                      $"--filter-tcp=80,443 --hostlist=\"{listGeneral}\" --dpi-desync=fake,split2 --dpi-desync-repeats=6 --dpi-desync-fooling=ts --dpi-desync-fake-tls=\"{tlsGoogleBin}\""
                    : $"--filter-tcp=80,443 --hostlist=\"{listGeneral}\" --dpi-desync=fake,split2 --dpi-desync-repeats=6 --dpi-desync-fooling=ts";
            }
            else
            {
                // Профиль #1 (По умолчанию — ALT11): fake,multisplit + seqovl=681 + ts
                tcpYoutubeAndGeneralPart = hasTls
                    ? $"--filter-tcp=443 --hostlist=\"{listGoogle}\" --ip-id=zero --dpi-desync=fake,multisplit --dpi-desync-split-seqovl=681 --dpi-desync-split-pos=1 --dpi-desync-fooling=ts --dpi-desync-repeats=8 --dpi-desync-split-seqovl-pattern=\"{tlsGoogleBin}\" --dpi-desync-fake-tls=\"{tlsGoogleBin}\" --new " +
                      $"--filter-tcp=80,443 --hostlist=\"{listGeneral}\" --dpi-desync=fake,multisplit --dpi-desync-split-seqovl=681 --dpi-desync-split-pos=1 --dpi-desync-fooling=ts --dpi-desync-repeats=8 --dpi-desync-split-seqovl-pattern=\"{tlsGoogleBin}\" --dpi-desync-fake-tls=\"{tlsGoogleBin}\""
                    : $"--filter-tcp=80,443 --hostlist=\"{listGeneral}\" --dpi-desync=fake,multisplit --dpi-desync-split-pos=1,midsld --dpi-desync-fooling=ts --dpi-desync-repeats=8";
            }

            return $"{wfFilter} {udpQuicPart}{udpDiscordVoicePart}{tcpDiscordMediaPart}{tcpYoutubeAndGeneralPart}";
        }

        private string GetDpiProfileShortName()
        {
            return config.DpiStrategyProfile switch
            {
                2 => "Профиль #2 (FAKE TLS AUTO)",
                3 => "Профиль #3 (General BadSeq)",
                4 => "Профиль #4 (Simple Fake)",
                _ => "Профиль #1 (ALT11 Multisplit)"
            };
        }

        private bool StartDpiBypass(bool silent = false)
        {
            try
            {
                StopDpiBypass();

                // Важно: Для работы Discord и YouTube в РФ не должен быть включен незашифрованный провайдерский DNS (режим "Выкл")
                if (config.CurrentMode == "off")
                {
                    string targetDns = !string.IsNullOrEmpty(config.LastActiveHost)
                        ? config.LastActiveHost
                        : (serverList.Count > 0 ? serverList[0].host : "dns.comss.one");
                    ApplyWindowsDnsServer(targetDns);
                }

                string baseDir = AppDomain.CurrentDomain.BaseDirectory;
                string dpiDir = Path.Combine(baseDir, "dpi");
                string winwsPath = Path.Combine(dpiDir, "winws.exe");

                if (!File.Exists(winwsPath))
                {
                    config.EnableDpiBypass = false;
                    SaveConfig();
                    UpdateDpiButtonVisualState();
                    if (!silent)
                    {
                        MessageBox.Show(
                            $"Файл движка не найден по пути:\n{winwsPath}\n\nПереустановите программу из свежего установщика.",
                            "Ошибка запуска Zapret",
                            MessageBoxButton.OK,
                            MessageBoxImage.Warning);
                    }
                    return false;
                }

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
                    config.EnableDpiBypass = false;
                    SaveConfig();
                    UpdateDpiButtonVisualState();
                    if (!silent)
                    {
                        MessageBox.Show(
                            "Процесс winws.exe завершился сразу после старта.\nПроверьте, что папка программы добавлена в исключения антивируса (для драйвера WinDivert64.sys).",
                            "Обход DPI не запустился",
                            MessageBoxButton.OK,
                            MessageBoxImage.Warning);
                    }
                    return false;
                }

                config.EnableDpiBypass = true;
                SaveConfig();
                RunCommand("ipconfig /flushdns");
                UpdateDpiButtonVisualState();
                UpdateTrayMenu();
                return true;
            }
            catch (Exception ex)
            {
                config.EnableDpiBypass = false;
                SaveConfig();
                UpdateDpiButtonVisualState();
                if (!silent)
                {
                    MessageBox.Show($"Ошибка при запуске обхода DPI: {ex.Message}", "Ошибка", MessageBoxButton.OK, MessageBoxImage.Warning);
                }
                return false;
            }
        }

        private void StopDpiBypass()
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
                        p.WaitForExit(1000);
                    }
                    catch { }
                    finally
                    {
                        p.Dispose();
                    }
                }

                RunCommand("sc stop WinDivert");
                RunCommand("sc stop WinDivert14");
            }
            catch { }
        }

        private void ToggleDpiBypass()
        {
            if (config.EnableDpiBypass)
            {
                StopDpiBypass();
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
                if (File.Exists(icoPath))
                {
                    return new Icon(icoPath);
                }

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
                if (found.Count > 0)
                {
                    ApplyBrowserDnsAutoFix(silent: true);
                }
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
                        var dohObj = new JsonObject
                        {
                            ["mode"] = "automatic",
                            ["templates"] = ""
                        };
                        rootNode["dns_over_https"] = dohObj;
                        File.WriteAllText(b.LocalStatePath, rootNode.ToJsonString(), Encoding.UTF8);
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

                    string networkDir = Path.Combine(bPath, "Network");
                    string cookiesFile = Path.Combine(networkDir, "Cookies");
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

                MessageBox.Show(
                    cleanedCount > 0
                        ? "✅ Кэш и куки Google/Gemini успешно очищены!\nПерезапустите браузер или вкладку Gemini."
                        : "ℹ️ Закройте браузер перед очисткой (файлы заняты процессом) либо кэш уже чист.",
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
            if (config.BlockIpv6) return false;
            return true;
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
                    if (trimmed == HostsBlockStartMarker)
                    {
                        insideBlock = true;
                        continue;
                    }
                    if (trimmed == HostsBlockEndMarker)
                    {
                        insideBlock = false;
                        continue;
                    }
                    if (trimmed.Contains("gemini.google.com", StringComparison.OrdinalIgnoreCase) ||
                        trimmed.Contains("aistudio.google.com", StringComparison.OrdinalIgnoreCase))
                    {
                        continue;
                    }
                    if (!insideBlock)
                    {
                        cleaned.Add(line);
                    }
                }

                if (blockCompletely)
                {
                    cleaned.Add(HostsBlockStartMarker);
                    cleaned.Add("0.0.0.0 gemini.google.com");
                    cleaned.Add("0.0.0.0 www.gemini.google.com");
                    cleaned.Add("0.0.0.0 aistudio.google.com");
                    cleaned.Add(HostsBlockEndMarker);
                }

                File.WriteAllLines(hostsPath, cleaned, Encoding.UTF8);
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

            mainLayout = new StackPanel
            {
                Margin = new Thickness(18, 16, 18, 16)
            };

            // Шапка
            var topGrid = new Grid { Margin = new Thickness(0, 0, 0, 12) };
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

            // Статус сети
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
                Padding = new Thickness(14, 10, 14, 10),
                Margin = new Thickness(0, 0, 0, 10),
                Cursor = Cursors.Hand,
                Child = netStatusText
            };
            netStatusBorder.MouseLeftButtonUp += (s, e) => VerifyActiveDnsInternet();
            mainLayout.Children.Add(netStatusBorder);

            // Сигнальная плашка конфликта DNS в браузере
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
                Padding = new Thickness(14, 9, 14, 9),
                Margin = new Thickness(0, 0, 0, 10),
                Visibility = Visibility.Collapsed,
                Cursor = Cursors.Hand,
                Child = browserWarningText
            };
            browserWarningBanner.MouseLeftButtonUp += (s, e) =>
            {
                var currentConflicts = CheckBrowserDnsConflicts();
                if (currentConflicts.Count > 0)
                    ShowBrowserDnsWarningDialog(currentConflicts);
                else
                    ShowBrowserManualGuideDialog();
            };
            mainLayout.Children.Add(browserWarningBanner);

            // Карточка системного режима
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

            rbOff = new RadioButton
            {
                Content = "Выкл",
                FontSize = 14,
                Foreground = BrushFromHex(textSubColorHex),
                VerticalContentAlignment = VerticalAlignment.Center,
                Cursor = Cursors.Hand
            };

            rbAuto = new RadioButton
            {
                Content = "Авто",
                FontSize = 14,
                Foreground = BrushFromHex(textSubColorHex),
                VerticalContentAlignment = VerticalAlignment.Center,
                Cursor = Cursors.Hand
            };

            rbOn = new RadioButton
            {
                Content = "Вкл",
                FontSize = 14,
                Foreground = BrushFromHex(textSubColorHex),
                VerticalContentAlignment = VerticalAlignment.Center,
                Cursor = Cursors.Hand
            };

            rbOff.Checked += (s, e) =>
            {
                if (isUpdatingUi) return;
                ResetWindowsDnsToDhcp(enableAutoEncryption: false);
            };

            rbAuto.Checked += (s, e) =>
            {
                if (isUpdatingUi) return;
                ResetWindowsDnsToDhcp(enableAutoEncryption: true);
            };

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
                Padding = new Thickness(16, 12, 16, 12),
                Margin = new Thickness(0, 0, 0, 10),
                Child = modeInner
            };
            mainLayout.Children.Add(modeCard);

            // Кнопка очистки кэша/куки Gemini
            var clearGeminiText = new TextBlock
            {
                Text = "🧹 Очистить кэш и куки Gemini (Решить проблему региона)",
                FontSize = 12,
                FontWeight = FontWeights.SemiBold,
                Foreground = BrushFromHex(accentColorHex),
                HorizontalAlignment = HorizontalAlignment.Center
            };

            clearGeminiCacheBtn = new Border
            {
                Background = BrushFromHex(accentCardBgHex),
                BorderBrush = BrushFromHex(accentColorHex),
                BorderThickness = new Thickness(1.5),
                CornerRadius = new CornerRadius(12),
                Padding = new Thickness(14, 9, 14, 9),
                Margin = new Thickness(0, 0, 0, 8),
                Cursor = Cursors.Hand,
                Child = clearGeminiText
            };
            clearGeminiCacheBtn.MouseLeftButtonUp += (s, e) => ClearGeminiCookiesAndCache();
            mainLayout.Children.Add(clearGeminiCacheBtn);

            // Кнопка переключения обхода DPI на главной панели (левый клик — вкл/выкл, правый клик — выбор профиля)
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
                Padding = new Thickness(14, 9, 14, 9),
                Margin = new Thickness(0, 0, 0, 14),
                Cursor = Cursors.Hand,
                Child = dpiBypassText
            };
            dpiBypassBtn.MouseLeftButtonUp += (s, e) => ToggleDpiBypass();
            dpiBypassBtn.MouseRightButtonUp += (s, e) => ShowDpiProfileDialog(returnToNetworkSettings: false);
            UpdateDpiButtonVisualState();
            mainLayout.Children.Add(dpiBypassBtn);

            // Заголовок списка + кнопка пинга
            var headerGrid = new Grid { Margin = new Thickness(0, 0, 0, 10) };
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

            // Плашка Undo
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
                Padding = new Thickness(14, 9, 14, 9),
                Margin = new Thickness(0, 0, 0, 10),
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
            clearGeminiCacheBtn.Background = BrushFromHex(accentCardBgHex);
            clearGeminiCacheBtn.BorderBrush = BrushFromHex(accentColorHex);
            ((TextBlock)clearGeminiCacheBtn.Child).Foreground = BrushFromHex(accentColorHex);
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

            if (knownIps.TryGetValue(host, out var fallback))
            {
                return fallback;
            }
            return Array.Empty<string>();
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

            if (config.EnableDohEncryption)
            {
                ConfigureWindowsDohForServer(ifaceGuid, host, ips);
            }
            else
            {
                ClearInterfaceDohRegistry(ifaceGuid);
            }

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

                string shieldColor = isSelected ? accentColorHex : textSubColorHex;
                var shieldIcon = CreateShieldIcon(shieldColor, isSelected);
                Grid.SetColumn(shieldIcon, 0);
                cardGrid.Children.Add(shieldIcon);

                var infoStack = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
                var nameRow = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };

                var nameTv = new TextBlock
                {
                    Text = server.name,
                    FontSize = 14.5,
                    FontWeight = FontWeights.SemiBold,
                    Foreground = BrushFromHex(textMainColorHex),
                    VerticalAlignment = VerticalAlignment.Center
                };
                nameRow.Children.Add(nameTv);

                if (isSelected)
                {
                    var onlineBadge = new Border
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
                    };
                    nameRow.Children.Add(onlineBadge);
                }

                string pingPart = server.ping switch
                {
                    -1 => "",
                    -2 => " • ⏳...",
                    -3 => " • ❌ Блок (853)",
                    _ => $" • ⚡ {server.ping} мс"
                };

                var subTv = new TextBlock
                {
                    Text = $"{server.host}{pingPart}",
                    FontSize = 12,
                    Foreground = BrushFromHex(server.ping == -3 ? "#FF5252" : textSubColorHex),
                    Margin = new Thickness(0, 2, 0, 0)
                };

                infoStack.Children.Add(nameRow);
                infoStack.Children.Add(subTv);
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
                    Padding = new Thickness(14, 9, 12, 9),
                    Margin = new Thickness(0, 0, 0, i == serverList.Count - 1 ? 0 : 7),
                    Cursor = Cursors.Hand,
                    Child = cardGrid
                };

                cardBorder.MouseLeftButtonUp += (s, e) =>
                {
                    if (!e.Handled)
                    {
                        ApplyWindowsDnsServer(server.host);
                    }
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
                    catch
                    {
                        resultPing = -3;
                    }
                    server.ping = resultPing;
                }
            });

            bool dnsResolves = await Task.Run(() =>
            {
                try
                {
                    var res = Dns.GetHostAddresses("google.com")
                        .Where(ip => ip.AddressFamily == AddressFamily.InterNetwork)
                        .ToArray();
                    return res.Length > 0;
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
                if (best != null)
                {
                    ApplyWindowsDnsServer(best.host);
                }
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
                    var a = Dns.GetHostAddresses("google.com")
                        .Where(ip => ip.AddressFamily == AddressFamily.InterNetwork)
                        .ToArray();
                    return a.Length > 0;
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
                Width = 395,
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
                Margin = new Thickness(8, 4, 8, 18)
            };
        }

        private Border CreateMenuCard(string title, string subtitle, Action onClick, string? customStroke = null, string? customTitleColor = null)
        {
            var stack = new StackPanel();
            stack.Children.Add(new TextBlock
            {
                Text = title
