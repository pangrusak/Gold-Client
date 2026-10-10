param(
    [switch] $RequireClientCorpus
)

$ErrorActionPreference = "Stop"

$repo = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$client = Join-Path $repo "unminified-clients\Eaglercraft_1.12_Offline_en_US.html"
$mod = Join-Path $repo "test-mods\jei1.12.2.jar"

$expectedModHash = "DAD60317D8FDF891AD7841ABF838564EA7CBD317EC85D92F57DCB8F55602124B"
$expectedClientHash = "B23DECDCA4FF9AF58F63EEA4E93772D8A0A1DCBB59781A32E505E9C9735D1E67"

$pipelineRoot = Join-Path $repo "target\jei-profile"
$reportPath = Join-Path $pipelineRoot "report.json"
$selectedJar = Join-Path $pipelineRoot "selected.jar"
$translatedDirectory = Join-Path $pipelineRoot "translated"
$translated = Join-Path $translatedDirectory "eagler-mod.js"

$output = Join-Path $repo `
    "target\jei-ingredient-elements\client\Eaglercraft_1.12_Offline_en_US.html"

$manifest = Join-Path $PSScriptRoot "jei-class-profile.local.json"
$assembled = Join-Path $pipelineRoot "assembled.jar"

$comparisonScript = Join-Path $repo `
    "target\jei-ingredient-elements\compare.cjs"

$comparisonData = Join-Path $repo `
    "target\jei-ingredient-elements\comparison.json"

$fastutil = Join-Path $env:USERPROFILE `
    ".m2\repository\it\unimi\dsi\fastutil\7.1.0\fastutil-7.1.0.jar"

$tool = Join-Path $repo "target\gold-client-0.1.0-SNAPSHOT.jar"

function Invoke-CheckedNative {
    param(
        [string] $Command,
        [string[]] $Arguments,
        [string] $Description
    )

    $previousPreference = $ErrorActionPreference
    $exitCode = $null

    try {
        $ErrorActionPreference = "Continue"
        & $Command @Arguments 2>&1 |
            ForEach-Object { $_.ToString() } |
            Out-Host
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }

    if ($exitCode -ne 0) {
        throw "$Description failed (exit code $exitCode)."
    }
}

foreach ($required in @($client, $mod)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Required original input is missing: $required"
    }
}

if ((Get-FileHash -LiteralPath $mod -Algorithm SHA256).Hash -ne $expectedModHash) {
    throw "Unexpected original JEI SHA-256."
}

if ((Get-FileHash -LiteralPath $client -Algorithm SHA256).Hash -ne $expectedClientHash) {
    throw "Unexpected original client SHA-256. This adapter is build-specific."
}

$maven = Get-Command mvn.cmd -ErrorAction SilentlyContinue
if (-not $maven) {
    $maven = Get-Command mvn -ErrorAction Stop
}

Push-Location $repo
try {
    & (Join-Path $PSScriptRoot "prepare-forge-runtime.ps1")

    Invoke-CheckedNative -Command "node" -Arguments @(
        "--check",
        (Join-Path $PSScriptRoot "inject-client.cjs")
    ) -Description "Injector syntax check"

    Invoke-CheckedNative -Command "node" -Arguments @(
        "--test",
        (Join-Path $PSScriptRoot "inject-client.test.cjs")
    ) -Description "Injector regression tests"

    $previousJavaHome = $env:JAVA_HOME
    $previousPath = $env:PATH
    $jdk17 = Join-Path $repo "target\forge-baseline\jdk-17.0.15+6"
    $java = Join-Path $jdk17 "bin\java.exe"

    if (-not (Test-Path -LiteralPath $java -PathType Leaf)) {
        throw "Prepared Java 17 executable is missing: $java"
    }

    try {
        $env:JAVA_HOME = $jdk17
        $env:PATH = (Join-Path $jdk17 "bin") + ";" + $previousPath

        $requireCorpus = if ($RequireClientCorpus) { "true" } else { "false" }

        Invoke-CheckedNative -Command $maven.Source -Arguments @(
            "-Dgoldclient.requireClientCorpus=$requireCorpus",
            "package"
        ) -Description "Full regression build"

        foreach ($required in @(
            $tool, $manifest, $fastutil, $comparisonScript, $comparisonData
        )) {
            if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
                throw "Required generated or dependency input is missing: $required"
            }
        }

        & (Join-Path $PSScriptRoot "prepare-jei-compat.ps1")

        Invoke-CheckedNative -Command $java -Arguments @(
            "-cp", $tool,
            "com.goldclient.planner.ProfileManifestTool",
            "assemble", $manifest, $assembled
        ) -Description "Pinned manifest assembly"

        Invoke-CheckedNative -Command $java -Arguments @(
            "-jar", $tool,
            "pipeline",
            "--input", $assembled,
            "--root", "JeiIngredientEntry",
            "--reachability", "teavm",
            "--report", $reportPath,
            "--stage", $selectedJar,
            "--to-js", $translatedDirectory,
            "--main-class", "JeiIngredientEntry",
            "--child-first", "org.apache.logging.log4j."
        ) -Description "JEI multi-JAR pipeline"

        Invoke-CheckedNative -Command "node" -Arguments @(
            $comparisonScript,
            $translated,
            $comparisonData
        ) -Description "Pipeline JavaScript versus JVM comparison"

        $report = Get-Content -LiteralPath $reportPath -Raw |
            ConvertFrom-Json

        if ($report.status -ne "TRANSLATED_WITH_DEFERRED_STRUCTURAL_REFERENCES") {
            throw "Unexpected pipeline report status: $($report.status)"
        }

        $comparison = Get-Content -LiteralPath $comparisonData -Raw |
            ConvertFrom-Json

        $corpusSource = [string]$comparison.corpusSource
        $records = @($comparison.ids).Count

        if ($RequireClientCorpus -and
            ($corpusSource -ne "browser-exported-client-corpus" -or
             $records -ne 411)) {
            throw "Real 411-record client corpus is required."
        }

        Invoke-CheckedNative -Command "node" -Arguments @(
            (Join-Path $PSScriptRoot "inject-client.cjs"),
            $client,
            $translated,
            $output
        ) -Description "Pipeline-backed browser injection"

        $browserBundle = Join-Path (Split-Path -Parent $output) `
            "eagler-jei-ingredient-elements.js"

        $pipelineHash = (Get-FileHash -LiteralPath $translated -Algorithm SHA256).Hash
        $browserHash = (Get-FileHash -LiteralPath $browserBundle -Algorithm SHA256).Hash

        if ($pipelineHash -ne $browserHash) {
            throw "Injected browser bundle differs from pipeline output."
        }

        Write-Output ""
        Write-Output "Manifest-backed browser client prepared."
        Write-Output "Assembly profile: $manifest"
        Write-Output "Comparison corpus: $corpusSource"
        Write-Output "Comparison records: $records"
        Write-Output "Browser bundle matches pipeline output: $pipelineHash"
        Write-Output "Deferred structural references remain recorded in $reportPath"
        Write-Output $output
    }
    finally {
        $env:JAVA_HOME = $previousJavaHome
        $env:PATH = $previousPath
    }
}
finally {
    Pop-Location
}