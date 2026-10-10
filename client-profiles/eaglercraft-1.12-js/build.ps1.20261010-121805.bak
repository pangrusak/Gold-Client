$ErrorActionPreference = "Stop"

$repo = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$client = Join-Path $repo "unminified-clients\Eaglercraft_1.12_Offline_en_US.html"
$mod = Join-Path $repo "test-mods\jei1.12.2.jar"
$expectedHash = "DAD60317D8FDF891AD7841ABF838564EA7CBD317EC85D92F57DCB8F55602124B"
$translated = Join-Path $repo "target\jei-ingredient-elements\translated\eagler-mod.js"
$output = Join-Path $repo "target\jei-ingredient-elements\client\Eaglercraft_1.12_Offline_en_US.html"
$maven = Get-Command mvn.cmd -ErrorAction SilentlyContinue
if (-not $maven) {
    $maven = Get-Command mvn -ErrorAction Stop
}

if (-not (Test-Path -LiteralPath $client -PathType Leaf)) {
    throw "Supplied Eaglercraft 1.12.2 HTML was not found: $client"
}
if (-not (Test-Path -LiteralPath $mod -PathType Leaf)) {
    throw "Exact external JEI input was not found: $mod"
}
if ((Get-FileHash -LiteralPath $mod -Algorithm SHA256).Hash -ne $expectedHash) {
    throw "Refusing to use a JEI JAR with an unexpected SHA-256."
}

Push-Location $repo
try {
    & (Join-Path $PSScriptRoot "prepare-forge-runtime.ps1")
    if ($LASTEXITCODE -ne 0) {
        throw "Matching Forge/Minecraft JVM runtime preparation failed ($LASTEXITCODE)."
    }

    & node --test (Join-Path $PSScriptRoot "inject-client.test.cjs")
    if ($LASTEXITCODE -ne 0) {
        throw "Client injection regression test failed ($LASTEXITCODE)."
    }

    $previousJavaHome = $env:JAVA_HOME
    $previousPath = $env:PATH
    $jdk17 = Join-Path $repo "target\forge-baseline\jdk-17.0.15+6"
    $env:JAVA_HOME = $jdk17
    $env:PATH = (Join-Path $jdk17 "bin") + ";" + $previousPath
    try {
        & $maven.Source -q "-Dtest=JeiSuffixTreeMilestoneTest,JeiIngredientElementMilestoneTest" test
        if ($LASTEXITCODE -ne 0) {
            throw "Original JEI JVM/TeaVM comparison failed ($LASTEXITCODE)."
        }
    }
    finally {
        $env:JAVA_HOME = $previousJavaHome
        $env:PATH = $previousPath
    }

    & node (Join-Path $PSScriptRoot "inject-client.cjs") `
        $client $translated $output
    if ($LASTEXITCODE -ne 0) {
        throw "Eaglercraft client profile preparation failed ($LASTEXITCODE)."
    }
}
finally {
    Pop-Location
}

Write-Output ""
Write-Output "Run .\client-profiles\eaglercraft-1.12-js\run-demo.ps1 to serve the isolated client."
Write-Output "The client-derived registry corpus is saved under ignored target\jei-suffix-tree\."
Write-Output $output
