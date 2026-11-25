; =====================================================================
;  Instalador do Lançador de Notas (Inno Setup 6)
;  Não edite à mão: quem usa este arquivo é o CRIAR-INSTALADOR.bat
; =====================================================================
;  - Instala SÓ para o usuário atual: não pede senha de administrador
;    (funciona nos computadores da escola).
;  - Leva o Java junto: o professor não precisa instalar mais nada.
;  - Cria o ícone na Área de Trabalho e no menu Iniciar.
;  - Desinstala por "Configurações > Aplicativos" como qualquer programa.
; =====================================================================

#define NomeApp "Lançador de Notas"
#define ExeApp "LancadorNotas.exe"
#ifndef VersaoApp
  #define VersaoApp "1.0.0"
#endif

[Setup]
AppId={{6B1E2F4A-3C5D-4E7F-9A0B-1C2D3E4F5A6B}
AppName={#NomeApp}
AppVersion={#VersaoApp}
AppVerName={#NomeApp} {#VersaoApp}
AppPublisher={#NomeApp}
DefaultDirName={localappdata}\Programs\Lancador de Notas
DefaultGroupName={#NomeApp}
PrivilegesRequired=lowest
DisableWelcomePage=no
DisableDirPage=yes
DisableProgramGroupPage=yes
DisableReadyPage=yes
ShowLanguageDialog=no
CloseApplications=yes
WizardStyle=modern
WizardSizePercent=120
SetupIconFile=icone.ico
UninstallDisplayIcon={app}\{#ExeApp}
UninstallDisplayName={#NomeApp}
OutputDir=..\target\instalador
OutputBaseFilename=Instalar-Lancador-de-Notas
Compression=lzma2/max
SolidCompression=yes

[Languages]
Name: "pt"; MessagesFile: "compiler:Languages\BrazilianPortuguese.isl"

[Messages]
pt.WelcomeLabel1=Bem-vindo(a) ao Lançador de Notas
pt.WelcomeLabel2=Este programa lança no DED+ as notas que você escreveu na folha, a partir de uma foto.%n%nPara instalar, é só clicar em "Instalar".%n%nNão precisa mudar nada, nem ter senha de administrador.
pt.FinishedHeadingLabel=Pronto! O Lançador de Notas foi instalado.
pt.FinishedLabel=Um ícone chamado "Lançador de Notas" foi colocado na sua Área de Trabalho.%n%nPara abrir o programa, dê dois cliques nesse ícone.

[Files]
Source: "..\target\app-image\LancadorNotas\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autodesktop}\{#NomeApp}"; Filename: "{app}\{#ExeApp}"; IconFilename: "{app}\{#ExeApp}"
Name: "{group}\{#NomeApp}"; Filename: "{app}\{#ExeApp}"
Name: "{group}\Desinstalar o {#NomeApp}"; Filename: "{uninstallexe}"

[Run]
Filename: "{app}\{#ExeApp}"; Description: "Abrir o Lançador de Notas agora"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
Type: filesandordirs; Name: "{app}"
