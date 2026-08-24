[CmdletBinding()]
param(
    [switch]$SkipFrontendInstall
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$RuntimeRoot = Join-Path $ProjectRoot '.data'
$RunRoot = Join-Path $RuntimeRoot 'run'
$LogRoot = Join-Path $RuntimeRoot 'logs'
$CacheRoot = Join-Path $ProjectRoot '.cache'
$MavenRepository = Join-Path $CacheRoot 'm2'
$ManifestPath = Join-Path $RunRoot 'dev-processes.json'

New-Item -ItemType Directory -Force -Path $RunRoot, $LogRoot, (Join-Path $RuntimeRoot 'h2'), $MavenRepository, (Join-Path $CacheRoot 'npm'), (Join-Path $CacheRoot 'pip') | Out-Null

function Import-DotEnv([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return }
    foreach ($Line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        $Trimmed = $Line.Trim()
        if ([string]::IsNullOrWhiteSpace($Trimmed) -or $Trimmed.StartsWith('#')) { continue }
        $Pair = $Trimmed -split '=', 2
        if ($Pair.Count -ne 2) { continue }
        $Name = $Pair[0].Trim()
        $Value = $Pair[1].Trim()
        if (($Value.StartsWith('"') -and $Value.EndsWith('"')) -or ($Value.StartsWith("'") -and $Value.EndsWith("'"))) {
            $Value = $Value.Substring(1, $Value.Length - 2)
        }
        if ($Name -match '^[A-Za-z_][A-Za-z0-9_]*$') {
            Set-Item -Path ("Env:{0}" -f $Name) -Value $Value
        }
    }
}

function Set-DefaultEnvironmentValue([string]$Name, [string]$Value) {
    $Current = [Environment]::GetEnvironmentVariable($Name, 'Process')
    if ([string]::IsNullOrWhiteSpace($Current)) {
        Set-Item -Path ("Env:{0}" -f $Name) -Value $Value
    }
}

Import-DotEnv (Join-Path $ProjectRoot '.env')
Set-DefaultEnvironmentValue 'PIP_CACHE_DIR' (Join-Path $CacheRoot 'pip')
Set-DefaultEnvironmentValue 'npm_config_cache' (Join-Path $CacheRoot 'npm')
Set-DefaultEnvironmentValue 'MAVEN_USER_HOME' (Join-Path $CacheRoot 'm2')
Set-DefaultEnvironmentValue 'MAVEN_ARGS' ("-Dmaven.repo.local={0}" -f $MavenRepository)
Set-DefaultEnvironmentValue 'SCIC_SERVER_PORT' '8080'
Set-DefaultEnvironmentValue 'SCIC_DB_DRIVER' 'org.h2.Driver'
$H2FilePath = (Join-Path $RuntimeRoot 'h2\scic') -replace '\\', '/'
Set-DefaultEnvironmentValue 'SCIC_DB_URL' ("jdbc:h2:file:{0};MODE=MySQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=TRUE" -f $H2FilePath)
Set-DefaultEnvironmentValue 'SCIC_DB_USER' 'sa'
Set-DefaultEnvironmentValue 'SCIC_DB_PASSWORD' ''
Set-DefaultEnvironmentValue 'SCIC_AI_BASE_URL' 'http://127.0.0.1:8001'
Set-DefaultEnvironmentValue 'SCIC_AI_SERVICE_TOKEN' 'CHANGE_ME_local_ai_service_token'
Set-DefaultEnvironmentValue 'SCIC_AI_CONNECT_TIMEOUT_MS' '2000'
Set-DefaultEnvironmentValue 'SCIC_AI_READ_TIMEOUT_MS' '15000'
Set-DefaultEnvironmentValue 'SCIC_CORS_ORIGINS' 'http://localhost:5173,http://127.0.0.1:5173'
Set-DefaultEnvironmentValue 'SCIC_LOG_FILE' (Join-Path $LogRoot 'backend.log')
Set-DefaultEnvironmentValue 'LLM_PROVIDER' 'rule'
Set-DefaultEnvironmentValue 'CORS_ALLOW_ORIGINS' $env:SCIC_CORS_ORIGINS
Set-DefaultEnvironmentValue 'VITE_API_BASE_URL' '/api/v1'
Set-DefaultEnvironmentValue 'VITE_ENABLE_DEMO_FALLBACK' 'false'
if ([string]::IsNullOrWhiteSpace($env:X_SERVICE_TOKEN)) { $env:X_SERVICE_TOKEN = $env:SCIC_AI_SERVICE_TOKEN }

if (Test-Path -LiteralPath $ManifestPath) {
    $Existing = Get-Content -LiteralPath $ManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $Alive = @($Existing.services | Where-Object { Get-Process -Id $_.pid -ErrorAction SilentlyContinue })
    if ($Alive.Count -gt 0) {
        throw "Development services are already running; run scripts/stop-dev.ps1 first."
    }
    Remove-Item -LiteralPath $ManifestPath -Force
}

$JavaCandidates = @()
if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    $JavaCandidates += (Join-Path $env:JAVA_HOME 'bin\java.exe')
}
$JavaCandidates += 'C:\Program Files\Huawei\DevEco Studio\jbr\bin\java.exe'
$PathJava = Get-Command java.exe -ErrorAction SilentlyContinue
if ($PathJava) { $JavaCandidates += $PathJava.Source }
$JavaPath = $null
foreach ($Candidate in $JavaCandidates | Select-Object -Unique) {
    if (-not (Test-Path -LiteralPath $Candidate)) { continue }
    $VersionText = (& $Candidate -version 2>&1 | Out-String)
    if ($VersionText -match 'version\s+"(?<major>\d+)(?:\.(?<minor>\d+))?') {
        $Major = [int]$Matches.major
        if ($Major -eq 1 -and $Matches.minor) { $Major = [int]$Matches.minor }
        if ($Major -ge 17) {
            $JavaPath = $Candidate
            break
        }
    }
}
$MavenCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
$NpmCommand = Get-Command npm.cmd -ErrorAction SilentlyContinue
if (-not $JavaPath) { throw 'Java 17+ was not found; configure JAVA_HOME. The script will not use an older Java runtime.' }
if (-not $MavenCommand) { throw 'Maven was not found; configure PATH first.' }
if (-not $NpmCommand) { throw 'npm was not found; configure PATH first.' }
$env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $JavaPath)

$AiPython = Join-Path $ProjectRoot 'ai-service\.venv\Scripts\python.exe'
if (-not (Test-Path -LiteralPath $AiPython)) {
    throw "AI service virtual environment was not found: $AiPython. Follow ai-service/README.md first."
}

$FrontendRoot = Join-Path $ProjectRoot 'frontend'
if (-not $SkipFrontendInstall -and -not (Test-Path -LiteralPath (Join-Path $FrontendRoot 'node_modules'))) {
    Push-Location $FrontendRoot
    try {
        & $NpmCommand.Source install --no-audit --no-fund
        if ($LASTEXITCODE -ne 0) { throw 'Frontend npm install failed.' }
    }
    finally { Pop-Location }
}

function Start-HiddenService([string]$Name, [string]$FilePath, [string[]]$Arguments, [string]$WorkingDirectory, [int]$Port) {
    $StdOut = Join-Path $LogRoot ("{0}.out.log" -f $Name)
    $StdErr = Join-Path $LogRoot ("{0}.err.log" -f $Name)
    $Process = Start-Process -FilePath $FilePath -ArgumentList $Arguments -WorkingDirectory $WorkingDirectory -WindowStyle Hidden -RedirectStandardOutput $StdOut -RedirectStandardError $StdErr -PassThru
    return [pscustomobject]@{
        name = $Name
        pid = $Process.Id
        port = $Port
        stdout = $StdOut
        stderr = $StdErr
    }
}

$Started = [System.Collections.Generic.List[object]]::new()
try {
    $AiRoot = Join-Path $ProjectRoot 'ai-service'
    $Started.Add((Start-HiddenService 'ai-service' $AiPython @('-m', 'uvicorn', 'app.main:app', '--host', '127.0.0.1', '--port', '8001') $AiRoot 8001))

    $Jar = Get-ChildItem -LiteralPath (Join-Path $ProjectRoot 'backend\target') -Filter '*.jar' -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike '*.original' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    $BackendRoot = Join-Path $ProjectRoot 'backend'
    if ($Jar) {
        $Started.Add((Start-HiddenService 'backend' $JavaPath @('-jar', $Jar.FullName) $BackendRoot 8080))
    }
    else {
        $Started.Add((Start-HiddenService 'backend' $MavenCommand.Source @(("-Dmaven.repo.local={0}" -f $MavenRepository), '-DskipTests', 'spring-boot:run') $BackendRoot 8080))
    }

    $Started.Add((Start-HiddenService 'frontend' $NpmCommand.Source @('run', 'dev', '--', '--host', '127.0.0.1', '--port', '5173') $FrontendRoot 5173))
    $Manifest = [ordered]@{
        startedAt = (Get-Date).ToUniversalTime().ToString('o')
        projectRoot = $ProjectRoot
        services = @($Started)
    }
    $Manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $ManifestPath -Encoding UTF8
    Write-Host 'Development services started in hidden windows.'
    foreach ($Service in $Started) { Write-Host ("{0}: PID {1}, port {2}, log {3}" -f $Service.name, $Service.pid, $Service.port, $Service.stdout) }
    Write-Host 'Run scripts/verify.ps1 for health checks; use scripts/stop-dev.ps1 to stop services.'
}
catch {
    foreach ($Service in $Started) {
        Stop-Process -Id $Service.pid -Force -ErrorAction SilentlyContinue
    }
    throw
}
