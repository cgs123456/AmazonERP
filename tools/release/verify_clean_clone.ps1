param(
  [string]$Repo = (Resolve-Path .).Path,
  [string]$Ref = 'HEAD',
  [string]$WorkRoot = (Join-Path $env:TEMP 'amazonerp-phase0-verify')
)

$ErrorActionPreference = 'Stop'
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp'

$evidence = [ordered]@{
  timestamp = (Get-Date -Format o)
  repo = $Repo
  ref = $Ref
  checks = [System.Collections.Generic.List[object]]::new()
}

function Add-Check {
  param(
    [string]$Name,
    [int]$ExitCode,
    [string]$Detail,
    [bool]$Required = $true
  )
  $evidence.checks.Add([ordered]@{
    name = $Name
    exitCode = $ExitCode
    detail = $Detail
    required = $Required
    status = if ($ExitCode -eq 0) { 'VERIFIED' } else { 'NOT VERIFIED' }
  })
}

function Write-Evidence {
  $evidencePath = Join-Path $WorkRoot 'evidence.json'
  $evidence | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $evidencePath -Encoding UTF8
  Write-Host "Evidence: $evidencePath"
}

function Invoke-Logged {
  param(
    [string]$Name,
    [scriptblock]$Command,
    [bool]$Required = $true
  )
  $logPath = Join-Path $WorkRoot ("$Name.log")
  $previousErrorActionPreference = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try {
    & $Command *>&1 | Out-File -LiteralPath $logPath -Encoding utf8
    $exitCode = $LASTEXITCODE
  } finally {
    $ErrorActionPreference = $previousErrorActionPreference
  }
  if ($null -eq $exitCode) { $exitCode = 0 }
  Add-Check -Name $Name -ExitCode $exitCode -Detail "log=$logPath" -Required $Required
  Get-Content -LiteralPath $logPath -Tail 20 | ForEach-Object { Write-Host $_ }
  return $exitCode
}

New-Item -ItemType Directory -Force -Path $WorkRoot | Out-Null
$cloneDir = Join-Path $WorkRoot ('clone-' + [guid]::NewGuid().ToString('N').Substring(0, 8))

$repoStatusBefore = @(& git -C $Repo status --porcelain=v2 2>&1)
$cloneExit = Invoke-Logged -Name 'git-clone' -Command { & git clone --config core.autocrlf=false --config core.eol=lf --no-hardlinks --local $Repo $cloneDir }
if ($cloneExit -ne 0) {
  Write-Evidence
  Write-Host "Clone dir: $cloneDir (preserved on failure)"
  exit 1
}

$checkoutExit = Invoke-Logged -Name 'git-checkout' -Command { & git -C $cloneDir checkout $Ref }
if ($checkoutExit -ne 0) {
  Write-Evidence
  Write-Host "Clone dir: $cloneDir (preserved on failure)"
  exit 1
}

$porcelain = @(& git -C $cloneDir status --porcelain)
Add-Check -Name 'clean-tree' -ExitCode $(if ($porcelain.Count -eq 0) { 0 } else { 1 }) -Detail "porcelain entries=$($porcelain.Count)"

Push-Location $cloneDir
try {
  Invoke-Logged -Name 'git-diff-check' -Command { & git diff --check } | Out-Null
} finally {
  Pop-Location
}

$python = Get-Command python -ErrorAction SilentlyContinue
if ($python) {
  Push-Location $cloneDir
  try {
    Invoke-Logged -Name 'python-release-tests' -Command { & python -m unittest tools.release.test_repository_hygiene tools.release.test_release_manifest tools.release.test_services_manifest tools.release.test_release_workflow tools.release.test_rollback_drill tools.release.test_cve_gate tools.release.test_verify_clean_clone -v } | Out-Null
    Invoke-Logged -Name 'hygiene-tracked' -Command { & python tools/release/repository_hygiene.py --root . } | Out-Null
    Invoke-Logged -Name 'hygiene-untracked' -Command { & python tools/release/repository_hygiene.py --root . --include-untracked } | Out-Null
  } finally {
    Pop-Location
  }
} else {
  Add-Check -Name 'python-release-tests' -ExitCode -1 -Detail 'python NOT FOUND' -Required $true
  Add-Check -Name 'hygiene-tracked' -ExitCode -1 -Detail 'python NOT FOUND' -Required $true
  Add-Check -Name 'hygiene-untracked' -ExitCode -1 -Detail 'python NOT FOUND' -Required $true
}

$mavenPath = $null
$maven = Get-Command mvn -ErrorAction SilentlyContinue
if ($maven) {
  $mavenPath = $maven.Source
} else {
  $fallbackMaven = 'C:\Users\Administrator\.cache\codex-tools\apache-maven-3.9.11\bin\mvn.cmd'
  if (Test-Path -LiteralPath $fallbackMaven) { $mavenPath = $fallbackMaven }
}
if ($mavenPath) {
  Invoke-Logged -Name 'maven-test' -Command { & $mavenPath -B -ntp test -fae } | Out-Null
} else {
  Add-Check -Name 'maven-test' -ExitCode -1 -Detail 'mvn NOT FOUND' -Required $true
}

$node = Get-Command node -ErrorAction SilentlyContinue
$npm = Get-Command npm -ErrorAction SilentlyContinue
$npx = Get-Command npx -ErrorAction SilentlyContinue
if ($node -and $npm -and $npx) {
  Push-Location (Join-Path $cloneDir 'amz-frontend')
  try {
    Invoke-Logged -Name 'npm-ci' -Command { & npm ci } | Out-Null
    Invoke-Logged -Name 'frontend-typecheck' -Command { & npx vue-tsc --noEmit } | Out-Null
    Invoke-Logged -Name 'frontend-tests' -Command { & npm run test:run } | Out-Null
    Invoke-Logged -Name 'frontend-build' -Command { & npm run build } | Out-Null
  } finally {
    Pop-Location
  }
} else {
  Add-Check -Name 'frontend-toolchain' -ExitCode -1 -Detail 'node/npm/npx NOT FOUND' -Required $true
}

Push-Location $cloneDir
try {
  $commit = (& git rev-parse HEAD).Trim()
  $manifestA = Join-Path $WorkRoot 'release-manifest-a.json'
  $manifestB = Join-Path $WorkRoot 'release-manifest-b.json'
  $imageRef = "ghcr.io/clean-clone/amazonerp:$commit"
  $imageDigest = 'sha256:' + ('0' * 64)
  $manifestBuildA = Invoke-Logged -Name 'release-manifest-build-a' -Command { & python tools/release/release_manifest.py build --root . --commit $commit --version '0.0.0-ci' --image-ref $imageRef --image-digest $imageDigest --output $manifestA }
  $manifestBuildB = Invoke-Logged -Name 'release-manifest-build-b' -Command { & python tools/release/release_manifest.py build --root . --commit $commit --version '0.0.0-ci' --image-ref $imageRef --image-digest $imageDigest --output $manifestB }
  if ($manifestBuildA -eq 0 -and $manifestBuildB -eq 0 -and (Test-Path -LiteralPath $manifestA) -and (Test-Path -LiteralPath $manifestB)) {
    $hashA = (Get-FileHash -LiteralPath $manifestA -Algorithm SHA256).Hash
    $hashB = (Get-FileHash -LiteralPath $manifestB -Algorithm SHA256).Hash
    Add-Check -Name 'release-manifest-determinism' -ExitCode $(if ($hashA -eq $hashB) { 0 } else { 1 }) -Detail "sha256=$hashA"
  } else {
    Add-Check -Name 'release-manifest-determinism' -ExitCode -1 -Detail 'manifest build failed or output missing' -Required $true
  }
  Invoke-Logged -Name 'release-manifest-verify' -Command { & python tools/release/release_manifest.py verify --manifest $manifestA --root . } | Out-Null
} finally {
  Pop-Location
}

$docker = Get-Command docker -ErrorAction SilentlyContinue
if ($docker) {
  Push-Location $cloneDir
  try {
    Invoke-Logged -Name 'docker-bake-print' -Command { & docker buildx bake --print -f docker-bake.hcl } | Out-Null
  } finally {
    Pop-Location
  }
} else {
  Add-Check -Name 'docker-bake-print' -ExitCode -1 -Detail 'docker NOT FOUND' -Required $false
}

$repoStatusAfter = @(& git -C $Repo status --porcelain=v2 2>&1)
$sourceUnchanged = (($repoStatusBefore -join "`n") -eq ($repoStatusAfter -join "`n"))
Add-Check -Name 'source-repo-unchanged' -ExitCode $(if ($sourceUnchanged) { 0 } else { 1 }) -Detail "before=$($repoStatusBefore.Count) after=$($repoStatusAfter.Count) entries"

Write-Evidence
$failedRequired = @($evidence.checks | Where-Object { $_.required -and $_.exitCode -ne 0 })
$notVerified = @($evidence.checks | Where-Object { $_.status -eq 'NOT VERIFIED' })
Write-Host "Checks: $($evidence.checks.Count); required failures: $($failedRequired.Count); not verified: $($notVerified.Count)"

if ($failedRequired.Count -gt 0) {
  Write-Host "Clone dir: $cloneDir (preserved on failure)"
  exit 1
}

Write-Host "Clone dir: $cloneDir (cleaned on success)"
$resolvedWork = [System.IO.Path]::GetFullPath($WorkRoot).TrimEnd('\')
$resolvedTemp = [System.IO.Path]::GetFullPath($env:TEMP).TrimEnd('\') + '\'
if ($resolvedWork.StartsWith($resolvedTemp, [System.StringComparison]::OrdinalIgnoreCase)) {
  Get-ChildItem -LiteralPath $resolvedWork -Directory -Filter 'clone-*' | ForEach-Object {
    $resolvedClone = [System.IO.Path]::GetFullPath($_.FullName)
    if ($resolvedClone.StartsWith($resolvedTemp, [System.StringComparison]::OrdinalIgnoreCase)) {
      Remove-Item -LiteralPath $resolvedClone -Recurse -Force
    }
  }
}
exit 0
