# SP-API Official Model Snapshots (contract fixtures)

This directory stores **verbatim** OpenAPI model snapshots published by Amazon in
[`amzn/selling-partner-api-models`](https://github.com/amzn/selling-partner-api-models)
(license: Apache-2.0). They are the known-answer source for contract tests against
Amazon-published facts: field names (`ReportsFieldContractTest`), request paths and
version segments (`SpApiPathContractTest`, P0-54), and the Feeds result-report loop
(Task 4).

Rules:

1. **Never rewrite a snapshot.** Byte count and SHA-256 are asserted by tests; a
   modified file fails the build on purpose.
2. When upstream changes, re-download, re-verify the hashes here **and** in the
   test, and treat the diff as a contract change that must be reviewed.
3. HTTP status `200` is not proof of content: the
   `models/fba-inventory-api-model/` directory returns 14-byte
   `404: Not Found` bodies for two non-existent file names, so every snapshot
   below is pinned by bytes + hash (plan Task 3「抓取陷阱」).

## Provenance

| File | Source URL | Retrieved at | Bytes | SHA-256 |
|---|---|---|---:|---|
| `reports_2021-06-30.json` | [reports_2021-06-30.json](https://raw.githubusercontent.com/amzn/selling-partner-api-models/main/models/reports-api-model/reports_2021-06-30.json) | `2026-09-24T21:07:54+08:00` | 83685 | `d72db9e5280262a92933a0e45e2207c150272f1d66177b2517c20671d69c732c` |
| `feeds_2021-06-30.json` | [feeds_2021-06-30.json](https://raw.githubusercontent.com/amzn/selling-partner-api-models/main/models/feeds-api-model/feeds_2021-06-30.json) | `2026-09-24T21:07:54+08:00` | 55901 | `ab235b4a0e5ce21083b885dd4f2b8cae7a6f597d7adf2647b47b90d6f5098a16` |
| `ordersV0.json` | [ordersV0.json](https://raw.githubusercontent.com/amzn/selling-partner-api-models/main/models/orders-api-model/ordersV0.json) | `2026-09-24T22:31:54+08:00` | 226555 | `027ac6f5c97126647c6925db9be09f78c7c741cd1d8727a5367374a1846bedc5` |
| `productFeesV0.json` | [productFeesV0.json](https://raw.githubusercontent.com/amzn/selling-partner-api-models/main/models/product-fees-api-model/productFeesV0.json) | `2026-09-24T22:33:37+08:00` | 49426 | `d06ad35f909d8c0985845f21420c1f75599531465b27f46d4946f7d7f522fc35` |
| `financesV0.json` | [financesV0.json](https://raw.githubusercontent.com/amzn/selling-partner-api-models/main/models/finances-api-model/financesV0.json) | `2026-09-24T22:31:58+08:00` | 134109 | `d80e881091367b0eccd4bde3ce834ed08877d3cf51095239eb8b1e328c0d19d6` |
| `fbaInventory.json` | [fbaInventory.json](https://raw.githubusercontent.com/amzn/selling-partner-api-models/main/models/fba-inventory-api-model/fbaInventory.json) | `2026-09-24T22:34:01+08:00` | 36985 | `7c14bcdb22de8ca2df45e5a40f2a422cff344d45985a68b9515b2e800edcc5ab` |
| `messaging.json` | [messaging.json](https://raw.githubusercontent.com/amzn/selling-partner-api-models/main/models/messaging-api-model/messaging.json) | `2026-09-25T10:57:25+08:00` | 106025 | `16b585e87a3b72c3637ffa0890e08acb4e2090864f1e8b4c1a9271a06700a8a1` |
| `uploads_2020-11-01.json` | [uploads_2020-11-01.json](https://raw.githubusercontent.com/amzn/selling-partner-api-models/main/models/uploads-api-model/uploads_2020-11-01.json) | `2026-09-25T11:20:53+08:00` | 12157 | `202444dd425c24308366a4aaab28680dfb2ec70c25f4d9cd5441ea7d968ec3bd` |
| `sellers.json` | [sellers.json](https://raw.githubusercontent.com/amzn/selling-partner-api-models/main/models/sellers-api-model/sellers.json) | `2026-09-26T12:55:06+08:00` | 29604 | `497862ea32de8040453649986e2cd7c6fcc15b55e8022becc783e4a6d6ffcffd` |
| `tokens_2021-03-01.json` | [tokens_2021-03-01.json](https://raw.githubusercontent.com/amzn/selling-partner-api-models/main/models/tokens-api-model/tokens_2021-03-01.json) | in-tree pinned; last-write `2026-09-26T14:47:55+08:00` | 15751 | `3cd09ae7f218c83f32536a894cb8c42f2191c94b9c27f6bcf0a164442089b061` |
| `notifications.json` | [notifications.json](https://raw.githubusercontent.com/amzn/selling-partner-api-models/main/models/notifications-api-model/notifications.json) | in-tree pinned; last-write `2026-09-26T15:32:01+08:00` | 95405 | `6a5e945f2a53a91b9b97c27cd4570dc399db3dde2b777f623fc226fbcdc8469a` |
| `listingsItems_2021-08-01.json` | [listingsItems_2021-08-01.json](https://raw.githubusercontent.com/amzn/selling-partner-api-models/main/models/listings-items-api-model/listingsItems_2021-08-01.json) | in-tree pinned; last-write `2026-09-26T15:32:01+08:00` | 157514 | `117617f4c86dbd5c1708913103806a24c0ef0bbcfb6054e415d044d07761faeb` |
| `productPricing_2022-05-01.json` | [productPricing_2022-05-01.json](https://raw.githubusercontent.com/amzn/selling-partner-api-models/main/models/product-pricing-api-model/productPricing_2022-05-01.json) | in-tree pinned; last-write `2026-09-26T15:32:02+08:00` | 105753 | `db6ffeab130bf1d4ab8fa47e4e83417d30d9cb682b3ce53f74ed51b62f6f817c` |
| `catalogItems_2022-04-01.json` | [catalogItems_2022-04-01.json](https://raw.githubusercontent.com/amzn/selling-partner-api-models/main/models/catalog-items-api-model/catalogItems_2022-04-01.json) | in-tree pinned; last-write `2026-09-26T15:32:02+08:00` | 151872 | `1a029b01df1d847d3057740a6e877f89f2ab78104b5b4d8f4b839f8d00f600c2` |
| `fulfillmentInbound_2024-03-20.json` | [fulfillmentInbound_2024-03-20.json](https://raw.githubusercontent.com/amzn/selling-partner-api-models/main/models/fulfillment-inbound-api-model/fulfillmentInbound_2024-03-20.json) | in-tree pinned; last-write `2026-09-26T15:32:03+08:00` | 560644 | `a4d4cdd08dd3f381f27154d7f9f503d45e0486d416c629598341bbd23c7ff487` |

The byte counts and hashes of the two Task-3 snapshots (`reports_2021-06-30.json`,
`feeds_2021-06-30.json`) were reproduced twice on 2026-09-24 (direct download and
post-copy re-hash) and match the baseline recorded in
`docs/superpowers/plans/2026-09-24-connector-api-ready-phase0.md` (Task 3 Step 1).
The four snapshots added in round 49 (`ordersV0` / `productFeesV0` / `financesV0` /
`fbaInventory`) were **re-downloaded from upstream at `2026-09-24T22:43:24+08:00`
and compared byte-for-byte with the in-tree copies — all four identical** (upstream
raw fetches were flaky, so each download was retried until it returned a >1 KB body;
the 14-byte `404: Not Found` bodies fail that size gate on purpose).
The Sellers snapshot added on 2026-09-26 is pinned byte-for-byte; its official paths are
`GET /sellers/v1/marketplaceParticipations` and `GET /sellers/v1/account`, both with
Usage Plan `0.016 req/s`, burst `15`. The repository provides a client/controller for
marketplace participations; `getAccount` is intentionally not exposed because it would
expand account/PII permission scope.

The Tokens snapshot is pinned in-tree with last-write `2026-09-26T14:47:55+08:00`; its
official operation is `POST /tokens/2021-03-01/restrictedDataToken`, Usage Plan
`1 req/s`, burst `10`. `TokensClient` uses it only with a normal LWA access token to
request an RDT; the restricted business request then uses that short-lived token and
must not fall back to LWA. The repository does not persist, log, or expose RDTs.

The five remaining-capability snapshots add 64 official operations: Notifications 10, Listings Items 5, Product Pricing 2, Catalog Items 2, and FBA Inbound 45. Five FBA Inbound operations publish no Usage Plan table, so they are intentionally absent from `SpiRateLimiter#officialPlans()` and use the conservative runtime fallback until an official value or response header is observed.

## What the snapshots pin down (verified against the files above)

| Definition | Properties observed | Used by |
|---|---|---|
| `definitions.Report` | `marketplaceIds`, `reportId`, `reportType`, `dataStartTime`, `dataEndTime`, `reportScheduleId`, `createdTime`, `processingStatus`, `processingStartTime`, `processingEndTime`, **`reportDocumentId`** | `ReportsRealClient.getReport` |
| `definitions.ReportDocument` | `reportDocumentId`, `url`, `compressionAlgorithm` | `ReportsRealClient.downloadDocument` |
| `definitions.Feed` | `feedId`, `feedType`, `marketplaceIds`, `createdTime`, `processingStatus`, `processingStartTime`, `processingEndTime`, **`resultFeedDocumentId`** | `ListingsMockClient.getFeedStatus` (mock must use the same field name as the real client) |
| `definitions.FeedDocument` | `feedDocumentId`, `url`, `compressionAlgorithm` | `FeedsClient` (upload/download paths) |
| `paths` / `definitions.Attachment` | 11 official Messaging paths; `Attachment.fileName` + `Attachment.uploadDestinationId` are required; 10 operations publish a Usage Plan table, `sendInvoice` does not | `AmazonMessagingRealClient`, `MessagingController`, `SpiRateLimiter` |
| `paths` / `definitions.CreateUploadDestinationResponse` / `definitions.UploadDestination` | `POST /uploads/2020-11-01/uploadDestinations/{resource}`; required query `marketplaceIds` (max 1) and `contentMD5`; optional `contentType`; `resource` is a greedy path; success is `201`; response payload exposes `uploadDestinationId`, `url`, `headers`; Usage Plan `10 req/s`, burst `10` | `AmazonUploadsRealClient`, `UploadsController`（服务端闭环，仅返回 `uploadDestinationId`）, `SpApiPathContractTest`, `SpiRateLimiterTest` |
| `paths` / `definitions.MarketplaceParticipationList` / `definitions.MarketplaceParticipation` / `definitions.Marketplace` / `definitions.Participation` | `GET /sellers/v1/marketplaceParticipations`（success `200`; `payload` is an array and may be empty）and `GET /sellers/v1/account`; each participation requires `storeName`, `marketplace` (`id`, `name`, `countryCode`, `defaultCurrencyCode`, `defaultLanguageCode`, `domainName`) and `participation` (`isParticipating`, `hasSuspendedListings`); both Usage Plan `0.016 req/s`, burst `15` | `SellersClient`, `SellersController`, `SpApiPathContractTest`, `SpiRateLimiterTest` |

`resultDocumentId` is **declared as a schema property by neither model**. Before
Task 3 the repository used it in two places (`ReportsRealClient`,
`ListingsMockClient`), which is why settlement report document IDs were always
`null` (spec `P0-27`).

### Upstream documentation trap (why a raw substring scan is not a valid check)

`feeds_2021-06-30.json` does contain the string `resultDocumentId` — once, inside the
natural-language `description` of `getFeed` (line 691 of the pinned snapshot):

> Returns feed details (including the `resultDocumentId`, if available) for the feed
> that you specify.

The property that sentence refers to is `resultFeedDocumentId`. **Amazon's prose is
wrong; the schema is authoritative.** This is the exact trap that produced `P0-27`
(the field name looked plausible and was copied from the docs), so
`ReportsFieldContractTest` asserts over parsed `properties` keys only and never over
raw file text. The test also pins the existence of this prose typo, so that fixing or
re-pinning the snapshot forces a review of this section.

## Consumers

| Test | Asserts |
|---|---|
| `src/test/java/com/amz/client/ReportsFieldContractTest.java` | Snapshot byte count + SHA-256; every `str(<var>, "<literal>")` field name in `ReportsRealClient` is a property of `Report`/`ReportDocument` (compared against **parsed property keys**, never raw text); no schema property anywhere is named `resultDocumentId`; source contains no `resultDocumentId`; source still maps `reportDocumentId`; the upstream Feeds prose typo is still present and documented above |
| `src/test/java/com/amz/client/SpApiPathContractTest.java` | Snapshot byte count + SHA-256 for **all fifteen** models; every SP-API path literal in `src/main/java` (roots `/orders/` `/reports/` `/feeds/` `/fba/` `/finances/` `/products/` `/messaging/` `/uploads/` `/sellers/` `/tokens/` `/notifications/` `/listings/` `/catalog/` `/batches/` `/inbound/`) must be declared by the parsed official `paths` (exact match, or covered as a `literal + "/"` prefix because clients build `PATH + "/" + id`); the unpublished Reports version `2021-09-01` must not appear in `src/main/java` or `src/test/java` (the guard class itself is the only exception, and the scan asserts it inspected >= 20 files so the exclusion cannot silently degenerate); Reports/Feeds version segments pinned to `2021-06-30` |
| `src/test/java/com/amz/client/ReportsRealClientStubTest.java` | In-process stub replay: a `DONE` report whose `reportDocumentId` is set maps to `ReportInfo.documentId`; the pre-Task-3 field name would not |
| `src/test/java/com/amz/client/AmazonMessagingRealClientContractTest.java` | Messaging GET/POST method + official paths + mandatory `marketplaceIds`; `201` is required for sends; null body, missing marketplace and malformed `Attachment` fail before HTTP; non-`201` remains an explicit failure |
| `src/test/java/com/amz/controller/MessagingControllerContractTest.java` | Only official Messaging action enum values are accepted; null body and unknown actions fail before the client; upstream platform status/error text is surfaced and never converted into an empty success |
| `src/test/java/com/amz/client/AmazonUploadsRealClientContractTest.java` | Uploads `201` creation, greedy-resource path encoding, required `marketplaceIds`/`contentMD5`, raw-byte pre-signed PUT, protected headers, and no credential/signature leakage in failures |
| `src/test/java/com/amz/client/SpApiGatewayUploadErrorTest.java` | Presigned upload `RuntimeException` / checked `IOException` / malformed URI failures are redacted and do not chain the original cause |
| `src/test/java/com/amz/connector/SpApiRequestFactoryPresignedHeadersTest.java` | Presigned requests reject protected headers, CRLF injection, blank values, and duplicate `Content-Type`; safe `Content-MD5` / SSE headers are forwarded without LWA or AWS auth |
| `src/test/java/com/amz/controller/UploadsControllerContractTest.java` | Upload endpoint requires `OPERATOR`/`ADMIN` + shop scope; forwards multipart bytes and content type; empty/missing fields fail before the upstream; non-200 is surfaced, not converted to success |
| `src/test/java/com/amz/ratelimit/SpiRateLimiterTest.java` | **Usage Plan** tables parsed from all fifteen pinned snapshots (byte count + SHA-256 re-asserted here too) must match `SpiRateLimiter#officialPlans()` **bidirectionally**: 106 operations, same key set, same `ratePerSecond`/`burst` per operation; reverse guard asserts operations with **no** usage-plan table (`fbaInventory.createInventoryItem`, `messaging.sendInvoice`, and the five FBA Inbound operations `cancelSelfShipAppointment`, `getSelfShipAppointmentSlots`, `generateSelfShipAppointmentSlots`, `scheduleSelfShipAppointment`, `listPrepDetails`) and the undocumented `JSON_LISTINGS_FEED` variant are **not** registered with invented numbers |
| `src/test/java/com/amz/client/TokensClientContractTest.java` | Tokens API official `POST` path, LWA-only authentication, 1–50 resource validation, `restrictedDataToken`/`expiresIn` parsing, non-200 failure, malformed response failure, and token redaction |
| `src/test/java/com/amz/client/SpApiGatewayRestrictedDataTest.java` | Default calls stay LWA; explicit RDT calls send RDT in `x-amz-access-token`; RDT failure sends zero business requests; 401/403 invalidates only the matching token source; Tokens API itself never recurses into RDT |
| `src/test/java/com/amz/client/SpApiOperationCatalogContractTest.java` | Parses the five new official snapshots and locks all 64 operation metadata entries, including methods, paths, success statuses, grantless boundaries, and required path/query/body fields |
| `src/test/java/com/amz/client/SpApiOperationClientTest.java` | Real/mock operation execution, grantless-vs-seller LWA routing, URI encoding, required-parameter rejection before HTTP, unknown operation rejection, and deterministic synthetic mock output |
| `src/test/java/com/amz/client/RemainingCapabilitiesClientTest.java` | Typed Notifications/Listings Items/Product Pricing/Catalog Items clients and the FBA Inbound generic client assemble the correct operation IDs and parameters; all five are available under the mock profile |
| `src/test/java/com/amz/controller/SpApiOperationControllerTest.java` | Unified operation metadata/execution routes; VIEWER read vs OPERATOR/ADMIN execute roles; `@ShopScoped` isolation; unknown operation and missing-required-parameter failures; mock-profile availability; success/failure responses do not leak credentials or tokens |

Feeds-side contract assertions land with Task 4 (result report closed loop).

## Evidence Boundary

These snapshots are **E3** evidence (official model + hash lock). Together with the
in-process stub replay they prove that this repository reads the field names Amazon
publishes — **not** that Amazon accepts our requests. Only a credential-backed run
(**A5/E4-E5**, see `docs/superpowers/runbooks/connector-acceptance-runbook.md`) can
close that gap.