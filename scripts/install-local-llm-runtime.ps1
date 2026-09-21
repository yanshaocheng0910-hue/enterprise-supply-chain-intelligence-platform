[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$RuntimeRoot = Join-Path $ProjectRoot '.tools\llama.cpp'
$ArchivePath = Join-Path $RuntimeRoot 'llama-b11026-bin-win-vulkan-x64.zip'
$PartsRoot = Join-Path $RuntimeRoot 'parts-b11026'
$DownloadUrl = 'https://github.com/ggml-org/llama.cpp/releases/download/b11026/llama-b11026-bin-win-vulkan-x64.zip'
$ExpectedLength = 31766385L
$ExpectedSha256 = 'CEB83D677CEDBC7EC427F157ADBC93DA82D8FDC336D8B105152568A3BE98BB18'
$ExtractRoot = Join-Path $RuntimeRoot 'b11026-vulkan'

New-Item -ItemType Directory -Force -Path $RuntimeRoot, $PartsRoot | Out-Null
if (Test-Path -LiteralPath $ArchivePath) {
    $ExistingHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $ArchivePath).Hash
    if ($ExistingHash -eq $ExpectedSha256) {
        if (-not (Test-Path -LiteralPath (Join-Path $ExtractRoot 'llama-server.exe'))) {
            Expand-Archive -LiteralPath $ArchivePath -DestinationPath $ExtractRoot -Force
        }
        Write-Host 'Verified llama.cpp runtime is already installed.'
        exit 0
    }
    $PartialPath = Join-Path $PartsRoot 'part000'
    Move-Item -LiteralPath $ArchivePath -Destination $PartialPath -Force
}

$PrefixPath = Join-Path $PartsRoot 'part000'
$StartOffset = if (Test-Path -LiteralPath $PrefixPath) { (Get-Item -LiteralPath $PrefixPath).Length } else { 0L }
if ($StartOffset -ge $ExpectedLength) { throw "Unexpected partial runtime size: $StartOffset" }

$ConnectionCount = 8
$ChunkSize = [math]::Ceiling(($ExpectedLength - $StartOffset) / $ConnectionCount)
$Processes = @()
$Segments = @()
for ($Index = 0; $Index -lt $ConnectionCount; $Index++) {
    $Start = [long]($StartOffset + $Index * $ChunkSize)
    if ($Start -ge $ExpectedLength) { break }
    $End = [long][math]::Min($ExpectedLength - 1, $Start + $ChunkSize - 1)
    $PartPath = Join-Path $PartsRoot ('part{0:D3}' -f ($Index + 1))
    $Arguments = @('-L', '--fail', '--retry', '4', '--retry-delay', '2', '--silent', '--show-error', '-r', ("$Start-$End"), '-o', $PartPath, $DownloadUrl)
    $Process = Start-Process -FilePath 'curl.exe' -ArgumentList $Arguments -WindowStyle Hidden -PassThru
    $Processes += $Process
    $Segments += [pscustomobject]@{ Path = $PartPath; Start = $Start; End = $End; Process = $Process }
}
$Processes | Wait-Process

foreach ($Segment in $Segments) {
    $Segment.Process.Refresh()
    if ($Segment.Process.ExitCode -ne 0) { throw "Runtime segment download failed: $($Segment.Start)-$($Segment.End)" }
    $ExpectedPartLength = $Segment.End - $Segment.Start + 1
    $ActualPartLength = (Get-Item -LiteralPath $Segment.Path).Length
    if ($ActualPartLength -ne $ExpectedPartLength) { throw "Runtime segment length mismatch: $($Segment.Path)" }
}

$Destination = [System.IO.File]::Create($ArchivePath)
try {
    Get-ChildItem -LiteralPath $PartsRoot -Filter 'part*' -File | Sort-Object Name | ForEach-Object {
        $Source = [System.IO.File]::OpenRead($_.FullName)
        try { $Source.CopyTo($Destination) } finally { $Source.Dispose() }
    }
}
finally { $Destination.Dispose() }

$ActualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $ArchivePath).Hash
if ($ActualHash -ne $ExpectedSha256) { throw "llama.cpp SHA256 mismatch: $ActualHash" }
Expand-Archive -LiteralPath $ArchivePath -DestinationPath $ExtractRoot -Force
if (-not (Test-Path -LiteralPath (Join-Path $ExtractRoot 'llama-server.exe'))) { throw 'Verified archive did not contain llama-server.exe.' }
Write-Host ("Installed verified llama.cpp runtime: {0}" -f $ExtractRoot)
