[CmdletBinding()]
param(
    [ValidatePattern('^[A-Za-z0-9_]+$')]
    [string]$Database = 'scic_platform_uat_clean',
    [ValidatePattern('^[A-Za-z0-9_]+$')]
    [string]$User = 'scic_uat',
    [Parameter(Mandatory)]
    [ValidatePattern('^[A-Za-z0-9_@#-]{12,128}$')]
    [string]$Password,
    [string]$AiServiceToken = $env:SCIC_AI_SERVICE_TOKEN,
    [ValidateRange(1024, 65535)]
    [int]$DatabasePort = 3307,
    [ValidateRange(1024, 65535)]
    [int]$ServerPort = 8081
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'local-process-identity.ps1')
$RunRoot = Join-Path $ProjectRoot '.data\run'
$LogRoot = Join-Path $ProjectRoot '.data\logs'
$ManifestPath = Join-Path $RunRoot 'backend-mysql-uat.json'
$Jar = Get-ChildItem -LiteralPath (Join-Path $ProjectRoot 'backend\target') -Filter '*.jar' -File |
    Where-Object { $_.Name -notlike '*.original' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $Jar) { throw 'Packaged backend JAR not found. Run Maven package first.' }

if ([string]::IsNullOrWhiteSpace($AiServiceToken)) {
    $DotEnvPath = Join-Path $ProjectRoot '.env'
    if (Test-Path -LiteralPath $DotEnvPath) {
        foreach ($Line in Get-Content -LiteralPath $DotEnvPath -Encoding UTF8) {
            $Trimmed = $Line.Trim()
            if ($Trimmed.StartsWith('#') -or -not $Trimmed.Contains('=')) { continue }
            $Pair = $Trimmed -split '=', 2
            if ($Pair[0].Trim() -ne 'SCIC_AI_SERVICE_TOKEN') { continue }
            $AiServiceToken = $Pair[1].Trim().Trim('"').Trim("'")
            break
        }
    }
}
if ([string]::IsNullOrWhiteSpace($AiServiceToken)) {
    throw 'SCIC_AI_SERVICE_TOKEN is missing. Set it in the process environment, .env, or -AiServiceToken.'
}

$JavaCandidates = @()
if ($env:JAVA_HOME) { $JavaCandidates += (Join-Path $env:JAVA_HOME 'bin\java.exe') }
$JavaCandidates += 'C:\Program Files\Huawei\DevEco Studio\jbr\bin\java.exe'
$Java = $JavaCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $Java) { throw 'Java 17+ runtime not found.' }

if (Test-Path -LiteralPath $ManifestPath) {
    $Existing = Get-Content -LiteralPath $ManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if (Get-ScicManagedProcess -Kind Backend -Manifest $Existing -ProjectRoot $ProjectRoot) {
        throw "MySQL UAT backend is already running: PID $($Existing.pid)."
    }
}

if (Get-NetTCPConnection -State Listen -LocalPort $ServerPort -ErrorAction SilentlyContinue) {
    throw 'The requested backend port is already occupied; no process was changed.'
}
$MySqlManifestPath = Join-Path $RunRoot 'mysql-uat.json'
if (Test-Path -LiteralPath $MySqlManifestPath) {
    $MySqlManifest = Get-Content -LiteralPath $MySqlManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $MySqlProcess = Get-ScicManagedProcess -Kind MySql -Manifest $MySqlManifest -ProjectRoot $ProjectRoot -RequireListener
    if (-not $MySqlProcess) { throw 'The recorded local MySQL process is not running. Start it first.' }
    Assert-ScicMySqlSettings -Manifest $MySqlManifest -Database $Database -User $User -Port $DatabasePort -Version $MySqlManifest.version
}

New-Item -ItemType Directory -Force -Path $RunRoot, $LogRoot | Out-Null
$env:SCIC_SERVER_PORT = "$ServerPort"
$env:SCIC_SERVER_ADDRESS = '127.0.0.1'
$env:SCIC_DB_DRIVER = 'com.mysql.cj.jdbc.Driver'
$env:SCIC_DB_URL = "jdbc:mysql://127.0.0.1:$DatabasePort/${Database}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false"
$env:SCIC_DB_USER = $User
$env:SCIC_DB_PASSWORD = $Password
$env:SCIC_JWT_SECRET = 'mysql-uat-only-secret-change-before-deployment-2026'
$env:SCIC_AI_BASE_URL = 'http://127.0.0.1:8001'
$env:SCIC_AI_SERVICE_TOKEN = $AiServiceToken
$env:SCIC_CORS_ORIGINS = 'http://localhost:5173,http://127.0.0.1:5173'
$env:SCIC_LOG_FILE = (Join-Path $LogRoot 'backend-mysql-uat.log') -replace '\\', '/'
$env:SCIC_DEMO_SYNTHETIC_HISTORY_ENABLED = 'false'

$StdOut = Join-Path $LogRoot 'backend-mysql-uat.out.log'
$StdErr = Join-Path $LogRoot 'backend-mysql-uat.err.log'
$Process = Start-Process -FilePath $Java -ArgumentList @('-jar', ('"' + $Jar.FullName + '"')) -WorkingDirectory (Join-Path $ProjectRoot 'backend') -WindowStyle Hidden -RedirectStandardOutput $StdOut -RedirectStandardError $StdErr -PassThru
$ProcessStartTimeUtc = $Process.StartTime.ToUniversalTime().ToString('o')

try {
    $Ready = $false
    for ($Attempt = 0; $Attempt -lt 90; $Attempt++) {
        Start-Sleep -Milliseconds 500
        if ($Process.HasExited) { throw 'The launched backend process exited before becoming healthy.' }
        try {
            $Health = Invoke-RestMethod -Uri "http://127.0.0.1:$ServerPort/actuator/health" -TimeoutSec 2
            $OwnsPort = Get-NetTCPConnection -State Listen -LocalPort $ServerPort -ErrorAction SilentlyContinue |
                Where-Object { $_.OwningProcess -eq $Process.Id }
            if ($Health.status -eq 'UP' -and $OwnsPort) { $Ready = $true; break }
        }
        catch { }
    }
    if (-not $Ready) { throw "MySQL UAT backend did not become healthy. See $StdOut and $StdErr." }
    [ordered]@{
        pid = $Process.Id
        processStartTimeUtc = $ProcessStartTimeUtc
        executablePath = $Java
        jarPath = $Jar.FullName
        port = $ServerPort
        databasePort = $DatabasePort
        database = $Database
        startedAt = (Get-Date).ToUniversalTime().ToString('o')
    } | ConvertTo-Json | Set-Content -LiteralPath $ManifestPath -Encoding UTF8
    Write-Host "MySQL UAT backend ready: PID $($Process.Id), port $ServerPort"
}
catch {
    if (-not $Process.HasExited) { $Process.Kill() }
    throw
}
