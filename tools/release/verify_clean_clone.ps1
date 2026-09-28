param(
  [string]$Repo = (Resolve-Path .).Path,
  [string]$Ref = 'HEAD',
  [string]$WorkRoot = (Join-Path $env:TEMP 'amazonerp-phase0-verify')
)
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp'
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
$env:PATH = "C:\Users\Administrator\.cache\codex-tools\apache-maven-3.9.11\bin;" + $env:PATH; $mvn = Get-Command mvn -ErrorAction SilentlyContinue
if ($mvn) { cmd /c "mvn -B -ntp test -fae 2>&1"; Add-Check 'maven-test' $LASTEXITCODE 'mvn test -fae' } else { Add-Check 'maven-test' -1 'mvn NOT FOUND' }
$node = Get-Command node -ErrorAction SilentlyContinue
if ($node) { Push-Location amz-frontend; cmd /c "npm ci 2>&1"; Add-Check 'npm-ci' $LASTEXITCODE 'npm ci'; cmd /c "npm run build 2>&1"; Add-Check 'frontend-build' $LASTEXITCODE 'npm run build'; Pop-Location } else { Add-Check 'frontend-build' -1 'node NOT FOUND' }
Pop-Location
$evidencePath = Join-Path $WorkRoot 'evidence.json'
$evidence | ConvertTo-Json -Depth 5 | Set-Content $evidencePath -Encoding UTF8
Write-Host "Evidence: $evidencePath"
Write-Host "Clone dir: $cloneDir (cleaned on success, preserved on failure)"
$failed = $evidence.checks | Where-Object { $_.exitCode -ne 0 }
if ($failed) { exit 1 }
# Success: keep evidence.json, clean only clone work dirs (failures preserve everything for debugging).
$resolvedWork = (Resolve-Path $WorkRoot).Path
if ($resolvedWork.StartsWith($env:TEMP)) { Get-ChildItem $resolvedWork -Directory -Filter 'clone-*' | Remove-Item -Recurse -Force }


