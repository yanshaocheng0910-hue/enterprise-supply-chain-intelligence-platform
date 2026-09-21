[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'local-process-identity.ps1')
$ManifestPath = Join-Path $ProjectRoot '.data\run\mysql-uat.json'

if (-not (Test-Path -LiteralPath $ManifestPath)) {
    Write-Host 'Local MySQL manifest not found; nothing to stop.'
    exit 0
}

$Manifest = Get-Content -LiteralPath $ManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
$Process = Get-ScicManagedProcess -Kind MySql -Manifest $Manifest -ProjectRoot $ProjectRoot
if ($Process) {
    $Process.Kill()
    Write-Host "Local MySQL stopped: PID $($Process.Id)"
}
Remove-Item -LiteralPath $ManifestPath -Force
