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

$mdkArchive = Join-Path $root $mdkName
$jdkArchive = Join-Path $root $jdkName
$teaVmJdkArchive = Join-Path $root $teaVmJdkName
Get-VerifiedArchive $mdkArchive $mdkUrl $mdkHash
Get-VerifiedArchive $jdkArchive $jdkUrl $jdkHash
Get-VerifiedArchive $teaVmJdkArchive $teaVmJdkUrl $teaVmJdkHash

$jdkHome = Join-Path $root "jdk8u504-b01"
if (-not (Test-Path -LiteralPath (Join-Path $jdkHome "bin\java.exe"))) {
    Expand-Archive -LiteralPath $jdkArchive -DestinationPath $root -Force
}
$teaVmJdkHome = Join-Path $root "jdk-17.0.15+6"
if (-not (Test-Path -LiteralPath (Join-Path $teaVmJdkHome "bin\java.exe"))) {
    Expand-Archive -LiteralPath $teaVmJdkArchive -DestinationPath $root -Force
}

$mdk = Join-Path $root "mdk"
if (-not (Test-Path -LiteralPath (Join-Path $mdk "gradlew.bat"))) {
    New-Item -ItemType Directory -Force -Path $mdk | Out-Null
    Expand-Archive -LiteralPath $mdkArchive -DestinationPath $mdk -Force
}

$minecraftJar = Join-Path $mdk "build\tmp\deobfMcSRG\deobfed.jar"
if (-not (Test-Path -LiteralPath $minecraftJar)) {
    $previousJavaHome = $env:JAVA_HOME
    $previousPath = $env:PATH
    $env:JAVA_HOME = $jdkHome
    $env:PATH = (Join-Path $jdkHome "bin") + ";" + $previousPath
    Push-Location $mdk
    try {
        & .\gradlew.bat setupDecompWorkspace --no-daemon
        if ($LASTEXITCODE -ne 0) {
            throw "ForgeGradle setupDecompWorkspace failed ($LASTEXITCODE)"
        }
    }
    finally {
        Pop-Location
        $env:JAVA_HOME = $previousJavaHome
        $env:PATH = $previousPath
    }
}

if (-not (Test-Path -LiteralPath $minecraftJar -PathType Leaf)) {
    throw "ForgeGradle did not produce the mapped Minecraft 1.12.2 runtime: $minecraftJar"
}
$cacheRoot = Join-Path $env:USERPROFILE ".gradle\caches\minecraft\net\minecraftforge\forge\1.12.2-14.23.5.2816\snapshot\20171003"
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
