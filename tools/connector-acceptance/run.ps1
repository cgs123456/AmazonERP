#requires -version 5
<#
.SYNOPSIS
    AmazonERP connector acceptance runner (runbook section 3; P0-52c).

.DESCRIPTION
    Thin wrapper over acceptance_runner.py.  It only ever talks to the AmazonERP service under
    test; it never calls Amazon and never holds platform credentials.  The JWT (token header)
    is read from a file by the Python runner and is never printed or written to the record.

.EXAMPLE
    ./run.ps1 -Selftest
    ./run.ps1 -DryRun -ServiceUrl http://127.0.0.1:8096 -ShopId 1001
    ./run.ps1 -ServiceUrl http://127.0.0.1:8096 -Connector spapi -ShopId 1001 `
              -MarketplaceId ATVPDKIKX0DER `
              -Operations orders,inventory,feeds,reports,finances,fees `
              -OutDir .\acceptance-out

.NOTES
    Exit codes of a REAL run: 0 = every criterion met, 1 = ran but has gaps,
    2 = precondition failed (never use 0 for a skipped run).  For -Selftest / -DryRun the
    Python runner treats 0 as "self-test passed", not as an acceptance verdict.

    Unbound arguments are forwarded verbatim to acceptance_runner.py, e.g.
    -ExtraArgs '--error-shop-401','1002' or -ExtraArgs '--quiet'.
#>
param(
    [string]$ServiceUrl,
    [string]$Connector = 'spapi',
    [int]$ShopId = 0,
    [int]$SecondShopId = 0,
    [string]$MarketplaceId,
    [string]$Operations,
    [string]$OutDir,
    [string]$AuthTokenFile,
    [string]$Attest,
    [string]$ConfigFile,
    [string]$ReportType,
    [int]$ReportPollAttempts = 0,
    [double]$ReportPollInterval = 0,
    [int]$AbsentShopId = 0,
    [int]$ErrorShop401 = 0,
    [int]$ErrorShop403 = 0,
    [int]$ErrorShop429 = 0,
    [string]$ConnectorsPath,
    [double]$Timeout = 0,
    [int]$BurstMax = 0,
    [switch]$DryRun,
    [switch]$Selftest,
    [switch]$Quiet,
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$ExtraArgs
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$python = if ($env:PYTHON) { $env:PYTHON } else { 'python' }

$arguments = @((Join-Path $here 'acceptance_runner.py'))
if ($ServiceUrl) { $arguments += @('--service-url', $ServiceUrl) }
if ($Connector) { $arguments += @('--connector', $Connector) }
if ($ShopId -gt 0) { $arguments += @('--shop-id', "$ShopId") }
if ($SecondShopId -gt 0) { $arguments += @('--second-shop-id', "$SecondShopId") }
if ($MarketplaceId) { $arguments += @('--marketplace-id', $MarketplaceId) }
if ($Operations) { $arguments += @('--operations', $Operations) }
if ($OutDir) { $arguments += @('--out-dir', $OutDir) }
if ($AuthTokenFile) { $arguments += @('--auth-token-file', $AuthTokenFile) }
if ($Attest) { $arguments += @('--attest', $Attest) }
if ($ConfigFile) { $arguments += @('--config-file', $ConfigFile) }
if ($ReportType) { $arguments += @('--report-type', $ReportType) }
if ($ReportPollAttempts -gt 0) { $arguments += @('--report-poll-attempts', "$ReportPollAttempts") }
if ($ReportPollInterval -gt 0) { $arguments += @('--report-poll-interval', "$ReportPollInterval") }
if ($AbsentShopId -gt 0) { $arguments += @('--absent-shop-id', "$AbsentShopId") }
if ($ErrorShop401 -gt 0) { $arguments += @('--error-shop-401', "$ErrorShop401") }
if ($ErrorShop403 -gt 0) { $arguments += @('--error-shop-403', "$ErrorShop403") }
if ($ErrorShop429 -gt 0) { $arguments += @('--error-shop-429', "$ErrorShop429") }
if ($ConnectorsPath) { $arguments += @('--connectors-path', $ConnectorsPath) }
if ($Timeout -gt 0) { $arguments += @('--timeout', "$Timeout") }
if ($BurstMax -gt 0) { $arguments += @('--burst-max', "$BurstMax") }
if ($DryRun) { $arguments += '--dry-run' }
if ($Selftest) { $arguments += '--selftest' }
if ($Quiet) { $arguments += '--quiet' }
if ($ExtraArgs) { $arguments += $ExtraArgs }

& $python @arguments
exit $LASTEXITCODE