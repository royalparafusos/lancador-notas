@echo off
rem =====================================================================
rem  CRIAR-INSTALADOR.bat  -  gera o "Instalar-Lancador-de-Notas.exe"
rem
rem  Uso: de dois cliques neste arquivo (no Windows 10 ou 11).
rem  Na primeira vez ele baixa sozinho o Java 21, o Maven e o Inno Setup
rem  para a pasta "ferramentas" (uns 300 MB, de 5 a 15 minutos).
rem  Nas proximas vezes e rapido.
rem
rem  ATUALIZACOES: depois de instalado, o programa se atualiza SOZINHO pelo GitHub
rem  (royalparafusos/lancador-notas). Este instalador so precisa ser gerado de novo
rem  quando as bibliotecas mudarem (libs.versao no pom.xml) - o programa avisa.
rem =====================================================================
setlocal EnableExtensions EnableDelayedExpansion
set "VERSAO=1.1.0"

title Criar instalador - Lancador de Notas
cd /d "%~dp0.."
set "RAIZ=%CD%"
set "FERR=%RAIZ%\ferramentas"
if not exist "%FERR%" mkdir "%FERR%"

echo.
echo  ==========================================================
echo     CRIAR INSTALADOR - Lancador de Notas  (versao %VERSAO%)
echo  ==========================================================
echo.

rem ---------------------------------------------------------- 1. Java 21
echo  [1/6] Java 21...
set "JDK=%FERR%\jdk"
if exist "%JDK%\bin\jpackage.exe" (
    echo        ja esta na pasta ferramentas.
) else (
    echo        baixando o Java 21 ^(Eclipse Temurin^)... pode demorar.
    call :baixar "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk" "%FERR%\jdk.zip" || goto :erro
    call :extrair "%FERR%\jdk.zip" "%JDK%" || goto :erro
)
if not exist "%JDK%\bin\jpackage.exe" (
    set "MSG=O Java foi baixado mas o jpackage nao foi encontrado em %JDK%\bin"
    goto :erro
)
set "JAVA_HOME=%JDK%"
set "PATH=%JDK%\bin;%PATH%"

rem ---------------------------------------------------------- 2. Maven
echo  [2/6] Maven...
set "MVN=%FERR%\maven\bin\mvn.cmd"
if exist "%MVN%" (
    echo        ja esta na pasta ferramentas.
) else (
    echo        baixando o Maven...
    call :baixar "https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.zip" "%FERR%\maven.zip" || goto :erro
    call :extrair "%FERR%\maven.zip" "%FERR%\maven" || goto :erro
)
if not exist "%MVN%" (
    set "MSG=O Maven foi baixado mas o mvn.cmd nao foi encontrado."
    goto :erro
)

rem ---------------------------------------------------------- 3. Inno Setup
echo  [3/6] Inno Setup...
set "ISCC="
for %%P in ("%FERR%\inno\ISCC.exe" "%ProgramFiles(x86)%\Inno Setup 6\ISCC.exe" "%ProgramFiles%\Inno Setup 6\ISCC.exe" "%LOCALAPPDATA%\Programs\Inno Setup 6\ISCC.exe") do (
    if not defined ISCC if exist "%%~P" set "ISCC=%%~P"
)
if defined ISCC (
    echo        encontrado.
) else (
    echo        baixando o Inno Setup...
    call :baixar "https://github.com/jrsoftware/issrc/releases/download/is-6_7_4/innosetup-6.7.4.exe" "%FERR%\innosetup.exe"
    if exist "%FERR%\innosetup.exe" (
        echo        instalando o Inno Setup na pasta ferramentas...
        start "" /wait "%FERR%\innosetup.exe" /VERYSILENT /SUPPRESSMSGBOXES /NORESTART /SP- /CURRENTUSER /PORTABLE=1 /DIR="%FERR%\inno"
        if exist "%FERR%\inno\ISCC.exe" set "ISCC=%FERR%\inno\ISCC.exe"
    )
)
rem plano B: instalar pelo winget (loja de programas do Windows)
if not defined ISCC (
    echo        tentando instalar o Inno Setup pelo winget...
    winget install --id JRSoftware.InnoSetup -e --scope user --silent --accept-package-agreements --accept-source-agreements >nul 2>&1
    for %%P in ("%LOCALAPPDATA%\Programs\Inno Setup 6\ISCC.exe" "%ProgramFiles(x86)%\Inno Setup 6\ISCC.exe" "%ProgramFiles%\Inno Setup 6\ISCC.exe") do (
        if not defined ISCC if exist "%%~P" set "ISCC=%%~P"
    )
)
if not defined ISCC (
    set "MSG=Nao consegui instalar o Inno Setup. Baixe e instale manualmente: https://jrsoftware.org/isdl.php  -  depois rode este arquivo de novo."
    goto :erro
)

rem ---------------------------------------------------------- 4. Chave de leitura das fotos
echo  [4/6] Chave de leitura das fotos...
set "CFG=%RAIZ%\instalador\config-padrao.properties"
if exist "%CFG%" (
    echo        usando a chave ja salva em instalador\config-padrao.properties
    echo        ^(para trocar, apague esse arquivo e rode de novo^)
) else (
    echo.
    echo        Cole a chave e aperte Enter:
    echo          - Groq ^(GRATUITA, comeca com gsk_^)   - crie em console.groq.com/keys
    echo          - ou Anthropic ^(paga, comeca com sk-ant-^)
    echo        Ela vai DENTRO do instalador: os professores nao precisam configurar nada.
    echo        Para pular, so aperte Enter ^(o professor tera que colar a chave^).
    echo.
    set "CHAVE="
    set /p "CHAVE=       Chave: "
    if defined CHAVE (
        set "CHAVE=!CHAVE: =!"
        if /i "!CHAVE:~0,4!"=="gsk_" (
            > "%CFG%" echo ia.groq.chave=!CHAVE!
            echo        chave do Groq salva.
        ) else if /i "!CHAVE:~0,7!"=="sk-ant-" (
            > "%CFG%" echo ia.chave=!CHAVE!
            echo        chave da Anthropic salva.
        ) else (
            echo        Essa chave nao parece do Groq ^(gsk_^) nem da Anthropic ^(sk-ant-^). Ignorada.
        )
    ) else (
        echo        sem chave.
    )
)

rem ---------------------------------------------------------- 5. Compilar e montar o programa
echo  [5/6] Compilando o programa ^(na primeira vez baixa as bibliotecas^)...
call "%MVN%" -q -B clean package -DskipTests "-Dapp.versao=%VERSAO%"
if errorlevel 1 (
    set "MSG=A compilacao falhou. Veja as mensagens acima."
    goto :erro
)
set "ENTRADA=%RAIZ%\target\jpackage-input"
mkdir "%ENTRADA%" 2>nul
copy /y "%RAIZ%\target\lancador-notas-ded.jar" "%ENTRADA%\" >nul || goto :erro
copy /y "%RAIZ%\target\lancador-libs.jar" "%ENTRADA%\" >nul || goto :erro
if exist "%CFG%" copy /y "%CFG%" "%ENTRADA%\" >nul

echo        montando o programa com o Java embutido...
"%JDK%\bin\jpackage.exe" --type app-image --name LancadorNotas --app-version %VERSAO% ^
    --input "%ENTRADA%" --main-jar lancador-notas-ded.jar --main-class br.com.lancadornotas.Launcher ^
    --icon "%RAIZ%\instalador\icone.ico" --dest "%RAIZ%\target\app-image" ^
    --description "Lancador de Notas DED+" ^
    --java-options "-Dfile.encoding=UTF-8" --java-options "-Xmx768m"
if errorlevel 1 (
    set "MSG=O jpackage falhou. Veja as mensagens acima."
    goto :erro
)

rem ---------------------------------------------------------- 6. Instalador
echo  [6/6] Gerando o instalador...
"%ISCC%" /Q /DVersaoApp=%VERSAO% "%RAIZ%\instalador\instalador.iss"
if errorlevel 1 (
    set "MSG=O Inno Setup falhou. Veja as mensagens acima."
    goto :erro
)

echo.
echo  ==========================================================
echo     PRONTO!
echo     Instalador criado em:
echo     %RAIZ%\target\instalador\Instalar-Lancador-de-Notas.exe
echo.
echo     Envie esse arquivo para os professores, junto com o
echo     "Guia-de-instalacao-para-professores.pdf".
echo  ==========================================================
echo.
explorer "%RAIZ%\target\instalador"
pause
exit /b 0

rem =====================================================================
:baixar
rem  %1 = endereco   %2 = arquivo de destino
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; try { Invoke-WebRequest -UseBasicParsing -Uri '%~1' -OutFile '%~2'; exit 0 } catch { Write-Host $_.Exception.Message; exit 1 }"
if errorlevel 1 (
    set "MSG=Nao consegui baixar %~1 . Verifique a internet e tente de novo."
    exit /b 1
)
exit /b 0

:extrair
rem  %1 = arquivo .zip   %2 = pasta final (o conteudo da pasta de dentro do zip vai para ela)
set "TMPX=%FERR%\_extraindo"
if exist "%TMPX%" rmdir /s /q "%TMPX%"
mkdir "%TMPX%"
tar -xf "%~1" -C "%TMPX%" 2>nul
if errorlevel 1 powershell -NoProfile -Command "Expand-Archive -Force -Path '%~1' -DestinationPath '%TMPX%'"
for /d %%D in ("%TMPX%\*") do (
    if exist "%~2" rmdir /s /q "%~2"
    move "%%~fD" "%~2" >nul
    goto :extraido
)
:extraido
rmdir /s /q "%TMPX%" 2>nul
del /q "%~1" 2>nul
if not exist "%~2" (
    set "MSG=Nao consegui extrair %~nx1"
    exit /b 1
)
exit /b 0

:erro
echo.
echo  ##########################################################
echo     DEU ERRO
if defined MSG echo     !MSG!
echo  ##########################################################
echo.
pause
exit /b 1
