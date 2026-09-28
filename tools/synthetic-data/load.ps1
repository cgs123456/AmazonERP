#requires -version 5
<#
.SYNOPSIS
    Load a generated synthetic dataset into MySQL (spec 7.9 / demo data runbook).

.DESCRIPTION
    Feeds every per-table SQL file of a dataset to the mysql client with the right
    --database, in manifest order.  Compared with `mysql < load-all.sql` this does not
    depend on `SOURCE` relative paths and works against a remote host or a docker
    container.

    The dataset must already exist: `python generate.py --tier demo [--reset]`.
    Run `purge.py --emit` first if you also want registry.sql / cleanup.sql.

.EXAMPLE
    ./load.ps1 -Tier demo -Container amz-mysql -User root -Password secret
    ./load.ps1 -Tier ci -Server 127.0.0.1 -User root -Password secret -DryRun
    ./load.ps1 -Tier demo -Server db.internal -User app -Password $env:MYSQL_PW -NoRegistry
#>
param(
    [ValidateSet('demo','ci','staging','perf','chaos')][string]$Tier = 'demo',
    [string]$Dataset,
    [string]$Server = '127.0.0.1',
    [int]$Port = 3306,
    [string]$User = 'root',
    [string]$Password = '',
    [string]$Container = '',
    [string]$MysqlPath = 'mysql',
    [switch]$NoRegistry,
    [switch]$AllowTruncate,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $Dataset) { $Dataset = Join-Path $here "out/$Tier" }
if (-not (Test-Path $Dataset)) { throw "dataset not found: $Dataset (run generate.py --tier $Tier first)" }

$manifestPath = Join-Path $Dataset 'manifest.json'
if (-not (Test-Path $manifestPath)) { throw "manifest not found: $manifestPath" }
$manifest = Get-Content $manifestPath -Raw | ConvertFrom-Json
$python = if ($env:PYTHON) { $env:PYTHON } else { 'python' }

function Invoke-SqlFile {
    param([string]$Database, [string]$FilePath, [string]$Label)

    $args = @('--host', $Server, '--port', "$Port", '--user', $User,
              '--default-character-set=utf8mb4', '--database', $Database)
    if ($Container) {
        $exe = 'docker'
        $args = @('exec', '-i', $Container, 'mysql') + $args
    } else {
        $exe = $MysqlPath
    }
    if ($DryRun) {
        Write-Host "[dry-run] $exe $($args -join ' ') < $FilePath"
        return
    }
    if ($Password) { $env:MYSQL_PWD = $Password }
    try {
        $proc = Start-Process -FilePath $exe -ArgumentList $args `
                              -RedirectStandardInput $FilePath `
                              -RedirectStandardOutput (Join-Path $Dataset '_load.out') `
                              -RedirectStandardError (Join-Path $Dataset '_load.err') `
                              -NoNewWindow -Wait -PassThru
        if ($proc.ExitCode -ne 0) {
            $err = Get-Content (Join-Path $Dataset '_load.err') -Raw
            throw "$Label failed (exit $($proc.ExitCode)): $err"
        }
    } finally {
        if ($Password) { Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue }
    }
}

Write-Host "[load] dataset=$Dataset tier=$($manifest.tier) rows=$($manifest.totals.rows) tables=$($manifest.tables.Count)"

if (-not $NoRegistry) {
    $registry = Join-Path $Dataset 'registry.sql'
    if (-not (Test-Path $registry)) {
        Write-Host "[load] registry.sql missing - generating it (purge.py --registry)"
        & $python (Join-Path $here 'purge.py') --dataset $Dataset --emit --registry --quiet
        if ($LASTEXITCODE -ne 0) { throw 'purge.py failed' }
    }
    Write-Host "[load] registry: $registry"
    Invoke-SqlFile -Database 'amz_ops' -FilePath $registry -Label 'registry'
}

if ($manifest.truncate_first) {
    if (-not $AllowTruncate) {
        throw "this dataset was generated with --truncate-first (it TRUNCATEs every target table). Re-run with -AllowTruncate if the target is a disposable test database, or regenerate without --truncate-first."
    }
    $truncateSql = Join-Path $Dataset '_truncate.sql'
    & $python (Join-Path $here 'purge.py') --dataset $Dataset --mode truncate --allow-destructive `
              --out $truncateSql --emit --quiet
    if ($LASTEXITCODE -ne 0) { throw 'purge.py (truncate) failed' }
    Write-Host "[load] truncate: $truncateSql"
    Invoke-SqlFile -Database 'amz_ops' -FilePath $truncateSql -Label 'truncate'
}

foreach ($table in $manifest.tables) {
    if (-not $table.sql) { continue }
    $path = Join-Path $Dataset $table.sql
    if (-not (Test-Path $path)) { throw "missing dataset file: $path" }
    Write-Host "[load] $($table.database).$($table.name) ($($table.rows) rows)"
    Invoke-SqlFile -Database $table.database -FilePath $path -Label "$($table.database).$($table.name)"
}

if (-not $DryRun) {
    Remove-Item (Join-Path $Dataset '_load.out') -ErrorAction SilentlyContinue
    Remove-Item (Join-Path $Dataset '_load.err') -ErrorAction SilentlyContinue
}
Write-Host "[load] done. Verify with: SELECT dataset_id, seed, tier, loaded_at, rows, is_demo FROM amz_ops.amz_synthetic_dataset_registry;"
