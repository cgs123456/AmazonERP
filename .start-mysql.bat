@echo off
rem 清理残留 mysqld（只杀本项目的实例：按数据目录 .mysql-data 识别，不影响正式 MySQL80 服务）
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='mysqld.exe'\" | Where-Object { $_.CommandLine -like '*.mysql-data*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }"
timeout /t 3 /nobreak >nul

set DATADIR=D:\Desktop\amazon-erp\.mysql-data
del /q "%DATADIR%\undo_001" "%DATADIR%\undo_002" "%DATADIR%\undo_1_trunc.log" >nul 2>&1

rem 正常模式启动（initialize-insecure 的 root 为空密码），TCP 3307
start "amz-mysql" /min cmd /c ""C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqld.exe" --no-defaults --port=3307 --datadir=D:/Desktop/amazon-erp/.mysql-data --innodb-undo-directory=D:/Desktop/amazon-erp/.mysql-data --console > "D:\Desktop\amazon-erp\.mysqld-err.log" 2>&1"

set CLIENT=C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe
set TRIES=0
:waitloop
timeout /t 2 /nobreak >nul
"%CLIENT%" -h127.0.0.1 -P3307 -uroot -e "SELECT VERSION();" >nul 2>&1
if %errorlevel%==0 goto ready
set /a TRIES+=1
if %TRIES% lss 20 goto waitloop
echo MYSQL_FAILED
type "D:\Desktop\amazon-erp\.mysqld-err.log"
goto :eof
:ready
echo MYSQL_READY
"%CLIENT%" -h127.0.0.1 -P3307 -uroot -e "SELECT VERSION();"
