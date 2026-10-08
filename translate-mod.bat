@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
set "JRE_DIR=%SCRIPT_DIR%.gold-client-jre"
set "JRE_ZIP=%JRE_DIR%\jre.zip"
set "JAVA_EXE=%JRE_DIR%\jdk-21.0.2+13-jre\bin\java.exe"
set "APP_JAR=%SCRIPT_DIR%target\gold-client-0.1.0-SNAPSHOT.jar"

if "%~1"=="" (
    echo Usage: translate-mod.bat path\to\mod.jar [--to-js ^<output_dir^>] [--main-class ^<class^>]
    exit /b 1
)

if not exist "%JAVA_EXE%" (
    echo [Gold Client] Downloading isolated Java 21 environment...
    if not exist "%JRE_DIR%" mkdir "%JRE_DIR%"
    powershell -NoProfile -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.2%%2B13/OpenJDK21U-jre_x64_windows_hotspot_21.0.2_13.zip' -OutFile '%JRE_ZIP%'"
    if errorlevel 1 (
        echo [Gold Client] Java download failed.
        exit /b 1
    )
    echo [Gold Client] Extracting Java 21...
    powershell -NoProfile -Command "Expand-Archive -Path '%JRE_ZIP%' -DestinationPath '%JRE_DIR%' -Force"
    if errorlevel 1 (
        echo [Gold Client] Java extraction failed.
        exit /b 1
    )
    del "%JRE_ZIP%"
)

if not exist "%JAVA_EXE%" (
    echo [Gold Client] Java runtime is missing: "%JAVA_EXE%"
    exit /b 1
)

if not exist "%APP_JAR%" (
    echo [Gold Client] Build artifact is missing: "%APP_JAR%"
    echo Run mvn clean package from the project directory first.
    exit /b 1
)

echo [Gold Client] Translating to Javascript...
"%JAVA_EXE%" -jar "%APP_JAR%" %*
set EXITCODE=%ERRORLEVEL%
exit /b %EXITCODE%
