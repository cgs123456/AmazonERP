$ErrorActionPreference = 'Continue'
# 只杀本项目实例（按数据目录 .mysql-data 识别），不影响正式 MySQL80 服务
Get-CimInstance Win32_Process -Filter "Name='mysqld.exe'" |
  Where-Object { $_.CommandLine -like '*.mysql-data*' } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Start-Sleep -Seconds 3

$dataDir = 'D:\Desktop\amazon-erp\.mysql-data'
Remove-Item "$dataDir\undo_001","$dataDir\undo_002","$dataDir\undo_1_trunc.log" -Force -ErrorAction SilentlyContinue

$mysqld = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqld.exe'
$args = @(
  '--no-defaults','--port=3307',
  "--datadir=D:/Desktop/amazon-erp/.mysql-data",
  "--innodb-undo-directory=D:/Desktop/amazon-erp/.mysql-data",
  '--console'
)
Start-Process -FilePath $mysqld -ArgumentList $args `
  -RedirectStandardOutput 'D:\Desktop\amazon-erp\.mysqld-out.log' `
  -RedirectStandardError 'D:\Desktop\amazon-erp\.mysqld-err.log' `
  -WorkingDirectory $dataDir -WindowStyle Hidden

$client = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$ready = $false
for ($i = 0; $i -lt 20; $i++) {
  Start-Sleep -Seconds 2
  $out = & $client -h127.0.0.1 -P3307 -uroot -e 'SELECT VERSION();' 2>&1
  if ($LASTEXITCODE -eq 0) { $ready = $true; break }
}
if ($ready) { Write-Output ("MYSQL_READY: " + ($out -join ' ').Trim()) }
else {
  Write-Output "MYSQL_FAILED"
  Get-Content 'D:\Desktop\amazon-erp\.mysqld-err.log' -Tail 10
}
