# Phase 0 Production Hardening Verification

## Evidence Summary

| # | Check | Command | Exit Code | Status | Date |
|---|-------|---------|-----------|--------|------|
| 1 | Full Maven Test (19/19) | `mvn -B -ntp test -fae` | 0 | VERIFIED | 2026-09-28 |
| 2 | Critical Checkstyle | `mvn -B -ntp checkstyle:check -Dcheckstyle.config.location=checkstyle-critical.xml` | 0 | VERIFIED | 2026-09-28 |
| 3 | Python Release Tests | `python -m unittest tools.release.test_repository_hygiene tools.release.test_release_manifest tools.release.test_services_manifest -v` | 0 | VERIFIED | 2026-09-28 |
| 3b | Python Release Tests (7 modules, 2026-09-29 re-run) | `python -m unittest ... test_release_workflow test_rollback_drill test_cve_gate test_verify_clean_clone` | 0 (68 tests) | VERIFIED | 2026-09-29 |
| 4 | Release Workflow Tests | `python -m unittest tools.release.test_release_workflow -v` | 0 | VERIFIED | 2026-09-28 |
| 5 | Rollback Drill Tests | `python -m unittest tools.release.test_rollback_drill -v` | 0 | VERIFIED | 2026-09-28 |
| 6 | Clean Clone Verify | `tools/release/verify_clean_clone.ps1` | 0 (7/7) | VERIFIED | 2026-09-28 |
| 7 | Frontend Build | `npm ci && npm run build` | 0 | VERIFIED | 2026-09-28 (Node v22.22.2 / npm 10.9.7) |

## Clean Clone Evidence (2026-09-28)

| Check | Exit Code | Status |
|-------|-----------|--------|
| git-clone | 0 | VERIFIED |
| git-checkout | 0 | VERIFIED |
| clean-tree | 0 | VERIFIED |
| python-release-tests | 0 | VERIFIED |
| maven-test | 0 | VERIFIED |
| npm-ci | 0 | VERIFIED (npm 10.9.7, 264 packages) |
| frontend-build | 0 | VERIFIED (vue-tsc + vite build, 3.4s) |

## Commits

| Task | Commit | Description |
|------|--------|-------------|
| 0 | 4dfde26 | Freeze phase 0 baseline inventory |
| 1 | 37a193b | Add repository hygiene gate |
| 2 | 8c8f60f | Add deterministic release manifest |
| 3 | a85d541 | Add reproducible multi-service OCI metadata |
| 4 | fc07f6b | Enforce phase 0 governance gates |
| 5 | 365ac05 | Add signed supply-chain workflow |
| 6 | a8f6b55 | Add safe rollback drill with dry-run default |
| 7 | d2b7619+7d933f2+f01738c | Clean clone build + remaining files |

## Frontend Verification Addendum (2026-09-28)

- Node.js v22.22.2 / npm 10.9.7 installed; `npm run test:run` (vitest): 22 files / 175 tests PASSED.
- `npm run build` (vue-tsc + vite): PASS, dist produced in ~3.4s.
- Clean clone re-verified 7/7 VERIFIED; machine-readable evidence archived at
  `docs/superpowers/evidence/2026-09-28-phase0-cleanclone-evidence.json`.
- `tools/release/verify_clean_clone.ps1` fixed: on success it now keeps `evidence.json`
  and removes only `clone-*` work dirs (previously the whole WorkRoot including evidence was deleted,
  making the "preserved" message misleading).

## Remote CI Evidence (2026-09-28)

First remote GitHub Actions runs for this branch (repo default branch `master`, commit `1f8d772`):

| Run | Trigger | Result | Evidence |
|-----|---------|--------|----------|
| 36384609340 | push to master (`cde9477`) | FAILURE (2 jobs) | exposed two real defects; fixed in `1f8d772` |
| 36385434376 | push to master (`1f8d772`) | **SUCCESS (9/9 jobs)** | checkstyle, checkstyle-full, hygiene, release-manifest, test (incl. MySQL ITs), frontend, synthetic-data, mysql-import, docker |

All jobs ran with `continue-on-error` absent except the informational `checkstyle-full`.

### Defects exposed by the first remote run (and fixed in `1f8d772`)

1. `hygiene` job: the allowlist was hash-pinned to raw working-tree bytes. Git rewrites line
   endings on checkout (`.gitattributes` / `core.autocrlf`), so 17 of 60 entries failed to match
   on the Linux runner. Fix: `repository_hygiene.py` now hashes text content with CRLF/CR
   normalised to LF; allowlist regenerated.
2. `synthetic-data` job (DDL drift gate): the committed `schema-snapshot.json` embedded 50 absolute
   paths (`C:\Users\...`) and per-file sha256 over raw bytes, so the gate could only ever pass on
   the machine and path that generated it. Fix: `snapshot_schema.py` emits repository-relative
   paths only; `ddl_parser.py` hashes DDL text with LF normalisation; snapshot + DDL_SOURCES
   regenerated. This was a direct violation of the Phase 0 deterministic-manifest rule (no
   absolute paths) that local verification could not catch.

### First-ever remote verifications unlocked by this run

- Full Maven reactor tests **including** `AdMigrationMySqlIT` and `AllModulesFlywayMySqlIT`
  (real MySQL 8 service containers) - PASSED. These had never run anywhere before (locally they
  are skipped without `AD_MYSQL_IT_*` / `FLYWAY_ALL_IT_*` env vars).
- `mysql-import` job (14 databases x 49 Flyway migrations, real MySQL 8 import + cleanup) - PASSED.
- `docker` job (image build on master) - PASSED.

### Still NOT VERIFIED

- `release.yml` (GHCR push + Syft SBOM + Grype scan + Cosign signing): tag-triggered; no tag has
  been pushed. Requires a deliberate release action.
- `actionlint` static check of `release.yml`: **已完成 2026-09-29** — actionlint 1.7.12，
  `ci.yml` / `release.yml` 0 问题。（不能替代远端执行。）

## Remote CI Evidence (2026-09-29)

| Run | Trigger | Result |
|-----|---------|--------|
| 36448966688 | PR #2 first push (`3e1293a`) | FAILURE (`test` job): the Java governance test pinned a hard-coded 5-module unittest command; the two YAMLs had already gained a 6th module |
| 36450370673 | PR #2 after `d2069d9` | SUCCESS (8/8; `docker` skipped off-master) -> merged 2026-09-28T16:25:47Z |
| 36450964636 | master push (`c055060`) | SUCCESS (9/9 incl. `docker`, job `109026928707`, 10m38s) |

Scope caveat: ci.yml's `docker` job runs only `docker build -t amazon-erp:latest .`
(root Java Dockerfile). It does not run `docker-bake.hcl` and does not build
`amz-frontend/Dockerfile`, so it is **not** a remote verification of the frontend image
slim (`cfa6149`), and it runs no grype scan.
