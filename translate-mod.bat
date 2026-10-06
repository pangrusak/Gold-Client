@echo off
setlocal

set JRE_DIR=.gold-client-jre
set JRE_ZIP=%JRE_DIR%\jre.zip
set JAVA_EXE=%JRE_DIR%\jdk-21.0.2+13-jre\bin\java.exe

if not exist "%JAVA_EXE%" (
    echo [Gold Client] Downloading isolated Java 21 environment...
    if not exist "%JRE_DIR%" mkdir "%JRE_DIR%"
    powershell -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.2%%2B13/OpenJDK21U-jre_x64_windows_hotspot_21.0.2_13.zip' -OutFile '%JRE_ZIP%'"
    echo [Gold Client] Extracting Java 21...
    powershell -Command "Expand-Archive -Path '%JRE_ZIP%' -DestinationPath '%JRE_DIR%' -Force"
    del "%JRE_ZIP%"
)

if "%~1"=="" (
    echo Usage: translate-mod.bat path\to\mod.jar
    exit /b 1
)

echo [Gold Client] Translating to Javascript...
"%JAVA_EXE%" -jar target\gold-client-0.1.0-SNAPSHOT.jar %* --to-js ./output
