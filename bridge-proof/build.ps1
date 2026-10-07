$ErrorActionPreference = "Stop"

$repo = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$client = Join-Path $repo "unminified-clients\Eaglercraft_1.12_Offline_en_US.html"
$bridgeOutput = Join-Path $repo "target\bridge-proof"
$fixtureJar = Join-Path $repo "target\client-bridge-fixture.jar"
$clientOutput = Join-Path $bridgeOutput "Eaglercraft_1.12_Offline_en_US.html"
$java = Join-Path $repo ".gold-client-jre\jdk-21.0.2+13-jre\bin\java.exe"

if (-not (Test-Path -LiteralPath $client -PathType Leaf)) {
    throw "Supplied client HTML was not found: $client"
}
if (-not (Test-Path -LiteralPath $java -PathType Leaf)) {
    throw "Project Java 21 runtime is not installed: $java"
}
if (-not (Test-Path -LiteralPath (Join-Path $repo "target\gold-client-0.1.0-SNAPSHOT.jar") -PathType Leaf)) {
    throw "Build Gold-Client first with mvn clean package."
}

Push-Location $repo
try {
    & mvn.cmd -q test-compile
    if ($LASTEXITCODE -ne 0) {
        throw "Maven test compilation failed with exit code $LASTEXITCODE"
    }

    & jar --create --file $fixtureJar -C target\test-classes com\goldclient\bridge\ClientBridgeFixture.class
    if ($LASTEXITCODE -ne 0) {
        throw "Could not package translated Java bridge fixture (exit code $LASTEXITCODE)"
    }

    & $java -jar target\gold-client-0.1.0-SNAPSHOT.jar $fixtureJar `
        --to-js $bridgeOutput --main-class com.goldclient.bridge.ClientBridgeFixture
    if ($LASTEXITCODE -ne 0) {
        throw "TeaVM bridge fixture compilation failed with exit code $LASTEXITCODE"
    }

    & node bridge-proof\test-bridge.cjs target\bridge-proof\eagler-mod.js
    if ($LASTEXITCODE -ne 0) {
        throw "Translated bridge callback test failed with exit code $LASTEXITCODE"
    }

    & node bridge-proof\inject-client.cjs $client `
        target\bridge-proof\eagler-mod.js $clientOutput
    if ($LASTEXITCODE -ne 0) {
        throw "Client adapter injection failed with exit code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}

Write-Output ""
Write-Output "Open this isolated build in a browser:"
Write-Output $clientOutput
