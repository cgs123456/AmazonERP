# LWA Token Contract Fixtures

This directory stores the known-answer LWA refresh-token exchange example published
by Amazon. Every `refresh_token`, `client_secret`, and token value below is a public
documentation example, **not a real credential**, and must never be used against
Amazon.

## Provenance

| Item | Value |
|---|---|
| Official document | [Connect to the SP-API](https://developer-docs.amazon.com/sp-api/docs/connecting-to-the-selling-partner-api) |
| Markdown snapshot | [https://developer-docs.amazon.com/sp-api/docs/connecting-to-the-selling-partner-api.md](https://developer-docs.amazon.com/sp-api/docs/connecting-to-the-selling-partner-api.md) |
| Document `updatedAt` | `2026-09-09T22:32:25.000Z` |
| Snapshot retrieved at | `2026-09-24T19:04:34+08:00` |
| Snapshot bytes | `19403` |
| Snapshot SHA-256 | `d8ff4f0d83ab41f04cbbe3266b17d65b47e966cb814b88464e7010f399a344cb` |
| Request example lines | `40-48` |
| Success response lines | `75-85` |
| Generate/check command | `python tools/contract-fixtures/generate_lwa_fixtures.py [--check]` |

This fixture uses the complete official documentation example (Step 4 path (a));
no official SDK was invoked, so there is no SDK coordinate/version to record. JSON
whitespace is normalized, while every field name and value is copied from the
official example. The source snapshot SHA-256 and fixture SHA-256 values make drift
independently detectable.

## Files

| File | Bytes | SHA-256 | Purpose |
|---|---:|---|---|
| `refresh-token-request.json` | 453 | `adf3fb2828c4669a24365fc1e2b52f4597cefe776b46dbc8080b18cc70c3a81d` | POST URL, media type, and the four form fields |
| `refresh-token-success.json` | 192 | `c11025da5bf607940bb61069d72f29ddf87b39cddde72c98b95e5d307b9f3ae0` | `access_token`, `token_type=bearer`, `expires_in`, `refresh_token` |
| `provenance.json` | 1237 | `2f0d564ec4b4262e77d69f2d0bc133004938a94097ec68a8b57cf8b47c89e613` | Source, retrieval time, generator, and fixture digests |

## Consumers

`amz-service/amz-service-spapi/src/test/java/com/amz/auth/LwaTokenExchangeContractTest.java`
loads these files from the test classpath and asserts that:

- the outbound request matches the official URL, method, media type, and form fields;
- the official success response parses and requires `token_type=bearer`;
- missing or invalid `access_token`, `token_type`, or `expires_in` fails closed;
- the byte counts and SHA-256 values recorded in `provenance.json` match the files.

## Evidence Boundary

The official documentation fixture is **E3** evidence. Running it through an
in-process transport proves only that this repository's implementation matches the
documented contract (**E2** code-level verification); it does not replace a real
credential-backed **A5/E4-E5** integration run.
