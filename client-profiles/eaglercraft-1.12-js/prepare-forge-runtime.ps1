$ErrorActionPreference = "Stop"

$repo = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$root = Join-Path $repo "target\forge-baseline"
$mdkName = "forge-1.12.2-14.23.5.2816-mdk.zip"
$jdkName = "OpenJDK8U-jdk_x64_windows_hotspot_8u504b01.zip"
$teaVmJdkName = "OpenJDK17U-jdk_x64_windows_hotspot_17.0.15_6.zip"
$mdkHash = "50F5FCB9D26AEEC4D9D8F209BBE18DCEA62C5F4EA7B27044D758B685107EAAF5"
$forgeMappedHash = "9F6ECF6FDBC4453651F76DBE2B145CCAF2711C1E51AEDE409183B890068D9C59"
$jdkHash = "EA43D46EDE95B51E44A12C66711706CDDC762E0A766C54BCCEA18954E902B2AA"
$teaVmJdkHash = "118CF8D586EE3200E2D2E6E49717DB4FB1A22005700FBF85F06166046EC74863"
$forgeBase = "https://maven.minecraftforge.net/net/minecraftforge/forge/1.12.2-14.23.5.2816"
$mdkUrl = "$forgeBase/$mdkName"
$jdkUrl = "https://github.com/adoptium/temurin8-binaries/releases/download/jdk8u504-b01/$jdkName"
$teaVmJdkUrl = "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.15%2B6/$teaVmJdkName"

New-Item -ItemType Directory -Force -Path $root | Out-Null

function Get-VerifiedArchive([string] $Path, [string] $Url, [string] $ExpectedHash) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        & curl.exe -L --fail --silent --show-error $Url -o $Path
        if ($LASTEXITCODE -ne 0) {
            throw "Could not download required runtime archive: $Url"
        }
    }
    $actualHash = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
    if ($actualHash -ne $ExpectedHash) {
        throw "Runtime archive SHA-256 mismatch for $Path"
    }
}

function Test-JdkHome([string] $Path, [string[]] $RequiredMarkers) {
    foreach ($required in @(
        (Join-Path $Path "bin\java.exe"),
        (Join-Path $Path "release")
    )) {
        if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
            return $false
        }
    }
    foreach ($marker in $RequiredMarkers) {
        if (-not (Test-Path -LiteralPath (Join-Path $Path $marker) -PathType Leaf)) {
            return $false
        }
    }
    return $true
}

$mdkArchive = Join-Path $root $mdkName
$jdkArchive = Join-Path $root $jdkName
$teaVmJdkArchive = Join-Path $root $teaVmJdkName
Get-VerifiedArchive $mdkArchive $mdkUrl $mdkHash
Get-VerifiedArchive $jdkArchive $jdkUrl $jdkHash
Get-VerifiedArchive $teaVmJdkArchive $teaVmJdkUrl $teaVmJdkHash

$jdkHome = Join-Path $root "jdk8u504-b01"
if (-not (Test-JdkHome $jdkHome @("bin\javac.exe", "lib\tools.jar"))) {
    Expand-Archive -LiteralPath $jdkArchive -DestinationPath $root -Force
}
$teaVmJdkHome = Join-Path $root "jdk-17.0.15+6"
if (-not (Test-JdkHome $teaVmJdkHome @("bin\javac.exe", "lib\modules"))) {
    Expand-Archive -LiteralPath $teaVmJdkArchive -DestinationPath $root -Force
}

if (-not (Test-JdkHome $jdkHome @("bin\javac.exe", "lib\tools.jar"))) {
    throw "Extracted Java 8 JDK is incomplete: $jdkHome"
}
if (-not (Test-JdkHome $teaVmJdkHome @("bin\javac.exe", "lib\modules"))) {
    throw "Extracted Java 17 JDK is incomplete: $teaVmJdkHome"
}

$mdk = Join-Path $root "mdk"
if (-not (Test-Path -LiteralPath (Join-Path $mdk "gradlew.bat"))) {
    New-Item -ItemType Directory -Force -Path $mdk | Out-Null
    Expand-Archive -LiteralPath $mdkArchive -DestinationPath $mdk -Force
}

# Gold-Client cached SRG baseline preparation
$minecraftJar = Join-Path $mdk "build\tmp\deobfMcSRG\deobfed.jar"
$cacheRoot = Join-Path $env:USERPROFILE ".gradle\caches\minecraft\net\minecraftforge\forge\1.12.2-14.23.5.2816\snapshot\20171003"
$cachedMinecraft = Join-Path $cacheRoot "forge-1.12.2-14.23.5.2816-srgBin.jar"

if (-not (Test-Path -LiteralPath $cachedMinecraft -PathType Leaf)) {
    $previousJavaHome = $env:JAVA_HOME
    $previousPath = $env:PATH
    $previousPreference = $ErrorActionPreference
    $gradleExit = $null
    $setupLog = Join-Path $root "deobf-baseline-setup.log"

    try {
        $env:JAVA_HOME = $jdkHome
        $env:PATH = (Join-Path $jdkHome "bin") + ";" + $previousPath

        Push-Location $mdk
        try {
            $ErrorActionPreference = "Continue"

            & .\gradlew.bat deobfMcSRG --no-daemon --stacktrace 2>&1 |
                ForEach-Object { $_.ToString() } |
                Tee-Object -FilePath $setupLog

            $gradleExit = $LASTEXITCODE
        }
        finally {
            Pop-Location
        }
    }
    finally {
        $env:JAVA_HOME = $previousJavaHome
        $env:PATH = $previousPath
        $ErrorActionPreference = $previousPreference
    }

    if ($gradleExit -ne 0) {
        throw "ForgeGradle deobfMcSRG failed ($gradleExit). See $setupLog"
    }
}

if (-not (Test-Path -LiteralPath $cachedMinecraft -PathType Leaf)) {
    throw "ForgeGradle SRG runtime output is missing: $cachedMinecraft"
}

if ((Get-Item -LiteralPath $cachedMinecraft).Length -eq 0) {
    throw "ForgeGradle SRG runtime output is empty: $cachedMinecraft"
}

$sourceMinecraftHash = (
    Get-FileHash -LiteralPath $cachedMinecraft -Algorithm SHA256
).Hash

New-Item -ItemType Directory -Force `
    -Path (Split-Path -Parent $minecraftJar) | Out-Null

Copy-Item -LiteralPath $cachedMinecraft `
    -Destination $minecraftJar -Force

$copiedMinecraftHash = (
    Get-FileHash -LiteralPath $minecraftJar -Algorithm SHA256
).Hash

if ($copiedMinecraftHash -ne $sourceMinecraftHash) {
    throw "Minecraft SRG runtime copy SHA-256 mismatch: $minecraftJar"
}

Write-Output "Minecraft SRG baseline copy matches its cached source."
$cachedForge = Join-Path $cacheRoot "forgeSrc-1.12.2-14.23.5.2816.jar"
if (-not (Test-Path -LiteralPath $cachedForge -PathType Leaf)) {
    throw "ForgeGradle did not produce the mapped Forge runtime: $cachedForge"
}
$actualForgeHash = (Get-FileHash -LiteralPath $cachedForge -Algorithm SHA256).Hash
if ($actualForgeHash -ne $forgeMappedHash) {
    throw "Mapped Forge runtime SHA-256 mismatch for $cachedForge"
}
$forgeClasses = Join-Path $root "forge-mapped.jar"
Copy-Item -LiteralPath $cachedForge -Destination $forgeClasses -Force
Write-Output "Verified Minecraft 1.12.2 / Forge 14.23.5.2816 JVM runtime:"
Write-Output $minecraftJar
Write-Output $forgeClasses
Write-Output "Verified TeaVM build JDK:"
Write-Output (Join-Path $teaVmJdkHome "bin\java.exe")
