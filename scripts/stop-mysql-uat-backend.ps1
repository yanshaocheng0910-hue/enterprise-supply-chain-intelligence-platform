[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'local-process-identity.ps1')
$ManifestPath = Join-Path $ProjectRoot '.data\run\backend-mysql-uat.json'
if (-not (Test-Path -LiteralPath $ManifestPath)) {
    Write-Host 'MySQL UAT backend manifest not found; nothing to stop.'
    exit 0
}
$Manifest = Get-Content -LiteralPath $ManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
$Process = Get-ScicManagedProcess -Kind Backend -Manifest $Manifest -ProjectRoot $ProjectRoot
if ($Process) {
    $Process.Kill()
    Write-Host "MySQL UAT backend stopped: PID $($Process.Id)"
}
Remove-Item -LiteralPath $ManifestPath -Force
