[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$ManifestPath = Join-Path $ProjectRoot '.data\run\local-llm.json'
if (-not (Test-Path -LiteralPath $ManifestPath)) {
    Write-Host 'No project-owned local LLM process was found.'
    exit 0
}

$Manifest = Get-Content -LiteralPath $ManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
$Process = Get-Process -Id ([int]$Manifest.pid) -ErrorAction SilentlyContinue
if ($Process) {
    $ActualPath = $Process.Path
    if (-not [string]::IsNullOrWhiteSpace($ActualPath) -and $ActualPath -ne $Manifest.server) {
        throw "PID $($Manifest.pid) no longer belongs to the recorded local LLM server; refusing to stop it."
    }
    Stop-Process -Id ([int]$Manifest.pid) -Force
    Write-Host ("Stopped local LLM (PID {0})" -f $Manifest.pid)
}
Remove-Item -LiteralPath $ManifestPath -Force
