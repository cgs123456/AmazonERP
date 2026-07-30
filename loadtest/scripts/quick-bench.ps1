<#
.SYNOPSIS
    Amazon-ERP 快速基准测试脚本（Windows PowerShell）
.DESCRIPTION
    使用 curl.exe / Invoke-WebRequest 对关键接口进行快速压测。
    Windows 10+ 自带 curl.exe，无需额外安装。
    如需更专业的并发压测，请使用 JMeter / Gatling 脚本。
.PARAMETER TargetHost
    目标主机，默认 erp.amz.local
.PARAMETER TargetPort
    目标端口，默认 80（K8s NodePort 时改为 30100）
.PARAMETER Protocol
    协议，默认 http
.PARAMETER AuthToken
    JWT Bearer Token
.PARAMETER Concurrency
    并发数（仅 curl 模式有效），默认 50
.PARAMETER Requests
    总请求数（仅 curl 模式有效），默认 500
.EXAMPLE
    .\quick-bench.ps1 -TargetHost 192.168.1.100 -TargetPort 30100 -AuthToken "eyJhbG..."
#>
[CmdletBinding()]
param(
    [string]$TargetHost   = $env:TARGET_HOST,
    [string]$TargetPort   = if ($env:TARGET_PORT) { $env:TARGET_PORT } else { "80" },
    [string]$Protocol     = if ($env:TARGET_PROTO) { $env:TARGET_PROTO } else { "http" },
    [string]$AuthToken    = $env:AUTH_TOKEN,
    [int]$Concurrency     = if ($env:CONCURRENCY) { [int]$env:CONCURRENCY } else { 50 },
    [int]$Requests        = if ($env:REQUESTS) { [int]$env:REQUESTS } else { 500 },
    [int]$TimeoutSec      = 10
)

if (-not $TargetHost) { $TargetHost = "erp.amz.local" }

$ApiBase = "${Protocol}://${TargetHost}:${TargetPort}/api"
$ErrorActionPreference = "Continue"

function Write-Section($title) {
    Write-Host ""
    Write-Host "===== $title =====" -ForegroundColor Cyan
}

function Write-Pass($msg) { Write-Host "[PASS] $msg" -ForegroundColor Green }
function Write-Fail($msg) { Write-Host "[FAIL] $msg" -ForegroundColor Red }
function Write-Warn($msg) { Write-Host "[WARN] $msg" -ForegroundColor Yellow }

# 检查 curl.exe 是否可用
function Test-CurlAvailable {
    $c = Get-Command curl.exe -ErrorAction SilentlyContinue
    if ($null -eq $c) {
        Write-Warn "curl.exe 不可用，将退化为 Invoke-WebRequest 单次探活"
        return $false
    }
    return $true
}

# 单次健康检查
function Invoke-HealthCheck {
    Write-Section "健康检查"
    $endpoints = @(
        @{ Name = "网关 health";         Url = "${Protocol}://${TargetHost}:${TargetPort}/actuator/health" },
        @{ Name = "网关 readiness";      Url = "${Protocol}://${TargetHost}:${TargetPort}/actuator/health/readiness" }
    )
    foreach ($ep in $endpoints) {
        try {
            $resp = Invoke-WebRequest -Uri $ep.Url -Method GET -TimeoutSec 5 -UseBasicParsing -ErrorAction Stop
            if ($resp.StatusCode -eq 200) {
                Write-Pass "$($ep.Name) (HTTP $($resp.StatusCode))"
            } else {
                Write-Fail "$($ep.Name) (HTTP $($resp.StatusCode))"
            }
        } catch {
            Write-Fail "$($ep.Name) ($($_.Exception.Message))"
        }
    }
}

# 使用 curl.exe 压测
function Invoke-BenchCurl {
    param(
        [string]$Name,
        [string]$Method,
        [string]$Url,
        [string]$Body = ""
    )
    Write-Host ""
    Write-Host ("-> {0} ({1} {2}) concurrency={3} requests={4}" -f $Name, $Method, $Url, $Concurrency, $Requests) -ForegroundColor White

    $curlArgs = @(
        "-s", "-o", "NUL", "-w",
        "\nHTTP:%{http_code} Time:%{time_total}s TPS:%{rate_per_second} Success:%{num_ok}",
        "-X", $Method,
        "-H", "Authorization: Bearer $AuthToken",
        "-H", "Content-Type: application/json",
        "--max-time", $TimeoutSec,
        "-n", $Requests.ToString(),
        "-c", $Concurrency.ToString()
    )
    if ($Body -ne "") {
        $curlArgs += @("-d", $Body)
    }
    $curlArgs += $Url

    & curl.exe @curlArgs
    Write-Host ""
}

# 退化：Invoke-WebRequest 单次探活
function Invoke-BenchIwr {
    param(
        [string]$Name,
        [string]$Method,
        [string]$Url,
        [string]$Body = ""
    )
    Write-Host ""
    Write-Host ("-> {0} ({1} {2}) [Invoke-WebRequest 探活]" -f $Name, $Method, $Url) -ForegroundColor White
    try {
        $headers = @{ "Authorization" = "Bearer $AuthToken"; "Content-Type" = "application/json" }
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        $params = @{ Uri = $Url; Method = $Method; Headers = $headers; TimeoutSec = $TimeoutSec; UseBasicParsing = $true }
        if ($Body -ne "") { $params.Body = $Body }
        $resp = Invoke-WebRequest @params -ErrorAction Stop
        $sw.Stop()
        Write-Host ("    HTTP {0}  耗时 {1} ms" -f $resp.StatusCode, $sw.ElapsedMilliseconds)
    } catch {
        Write-Host ("    失败: {0}" -f $_.Exception.Message) -ForegroundColor Red
    }
}

function Invoke-Bench {
    param([string]$Name, [string]$Method, [string]$Url, [string]$Body = "")
    if ($script:HasCurl) {
        Invoke-BenchCurl -Name $Name -Method $Method -Url $Url -Body $Body
    } else {
        Invoke-BenchIwr -Name $Name -Method $Method -Url $Url -Body $Body
    }
}

# ====== 主流程 ======
$script:HasCurl = Test-CurlAvailable

Write-Host ""
Write-Host "============================================================" -ForegroundColor Magenta
Write-Host "          Amazon-ERP Quick Benchmark (PowerShell)           " -ForegroundColor Magenta
Write-Host "============================================================" -ForegroundColor Magenta
Write-Host ("Target      : {0}" -f $ApiBase)
Write-Host ("Concurrency : {0}" -f $Concurrency)
Write-Host ("Requests    : {0}" -f $Requests)
Write-Host ("Token       : {0}" -f $(if ($AuthToken) { "已设置" } else { "(未设置)" }))
Write-Host ""

Invoke-HealthCheck

# 1. 登录接口
Invoke-Bench "登录" POST "${ApiBase}/user/login" '{"username":"admin","password":"admin123"}'

# 2. 订单列表
Invoke-Bench "订单列表" GET "${ApiBase}/order/list?page=1&size=20"

# 3. 库存列表
Invoke-Bench "库存列表" GET "${ApiBase}/inventory/list?page=1&size=50"

# 4. Agent 聊天
Invoke-Bench "Agent 聊天" POST "${ApiBase}/ai/agent/chat" '{"sessionId":"quick-bench","message":"分析上周销量","context":{"marketplace":"US"}}'

Write-Host ""
Write-Host "基准测试完成。" -ForegroundColor Green
