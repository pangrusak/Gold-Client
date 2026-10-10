$ErrorActionPreference = "Stop"

$repo = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$manifestFile = Join-Path $PSScriptRoot "jei-class-profile.local.json"
$compatSources = Join-Path $PSScriptRoot "compat"
$classes = Join-Path $repo "target\jei-ingredient-elements\generated-classes"

$jdk = Join-Path $repo "target\forge-baseline\jdk-17.0.15+6"
$javac = Join-Path $jdk "bin\javac.exe"

$mod = Join-Path $repo "test-mods\jei1.12.2.jar"
$baseline = Join-Path $repo `
    "target\forge-baseline\mdk\build\tmp\deobfMcSRG\deobfed.jar"
$forge = Join-Path $repo "target\forge-baseline\forge-mapped.jar"
$language = Join-Path $repo "target\forge-baseline\forge-language-srg.jar"

$fastutil = Join-Path $env:USERPROFILE `
    ".m2\repository\it\unimi\dsi\fastutil\7.1.0\fastutil-7.1.0.jar"
$jso = Join-Path $env:USERPROFILE `
    ".m2\repository\org\teavm\teavm-jso\0.11.0\teavm-jso-0.11.0.jar"

function Find-Dependency(
    [string] $Group,
    [string] $Artifact,
    [string] $Version
) {
    $directory = Join-Path $env:USERPROFILE `
        ".gradle\caches\modules-2\files-2.1\$Group\$Artifact\$Version"

    $matches = @(
        Get-ChildItem -LiteralPath $directory -Recurse -File |
        Where-Object { $_.Name -eq "$Artifact-$Version.jar" }
    )

    if ($matches.Count -ne 1) {
        throw "Expected one $Artifact-$Version.jar; found $($matches.Count)."
    }

    return $matches[0].FullName
}

$guava = Find-Dependency "com.google.guava" "guava" "21.0"
$commonsLang = Find-Dependency "org.apache.commons" "commons-lang3" "3.5"

foreach ($file in @(
    $manifestFile, $javac, $mod, $baseline,
    $forge, $language, $fastutil, $jso, $guava, $commonsLang
)) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
        throw "Required compatibility-build input is missing: $file"
    }
}

$manifest = Get-Content -LiteralPath $manifestFile -Raw |
    ConvertFrom-Json

if ($manifest.schemaVersion -ne 1) {
    throw "Unsupported compatibility manifest schema."
}

$sourceFiles = @(
    Get-ChildItem -LiteralPath $compatSources -Recurse -File `
        -Filter "*.java" |
    Sort-Object FullName |
    ForEach-Object { $_.FullName }
)

if ($sourceFiles.Count -eq 0) {
    throw "No compatibility Java sources were found."
}

$workRoot = Join-Path $repo "target\jei-profile"
New-Item -ItemType Directory -Force -Path $workRoot | Out-Null

$candidate = Join-Path $workRoot `
    ("compat-candidate-" + [guid]::NewGuid().ToString("N"))

$backup = Join-Path $workRoot `
    ("compat-previous-" + [guid]::NewGuid().ToString("N"))

New-Item -ItemType Directory -Path $candidate | Out-Null

$classPath = @(
    $language,
    $mod,
    $baseline,
    $forge,
    $fastutil,
    $jso,
    $guava,
    $commonsLang
) -join [System.IO.Path]::PathSeparator

$installed = $false

try {
    $previousPreference = $ErrorActionPreference
    $exitCode = $null

    try {
        $ErrorActionPreference = "Continue"

        & $javac `
            --release 17 `
            -encoding UTF-8 `
            -classpath $classPath `
            -d $candidate `
            @sourceFiles 2>&1 |
            ForEach-Object { $_.ToString() } |
            Out-Host

        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }

    if ($exitCode -ne 0) {
        throw "Dedicated compatibility compilation failed ($exitCode)."
    }

    foreach ($forbidden in @(
        "mezz\jei\util\Translator.class",
        "net\minecraft\client\resources\Language.class"
    )) {
        if (Test-Path -LiteralPath (Join-Path $candidate $forbidden)) {
            throw "Forbidden replacement for an original class: $forbidden"
        }
    }

    $verified = 0

    foreach ($property in $manifest.classes.PSObject.Properties) {
        $selection = $property.Value
        $sourceIndex = [int]$selection.source

        if ($sourceIndex -lt 0 -or
            $sourceIndex -ge @($manifest.sources).Count) {
            throw "Invalid manifest source index."
        }

        $origin = $manifest.sources[$sourceIndex]

        if (-not $origin.directory) {
            continue
        }

        $originPath = [System.IO.Path]::GetFullPath([string]$origin.path)

        if (-not [string]::Equals(
            $originPath,
            [System.IO.Path]::GetFullPath($classes),
            [System.StringComparison]::OrdinalIgnoreCase
        )) {
            throw "Unexpected compatibility directory in manifest: $originPath"
        }

        $entry = [string]$selection.entry

        if ($entry.Contains("..") -or
            $entry.StartsWith("/") -or
            $entry.Contains(":") -or
            $entry.Contains("\")) {
            throw "Unsafe compatibility entry in manifest: $entry"
        }

        $compiled = Join-Path $candidate $entry.Replace("/", "\")

        if (-not (Test-Path -LiteralPath $compiled -PathType Leaf)) {
            throw "Pinned compatibility class was not compiled: $entry"
        }

        $actual = (Get-FileHash -LiteralPath $compiled -Algorithm SHA256).Hash

        if (-not [string]::Equals(
            $actual,
            [string]$selection.sha256,
            [System.StringComparison]::OrdinalIgnoreCase
        )) {
            throw "Compatibility bytes differ from pinned profile: $entry. Manifest was not updated."
        }

        $verified++
    }

    if ($verified -eq 0) {
        throw "Manifest selected no generated compatibility classes."
    }

    if (Test-Path -LiteralPath $classes) {
        Move-Item -LiteralPath $classes -Destination $backup
    }

    try {
        Move-Item -LiteralPath $candidate -Destination $classes
        $installed = $true
    }
    catch {
        if (Test-Path -LiteralPath $backup) {
            Move-Item -LiteralPath $backup -Destination $classes
        }
        throw
    }

    if (Test-Path -LiteralPath $backup) {
        Remove-Item -LiteralPath $backup -Recurse -Force
    }

    Write-Output "Dedicated compatibility compilation passed."
    Write-Output "Pinned compatibility classes verified: $verified"
    Write-Output $classes
}
finally {
    if (-not $installed -and (Test-Path -LiteralPath $candidate)) {
        Remove-Item -LiteralPath $candidate -Recurse -Force
    }
}