[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$ManifestPath = Join-Path $ProjectRoot '.data\run\dev-processes.json'
$StopLocalLlm = Join-Path $PSScriptRoot 'stop-local-llm.ps1'

function Convert-ToUtcDateTime($Value) {
    if ($Value -is [datetime]) { return $Value.ToUniversalTime() }
    return ([datetimeoffset]::Parse([string]$Value)).UtcDateTime
}

function Get-VerifiedDevelopmentProcess($Service, $Manifest) {
    $ManagedProcess = Get-Process -Id ([int]$Service.pid) -ErrorAction SilentlyContinue
    if (-not $ManagedProcess) { return $null }
    $CapturedStart = $ManagedProcess.StartTime.ToUniversalTime()
    $Snapshot = Get-CimInstance Win32_Process -Filter "ProcessId = $([int]$Service.pid)"
    if (-not $Snapshot -or -not $Snapshot.ExecutablePath -or -not $Snapshot.CommandLine) {
        throw "Cannot verify development service identity: $($Service.name)."
    }

    $CreatedAt = ([datetime]$Snapshot.CreationDate).ToUniversalTime()
    if ($Service.processStartTimeUtc) {
        $ExpectedStart = Convert-ToUtcDateTime $Service.processStartTimeUtc
        if ([math]::Abs(($CreatedAt - $ExpectedStart).TotalSeconds) -gt 1) {
            throw "Development service creation time differs: $($Service.name)."
        }
    }
    else {
        # Legacy manifests recorded readiness after all three roots started.
        $ReadyAt = Convert-ToUtcDateTime $Manifest.startedAt
        $StartupSeconds = ($ReadyAt - $CreatedAt).TotalSeconds
        if ($StartupSeconds -lt 0 -or $StartupSeconds -gt 240) {
            throw "Legacy development manifest does not match: $($Service.name)."
        }
    }
    if ([math]::Abs(($CreatedAt - $CapturedStart).TotalSeconds) -gt 1) {
        throw "Development service changed during verification: $($Service.name)."
    }
    if ($Service.executablePath -and $Service.executablePath -ine $Snapshot.ExecutablePath) {
        throw "Development service executable differs: $($Service.name)."
    }

    $AiPython = Join-Path $ProjectRoot 'ai-service\.venv\Scripts\python.exe'
    $BackendJarRoot = Join-Path $ProjectRoot 'backend\target'
    switch ($Service.name) {
        'ai-service' {
            if ($Snapshot.ExecutablePath -ine $AiPython -or $Snapshot.CommandLine -notmatch '(?i)\s-m\s+uvicorn\s+app\.main:app') {
                throw 'AI service manifest points to an unexpected process.'
            }
        }
        'backend' {
            $IsJavaJar = [IO.Path]::GetFileName($Snapshot.ExecutablePath) -ieq 'java.exe' -and
                $Snapshot.CommandLine -match '(?i)\s-jar\s+' -and $Snapshot.CommandLine -like "*$BackendJarRoot*"
            $IsProjectMaven = [IO.Path]::GetFileName($Snapshot.ExecutablePath) -in @('mvn.cmd', 'cmd.exe') -and
                $Snapshot.CommandLine -match '(?i)spring-boot:run'
            if (-not ($IsJavaJar -or $IsProjectMaven)) {
                throw 'Backend manifest points to an unexpected process.'
            }
        }
        'frontend' {
            if ([IO.Path]::GetFileName($Snapshot.ExecutablePath) -notin @('npm.cmd', 'cmd.exe', 'node.exe') -or
                $Snapshot.CommandLine -notmatch '(?i)(npm(?:\.cmd)?[^\r\n]*run\s+dev|vite[^\r\n]*--port\s+5173)') {
                throw 'Frontend manifest points to an unexpected process.'
            }
        }
        default { throw 'Unknown service name in development manifest.' }
    }
    return $ManagedProcess
}

if (-not (Test-Path -LiteralPath $ManifestPath)) {
    if (Test-Path -LiteralPath $StopLocalLlm) { & $StopLocalLlm }
    Write-Host 'No development service manifest was found; nothing to stop.'
    exit 0
}

$Manifest = Get-Content -LiteralPath $ManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($Manifest.projectRoot -ine $ProjectRoot) {
    throw 'Development manifest belongs to a different project root.'
}
foreach ($Service in @($Manifest.services)) {
    $Process = Get-VerifiedDevelopmentProcess -Service $Service -Manifest $Manifest
    if ($Process) {
        # Identity is verified before /T closes the service's own Maven/npm tree.
        & taskkill.exe /PID ([int]$Service.pid) /T /F | Out-Null
        Write-Host ("Stopped {0} (PID {1})" -f $Service.name, $Service.pid)
    }
    else {
        Write-Host ("{0} (PID {1}) had already exited" -f $Service.name, $Service.pid)
    }
}
Remove-Item -LiteralPath $ManifestPath -Force
if (Test-Path -LiteralPath $StopLocalLlm) { & $StopLocalLlm }
Write-Host 'Development service manifest removed; logs, H2 data, and caches under .data were preserved.'
