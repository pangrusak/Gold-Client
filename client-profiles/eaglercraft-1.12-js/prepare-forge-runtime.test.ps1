$ErrorActionPreference = "Stop"

$script = Get-Content -LiteralPath (Join-Path $PSScriptRoot "prepare-forge-runtime.ps1") -Raw

if ($script -notmatch 'function Test-JdkHome') {
    throw "Runtime preparation must validate the complete JDK layout."
}

foreach ($marker in @(
    'bin\java.exe',
    'bin\javac.exe',
    'lib\modules',
    'release'
)) {
    if ($script -notmatch [regex]::Escape($marker)) {
        throw "Runtime preparation is missing JDK marker: $marker"
    }
}

if ($script -notmatch 'Test-JdkHome \$jdkHome' -or
    $script -notmatch 'Test-JdkHome \$teaVmJdkHome') {
    throw "Both pinned JDKs must use complete-layout validation."
}

Write-Output "prepare-forge-runtime layout validation test passed."
