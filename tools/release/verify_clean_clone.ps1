param(
  [string]$Repo = (Resolve-Path .).Path,
  [string]$Ref = 'HEAD',
  [string]$WorkRoot = (Join-Path $env:TEMP 'amazonerp-phase0-verify')
)
$evidence = @{ timestamp = (Get-Date -Format o); repo = $Repo; ref = $Ref; checks = @() }
function Add-Check([string]$name, [int]$exitCode, [string]$detail) {
  $script:evidence.checks += @{ name = $name; exitCode = $exitCode; detail = $detail; status = if ($exitCode -eq 0) { 'VERIFIED' } else { 'NOT VERIFIED' } }
}
$cloneDir = Join-Path $WorkRoot ('clone-' + [guid]::NewGuid().ToString('N').Substring(0,8))
New-Item -ItemType Directory -Force -Path $WorkRoot | Out-Null
cmd /c "git clone --no-hardlinks --local `"$Repo`" `"$cloneDir`" 2>&1"
Add-Check 'git-clone' $LASTEXITCODE 'clone done'
Push-Location $cloneDir
cmd /c "git checkout $Ref 2>&1"
Add-Check 'git-checkout' $LASTEXITCODE "ref=$Ref"
$porcelain = git status --porcelain
Add-Check 'clean-tree' $(if ($porcelain) { 1 } else { 0 }) "porcelain=$($porcelain.Count)"
python -m unittest tools.release.test_repository_hygiene tools.release.test_release_manifest tools.release.test_services_manifest tools.release.test_rollback_drill -v 2>&1
Add-Check 'python-release-tests' $LASTEXITCODE 'unittest'
$mvn = Get-Command mvn -ErrorAction SilentlyContinue
if ($mvn) { cmd /c "mvn -B -ntp test -fae 2>&1"; Add-Check 'maven-test' $LASTEXITCODE 'mvn test -fae' } else { Add-Check 'maven-test' -1 'mvn NOT FOUND' }
$node = Get-Command node -ErrorAction SilentlyContinue
if ($node) { Push-Location amz-frontend; cmd /c "npm ci 2>&1"; Add-Check 'npm-ci' $LASTEXITCODE 'npm ci'; cmd /c "npm run build 2>&1"; Add-Check 'frontend-build' $LASTEXITCODE 'npm run build'; Pop-Location } else { Add-Check 'frontend-build' -1 'node NOT FOUND' }
Pop-Location
$evidencePath = Join-Path $WorkRoot 'evidence.json'
$evidence | ConvertTo-Json -Depth 5 | Set-Content $evidencePath -Encoding UTF8
Write-Host "Evidence: $evidencePath"
Write-Host "Clone dir preserved: $cloneDir"
$failed = $evidence.checks | Where-Object { $_.exitCode -ne 0 }
if ($failed) { exit 1 }
$resolvedWork = (Resolve-Path $WorkRoot).Path
if ($resolvedWork.StartsWith($env:TEMP)) { Remove-Item -Recurse -Force $resolvedWork }
