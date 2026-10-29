; Dead Drop Finance for Windows: the installer. Built by desktop\build.ps1 with Inno Setup 6 (ISCC):
;   ISCC /DAppVersion=<ddf.desktop.versionName> /DDistDir=<the Compose app image> /O<output folder> desktop\installer\deaddropfinance.iss
; The app image (DeadDropFinance.exe, its jars and the bundled Java runtime) goes to Program Files (64-bit), with one
; UAC prompt. Inno Setup is a build tool only: nothing of it ships.
;
; The user's data lives in %APPDATA%\DeadDropFinance (and HKCU), never in the program folder. Setup never writes
; there, and uninstall never touches it.
;
; Updates: the program's updater starts this Setup with /SILENT /SP- /SUPPRESSMSGBOXES /NORESTART /RELAUNCH=1. The
; same AppId upgrades the installed copy in place (shortcuts and the Apps entry follow); /RELAUNCH=1 starts the
; program again afterwards as the original, unelevated user.
;
; /DTestBuild=1 makes a TEST installer with its own identity (AppId, name, folder, shortcut names), so testing never
; touches a real installation's Apps entry or shortcuts.
;
; The AppId below never changes: every later version must keep it to upgrade in place.

#ifndef AppVersion
  #error AppVersion must be given: ISCC /DAppVersion=x.y.z
#endif
#ifndef DistDir
  #define DistDir "..\build\compose\binaries\main\app\DeadDropFinance"
#endif
#define ExeName "DeadDropFinance.exe"
#ifdef TestBuild
  #define AppGuid "{6C662EDE-9A0B-405E-9F6D-71BFB2DD96A5}"
  #define ProductName "Dead Drop Finance Test"
  #define DirName "DeadDropFinance Test"
  #define SetupBase "DeadDropFinance-Test-Setup"
#else
  #define AppGuid "{33708C09-3381-4041-BACB-182E61BDE98A}"
  #define ProductName "Dead Drop Finance"
  #define DirName "DeadDropFinance"
  #define SetupBase "DeadDropFinance-Setup"
#endif

[Setup]
AppId={{#AppGuid}
AppName={#ProductName}
AppVersion={#AppVersion}
AppVerName={#ProductName} {#AppVersion}
AppPublisher=DarkRelay
AppPublisherURL=https://github.com/Crazy8697/DeadDropFinance
AppSupportURL=https://github.com/Crazy8697/DeadDropFinance/issues
AppUpdatesURL=https://github.com/Crazy8697/DeadDropFinance/releases
AppComments=Local-first envelope budgeting
VersionInfoVersion={#AppVersion}
VersionInfoDescription={#ProductName} Setup
VersionInfoProductName={#ProductName}
DefaultDirName={autopf}\{#DirName}
DisableProgramGroupPage=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=admin
#ifdef TestBuild
; the test installer can also install for the current user only (/CURRENTUSER /DIR=<a temp folder>): tests then
; need no UAC prompt and leave nothing outside that folder, the user's Start menu entry and HKCU
PrivilegesRequiredOverridesAllowed=commandline
#endif
SetupIconFile=..\icons\deaddropfinance.ico
UninstallDisplayIcon={app}\{#ExeName}
UninstallDisplayName={#ProductName}
; one fixed name for every version (the version is inside the file); build.ps1 uploads exactly this + its .sha256
OutputBaseFilename={#SetupBase}
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
CloseApplications=yes
RestartApplications=no

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Shortcuts:"

[InstallDelete]
; an upgrade replaces the app image completely, so jars of an older version never linger beside the new ones
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\runtime"

[Files]
Source: "{#DistDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "..\..\LICENSE"; DestDir: "{app}"; DestName: "LICENSE.txt"; Flags: ignoreversion
Source: "..\NOTICE"; DestDir: "{app}"; DestName: "NOTICE.txt"; Flags: ignoreversion
Source: "..\THIRD-PARTY-NOTICES.md"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\..\licenses\Apache-2.0.txt"; DestDir: "{app}\licenses"; Flags: ignoreversion
Source: "..\..\licenses\libpng-2.0.txt"; DestDir: "{app}\licenses"; Flags: ignoreversion
Source: "..\..\licenses\SQLite-Public-Domain.txt"; DestDir: "{app}\licenses"; Flags: ignoreversion
Source: "..\..\licenses\MPL-2.0.txt"; DestDir: "{app}\licenses"; Flags: ignoreversion
Source: "..\..\licenses\ISC-libsodium.txt"; DestDir: "{app}\licenses"; Flags: ignoreversion
Source: "..\licenses\*.txt"; DestDir: "{app}\licenses"; Flags: ignoreversion

[Icons]
Name: "{autoprograms}\{#ProductName}"; Filename: "{app}\{#ExeName}"; WorkingDir: "{app}"; IconFilename: "{app}\{#ExeName}"; Comment: "Dead Drop Finance - envelope budgeting"
Name: "{autodesktop}\{#ProductName}"; Filename: "{app}\{#ExeName}"; WorkingDir: "{app}"; IconFilename: "{app}\{#ExeName}"; Comment: "Dead Drop Finance - envelope budgeting"; Tasks: desktopicon

[Run]
; offered on the last page; postinstall entries run as the original (unelevated) user
Filename: "{app}\{#ExeName}"; Description: "Launch Dead Drop Finance"; Flags: postinstall nowait skipifsilent
; an update from inside the program (/RELAUNCH=1): start it again when done, unelevated
Filename: "{app}\{#ExeName}"; Check: ShouldRelaunch; Flags: nowait runasoriginaluser

[Code]
function ShouldRelaunch(): Boolean;
begin
  Result := ExpandConstant('{param:relaunch|0}') = '1';
end;
