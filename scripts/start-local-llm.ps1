[CmdletBinding()]
param(
    [string]$ModelPath,
    [string]$ModelAlias = 'qwen2.5:1.5b',
    [int]$Port = 11435,
    [string]$ApiKey = 'local-llama-cpp'
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$RunRoot = Join-Path $ProjectRoot '.data\run'
$LogRoot = Join-Path $ProjectRoot '.data\logs'
$ManifestPath = Join-Path $RunRoot 'local-llm.json'
if ([string]::IsNullOrWhiteSpace($ModelPath)) {
    $ModelPath = Join-Path $ProjectRoot '.data\models\qwen2.5-1.5b-instruct-q4_k_m.gguf'
}

New-Item -ItemType Directory -Force -Path $RunRoot, $LogRoot | Out-Null
if (-not (Test-Path -LiteralPath $ModelPath)) { throw "Local LLM model was not found: $ModelPath" }

if (Test-Path -LiteralPath $ManifestPath) {
    $Existing = Get-Content -LiteralPath $ManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if (Get-Process -Id ([int]$Existing.pid) -ErrorAction SilentlyContinue) {
        Write-Host ("Local LLM is already running: PID {0}, port {1}" -f $Existing.pid, $Existing.port)
        exit 0
    }
    Remove-Item -LiteralPath $ManifestPath -Force
}

try {
    $ExistingHealth = Invoke-WebRequest -Uri ("http://127.0.0.1:{0}/health" -f $Port) -UseBasicParsing -TimeoutSec 2
    if ($ExistingHealth.StatusCode -eq 200) {
        Write-Host ("An existing local model server is healthy on port {0}; it was not started by this project." -f $Port)
        exit 0
    }
}
catch { }

$Server = Get-ChildItem -LiteralPath (Join-Path $ProjectRoot '.tools\llama.cpp') -Filter 'llama-server.exe' -File -Recurse -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $Server) { throw 'llama-server.exe was not found under D:\论文\.tools\llama.cpp.' }

$StdOut = Join-Path $LogRoot 'local-llm.out.log'
$StdErr = Join-Path $LogRoot 'local-llm.err.log'
$Threads = [Math]::Max(2, [Math]::Floor([Environment]::ProcessorCount / 2))
$Arguments = @(
    '--model', $ModelPath,
    '--alias', $ModelAlias,
    '--host', '127.0.0.1',
    '--port', $Port.ToString(),
    '--api-key', $ApiKey,
    '--cors-origins', 'localhost',
    '--ctx-size', '4096',
    '--parallel', '1',
    '--n-predict', '512',
    '--threads', $Threads.ToString(),
    '--gpu-layers', '0',
    '--jinja'
)
$Process = Start-Process -FilePath $Server.FullName -ArgumentList $Arguments -WorkingDirectory $Server.DirectoryName -WindowStyle Hidden -RedirectStandardOutput $StdOut -RedirectStandardError $StdErr -PassThru

$Healthy = $false
for ($Attempt = 0; $Attempt -lt 90; $Attempt++) {
    if ($Process.HasExited) { break }
    try {
        $Response = Invoke-WebRequest -Uri ("http://127.0.0.1:{0}/health" -f $Port) -UseBasicParsing -TimeoutSec 2
        if ($Response.StatusCode -eq 200) { $Healthy = $true; break }
    }
    catch { }
    Start-Sleep -Seconds 2
}
if (-not $Healthy) {
    if (-not $Process.HasExited) { Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue }
    throw "Local LLM did not become healthy. Check $StdErr"
}

$Manifest = [ordered]@{ pid = $Process.Id; port = $Port; model = $ModelAlias; modelPath = $ModelPath; server = $Server.FullName; startedAt = (Get-Date).ToUniversalTime().ToString('o') }
$Manifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $ManifestPath -Encoding UTF8

$WarmupBody = @{
    model = $ModelAlias
    temperature = 0
    max_tokens = 32
    response_format = @{ type = 'json_object' }
    messages = @(
        @{ role = 'system'; content = '只输出JSON。' },
        @{ role = 'user'; content = '输出 {"status":"ready"}' }
    )
} | ConvertTo-Json -Depth 6 -Compress
try {
    $WarmupHeaders = @{ Authorization = "Bearer $ApiKey" }
    Invoke-WebRequest -Method Post -Uri ("http://127.0.0.1:{0}/v1/chat/completions" -f $Port) -Headers $WarmupHeaders -ContentType 'application/json' -Body $WarmupBody -UseBasicParsing -TimeoutSec 90 | Out-Null
}
catch {
    & (Join-Path $PSScriptRoot 'stop-local-llm.ps1') | Out-Null
    throw "Local LLM warm-up failed. Check $StdErr"
}

Write-Host ("Local LLM ready: {0}, PID {1}, port {2}" -f $ModelAlias, $Process.Id, $Port)
