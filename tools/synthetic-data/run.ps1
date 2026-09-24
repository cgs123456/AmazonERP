#requires -version 5
<#
.SYNOPSIS
    Generate the deterministic synthetic AmazonERP dataset (spec 7.9).

.EXAMPLE
    ./run.ps1 -Tier ci
    ./run.ps1 -Tier demo -Orders 2000 -Reset
    ./run.ps1 -Tier ci -TruncateFirst -Out out/ci-reload

.NOTES
    No API credentials and no network access are required.  The output is byte-identical for a
    given (dataset_id, seed, tier, overrides); never load it into production.

    -TruncateFirst emits TRUNCATE TABLE statements (global reverse load order, each preceded by
    USE <db>) so load-all.sql can be applied repeatedly and stay idempotent.  It deletes rows
    in the target databases: never point it at a production server.
#>
param(
    [ValidateSet('demo', 'ci', 'staging', 'perf', 'chaos')][string]$Tier = 'ci',
    [int]$Orders = 0,
    [int]$Shops = 0,
    [int]$Marketplaces = 0,
    [switch]$IncludeConditional,
    [switch]$Reset,
    [switch]$TruncateFirst,
    [string]$Out
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$python = if ($env:PYTHON) { $env:PYTHON } else { 'python' }

$arguments = @((Join-Path $here 'generate.py'), '--tier', $Tier)
if ($Orders -gt 0) { $arguments += @('--orders', "$Orders") }
if ($Shops -gt 0) { $arguments += @('--shops', "$Shops") }
if ($Marketplaces -gt 0) { $arguments += @('--marketplaces', "$Marketplaces") }
if ($IncludeConditional) { $arguments += '--include-conditional' }
if ($Reset) { $arguments += '--reset' }
if ($TruncateFirst) { $arguments += '--truncate-first' }
if ($Out) { $arguments += @('--out', $Out) }

& $python @arguments
exit $LASTEXITCODE