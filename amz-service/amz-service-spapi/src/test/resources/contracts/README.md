# SP-API Official Model Snapshots (contract fixtures)

This directory stores **verbatim** OpenAPI model snapshots published by Amazon in
[`amzn/selling-partner-api-models`](https://github.com/amzn/selling-partner-api-models)
(license: Apache-2.0). They are the known-answer source for field-name contract
tests (`ReportsFieldContractTest` today, Feeds in Task 4).

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

Both byte counts and hashes were reproduced twice on 2026-09-24 (direct download and
post-copy re-hash) and match the baseline recorded in
`docs/superpowers/plans/2026-09-24-connector-api-ready-phase0.md` (Task 3 Step 1).

## What the snapshots pin down (verified against the files above)

| Definition | Properties observed | Used by |
|---|---|---|
| `definitions.Report` | `marketplaceIds`, `reportId`, `reportType`, `dataStartTime`, `dataEndTime`, `reportScheduleId`, `createdTime`, `processingStatus`, `processingStartTime`, `processingEndTime`, **`reportDocumentId`** | `ReportsRealClient.getReport` |
| `definitions.ReportDocument` | `reportDocumentId`, `url`, `compressionAlgorithm` | `ReportsRealClient.downloadDocument` |
| `definitions.Feed` | `feedId`, `feedType`, `marketplaceIds`, `createdTime`, `processingStatus`, `processingStartTime`, `processingEndTime`, **`resultFeedDocumentId`** | `ListingsMockClient.getFeedStatus` (mock must use the same field name as the real client) |
| `definitions.FeedDocument` | `feedDocumentId`, `url`, `compressionAlgorithm` | `FeedsClient` (upload/download paths) |

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
| `src/test/java/com/amz/client/ReportsRealClientStubTest.java` | In-process stub replay: a `DONE` report whose `reportDocumentId` is set maps to `ReportInfo.documentId`; the pre-Task-3 field name would not |

Feeds-side contract assertions land with Task 4 (result report closed loop).

## Evidence Boundary

These snapshots are **E3** evidence (official model + hash lock). Together with the
in-process stub replay they prove that this repository reads the field names Amazon
publishes — **not** that Amazon accepts our requests. Only a credential-backed run
(**A5/E4-E5**, see `docs/superpowers/runbooks/connector-acceptance-runbook.md`) can
close that gap.