[Setup]
AppId={{8F9A2B4C-5D6E-4A1B-9C8D-7E6F5A4B3C2D}
AppName=DNS Switcher Pro
AppVersion=1.2.0
AppPublisher=DNS Switcher Pro
DefaultDirName={autopf}\DNS Switcher Pro
DefaultGroupName=DNS Switcher Pro
PrivilegesRequired=admin
OutputDir=.\installer_out
OutputBaseFilename=DNS_Switcher_Pro_Setup
SetupIconFile=icon.ico
UninstallDisplayIcon={app}\DNS_Switcher_Pro.exe
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible

[Languages]
Name: "russian"; MessagesFile: "compiler:Languages\Russian.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"
Name: "autostart"; Description: "Запускать автоматически при старте Windows (в системном трее)"; GroupDescription: "Автозапуск:"

[Files]
Source: "publish\DNS_Switcher_Pro.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "icon.ico"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\DNS Switcher Pro"; Filename: "{app}\DNS_Switcher_Pro.exe"; IconFilename: "{app}\icon.ico"
Name: "{autodesktop}\DNS Switcher Pro"; Filename: "{app}\DNS_Switcher_Pro.exe"; IconFilename: "{app}\icon.ico"; Tasks: desktopicon

[Run]
; Регистрация автозапуска с правами Администратора через Планировщик задач (запуск в трее с флагом --minimized)
Filename: "schtasks.exe"; Parameters: "/Create /TN ""DnsSwitcherProAutoStart"" /TR ""\""{app}\DNS_Switcher_Pro.exe\"" --minimized"" /SC ONLOGON /RL HIGHEST /F"; Flags: runhidden; Tasks: autostart
; Запуск самой программы после окончания установки
Filename: "{app}\DNS_Switcher_Pro.exe"; Description: "Запустить DNS Switcher Pro"; Flags: nowait postinstall skipifsilent shellexec

[UninstallRun]
Filename: "schtasks.exe"; Parameters: "/Delete /TN ""DnsSwitcherProAutoStart"" /F"; Flags: runhidden; RunOnceId: "DelAutoStartTask"
