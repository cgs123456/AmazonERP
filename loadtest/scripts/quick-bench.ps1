<#
.SYNOPSIS
    Run the dependency-free AmazonERP gateway benchmark.

.DESCRIPTION
    This is a thin PowerShell wrapper around loadtest/scripts/bench.py.
    It targets the gateway origin directly and uses the gateway's token header.
    It reports P50/P95/P99, TPS, and error rate; it does not pretend that
    curl --netrc/--cookie-jar options are concurrency controls.

.EXAMPLE
    .\quick-bench.ps1 -TargetBaseUrl http://127.0.0.1:10010 `
      -AuthToken $env:AUTH_TOKEN -ShopId 900000000000001000 `
      -Scenario order-list -Concurrency 10 -Requests 100
#>
[CmdletBinding()]
param(
    [string]$TargetBaseUrl = if ($env:TARGET_BASE_URL) { $env:TARGET_BASE_URL } else { "http://127.0.0.1:10010" },
    [string]$AuthToken = if ($env:AUTH_TOKEN) { $env:AUTH_TOKEN } else { $env:TOKEN },
    [string]$ShopId = $env:SHOP_ID,
    [string[]]$Scenario = @("all"),
    [int]$Concurrency = if ($env:CONCURRENCY) { [int]$env:CONCURRENCY } else { 10 },
    [int]$Requests = if ($env:REQUESTS) { [int]$env:REQUESTS } else { 100 },
    [double]$TimeoutSec = if ($env:TIMEOUT_SEC) { [double]$env:TIMEOUT_SEC } else { 30 },
    [int]$Warmup = 3,
    [string]$Output = ""
)

$ErrorActionPreference = "Stop"
$runner = Join-Path $PSScriptRoot "bench.py"
if (-not (Test-Path -LiteralPath $runner)) {
    throw "bench.py not found at $runner"
}
if (-not $AuthToken) {
    throw "AuthToken is required. Obtain a gateway JWT and pass -AuthToken or set AUTH_TOKEN."
}

$pythonCommand = Get-Command python -ErrorAction SilentlyContinue
if ($null -ne $pythonCommand) {
    $pythonExe = $pythonCommand.Source
    $pythonPrefix = @()
} else {
    $pyCommand = Get-Command py -ErrorAction SilentlyContinue
    if ($null -eq $pyCommand) {
        throw "Python 3.11+ is required to run bench.py"
    }
    $pythonExe = $pyCommand.Source
    $pythonPrefix = @("-3")
}

$arguments = @($runner, "--base-url", $TargetBaseUrl, "--token", $AuthToken,
    "--concurrency", $Concurrency.ToString(), "--requests", $Requests.ToString(),
    "--timeout", $TimeoutSec.ToString([Globalization.CultureInfo]::InvariantCulture),
    "--warmup", $Warmup.ToString())
if ($ShopId) {
    $arguments += @("--shop-id", $ShopId)
}
foreach ($item in $Scenario) {
    $arguments += @("--scenario", $item)
}
if ($Output) {
    $arguments += @("--output", $Output)
}

& $pythonExe @($pythonPrefix + $arguments)
exit $LASTEXITCODE
