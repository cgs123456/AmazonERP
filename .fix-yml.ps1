$ErrorActionPreference = 'Continue'
$targets = Get-ChildItem 'D:\Desktop\amazon-erp\amz-service','D:\Desktop\amazon-erp\amz-gateway' -Recurse -Filter 'application*.yml' |
  Where-Object { $_.FullName -notmatch '\\target\\' }

foreach ($f in $targets) {
  $lines = [IO.File]::ReadAllLines($f.FullName)
  $changed = $false
  for ($i = 0; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -like '*console: "%d*%msg%n*') {
      $idx = $lines[$i].LastIndexOf('%msg%n')
      $fixed = $lines[$i].Substring(0, $idx + '%msg%n'.Length) + '"'
      if ($lines[$i] -ne $fixed) {
        Write-Output ("FIX " + $f.FullName + " line " + ($i+1))
        Write-Output ("  OLD: " + $lines[$i])
        Write-Output ("  NEW: " + $fixed)
        $lines[$i] = $fixed
        if (($i + 1 -ge $lines.Count) -or (-not $lines[$i+1].StartsWith('---'))) {
          $newList = @()
          if ($i -gt 0) { $newList += $lines[0..$i] }
          $newList += '---'
          if ($i + 1 -lt $lines.Count) { $newList += $lines[($i+1)..($lines.Count-1)] }
          $lines = $newList
        }
        $changed = $true
      }
    }
  }
  if ($changed) {
    [IO.File]::WriteAllLines($f.FullName, $lines)
  }
}
Write-Output 'YML_CONSOLE_FIX_DONE'
