; CASA Suite installer (per-user, no admin needed)
#define AppVer GetEnv("CASA_VERSION")
#if AppVer == ""
  #define AppVer "1.0.0"
#endif

[Setup]
AppId={{6E1B6A0C-4C3B-4D6E-9F2A-CA5A5A17E001}
AppName=CASA Suite
AppVersion={#AppVer}
AppPublisher=CASA Audit Group
DefaultDirName={localappdata}\Programs\CASA Suite
DefaultGroupName=CASA Suite
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=..\..\out-installer
OutputBaseFilename=CASA-Suite-Setup
SetupIconFile=..\CasaSuite\app.ico
UninstallDisplayIcon={app}\CASA Suite.exe
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
CloseApplications=yes

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Shortcuts:"

[Files]
Source: "..\..\out\CASA Suite.exe"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{userprograms}\CASA Suite"; Filename: "{app}\CASA Suite.exe"
Name: "{userdesktop}\CASA Suite"; Filename: "{app}\CASA Suite.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\CASA Suite.exe"; Description: "Open CASA Suite now"; Flags: nowait postinstall skipifsilent
