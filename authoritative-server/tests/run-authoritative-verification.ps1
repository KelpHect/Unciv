[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Lane
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$bash = Get-Command bash.exe -ErrorAction SilentlyContinue
if ($null -eq $bash) {
    throw 'Git Bash (bash.exe) is required for the shared authoritative verification contract.'
}

$arguments = @('authoritative-server/tests/run-authoritative-verification.sh')
if ($Lane.Count -gt 0) {
    $arguments += $Lane
}

Write-Host ('=> bash {0}' -f ($arguments -join ' '))
Push-Location $root
try {
    & $bash.Source @arguments
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}
finally {
    Pop-Location
}
