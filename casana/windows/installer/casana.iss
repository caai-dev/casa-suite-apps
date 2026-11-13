; Casana installer (per-user, no admin needed)
#define AppVer GetEnv("CASANA_VERSION")
#if AppVer == ""
  #define AppVer "1.1.0"
#endif

[Setup]
AppId={{3F7C2B1E-9A4D-4C8B-B6E2-CA5A5A17E002}
AppName=Casana
AppVersion={#AppVer}
AppPublisher=CASA Audit Group
DefaultDirName={localappdata}\Programs\Casana
DefaultGroupName=Casana
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=..\..\..\out-installer
OutputBaseFilename=Casana-Setup
SetupIconFile=..\Casana\app.ico
UninstallDisplayIcon={app}\Casana.exe
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
CloseApplications=yes

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Shortcuts:"

[Files]
Source: "..\..\..\out-casana\Casana.exe"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
; CASA Suite opens Casana by this Start menu name - keep it "Casana".
Name: "{userprograms}\Casana"; Filename: "{app}\Casana.exe"
Name: "{userdesktop}\Casana"; Filename: "{app}\Casana.exe"; Tasks: desktopicon

[Registry]
; casana:// lets CASA Suite in a normal browser open the installed Casana app.
Root: HKCU; Subkey: "Software\Classes\casana"; ValueType: string; ValueName: ""; ValueData: "URL:Casana"; Flags: uninsdeletekey
Root: HKCU; Subkey: "Software\Classes\casana"; ValueType: string; ValueName: "URL Protocol"; ValueData: ""
Root: HKCU; Subkey: "Software\Classes\casana\DefaultIcon"; ValueType: string; ValueName: ""; ValueData: """{app}\Casana.exe"",0"
Root: HKCU; Subkey: "Software\Classes\casana\shell\open\command"; ValueType: string; ValueName: ""; ValueData: """{app}\Casana.exe"" ""%1"""

[Run]
Filename: "{app}\Casana.exe"; Description: "Open Casana now"; Flags: nowait postinstall skipifsilent
; In-app updates install quietly; reopen the app when they finish.
Filename: "{app}\Casana.exe"; Flags: nowait; Check: WizardSilent
