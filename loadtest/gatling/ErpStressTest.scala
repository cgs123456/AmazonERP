/*
 * Amazon-ERP Gatling performance simulation.
 *
 * This simulation targets the gateway origin directly.  The gateway expects
 * the JWT in the ``token`` header and does not expose a generic /api prefix.
 *
 * Dependency: Gatling 3.9+ / Scala 2.13.x / JDK 17+.
 *
 * Properties:
 *   -Dtarget.host=127.0.0.1
 *   -Dtarget.port=10010
 *   -Dtarget.protocol=http
 *   -Dtarget.token=eyJ...
 *   -Dtarget.shopId=900000000000001000
 *   -Dtarget.context=            (empty for direct gateway access)
 *   -Dusers.order=50
 *   -Dusers.inventory=25
 *   -Dusers.chat=0            (AI is opt-in; use >0 only with a real key)
 *   -Dusers.report=25
 */
package amzerp

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import scala.util.Random

class ErpStressTest extends Simulation {

  val targetHost  = System.getProperty("target.host", "127.0.0.1")
  val targetPort  = System.getProperty("target.port", "10010")
  val targetProto = System.getProperty("target.protocol", "http")
  val authToken   = System.getProperty("target.token", "")
  val shopId      = System.getProperty("target.shopId", "")
  val apiContext  = System.getProperty("target.context", "")

  val usersOrder     = Integer.getInteger("users.order", 50).toInt
  val usersInventory = Integer.getInteger("users.inventory", 25).toInt
  val usersChat      = Integer.getInteger("users.chat", 0).toInt
  val usersReport    = Integer.getInteger("users.report", 25).toInt

  require(shopId.nonEmpty, "target.shopId is required for @ShopScoped endpoints")
  require(authToken.nonEmpty, "target.token is required")

  val httpProtocol = http
    .baseUrl(s"$targetProto://$targetHost:$targetPort")
    .acceptHeader("application/json")
    .contentTypeHeader("application/json;charset=UTF-8")
    .header("token", authToken)
    .header("User-Agent", "amazon-erp-gatling/1.0")
    .acceptEncodingHeader("gzip, deflate")
    .connectionHeader("keep-alive")
    .disableFollowRedirect

  val rndOrderNo = Iterator.continually(
    Map("orderNo" -> s"AMZ-${Random.nextInt(8999999) + 1000000}")
  )

  val scnOrder = scenario("order-list")
    .feed(rndOrderNo)
    .exec(
      http("GET /order/list")
        .get(s"$apiContext/order/list")
        .queryParam("shopId", shopId)
        .queryParam("page", "1")
        .queryParam("size", "20")
        .queryParam("orderNo", "${orderNo}")
        .check(status.is(200))
        .check(jsonPath("$.code").is("200"))
    )
    .pause(500.millis, 2.seconds)

  val scnInventory = scenario("inventory-health")
    .exec(
      http("GET /spapi/inventory/health/{shopId}")
        .get(s"$apiContext/spapi/inventory/health/$shopId")
        .check(status.is(200))
        .check(jsonPath("$.code").is("200"))
    )
    .pause(500.millis, 2.seconds)

  val scnChat = scenario("ai-agent")
    .exec(
      http("POST /ai/agent/chat")
        .post(s"$apiContext/ai/agent/chat")
        .body(StringBody(
          """{
            |  "messages": [
            |    {
            |      "role": "user",
            |      "content": "Analyze last week's sales and give top 5 inventory actions."
            |    }
            |  ]
            |}""".stripMargin)).asJson
        .check(status.is(200))
        .check(jsonPath("$.code").is("200"))
        .check(responseTimeInMillis.lte(60000))
    )
    .pause(2.seconds, 5.seconds)

  val scnReport = scenario("report-dashboard")
    .exec(
      http("GET /report/dashboard/{shopId}")
        .get(s"$apiContext/report/dashboard/$shopId")
        .queryParam("dateRange", "30d")
        .check(status.is(200))
        .check(jsonPath("$.code").is("200"))
    )
    .exec(
      http("GET /finance/profit/sku/{shopId}")
        .get(s"$apiContext/finance/profit/sku/$shopId")
        .queryParam("depositAfter", "2026-01-01")
        .queryParam("depositBefore", "2026-12-31")
        .check(status.is(200))
        .check(jsonPath("$.code").is("200"))
    )
    .pause(1.second, 3.seconds)

  // Deliberately not injected: order sync is a write path that requires
  // OPERATOR/ADMIN plus a real or mock SP-API boundary.  Enable it only in a
  // controlled environment after credentials and idempotency checks.
  val scnSync = scenario("spapi-sync-write-path")
    .exec(
      http("POST /spapi/sync/orders")
        .post(s"$apiContext/spapi/sync/orders")
        .queryParam("shopId", shopId)
        .check(status.is(200))
        .check(jsonPath("$.code").is("200"))
    )

  val readInjections = Seq(
    scnOrder.inject(rampUsers(usersOrder).during(30.seconds)).protocols(httpProtocol),
    scnInventory.inject(rampUsers(usersInventory).during(20.seconds)).protocols(httpProtocol),
    scnReport.inject(rampUsers(usersReport).during(30.seconds)).protocols(httpProtocol)
  )
  val chatInjections = if (usersChat > 0) {
    Seq(scnChat.inject(rampUsers(usersChat).during(10.seconds)).protocols(httpProtocol))
  } else {
    Seq.empty
  }

  setUp((readInjections ++ chatInjections): _*)
  .maxDuration(5.minutes)
  .assertions(
    global.responseTime.mean.lte(5000),
    global.responseTime.percentile3.lte(10000),
    global.responseTime.percentile4.lte(30000),
    global.successfulRequests.percent.gte(95.0)
  )
}
