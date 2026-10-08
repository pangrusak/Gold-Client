$ErrorActionPreference = "Stop"

$repo = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$client = Join-Path $repo "unminified-clients\Eaglercraft_1.12_Offline_en_US.html"
$mod = Join-Path $repo "test-mods\jei1.12.2.jar"
$expectedHash = "DAD60317D8FDF891AD7841ABF838564EA7CBD317EC85D92F57DCB8F55602124B"
$translated = Join-Path $repo "target\jei-suffix-tree\translated\eagler-mod.js"
$output = Join-Path $repo "target\jei-suffix-tree\client\Eaglercraft_1.12_Offline_en_US.html"
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
    & $maven.Source -q -Dtest=JeiSuffixTreeMilestoneTest test
    if ($LASTEXITCODE -ne 0) {
        throw "Original JEI suffix-tree JVM/TeaVM comparison failed ($LASTEXITCODE)."
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
Write-Output "Open the isolated client and enter a world, then open/close a screen."
Write-Output "Verify a '[JEI original suffix-tree adapter]' console entry and inspect:"
Write-Output "window.__goldClientJeiSearchCalls"
Write-Output $output
