[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$ManifestPath = Join-Path $ProjectRoot '.data\run\dev-processes.json'

if (-not (Test-Path -LiteralPath $ManifestPath)) {
    Write-Host 'No development service manifest was found; nothing to stop.'
    exit 0
}

$Manifest = Get-Content -LiteralPath $ManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
foreach ($Service in @($Manifest.services)) {
    $Process = Get-Process -Id ([int]$Service.pid) -ErrorAction SilentlyContinue
    if ($Process) {
        # The PID comes only from our own manifest; /T also closes a Maven/npm
        # child tree without touching unrelated processes.
        & taskkill.exe /PID ([int]$Service.pid) /T /F | Out-Null
        Write-Host ("Stopped {0} (PID {1})" -f $Service.name, $Service.pid)
    }
    else {
        Write-Host ("{0} (PID {1}) had already exited" -f $Service.name, $Service.pid)
    }
}
Remove-Item -LiteralPath $ManifestPath -Force
Write-Host 'Development service manifest removed; logs, H2 data, and caches under .data were preserved.'
