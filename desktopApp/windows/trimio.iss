; Trimio's Windows installer (Inno Setup 6), built from the app image of :desktopApp:createDistributable.
;
; - Per-user install, no administrator prompt: %LOCALAPPDATA%\Programs\Trimio.
; - A newer installer upgrades in place (same AppId) and keeps the downloaded models.
; - Uninstalling from Settings → Apps or the Control Panel removes everything the app made:
;   %APPDATA%\Trimio (models, libraries, animations, projects, settings, reports) and
;   %LOCALAPPDATA%\Trimio. The creator's exported videos (Videos\Trimio) are their own and stay.
; - 1.0.14 and earlier were MSI installs: the first run of this installer removes that MSI quietly
;   (its data stays, so the models carry over).
;
; ISCC /DAppVersion=1.0.15 trimio.iss

#ifndef AppVersion
  #define AppVersion "1.0.0"
#endif
#define AppImage "..\build\compose\binaries\main\app\Trimio"

[Setup]
AppId={{B7E3C2A1-6F0F-4C7E-9A3C-2C1F7B9E4D21}
AppName=Trimio
AppVersion={#AppVersion}
AppVerName=Trimio {#AppVersion}
AppPublisher=Trimio
VersionInfoVersion={#AppVersion}
DefaultDirName={autopf}\Trimio
DefaultGroupName=Trimio
DisableProgramGroupPage=yes
DisableDirPage=auto
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir=..\build\compose\binaries\main\exe
OutputBaseFilename=Trimio-{#AppVersion}
SetupIconFile=..\icons\trimio.ico
UninstallDisplayIcon={app}\Trimio.exe
UninstallDisplayName=Trimio
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
ChangesAssociations=yes
CloseApplications=yes

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
Source: "{#AppImage}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\Trimio"; Filename: "{app}\Trimio.exe"
Name: "{autodesktop}\Trimio"; Filename: "{app}\Trimio.exe"; Tasks: desktopicon

[Registry]
; "Open with → Trimio" for .mp4 and .mov (the app starts a new edit with the file).
Root: HKA; Subkey: "Software\Classes\Trimio.Video"; ValueType: string; ValueData: "Video"; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\Trimio.Video\DefaultIcon"; ValueType: string; ValueData: "{app}\Trimio.exe,0"
Root: HKA; Subkey: "Software\Classes\Trimio.Video\shell\open\command"; ValueType: string; ValueData: """{app}\Trimio.exe"" ""%1"""
Root: HKA; Subkey: "Software\Classes\.mp4\OpenWithProgids"; ValueType: string; ValueName: "Trimio.Video"; ValueData: ""; Flags: uninsdeletevalue
Root: HKA; Subkey: "Software\Classes\.mov\OpenWithProgids"; ValueType: string; ValueName: "Trimio.Video"; ValueData: ""; Flags: uninsdeletevalue

[Run]
Filename: "{app}\Trimio.exe"; Description: "{cm:LaunchProgram,Trimio}"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
Type: filesandordirs; Name: "{userappdata}\Trimio"
Type: filesandordirs; Name: "{localappdata}\Trimio"
Type: filesandordirs; Name: "{app}"

[Code]
const
  UninstallKey = 'Software\Microsoft\Windows\CurrentVersion\Uninstall';

{ The product code of an MSI-installed Trimio (1.0.14 and earlier), or '' when there is none. }
function OldMsiProductCode(): String;
var
  Keys: TArrayOfString;
  I: Integer;
  Name, Command: String;
begin
  Result := '';
  if not RegGetSubkeyNames(HKCU, UninstallKey, Keys) then Exit;
  for I := 0 to GetArrayLength(Keys) - 1 do
  begin
    if RegQueryStringValue(HKCU, UninstallKey + '\' + Keys[I], 'DisplayName', Name) and (Name = 'Trimio') and
       RegQueryStringValue(HKCU, UninstallKey + '\' + Keys[I], 'UninstallString', Command) and
       (Pos('msiexec', Lowercase(Command)) > 0) then
    begin
      Result := Keys[I];
      Exit;
    end;
  end;
end;

{ Runs once the creator has pressed Install: the old MSI goes first, quietly; its data stays. }
function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  Code: String;
  ResultCode: Integer;
begin
  Result := '';
  Code := OldMsiProductCode();
  if Code <> '' then
    Exec(ExpandConstant('{sys}\msiexec.exe'), '/x ' + Code + ' /qn /norestart', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
end;
