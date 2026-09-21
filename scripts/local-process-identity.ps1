# Shared identity checks for the isolated MySQL acceptance services. Never log a command line.
function Assert-ScicProcessIdentity {
    param(
        [Parameter(Mandatory)][ValidateSet('MySql', 'Backend')][string]$Kind,
        [Parameter(Mandatory)]$Manifest,
        [Parameter(Mandatory)]$Snapshot,
        [Parameter(Mandatory)][string]$ProjectRoot
    )

    if ([int]$Manifest.pid -le 0 -or [int]$Snapshot.ProcessId -ne [int]$Manifest.pid) {
        throw 'Managed process PID does not match the manifest.'
    }
    if (-not $Snapshot.ExecutablePath -or -not $Snapshot.CommandLine -or -not $Snapshot.CreationDate) {
        throw 'Cannot verify the managed process executable, arguments and creation time.'
    }
    $CreatedAt = ([datetime]$Snapshot.CreationDate).ToUniversalTime()
    if ($Manifest.processStartTimeUtc) {
        # ConvertFrom-Json already materializes ISO-8601 values as DateTime on
        # current PowerShell versions. Parsing that value again as a localized
        # string applies the timezone twice (eight hours on this workstation).
        $ExpectedStart = if ($Manifest.processStartTimeUtc -is [datetime]) {
            $Manifest.processStartTimeUtc.ToUniversalTime()
        }
        else {
            ([datetimeoffset]::Parse([string]$Manifest.processStartTimeUtc)).UtcDateTime
        }
        if ([math]::Abs(($CreatedAt - $ExpectedStart).TotalSeconds) -gt 1) {
            throw 'Managed process creation time differs; the manifest PID may have been reused.'
        }
    }
    else {
        # Old manifests recorded readiness, shortly after process creation.
        if (-not $Manifest.startedAt) { throw 'Legacy manifest has no verifiable start time.' }
        $ReadyAt = if ($Manifest.startedAt -is [datetime]) {
            $Manifest.startedAt.ToUniversalTime()
        }
        else {
            ([datetimeoffset]::Parse([string]$Manifest.startedAt)).UtcDateTime
        }
        $StartupSeconds = ($ReadyAt - $CreatedAt).TotalSeconds
        if ($StartupSeconds -lt 0 -or $StartupSeconds -gt 240) {
            throw 'Legacy manifest does not match the process creation time.'
        }
    }

    $Arguments = @([regex]::Matches($Snapshot.CommandLine, '(?:[^\s"]+|"[^"]*")+') |
        ForEach-Object { $_.Value.Replace('"', '') })
    if ($Kind -eq 'MySql') {
        if ($Manifest.version -notmatch '^\d+\.\d+\.\d+$') { throw 'Invalid MySQL version in manifest.' }
        $ExpectedRuntime = Join-Path $ProjectRoot ".tools\mysql\mysql-$($Manifest.version)-winx64"
        $ExpectedExecutable = Join-Path $ExpectedRuntime 'bin\mysqld.exe'
        $ExpectedData = Join-Path $ProjectRoot '.data\mysql-uat'
        if ($Snapshot.ExecutablePath -ine $ExpectedExecutable -or
            $Manifest.runtimeRoot -ine $ExpectedRuntime -or $Manifest.dataRoot -ine $ExpectedData) {
            throw 'Manifest process is not the expected project MySQL runtime and data directory.'
        }
        foreach ($Argument in @("--basedir=$ExpectedRuntime", "--datadir=$ExpectedData", "--port=$($Manifest.port)")) {
            if ($Arguments -inotcontains $Argument) { throw 'MySQL process arguments do not match the manifest.' }
        }
    }
    else {
        if ([IO.Path]::GetFileName($Snapshot.ExecutablePath) -ine 'java.exe' -or
            $Arguments.Count -lt 3 -or $Arguments[1] -cne '-jar') {
            throw 'Manifest process is not a Java JAR backend.'
        }
        $JarPath = $Arguments[2]
        $ExpectedJarRoot = Join-Path $ProjectRoot 'backend\target'
        if ([IO.Path]::GetDirectoryName($JarPath) -ine $ExpectedJarRoot -or
            [IO.Path]::GetFileName($JarPath) -notlike 'scic-platform-backend-*.jar') {
            throw 'Manifest process does not run the project backend JAR.'
        }
        if (($Manifest.jarPath -and $Manifest.jarPath -ine $JarPath) -or
            ($Manifest.executablePath -and $Manifest.executablePath -ine $Snapshot.ExecutablePath)) {
            throw 'Backend executable or JAR differs from the manifest.'
        }
    }
}

function Get-ScicManagedProcess {
    param(
        [Parameter(Mandatory)][ValidateSet('MySql', 'Backend')][string]$Kind,
        [Parameter(Mandatory)]$Manifest,
        [Parameter(Mandatory)][string]$ProjectRoot,
        [switch]$RequireListener
    )
    if ([int]$Manifest.pid -le 0) { throw 'Invalid PID in managed process manifest.' }
    $ManagedProcess = Get-Process -Id $Manifest.pid -ErrorAction SilentlyContinue
    if (-not $ManagedProcess) { return $null }
    # Access StartTime before verification so the Process object retains the original process handle.
    $CapturedStart = $ManagedProcess.StartTime.ToUniversalTime()
    $Snapshot = Get-CimInstance Win32_Process -Filter "ProcessId = $([int]$Manifest.pid)"
    if (-not $Snapshot) { throw 'Managed process disappeared during identity verification; retry safely.' }
    Assert-ScicProcessIdentity -Kind $Kind -Manifest $Manifest -Snapshot $Snapshot -ProjectRoot $ProjectRoot
    if ([math]::Abs((([datetime]$Snapshot.CreationDate).ToUniversalTime() - $CapturedStart).TotalSeconds) -gt 1) {
        throw 'Managed process changed during identity verification.'
    }
    # A legacy Java manifest also needs its dedicated port to distinguish it from the H2 backend.
    if ($RequireListener -or ($Kind -eq 'Backend' -and -not $Manifest.processStartTimeUtc)) {
        $Listener = Get-NetTCPConnection -State Listen -LocalPort $Manifest.port -ErrorAction SilentlyContinue |
            Where-Object { $_.OwningProcess -eq $ManagedProcess.Id }
        if (-not $Listener) { throw 'Managed process does not own the manifest listening port.' }
    }
    return $ManagedProcess
}

function Assert-ScicMySqlSettings {
    param($Manifest, [string]$Database, [string]$User, [int]$Port, [string]$Version)
    if ($Manifest.database -cne $Database -or $Manifest.user -cne $User -or
        [int]$Manifest.port -ne $Port -or $Manifest.version -cne $Version) {
        throw 'Existing MySQL settings differ from the requested database, user, port or version. Use the recorded settings, or stop it explicitly before changing configuration.'
    }
}
