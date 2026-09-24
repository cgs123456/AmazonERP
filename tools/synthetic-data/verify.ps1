#requires -version 5
<#
.SYNOPSIS
    Verify the synthetic dataset toolchain: structure, weak FKs, SYNTHETIC markers, determinism,
    manifest digests and schema-snapshot freshness (spec 7.9).

.EXAMPLE
    ./verify.ps1
    ./verify.ps1 -Tier demo -Dataset out/demo
#>
param(
    [ValidateSet('demo', 'ci', 'staging', 'perf', 'chaos')][string]$Tier = 'ci',
    [int]$Orders = 200,
    [int]$Shops = 1,
    [int]$Marketplaces = 1,
    [string]$Dataset = '',
    [switch]$SkipDeterminism
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$python = if ($env:PYTHON) { $env:PYTHON } else { 'python' }

if (-not $Dataset) {
    $candidate = Join-Path $here "out/$Tier"
    if (Test-Path $candidate) { $Dataset = $candidate }
}

$arguments = @((Join-Path $here 'verify.py'), '--tier', $Tier, '--orders', "$Orders",
               '--shops', "$Shops", '--marketplaces', "$Marketplaces")
if ($Dataset) { $arguments += @('--dataset', $Dataset) }
if ($SkipDeterminism) { $arguments += '--skip-determinism' }

& $python @arguments
exit $LASTEXITCODE