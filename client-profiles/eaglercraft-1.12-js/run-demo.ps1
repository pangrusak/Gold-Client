$ErrorActionPreference = "Stop"

& (Join-Path $PSScriptRoot "build.ps1")
if ($LASTEXITCODE -ne 0) {
    throw "JEI diagnostic client build failed ($LASTEXITCODE)."
}

& node (Join-Path $PSScriptRoot "serve-demo.cjs")
