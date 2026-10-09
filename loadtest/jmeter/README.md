# AmazonERP performance test assets

These assets target the **gateway origin directly** (default `http://127.0.0.1:10010`).
The gateway authenticates with the `token` header and does not expose a generic
`/api/**` route. Do not add a frontend `/api` prefix unless you are testing the
frontend nginx configuration itself.

## Read-path baseline

The recommended first pass is the dependency-free runner:

```powershell
$env:AUTH_TOKEN = "<JWT>"
$env:SHOP_ID = "900000000000001000"
python loadtest/scripts/bench.py `
  --base-url http://127.0.0.1:10010 `
  --scenario order-list `
  --concurrency 10 `
  --requests 100 `
  --output .\loadtest-results\order-list.json
```

It reports success/error counts, TPS, and P50/P95/P99 latency. The runner uses
`token: <JWT>` and the real gateway paths:

| Scenario | Method and path |
| --- | --- |
| `order-list` | `GET /order/list?shopId=<id>&page=1&size=20` |
| `report-dashboard` | `GET /report/dashboard/<id>?dateRange=7d` |
| `finance-profit` | `GET /finance/profit/sku/<id>?depositAfter=...&depositBefore=...` |
| `inventory-health` | `GET /spapi/inventory/health/<id>` |
| `ai-agent` | `POST /ai/agent/chat` with `messages[]` |
| `spapi-sync` | `POST /spapi/sync/orders?shopId=<id>` (write path, opt-in only) |

`spapi-sync` and `ai-agent` are not valid general read-path baselines:
`spapi-sync` requires OPERATOR/ADMIN and a real or mock SP-API boundary; with no
`DEEPSEEK_API_KEY`, `ai-agent` exercises the failure path only.

## JMeter

`order-api-stress-test.jmx` is the JMeter 5.6+ equivalent. Override the
following properties with `-J`:

| Property | Default | Meaning |
| --- | --- | --- |
| `host` | `127.0.0.1` | Gateway host |
| `port` | `10010` | Gateway port |
| `protocol` | `http` | `http` or `https` |
| `context` | empty | Leave empty for direct gateway access |
| `shopId` | empty | Required for `@ShopScoped` endpoints |
| `auth.token` | empty | JWT sent as `token: <JWT>` |
| `users.order` | `50` | Order-list users |
| `users.report` | `25` | Report users |
| `users.finance` | `25` | Finance users |
| `users.inventory` | `25` | Inventory users |
| `users.chat` | `0` | AI is opt-in; enable the disabled AI thread group only with a real key |

Example:

```bash
jmeter -n -t order-api-stress-test.jmx \
  -l result.jtl -e -o ./report \
  -Jhost=127.0.0.1 -Jport=10010 -Jprotocol=http \
  -JshopId=900000000000001000 \
  -Jauth.token="$AUTH_TOKEN"
```

The write-path thread group is disabled by default. Enable it only after
confirming the role, shop scope, idempotency, and SP-API boundary. The
`ai-agent` thread group is also disabled by default; enable it only when a real
`DEEPSEEK_API_KEY` is configured, otherwise it would measure a failure path.
Each enabled sampler asserts both HTTP 200 and the gateway response
`$.code == 200`.

## Gatling

`ErpStressTest.scala` is the Gatling 3.9+/Scala 2.13 equivalent. It requires
`target.token` and `target.shopId`; `target.context` defaults to empty.

## Target values (not measured)

The numbers below are **target values only**. They are not results, are not a
capacity commitment, and are **not a measured baseline**:

| Interface | Target TPS | Target P95 | Target error rate |
| --- | --- | --- | --- |
| Order list | >= 50 | <= 5s | < 1% |
| Report / profit read | >= 100 | <= 2s | < 0.5% |
| Inventory health | >= 50 | <= 2s | < 1% |
| Agent chat (real key required) | >= 5 | <= 30s | < 5% |

A real baseline must include the hardware, JVM parameters, dataset size,
concurrency/ramp, duration, error rate, TPS, P50/P95/P99, and service/DB/Redis
resource observations.

## Prerequisites and limits

- Use a deterministic synthetic dataset and record its tier/count.
- Keep the gateway, services, MySQL, Redis, and the load generator on the same
  host only for smoke; it is not a production capacity test.
- A failed AI request with an empty `DEEPSEEK_API_KEY` is not an LLM latency
  measurement.
- Run `python -m unittest loadtest.tests.test_loadtest_contracts -v` before
  trusting any performance asset.
