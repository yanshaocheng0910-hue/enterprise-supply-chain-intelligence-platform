[CmdletBinding()]
param(
    [ValidatePattern('^[A-Za-z0-9_]+$')]
    [string]$Database = 'scic_platform_uat_clean',
    [ValidatePattern('^[A-Za-z0-9_]+$')]
    [string]$User = 'scic_uat',
    [Parameter(Mandatory)]
    [ValidatePattern('^[A-Za-z0-9_@#-]{12,128}$')]
    [string]$Password,
    [Parameter(Mandatory)]
    [ValidatePattern('^[A-Za-z0-9_@#-]{12,128}$')]
    [string]$RootPassword,
    [ValidateRange(1024, 65535)]
    [int]$Port = 3307,
    [ValidatePattern('^\d+\.\d+\.\d+$')]
    [string]$Version = '8.4.11'
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'local-process-identity.ps1')
$RuntimeRoot = Join-Path $ProjectRoot ".tools\mysql\mysql-$Version-winx64"
$DataRoot = Join-Path $ProjectRoot '.data\mysql-uat'
$LogRoot = Join-Path $ProjectRoot '.data\logs'
$RunRoot = Join-Path $ProjectRoot '.data\run'
$ManifestPath = Join-Path $RunRoot 'mysql-uat.json'
$Server = Join-Path $RuntimeRoot 'bin\mysqld.exe'
$Client = Join-Path $RuntimeRoot 'bin\mysql.exe'
$Admin = Join-Path $RuntimeRoot 'bin\mysqladmin.exe'
$SharedMemoryName = 'SCICMYSQLUAT'

if (-not (Test-Path -LiteralPath $Server)) {
    throw 'Local MySQL runtime is missing. Run scripts/install-local-mysql.ps1 first.'
}
New-Item -ItemType Directory -Force -Path $DataRoot, $LogRoot, $RunRoot | Out-Null

if (Test-Path -LiteralPath $ManifestPath) {
    $Existing = Get-Content -LiteralPath $ManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $ExistingProcess = Get-ScicManagedProcess -Kind MySql -Manifest $Existing -ProjectRoot $ProjectRoot -RequireListener
    if ($ExistingProcess) {
        Assert-ScicMySqlSettings -Manifest $Existing -Database $Database -User $User -Port $Port -Version $Version
        & $Client --protocol=TCP -h 127.0.0.1 "--port=$Port" "--user=$User" "--password=$Password" "--database=$Database" --execute='SELECT 1' --silent 2>$null | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Existing MySQL is running, but the requested database credentials could not be verified.' }
        Write-Host "Local MySQL is already running: PID $($Existing.pid), port $($Existing.port)"
        exit 0
    }
}

if (Get-NetTCPConnection -State Listen -LocalPort $Port, 33070 -ErrorAction SilentlyContinue) {
    throw 'A required MySQL port is already occupied; no process was changed.'
}

if (-not (Test-Path -LiteralPath (Join-Path $DataRoot 'mysql'))) {
    & $Server --initialize-insecure "--basedir=$RuntimeRoot" "--datadir=$DataRoot" --console
    if ($LASTEXITCODE -ne 0) { throw 'MySQL data directory initialization failed.' }
}

$Arguments = @(
    "--basedir=$RuntimeRoot",
    "--datadir=$DataRoot",
    "--port=$Port",
    '--bind-address=127.0.0.1',
    '--mysqlx-port=33070',
    '--mysqlx-bind-address=127.0.0.1',
    '--skip-name-resolve',
    '--shared-memory',
    "--shared-memory-base-name=$SharedMemoryName",
    '--character-set-server=utf8mb4',
    '--collation-server=utf8mb4_unicode_ci',
    '--no-monitor',
    "--log-error=$(Join-Path $LogRoot 'mysql-uat.err.log')"
)
$QuotedArguments = $Arguments | ForEach-Object { '"' + $_ + '"' }
$Process = Start-Process -FilePath $Server -ArgumentList $QuotedArguments -WorkingDirectory $RuntimeRoot -WindowStyle Hidden -PassThru
$ProcessStartTimeUtc = $Process.StartTime.ToUniversalTime().ToString('o')

try {
    $Ready = $false
    for ($Attempt = 0; $Attempt -lt 60; $Attempt++) {
        Start-Sleep -Milliseconds 500
        if ($Process.HasExited) { throw 'The launched MySQL process exited before becoming ready.' }
        & $Admin --protocol=MEMORY "--shared-memory-base-name=$SharedMemoryName" -u root "--password=$RootPassword" ping --silent 2>$null
        if ($LASTEXITCODE -eq 0) { $Ready = $true; break }
        & $Admin --protocol=MEMORY "--shared-memory-base-name=$SharedMemoryName" -u root ping --silent 2>$null
        if ($LASTEXITCODE -eq 0) { $Ready = $true; break }
    }
    if (-not $Ready) { throw 'Local MySQL did not become ready.' }

    $Sql = @"
CREATE DATABASE IF NOT EXISTS $Database CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '$User'@'127.0.0.1' IDENTIFIED BY '$Password';
ALTER USER '$User'@'127.0.0.1' IDENTIFIED BY '$Password';
GRANT ALL PRIVILEGES ON $Database.* TO '$User'@'127.0.0.1';
ALTER USER 'root'@'localhost' IDENTIFIED BY '$RootPassword';
FLUSH PRIVILEGES;
"@

    & $Client --protocol=MEMORY "--shared-memory-base-name=$SharedMemoryName" -u root "--password=$RootPassword" "--execute=$Sql" 2>$null
    if ($LASTEXITCODE -ne 0) {
        & $Client --protocol=MEMORY "--shared-memory-base-name=$SharedMemoryName" -u root "--execute=$Sql"
    }
    if ($LASTEXITCODE -ne 0) { throw 'MySQL database/user initialization failed.' }

    [ordered]@{
        pid = $Process.Id
        processStartTimeUtc = $ProcessStartTimeUtc
        port = $Port
        version = $Version
        database = $Database
        user = $User
        runtimeRoot = $RuntimeRoot
        dataRoot = $DataRoot
        startedAt = (Get-Date).ToUniversalTime().ToString('o')
    } | ConvertTo-Json | Set-Content -LiteralPath $ManifestPath -Encoding UTF8
    Write-Host "Local MySQL ready: PID $($Process.Id), port $Port, database $Database"
}
catch {
    if (-not $Process.HasExited) { $Process.Kill() }
    throw
}
