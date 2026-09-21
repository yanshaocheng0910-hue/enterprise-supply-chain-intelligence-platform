[CmdletBinding()]
param(
    [string]$Version = '8.4.11'
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$InstallRoot = Join-Path $ProjectRoot '.tools\mysql'
$ArchiveName = "mysql-$Version-winx64.zip"
$ArchivePath = Join-Path $InstallRoot $ArchiveName
$RuntimeRoot = Join-Path $InstallRoot "mysql-$Version-winx64"
$DownloadUrl = "https://cdn.mysql.com/Downloads/MySQL-8.4/$ArchiveName"
$ExpectedSha256 = if ($Version -eq '8.4.11') { 'A492371D687D2BAB088B0062581144A0044B8964BAEFDF4FAA579292B423D25C' } else { $null }

New-Item -ItemType Directory -Force -Path $InstallRoot | Out-Null

if (-not (Test-Path -LiteralPath $ArchivePath)) {
    Write-Host "Downloading MySQL $Version Windows ZIP to D drive..."
    & curl.exe -L --fail --retry 3 --output $ArchivePath $DownloadUrl
    if ($LASTEXITCODE -ne 0) { throw "MySQL download failed: $DownloadUrl" }
}

$ActualSha256 = (Get-FileHash -LiteralPath $ArchivePath -Algorithm SHA256).Hash
if ($ExpectedSha256 -and $ActualSha256 -ne $ExpectedSha256) {
    throw "MySQL archive checksum mismatch. Expected $ExpectedSha256, got $ActualSha256."
}

if (-not (Test-Path -LiteralPath (Join-Path $RuntimeRoot 'bin\mysqld.exe'))) {
    Expand-Archive -LiteralPath $ArchivePath -DestinationPath $InstallRoot
}

$Server = Join-Path $RuntimeRoot 'bin\mysqld.exe'
if (-not (Test-Path -LiteralPath $Server)) { throw "MySQL server was not extracted: $Server" }

Write-Host "MySQL runtime ready: $RuntimeRoot"
Write-Host "SHA256: $ActualSha256"
