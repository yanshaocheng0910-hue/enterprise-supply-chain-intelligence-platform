[CmdletBinding()]
param(
    [switch]$StaticOnly
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$Failures = [System.Collections.Generic.List[string]]::new()

function Require-Path([string]$RelativePath) {
    if (-not (Test-Path -LiteralPath (Join-Path $ProjectRoot $RelativePath))) {
        $Failures.Add("Missing path: $RelativePath")
    }
}

$RequiredPaths = @(
    '.env.example', 'docker-compose.yml',
    'scripts/start-dev.ps1', 'scripts/stop-dev.ps1', 'scripts/verify.ps1',
    'backend/Dockerfile', 'ai-service/Dockerfile', 'frontend/Dockerfile', 'frontend/nginx.conf',
    'samples/import/suppliers.csv', 'samples/import/materials.csv', 'samples/import/inventory.csv', 'samples/import/demand_history.csv',
    'docs/01-requirements/REQUIREMENTS_V1.0.md',
    'docs/02-design/ARCHITECTURE_AND_API_V1.0.md', 'docs/02-design/DATABASE_DICTIONARY.md',
    'docs/03-api/API_CONTRACT.md',
    'docs/04-testing/TEST_PLAN_AND_CASES.md',
    'docs/05-experiments/EVIDENCE_INDEX.md',
    'docs/06-thesis/THESIS_EVIDENCE_MAP.md',
    'docs/08-operations/LOCAL_RUNBOOK.md', 'docs/08-operations/USER_MANUAL_DRAFT.md'
)
$RequiredPaths | ForEach-Object { Require-Path $_ }

foreach ($ScriptName in @('start-dev.ps1', 'stop-dev.ps1', 'verify.ps1')) {
    $ScriptPath = Join-Path $ProjectRoot ("scripts/{0}" -f $ScriptName)
    if (Test-Path -LiteralPath $ScriptPath) {
        $Tokens = $null
        $ParseErrors = $null
        [System.Management.Automation.Language.Parser]::ParseFile($ScriptPath, [ref]$Tokens, [ref]$ParseErrors) | Out-Null
        if ($ParseErrors.Count -gt 0) { $Failures.Add("PowerShell syntax error: scripts/$ScriptName") }
    }
}

$StartText = Get-Content -LiteralPath (Join-Path $ProjectRoot 'scripts/start-dev.ps1') -Raw -ErrorAction SilentlyContinue
if ($StartText -and $StartText -notmatch '-WindowStyle\s+Hidden') { $Failures.Add('start-dev.ps1 does not explicitly use -WindowStyle Hidden') }

$PathCheckFiles = @('.env.example', 'docker-compose.yml')
foreach ($RelativePath in $PathCheckFiles) {
    $Path = Join-Path $ProjectRoot $RelativePath
    if (Test-Path -LiteralPath $Path) {
        $Content = Get-Content -LiteralPath $Path -Raw
        if ($Content -match '(?i)(C:\\|C:/)') { $Failures.Add("Project data or cache points to C drive: $RelativePath") }
    }
}

$CsvHeaders = @{
    'samples/import/suppliers.csv' = 'supplier_code,supplier_name,contact_name,contact_phone,level_code'
    'samples/import/materials.csv' = 'material_code,material_name,category,unit,safety_stock,min_order_qty,pack_size,lead_time_days,standard_price'
    'samples/import/inventory.csv' = 'warehouse_code,material_code,on_hand_qty,reserved_qty,in_transit_qty'
    'samples/import/demand_history.csv' = 'material_code,demand_date,quantity'
}
foreach ($Entry in $CsvHeaders.GetEnumerator()) {
    $Path = Join-Path $ProjectRoot $Entry.Key
    if (Test-Path -LiteralPath $Path) {
        $Header = (Get-Content -LiteralPath $Path -Encoding UTF8 -TotalCount 1).Trim()
        if ($Header -ne $Entry.Value) { $Failures.Add("CSV header does not match backend contract: $($Entry.Key)") }
    }
}

if (-not $StaticOnly) {
    $Checks = @(
        @{ name = 'AI'; url = 'http://127.0.0.1:8001/health' },
        @{ name = 'Backend'; url = 'http://127.0.0.1:8080/actuator/health' },
        @{ name = 'Frontend'; url = 'http://127.0.0.1:5173/' }
    )
    foreach ($Check in $Checks) {
        try {
            $Response = Invoke-WebRequest -Uri $Check.url -UseBasicParsing -TimeoutSec 3
            if ($Response.StatusCode -ge 200 -and $Response.StatusCode -lt 400) { Write-Host ("{0}: healthy ({1})" -f $Check.name, $Response.StatusCode) }
            else { $Failures.Add("$($Check.name) returned HTTP $($Response.StatusCode)") }
        }
        catch { $Failures.Add("$($Check.name) did not respond: $($Check.url)") }
    }
}

if ($Failures.Count -gt 0) {
    Write-Host 'Verification failed:'
    $Failures | ForEach-Object { Write-Host ("- $_") }
    exit 1
}
Write-Host ($(if ($StaticOnly) { 'Static verification passed (Docker and running services not required).' } else { 'Static paths and local health checks passed.' }))
