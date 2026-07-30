/*
 * Amazon-ERP Gatling 压测脚本
 * ============================================================
 * 4 种场景：订单查询、库存查询、Agent 聊天、利润报表
 *
 * 依赖：Gatling 3.9+ / Scala 2.13.x / JDK 17+
 * 目录约定：本文件应置于 Gatling 工程的 src/test/scala/amzerp/ 下
 *
 * 执行方式：
 *   mvn gatling:test -Dgatling.simulationClass=amzerp.ErpStressTest
 *   或：
 *   $GATLING_HOME/bin/gatling.sh -rsf . -s amzerp.ErpStressTest
 *
 * 参数覆盖（-D）：
 *   -Dtarget.host=erp.amz.local
 *   -Dtarget.port=80
 *   -Dtarget.protocol=http
 *   -Dtarget.token=eyJhbGciOiJIUzI1NiJ9.xxx.yyy
 *   -Dusers.order=1000
 *   -Dusers.inventory=500
 *   -Dusers.chat=100
 *   -Dusers.report=200
 */
package amzerp

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import scala.util.Random

class ErpStressTest extends Simulation {

  // ====== 配置参数（支持 -D 系统属性覆盖） ======
  val targetHost    = System.getProperty("target.host", "erp.amz.local")
  val targetPort    = System.getProperty("target.port", "80")
  val targetProto   = System.getProperty("target.protocol", "http")
  val authToken     = System.getProperty("target.token", "")
  val apiContext    = System.getProperty("target.context", "/api")

  val usersOrder     = Integer.getInteger("users.order", 1000).toInt
  val usersInventory = Integer.getInteger("users.inventory", 500).toInt
  val usersChat      = Integer.getInteger("users.chat", 100).toInt
  val usersReport    = Integer.getInteger("users.report", 200).toInt

  // ====== HTTP 协议配置 ======
  val httpProtocol = http
    .baseUrl(s"$targetProto://$targetHost:$targetPort")
    .acceptHeader("application/json")
    .contentTypeHeader("application/json;charset=UTF-8")
    .header("Authorization", s"Bearer $authToken")
    .header("User-Agent", "amazon-erp-gatling/1.0")
    .acceptEncodingHeader("gzip, deflate")
    .connectionHeader("keep-alive")
    .disableFollowRedirect

  // ====== 随机数据生成器 ======
  val rndSku       = Iterator.continually(Map("sku" -> s"SKU-${Random.nextInt(89999) + 10000}"))
  val rndOrderId   = Iterator.continually(Map("orderId" -> s"AMZ-${Random.nextInt(8999999) + 1000000}"))
  val rndDateRange = Iterator.continually {
    val start = java.time.LocalDate.now().minusDays(Random.nextInt(30) + 1)
    Map("startDate" -> start.toString, "endDate" -> start.plusDays(Random.nextInt(7) + 1).toString)
  }

  // ====== 场景 1：订单查询 ======
  val scnOrder = scenario("订单查询")
    .feed(rndOrderId)
    .exec(
      http("GET /api/order/list")
        .get(s"$apiContext/order/list")
        .queryParam("page", "1")
        .queryParam("size", "20")
        .queryParam("sort", "createTime,desc")
        .check(status.is(200))
        .check(jsonPath("$.code").saveAs("respCode"))
    )
    .exec(
      http("GET /api/order/{orderId}")
        .get(s"$apiContext/order/${"orderId"}")
        .check(status.is(200))
    )
    .pause(1, 3)  // 用户思考时间 1-3 秒

  // ====== 场景 2：库存查询 ======
  val scnInventory = scenario("库存查询")
    .feed(rndSku)
    .exec(
      http("GET /api/inventory/list")
        .get(s"$apiContext/inventory/list")
        .queryParam("page", "1")
        .queryParam("size", "50")
        .check(status.is(200))
    )
    .exec(
      http("GET /api/inventory/sku/{sku}")
        .get(s"$apiContext/inventory/sku/${"sku"}")
        .check(status.is(200))
    )
    .pause(500.millis, 2.seconds)

  // ====== 场景 3：Agent 聊天（含 LLM 响应延迟） ======
  val scnChat = scenario("Agent 聊天")
    .exec(
      http("POST /api/ai/agent/chat")
        .post(s"$apiContext/ai/agent/chat")
        .body(StringBody(
          """{
            |  "sessionId": "gatling-${java.util.UUID.randomUUID()}",
            |  "message": "帮我分析上周销量下滑的原因并给出 Top 5 SKU 库存建议",
            |  "context": { "marketplace": "US", "dateRange": "last_7_days" }
            |}""".stripMargin)).asJson
        .check(status.is(200))
        // LLM 响应延迟较大，允许最长 60s
        .check(responseTimeInMillis.lte(60000))
    )
    .pause(3, 8)  // 用户阅读回复后的思考时间

  // ====== 场景 4：利润报表 ======
  val scnReport = scenario("利润报表")
    .feed(rndDateRange)
    .exec(
      http("GET /api/finance/profit/calc")
        .get(s"$apiContext/finance/profit/calc")
        .queryParam("startDate", "${startDate}")
        .queryParam("endDate", "${endDate}")
        .queryParam("groupBy", "sku")
        .check(status.is(200))
    )
    .exec(
      http("GET /api/report/dashboard")
        .get(s"$apiContext/report/dashboard")
        .queryParam("type", "profit_summary")
        .queryParam("range", "30d")
        .check(status.is(200))
    )
    .pause(2, 5)

  // ====== 注入策略 ======
  // 4 个场景并行执行，各自爬坡
  setUp(
    scnOrder.inject(
      rampUsers(usersOrder).during(60.seconds)         // 60s 内爬到 1000 用户
    ).protocols(httpProtocol),

    scnInventory.inject(
      rampUsers(usersInventory).during(30.seconds)
    ).protocols(httpProtocol),

    scnChat.inject(
      rampUsers(usersChat).during(20.seconds)
    ).protocols(httpProtocol),

    scnReport.inject(
      rampUsers(usersReport).during(40.seconds)
    ).protocols(httpProtocol)
  )
  .maxDuration(5.minutes)  // 总持续 5 分钟
  .assertions(
    global.responseTime.mean.lte(5000),
    global.responseTime.percentile3.lte(10000),  // P95
    global.responseTime.percentile4.lte(30000),  // P99
    global.successfulRequests.percent.gte(95.0),
    global.requestsPerSec.gte(50.0)
  )
}
