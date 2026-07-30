# =====================================================================
# 读写分离（Dynamic DataSource Routing）验证脚本
# =====================================================================
# 验证 amz-service-order / amz-service-spapi 的 master/slave 数据源
# 是否正确启用，以及 @DS("slave") 注解是否将读请求路由到从库。
#
# 用法：
#   .\verify-readwrite-split.ps1                        # 检查本地默认日志
#   .\verify-readwrite-split.ps1 -LogFile D:\app.log    # 指定日志文件
#   .\verify-readwrite-split.ps1 -CheckSql              # 额外通过 MySQL general_log 验证
#
# Linux 等价命令（在 bash 中执行）：
#   grep "\[DataSource\] 动态数据源已启用" /var/log/amz-service-order.log
#   grep "slave" /var/log/amz-service-order.log | head
# =====================================================================
param(
    [string]$LogFile = "",
    [switch]$CheckSql = $false,
    [string]$MysqlHost = "127.0.0.1",
    [int]$MysqlPort = 3307,
    [string]$MysqlUser = "root",
    [string]$MysqlPassword = ""
)

$ErrorActionPreference = "Continue"
Write-Host "===== 读写分离验证开始 =====" -ForegroundColor Cyan

# ---------- 1. 检查启动日志中的数据源拓扑 ----------
Write-Host "`n[1/3] 检查数据源拓扑启动日志..." -ForegroundColor Yellow

$topologyPattern = "\[DataSource\] 动态数据源已启用.*master.*slave"
$foundTopology = $false

if ($LogFile -and (Test-Path $LogFile)) {
    $matches = Select-String -Path $LogFile -Pattern $topologyPattern
    if ($matches) {
        $foundTopology = $true
        Write-Host "  [PASS] 日志中发现动态数据源拓扑: $($matches[0].Line)" -ForegroundColor Green
    } else {
        Write-Host "  [FAIL] 日志中未发现数据源拓扑打印，可能未启用 dynamic-datasource" -ForegroundColor Red
    }
} else {
    # 未指定日志文件，输出检查指引
    Write-Host "  [INFO] 未指定 -LogFile，请在服务日志中确认包含以下行：" -ForegroundColor Gray
    Write-Host "         [DataSource] 动态数据源已启用，可用数据源: [master, slave]" -ForegroundColor Gray
    Write-Host "         若只出现 [master]，说明 slave 未配置（MYSQL_SLAVE_HOST 环境变量缺失）" -ForegroundColor Gray
    Write-Host "         此时读写都打到 master，功能正常但无读分流" -ForegroundColor Gray
}

# ---------- 2. 检查 @DS("slave") 注解覆盖 ----------
Write-Host "`n[2/3] 检查 @DS(slave) 注解覆盖（代码层面）..." -ForegroundColor Yellow

$codeRoot = Resolve-Path "$PSScriptRoot\..\.."
$dsAnnotations = Select-String -Path "$codeRoot\amz-service\amz-service-order\src\main\java\**\*.java",
                                       "$codeRoot\amz-service\amz-service-spapi\src\main\java\**\*.java" `
                          -Pattern '@DS\("slave"\)' -ErrorAction SilentlyContinue

if ($dsAnnotations -and $dsAnnotations.Count -gt 0) {
    Write-Host "  [PASS] 发现 $($dsAnnotations.Count) 处 @DS(slave) 注解，读请求将路由到从库" -ForegroundColor Green
    $dsAnnotations | Select-Object -First 5 | ForEach-Object {
        Write-Host "         - $($_.RelativePath):$($_.LineNumber)" -ForegroundColor Gray
    }
} else {
    Write-Host "  [WARN] 未发现 @DS(slave) 注解，所有读写都走 master" -ForegroundColor Yellow
}

# ---------- 3. 可选：通过 MySQL general_log 验证实际路由 ----------
if ($CheckSql) {
    Write-Host "`n[3/3] 通过 MySQL general_log 验证读请求路由..." -ForegroundColor Yellow
    if (-not $MysqlPassword) {
        Write-Host "  [SKIP] 未提供 -MysqlPassword，跳过 SQL 层验证" -ForegroundColor Gray
    } else {
        $mysqlCmd = "mysql -h $MysqlHost -P $MysqlPort -u $MysqlUser -p$MysqlPassword -e `"SELECT argument FROM mysql.general_log WHERE argument LIKE 'SELECT%' ORDER BY event_time DESC LIMIT 10;`""
        Write-Host "  执行: $mysqlCmd"
        try {
            $result = Invoke-Expression $mysqlCmd 2>&1
            if ($LASTEXITCODE -eq 0) {
                Write-Host "  [INFO] 最近的 SELECT 查询（确认 host_port 指向 slave 端口 3307）:" -ForegroundColor Green
                $result | Select-Object -First 10
            } else {
                Write-Host "  [WARN] MySQL 查询失败: $result" -ForegroundColor Yellow
            }
        } catch {
            Write-Host "  [WARN] MySQL 连接异常: $_" -ForegroundColor Yellow
        }
    }
} else {
    Write-Host "`n[3/3] 跳过 SQL 层验证（使用 -CheckSql 启用）" -ForegroundColor Gray
}

# ---------- 汇总 ----------
Write-Host "`n===== 验证汇总 =====" -ForegroundColor Cyan
Write-Host "数据源拓扑: $(if ($foundTopology) {'已启用'} else {'需人工确认日志'})"
Write-Host "@DS 注解  : $($dsAnnotations.Count) 处"
Write-Host ""
Write-Host "结论：" -ForegroundColor Cyan
if ($dsAnnotations.Count -gt 0) {
    Write-Host "  读写分离代码层已就绪。生产环境部署前请确认：" -ForegroundColor Green
    Write-Host "  1. MYSQL_SLAVE_HOST 环境变量已配置指向从库" -ForegroundColor Green
    Write-Host "  2. 启动日志包含 [master, slave] 两个数据源" -ForegroundColor Green
    Write-Host "  3. 从库与主库延迟在可接受范围（建议 < 1s）" -ForegroundColor Green
} else {
    Write-Host "  读写分离未启用，所有请求走 master 单点" -ForegroundColor Yellow
}
