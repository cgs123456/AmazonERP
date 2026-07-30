# Amazon-ERP JMeter 压测说明

本目录包含基于 Apache JMeter 5.6+ 的订单接口压测计划。

## 压测场景

| 场景 | 接口 | 并发用户 | 持续时间 | 说明 |
| --- | --- | --- | --- | --- |
| 订单同步 | `POST /api/order/sync` | 1000 | 5 分钟 | 60s 爬坡，吞吐量 600/min |
| 利润计算 | `GET  /api/finance/profit/calc` | 500 | 100 次循环 | 30s 爬坡 |
| Agent 聊天 | `POST /api/ai/agent/chat` | 100 | 20 次循环 | 20s 爬坡，超时 120s（含 LLM 延迟） |

## 前置准备

1. 安装 JDK 17+
2. 下载并解压 [Apache JMeter 5.6.3](https://jmeter.apache.org/download_jmeter.cgi)
3. 将 `bin/` 加入 `PATH`，或使用全路径调用 `jmeter` / `jmeter.bat`
4. 准备好可用的 JWT Token（通过登录接口获取），用于 `auth.token` 参数

## 执行命令

### GUI 模式（调试用，不要用于正式压测）

```bash
jmeter -t order-api-stress-test.jmx
```

### 非 GUI 模式（推荐，正式压测）

```bash
# 使用默认配置（host=erp.amz.local, port=80）
jmeter -n -t order-api-stress-test.jmx \
  -l result.jtl \
  -e -o ./report \
  -Jauth.token=eyJhbGciOiJIUzI1NiJ9.xxx.yyy

# 自定义目标主机与端口
jmeter -n -t order-api-stress-test.jmx \
  -l result-$(date +%Y%m%d-%H%M).jtl \
  -e -o ./report-$(date +%Y%m%d-%H%M) \
  -Jhost=192.168.1.100 \
  -Jport=30100 \
  -Jprotocol=http \
  -Jauth.token=eyJhbGciOiJIUzI1NiJ9.xxx.yyy
```

### 参数说明（命令行 `-J` 覆盖）

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `host` | `erp.amz.local` | 目标主机（域名/IP） |
| `port` | `80` | 目标端口（K8s NodePort 时改为 30100） |
| `protocol` | `http` | 协议（http/https） |
| `context` | `/api` | 接口上下文路径 |
| `auth.token` | 空 | JWT Bearer Token |

## 结果查看

- **HTML 报告**：`-e -o ./report` 生成的目录，浏览器打开 `index.html`
- **JTL 文件**：原始结果数据，可后续用 `jmeter -g result.jtl -o report/` 重新生成 HTML 报告
- **关键指标**：
  - Throughput（TPS）—— 每秒事务数
  - Response Time（P95/P99）—— 95/99 分位响应时间
  - Error % —— 错误率
  - Active Threads —— 活跃线程数

## 性能基线参考

| 接口 | 目标 TPS | P95 响应时间 | 错误率 |
| --- | --- | --- | --- |
| 订单同步 | ≥ 50 | ≤ 5s | < 1% |
| 利润计算 | ≥ 100 | ≤ 2s | < 0.5% |
| Agent 聊天 | ≥ 5 | ≤ 30s | < 5% |

## 注意事项

1. **不要在 GUI 模式下做正式压测**，GUI 模式开销大，结果不准
2. 压测客户端机器需要足够资源（1000 并发约需 4C8G）
3. 压测前请确认目标环境容量，避免压垮生产环境
4. 压测期间关注：DB 连接池、Redis 连接数、JVM GC、网络带宽
5. Agent 聊天场景涉及 LLM 调用，外部 API 速率限制可能成为瓶颈
6. 如需分布式压测，参考 [JMeter Distributed Testing](https://jmeter.apache.org/usermanual/jmeter_distributed_testing_step_by_step.html)
