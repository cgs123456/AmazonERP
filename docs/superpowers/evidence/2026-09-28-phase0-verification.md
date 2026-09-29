# Phase 0 Production Hardening Verification

## Evidence Summary

| # | Check | Command | Exit Code | Status | Date |
|---|-------|---------|-----------|--------|------|
| 1 | Full Maven Test (19/19) | `mvn -B -ntp test -fae` | 0 | VERIFIED | 2026-09-28 |
| 2 | Critical Checkstyle | `mvn -B -ntp checkstyle:check -Dcheckstyle.config.location=checkstyle-critical.xml` | 0 | VERIFIED | 2026-09-28 |
| 3 | Python Release Tests | `python -m unittest tools.release.test_repository_hygiene tools.release.test_release_manifest tools.release.test_services_manifest -v` | 0 | VERIFIED | 2026-09-28 |
| 3b | Python Release Tests (7 modules, 2026-09-29 final12 historical re-run) | `python -m unittest ... test_release_workflow test_rollback_drill test_cve_gate test_verify_clean_clone` | 0 (86 tests) | VERIFIED | 2026-09-29 |
| 3c | Python Release Tests (7 modules, 2026-09-29 final15 historical re-run) | same seven-module release suite | 0 (88 tests) | VERIFIED | 2026-09-29 |
| 3d | Python Release Tests (7 modules, 2026-09-29 final16 historical re-run) | same seven-module release suite | 0 (88 tests) | VERIFIED | 2026-09-29 |
| 3e | Python Release Tests (7 modules, 2026-09-29 final17 current re-run) | same seven-module release suite | 0 (88 tests) | VERIFIED | 2026-09-29 |
| 4 | Release Workflow Tests | `python -m unittest tools.release.test_release_workflow -v` | 0 (25 tests) | VERIFIED | 2026-09-29 |
| 5 | Rollback Drill Tests | `python -m unittest tools.release.test_rollback_drill -v` | 0 | VERIFIED | 2026-09-28 |
| 6 | Clean Clone Verify (HEAD) | `tools/release/verify_clean_clone.ps1` | 0 (18/18) | VERIFIED | 2026-09-29 (final15 historical) |
| 6b | Clean Clone Verify (HEAD) | `tools/release/verify_clean_clone.ps1` | 0 (18/18) | VERIFIED | 2026-09-29 (final16 historical) |
| 6c | Clean Clone Verify (HEAD) | `tools/release/verify_clean_clone.ps1` | 0 (18/18) | VERIFIED | 2026-09-29 (final17 current) |
| 7 | Frontend Build | `npm ci && npm run build` | 0 | VERIFIED | 2026-09-28 (Node v22.22.2 / npm 10.9.7) |
| 7b | Remote Release Workflow (v0.1.2) | `gh run view 36560040245 --repo cgs123456/AmazonERP --json status,conclusion,headSha,url,jobs` | 0 | VERIFIED (quality-gate + release success) | 2026-09-29 |

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

### Verification Status and Remaining Limits

- **VERIFIED** — Release commit `6de12f7` / tag `v0.1.2` `release.yml` (GHCR push + Syft SBOM + Grype scan + Cosign signing): remote run `36560040245` completed successfully on 2026-09-29; GitHub Release `v0.1.2` published with 19 assets. See Checkpoint 17 at the end.
  The old `v0.1.0` tag triggered two failed runs (see the audit below); the `v0.1.2` release commit is verified, while subsequent documentation-only commits are not part of that release.
- **VERIFIED (static only)** — `actionlint` static check of `release.yml`: **已完成 2026-09-29（重跑）** — actionlint 1.7.12，`ci.yml` / `release.yml` exit 0、无诊断输出；发布 ZIP SHA-256 `6e7241b51e6817ea6a047693d8e6fed13b31819c9a0dd6c5a726e1592d22f6e9`，日志 `C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-actionlint-20260929\actionlint-ci-release.log`（空输出 SHA-256 `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`）。（不能替代远端执行。）

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


## Remote release.yml Evidence Audit (historical v0.1.0, 2026-09-29)

> 本节只记录旧 `v0.1.0` 的失败审计；当前 `6de12f7` / `v0.1.2` 的成功审计见文末 Checkpoint 17。

`gh run list --workflow release.yml --limit 20` found two runs, both failures, both from tag `v0.1.0`:

| Run | Head SHA | Result | Failure |
|-----|----------|--------|---------|
| 36392169854 | 30f5e7a1da9ac5cd106b158be7fc4974fdb7eac3 | FAILURE | `Bake images` failed when pushing `ghcr.io/amazonerp-frontend:0.1.0` with `400 Bad Request`; later steps skipped |
| 36394234844 | 4d644da40931a99c65a20675454c3151d779155f | FAILURE | `quality-gate` passed; `anchore/scan-action@v4` reported a Grype DB built 29 weeks ago (max allowed 5 days) and failed on HIGH/CRITICAL; Cosign, manifest, and GitHub Release steps skipped |

The current `v0.1.0` tag object is `3b6385d3372048d01d2abd9a483daee687566da0` and peels to `4d644da40931a99c65a20675454c3151d779155f`. The first run's head SHA is `30f5e7a...`, so the tag was moved/updated between the runs; the exact update mechanism was not established in this audit.

At the time of this audit, HEAD `95397b66be945656f965abe8587fe3de0129114e` differed from the old tag: it removed `anchore/scan-action@v4` and now runs `anchore/grype:latest` plus `tools/release/cve_gate.py` against all 17 release images. That revision had **not** been run remotely. The historical conclusion was later superseded: `6de12f7` / `v0.1.2` passed run `36560040245` (see Checkpoint 17). Therefore the old failures prove only that the old tag's release path executed and failed; they are not evidence that the current workflow fails.

## Task 0 Baseline Inventory Evidence Defect (2026-09-29 addendum)

The frozen inventory `2026-09-28-phase0-baseline-inventory.json` has two independently
verified defects. **Do not edit or overwrite the original inventory JSON**; this addendum
records the correction and the authoritative source.

### Defect 1: statusEntryCount = 631 is wrong; authoritative count is 652

| Source | Count | Status |
|--------|-------|--------|
| `inventory.statusEntryCount` | 631 | WRONG |
| `recovery snapshot changed-files-sha256.csv` data rows | 652 | AUTHORITATIVE |
| `recovery snapshot git-status-porcelain-v2.txt` lines | 652 | AUTHORITATIVE |
| recovery `snapshot-metadata.json` (`StatusEntries`, `ManifestRows`) | 652 | consistent |

- 652 = 325 `tracked-changed` + 327 `untracked`, and the CSV path set and the porcelain-v2
  path set are exactly equal (verified 2026-09-29).
- The value 631 is exactly the insertion count of plan commit `17099b8`
  (`docs(release): define phase 0 production baseline plan`, 1 file, 631 insertions).
  The inventory therefore copied a plan-commit line count instead of counting the frozen
  working-tree status entries. This is a provenance error, not a snapshot error.

### Defect 2: statusSha256 = 17a44917... is unreproducible

`17a449171c5aff132e7c2f38f79c1f71cbb053d838d9375d73886549d74cd4f3` does not match any
file in the recovery snapshot under either raw-byte or LF-normalised hashing. The actual
hashes are:

| File | Raw SHA-256 |
|------|-------------|
| `changed-files-sha256.csv` | `bbb2e3a7eb2cfb178c3b4cda7b041c1e0efe0051e665e12d076998d9ae36713f` |
| `git-status-porcelain-v2.txt` | `3b829fec6aefd249bdf56faf69ac9ebae57ffb906519e77696b24d87410b7145` |
| `git-status-porcelain-v1.txt` | `22cf39f0e12490a62011d57f10df866eba2a820ba7f5d2de376efd02e770f41f` |

The `17a44917...` value is retained in the original inventory only as a historical record.

### Defect 3 (newly found 2026-09-29): inventory sidecar hash does not match the inventory

`2026-09-28-phase0-baseline-inventory.sha256` records
`7b460be79ad53fe735291c954ea1ad03359fc1883b9df0b4edbe10bc28cdfac5`, but the actual
SHA-256 of `2026-09-28-phase0-baseline-inventory.json` is
`a928fe32bab9c8dbb4384f6a263ce0d6129252fc6d07d43c2e0e8565f6579dc4` (2062 bytes, LF, no CR).
Both the JSON and the sidecar are unchanged since `4dfde26`, so the sidecar was wrong at
creation and has been wrong in every commit since. This is a Task 0 evidence-integrity
defect in addition to the 631 / `17a44917...` problems.

### Authoritative correction

Use the recovery snapshot (`C:\Users\Administrator\Desktop\AmazonERP-recovery-20260928-092159`)
and the corrected inventory as the baseline:

- `docs/superpowers/evidence/2026-09-29-phase0-baseline-inventory-corrected.json`
- `docs/superpowers/evidence/2026-09-29-phase0-baseline-inventory-corrected.json.sha256`

The corrected inventory records 652 entries, recovery head `3c8f21e`, raw status-file
SHA-256 `3b829fec6aefd249bdf56faf69ac9ebae57ffb906519e77696b24d87410b7145`,
LF-normalised status-output SHA-256
`08eb4587f47614f52019a89454cc5069928a2fad6e14a7a0357f22f79ed9aed3`, and manifest
SHA-256 `bbb2e3a7eb2cfb178c3b4cda7b041c1e0efe0051e665e12d076998d9ae36713f`. Its sidecar
matches the actual JSON hash, and its entry count / head / status hash / manifest hash agree
with the 652-entry split map:

- `docs/superpowers/evidence/2026-09-28-baseline-split-map.json`
- `docs/superpowers/evidence/2026-09-28-baseline-split-map.json.sha256`

The split map self-check confirms 652 unique paths, exact set equality with both the CSV
manifest and the porcelain-v2 status file, and per-path `status` / `sha256` agreement with
the manifest (1 deleted path correctly has `sha256 = null`). The original defective inventory
is retained unchanged as a historical record; exit condition #1 is satisfied by the corrected
evidence chain.

## Hygiene Triage Addendum (2026-09-29)

The recovery snapshot scan (`--include-untracked`, current allowlist) reports **67 findings**.
All 67 are triaged as non-secrets; **no real Secret requires rotation**. Machine-readable
triage: `docs/superpowers/evidence/2026-09-28-hygiene-triage-addendum.json`.

| Triage | Count | Meaning |
|--------|-------|---------|
| diff-text-noise | 26 | variable names / expressions inside the three recovery diff `.patch` files |
| temporary-artifact-not-for-git | 15 | `.patch*.py` scratch scripts; external snapshot only |
| allowlisted-placeholder-or-test-fixture | 23 | placeholder or test fixture, allowlist path+rule+sha256 match |
| eof-newline-noise | 1 | `RestrictedDataTokenManager.java` differs from the allowlisted file only by a missing final newline |
| aws-official-example-key | 1 | `AKIA...EXAMPLE` (AWS official documentation example, masked to avoid scanner false positive) |
| aws-official-model-fixture | 1 | presigned-URL credential in the official `uploads_2020-11-01.json` model fixture |

### Measurement artifact that must not be misread

Scanning the snapshot root reports untracked files with an `untracked/` path prefix, so the
repository-relative allowlist cannot match them and 25 placeholder findings appear
"unallowlisted". Re-scanning with the snapshot's `untracked/` directory as root removes the
artifact and leaves 21 findings (15 temporary scripts, 5 round artifacts, 1 EOF-newline
mismatch). The 25 findings were independently confirmed to match the allowlist by path,
rule and LF-normalised SHA-256.

### Current published tree

`python tools/release/repository_hygiene.py --root .` on the current checkout: **0 findings,
exit 0**. The 67 findings exist only in the external recovery snapshot and are not a defect
in the tracked release tree.


## Task 8 Baseline Split Map Addendum (2026-09-29)

### Artifacts

| Artifact | SHA-256 | Entries |
|----------|---------|---------|
| `2026-09-28-baseline-split-map.json` | `b71985b668a078726ffb71f04bc3f35688f375860c71e7200775a70c91bf515e` | 652 |
| `2026-09-28-baseline-review-required.md` | `932cf176be07550c809db9c6c0d2370b0d2bee514898c74fc870453fa323bbb6` | 182 |

The split map is derived from the authoritative recovery snapshot. Self-check (2026-09-29):
652 unique paths, exact set equality with `changed-files-sha256.csv` and
`git-status-porcelain-v2.txt`, per-path `status` / `sha256` agreement, deterministic
path ordering, and a matching `.sha256` sidecar.

### Group counts

| Group | Count |
|-------|-------|
| review-required | 182 |
| spapi | 176 |
| ad | 65 |
| finance | 48 |
| common | 32 |
| product | 27 |
| frontend | 26 |
| ai | 22 |
| docs | 20 |
| deploy/k8s | 18 |
| synthetic-data | 17 |
| order | 16 |
| gateway | 3 |

### Classification counts

| Classification | Count |
|----------------|-------|
| source | 340 |
| test | 176 |
| temporary | 73 |
| config | 38 |
| docs | 23 |
| generated | 2 |

`secret-suspect = 0`. The 73 `temporary` entries are preserved in the external recovery
snapshot; 71 of them fall outside the group allowlist and are listed in
`2026-09-28-baseline-review-required.md` section B.

### review-required (exit condition #9 "or" branch)

182 of 652 paths fall outside the Task 8 group allowlist: **111 non-temporary paths needing
owner review** plus **71 temporary artifacts that must not enter Git**. The complete list is
in `2026-09-28-baseline-review-required.md`. This satisfies the exit-condition #9 alternative
("or explicitly list the remaining `review-required` paths") but does **not** satisfy
Step 3's per-subsystem commit split.

### Where the frozen paths went

| Disposition | Count | Evidence |
|-------------|-------|----------|
| Present in current `HEAD` tree | 580 | `git ls-tree -r --name-only 95397b6` |
| Added then deleted (only `spapi/.../db/schema.sql`) | 1 | added in `7d933f2`, absent from `HEAD` |
| Never entered later history (all `temporary`) | 71 | absent from `HEAD` and from the absorption commits |

First-commit absorption of the 580 paths still present (commit where each path first appears
in `3c8f21e..HEAD`): `fc07f6b` 235, `7d933f2` 206, `d2b7619` 134, `f01738c` 2,
`d12a9e2` 2, `37a193b` 1.

### Step 3 status: NOT DONE

No `feat(api-ready):` / `docs(api-ready):` per-subsystem commit sequence exists. The frozen
dirty work was absorbed by whole-package commits (`fc07f6b`, `d2b7619`, `7d933f2`, ...).
Producing the original per-subsystem sequence now requires either history rewrite /
force-push (not approved, high risk) or revert + re-split (heavy). Neither was performed.
Task 8 is therefore **partially / alternatively complete**: Step 1 and the exit-condition #9
"or" branch are satisfied; Step 3 is explicitly not.

## Checkpoint 4 Local Re-verification (2026-09-29)

This checkpoint records the local re-run after the Task 0 / Task 8 evidence corrections. It
does not change code behavior and does not constitute remote `release.yml` verification.

| Check | Command | Result |
|-------|---------|--------|
| Repository hygiene | `python tools/release/repository_hygiene.py --root .` | 0 findings, exit 0 |
| Python release tests | `python -m unittest discover -s tools/release -p 'test_*.py'` | 68 tests OK, exit 0 |
| Maven full test | `mvn -B -ntp test -fae` | 19/19 SUCCESS, BUILD SUCCESS |
| Diff whitespace | `git diff --check` | exit 0 |
| Untracked-inclusive hygiene | `python tools/release/repository_hygiene.py --root . --include-untracked` | 48 findings, exit 1; all are local runtime logs outside the tracked release tree |

### Artifact hash self-check

| Artifact | Actual SHA-256 | Sidecar SHA-256 | Result |
|----------|----------------|-----------------|--------|
| `2026-09-28-baseline-split-map.json` | `b71985b668a078726ffb71f04bc3f35688f375860c71e7200775a70c91bf515e` | same | MATCH |
| `2026-09-28-baseline-review-required.md` | `932cf176be07550c809db9c6c0d2370b0d2bee514898c74fc870453fa323bbb6` | same | MATCH |
| `2026-09-28-hygiene-triage-addendum.json` | `f7c9c45dcb4d22f1f5f9718808bf1e0e06a5b6601ef57aa7b3ac4fd50be081a0` | same | MATCH |
| `2026-09-28-phase0-baseline-inventory.json` | `a928fe32bab9c8dbb4384f6a263ce0d6129252fc6d07d43c2e0e8565f6579dc4` | `7b460be79ad53fe735291c954ea1ad03359fc1883b9df0b4edbe10bc28cdfac5` | MISMATCH (do not overwrite inventory) |

The `--include-untracked` result is intentionally recorded as a local measurement, not as a
tracked-release-tree failure. The findings come from untracked runtime logs in the working
directory (for example `_*.log`, `round*.log`, `mvn-*.log`); the tracked tree remains 0
findings. No user runtime logs were deleted to make this command pass.

## Checkpoint 5: Task 0 Corrected Inventory (2026-09-29)

| Artifact | Actual SHA-256 | Sidecar SHA-256 | Result |
|----------|----------------|-----------------|--------|
| `2026-09-29-phase0-baseline-inventory-corrected.json` | `d47724c6c9098ee12e58e711ca3417853d29111b53e445358aeebab3d2ee0922` | same | MATCH |
| `2026-09-28-baseline-split-map.json` | `b71985b668a078726ffb71f04bc3f35688f375860c71e7200775a70c91bf515e` | same | MATCH |

Consistency checks: corrected inventory `statusEntryCount=652`; recovery manifest rows = 652;
porcelain-v2 lines = 652; split-map entries = 652; corrected inventory head/status/manifest
hashes equal the split-map `generatedFrom` values; all 10 recovery snapshot file byte counts
and SHA-256 values match the corrected inventory. The original inventory remains untouched.

## Checkpoint 6: Current Uncommitted Worktree Clean-Copy Verification (2026-09-29)

This checkpoint covers the exact 12-path uncommitted candidate state in the main worktree. It
is separate from the 2026-09-28 clean clone, which covered the committed `HEAD` only. The copy
is intentionally dirty after applying the candidate patch; this proves reproducibility of the
candidate tree, not that the candidate is committed or that `release.yml` passed remotely.

### Reproduction and provenance

- Source repository: `C:\Users\Administrator\Desktop\AmazonERP`
- Source HEAD: `95397b66be945656f965abe8587fe3de0129114e`
- Temporary root: `C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-current-20260929-01`
- Procedure: `git clone --no-hardlinks --local`, `git checkout --detach 95397b6`,
  `git diff --binary --full-index` to `working-tree-final.patch`, `git apply --check`, `git apply`,
  then copy the 8 untracked candidate files.
- `working-tree-final.patch` is a **tracked-diff-only** artifact: it contains the 4 modified
  tracked files and excludes the 8 untracked candidate files. Its SHA-256 is recorded in the
  external final evidence file below; it must not be described as a hash of the full 12-path
  candidate.
- Candidate status after materialisation: 12 paths, matching the main worktree status set.
- Full candidate identity: the deterministic 12-path inventory digest, tracked-diff-only patch
  hash, and per-path byte counts / SHA-256 values are recorded in the external final evidence
  file below. The inventory digest is computed from canonical JSON of `head`, the sorted
  `status` list, and sorted per-path `status` / `bytes` / `sha256` entries. It is not embedded
  in this document because editing this document would change its own hash.
- Checkpoint 6 external machine-readable evidence (historical):
  `C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-current-20260929-01\current-worktree-evidence-final.json`; current final8 evidence is `current-worktree-evidence-final8.json` in the same evidence root.
  (the file's own SHA-256 is intentionally not embedded here for the same self-reference
  reason; verify it independently with `Get-FileHash`).

### Verification results

| Check | Command | Result |
|-------|---------|--------|
| Whitespace diff | `git diff --check` | PASS, exit 0 |
| Tracked-tree hygiene | `python tools/release/repository_hygiene.py --root .` | 0 findings, exit 0 |
| Untracked-inclusive hygiene in copy | `python tools/release/repository_hygiene.py --root . --include-untracked` | 0 findings, exit 0 |
| Python release tests | `python -m unittest discover -s tools/release -p 'test_*.py'` | 68 tests OK, exit 0 |
| Maven full test | `mvn -B -ntp test -fae` | 19/19 modules SUCCESS, BUILD SUCCESS, exit 0 |
| Frontend typecheck | `npx vue-tsc --noEmit` | PASS |
| Frontend tests | `npm run test:run` | 22 files / 175 tests PASS |
| Frontend build | `npm run build` | PASS, exit 0 |
| Release manifest determinism | two manifest generations + `verify` | byte-identical; file SHA-256 `1b3fbafb7399d88ad3f487b5489d5b725fe1b15321eaec6a77493d2bdbf66573`; internal `manifestSha256` `38d23f970dfc738d3fd080993418b6c2db3541404e045588f9e30a71c9b1f4a7`; `source.commit` `95397b66be945656f965abe8587fe3de0129114e`; `migrations.treeSha256` `832b6563496e2eec059da496bf202ad74892c2ff87c4093226cff7fb53260444`; `migrations.count` 49; `frontend.treeSha256` `be9e681335058cdf2bebe06885db25d9686a50b8cc61716722ab06224bc3fa99`; `frontend.fileCount` 75; no absolute paths |
| Docker Bake expression | `docker buildx bake -f docker-bake.hcl --print` | exit 0; 17 targets = gateway + 15 services + frontend |
| Recovery snapshot integrity | 10/10 snapshot files | byte counts and SHA-256 MATCH |

The release manifest's `source.commit` now equals the current source HEAD. Because the
candidate is still uncommitted, this is a local determinism check, not a release approval.
The manifest covers frontend and migration inputs only; it is not a hash of the full 12-path
candidate, and it uses a placeholder image digest. The `832b...` value above is
`migrations.treeSha256`; `be9e...` is `frontend.treeSha256`. There is no separately verified
root-tree hash in this manifest.

### Limitations

- The candidate copy is dirty by design; this is not a clean-checkout build of a commit.
- The main worktree's `--include-untracked` scan still reports 48 findings from untracked
  runtime logs. Those logs were not deleted; the clean-copy scan is the evidence that the
  candidate's tracked plus candidate-evidence files are clean.
- At the time of this checkpoint, the then-current HEAD's remote `release.yml` execution remained **NOT VERIFIED** (historical; superseded by Checkpoint 17); the old `v0.1.0` tag has two failed remote runs (see the audit above).
- `npm audit --json` reports 2 moderate vulnerabilities from the dev-only `vitest` /
  `@vitest/mocker` chain (`GHSA-82fw-gwwq-j7x9`, CVSS 5.9). The fix requires upgrading
  `vitest` to 5.0.2 (a major upgrade); no production `dist` dependency is implicated and no
  upgrade was performed in this checkpoint.

## Checkpoint 7: Final Clean-Copy Full Verification Rerun (2026-09-29)

This checkpoint closes the evidence gap where Checkpoint 6 recorded Maven, frontend, and
Docker Bake results from an earlier candidate copy. The commands below were rerun in the
final candidate copy created for the exact 12-path uncommitted worktree.

- Source HEAD: `95397b66be945656f965abe8587fe3de0129114e`
- Final copy: `C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-final8-20260929\clone`
- Materialisation: local no-hardlink clone, detached checkout of the source HEAD, apply the
  4-file tracked-diff-only patch `working-tree-final8.patch`, then copy the 8 untracked candidate files.
- A fresh clone does not contain the ignored `amz-frontend\node_modules` directory. The
  frontend checks therefore started with `npm ci --no-audit --no-fund`, which installed 266
  packages with exit 0.

| Check | Command | Result |
|-------|---------|--------|
| Dependency install | `npm ci --no-audit --no-fund` | 266 packages, exit 0; log `frontend-npm-ci-final.log` |
| Maven full test | `$env:TEMP='C:\Temp\amz-uds'; $env:TMP='C:\Temp\amz-uds'; mvn -B -ntp '-Djdk.net.unixdomain.tmpdir=C:\Temp\amz-uds' test -fae` | 19/19 reactor modules `SUCCESS`, `BUILD SUCCESS`, exit 0; current candidate log `maven-final8.log` |
| Frontend typecheck | `node node_modules\vue-tsc\bin\vue-tsc.js --noEmit` | exit 0; log `typecheck-final8.log` |
| Frontend tests | `node node_modules\vitest\vitest.mjs run` | 22 files / 175 tests PASS, exit 0; log `vitest-final8.log` |
| Frontend production build | `node node_modules\vite\bin\vite.js build` | exit 0; log `vite-build-final8.log` |
| Docker Bake expression | `docker buildx bake -f docker-bake.hcl --print` | exit 0; 17 targets = gateway + 15 services + frontend; log `docker-bake-final8.log` |

The Maven, frontend, and Docker Bake rows above are the final8 rerun executed after this document revision was copied into the final candidate copy; `npm ci`
was run earlier in the same final copy against the unchanged lockfile. The final4 and final7 logs are retained as earlier reruns and are not the current candidate evidence.

The first final3 Maven invocation omitted the documented JDK AF_UNIX prefix and failed in
`amz-service-ai` and `amz-service-ad` with
`Unable to establish loopback connection / SocketException: Invalid argument: connect`.
That failure was reproduced independently of the repository with a minimal Java
`LoopbackProbe` using `Selector.open()` and `HttpClient.newHttpClient()`. Re-running with
`TEMP/TMP=C:\Temp\amz-uds` and `-Djdk.net.unixdomain.tmpdir=C:\Temp\amz-uds` made the targeted
modules pass (AI 108/108, AD 117/117) and then made the full reactor pass 19/19. The original
failure log is preserved as `maven-final3-initial-failure.log`; the current candidate rerun is
`maven-final8.log` (the earlier recovery rerun is `maven-final3.log`), with `maven-final3-targeted-uds.log` and `loopback-probe-uds-final.log`
retained as supporting evidence. This was an environment-only workaround; no repository source
change was made for it.

The final copy is still a dirty candidate tree by design. This rerun proves that the candidate
files can reproduce the full local verification; it does not prove a committed clean checkout
or remote `release.yml` execution of the current HEAD. No commit, push, tag, or history rewrite was performed; the old `v0.1.0` runs predate this checkpoint.
The machine-readable per-path hashes, canonical candidate digest, and tracked-diff-only patch
hash are maintained in the external final evidence JSON, which is regenerated after this
document edit so that its candidate digest is not self-referential.


## Checkpoint 8 (Historical): final9 14-Path Candidate Verification (2026-09-29)

This checkpoint superseded Checkpoint 6/7 for the earlier 12-path / final8 snapshot and is now
historical; Checkpoint 9 supersedes it for the current final11 candidate.

- Current candidate: **14 paths** = **6 modified tracked files + 8 untracked files**.
- Current tracked-diff-only patch: `working-tree-final9.patch`; it contains the 6 modified
  tracked files and excludes the 8 untracked candidate files.
- final9 clone: `C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-final9-20260929\clone`.
- final9 machine-readable evidence:
  `C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-current-20260929-01\current-worktree-evidence-final9.json`;
  independent verification log:
  `C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-current-20260929-01\independent-final9-verification.log`.
- Python release tests are **78 tests OK** in final9. The 68-test values in Checkpoint 4/6/7
  are historical and are not the current count.
- final9 uses the current PATH toolchain: Maven `3.9.16` and Temurin JDK `21.0.12.1`. The
  Maven 3.9.11 fixed path in the historical plan is not the toolchain used for this rerun.
- The clone is intentionally a dirty candidate tree after materialisation, not a committed
  clean checkout. At the time of this checkpoint, the then-current HEAD's remote `release.yml` execution remained **NOT VERIFIED** (historical; superseded by Checkpoint 17);
  actionlint 1.7.12 is static-only and does not replace a remote run.
- No commit, push, tag, or history rewrite was performed for this checkpoint.

The authoritative per-path status/bytes/SHA-256 records, canonical inventory digest,
tracked-diff-only patch hash, and verification-log hashes are maintained in the external
final9 evidence JSON. They are intentionally not embedded in this document to avoid a
self-referential candidate digest.

## Checkpoint 9 (Historical): final11 17-Path Candidate Verification (2026-09-29)

This checkpoint records the historical final11 candidate and is now superseded by
Checkpoint 10. Checkpoint 6-8 remain historical evidence for the earlier final8/final9 snapshots.

- Current candidate: **17 paths** = **9 modified tracked files + 8 untracked files**.
- Current tracked-diff-only patch: `working-tree-final11.patch`; it contains the 9 modified
  tracked files and excludes the 8 untracked candidate files.
- final11 clone: `C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-final11-20260929\clone`.
- final11 machine-readable evidence:
  `C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-current-20260929-01\current-worktree-evidence-final11.json`;
  independent verification log:
  `C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-current-20260929-01\independent-final11-verification.log`.
- Python release tests are **86 tests OK** in final11 (including **24/24** in `test_release_workflow.py`). Maven is **19/19 reactor modules
  SUCCESS**. Frontend is **22 files / 175 tests PASS**, with typecheck and production build
  exit 0. Docker Bake exposes **17 targets**. `npm audit` reports **2 moderate / 0 high /
  0 critical** issues in the dev-only Vitest chain; no dependency upgrade was performed in
  this checkpoint.
- The clean-clone orchestration check (HEAD, not the dirty candidate) is **18/18 checks
  VERIFIED**, with 0 required failures and 0 not-verified checks. actionlint **1.7.12**
  exits 0 for `ci.yml` and `release.yml`, but remains static-only and does not replace a
  remote run.
- Release workflow hardening: SBOM, Grype+CVE gate, and Cosign loops now all consume the
  workflow-level `RELEASE_IMAGES` list. A contract test verifies that this ordered list
  matches the 17 tag-bearing targets in `docker-bake.hcl`; the quality gate now explicitly
  runs frontend typecheck, unit tests, and the production build.
- **Known limitation:** `release_manifest.py` is schema v1 and records only the gateway
  single-image `image.ref` / `image.digest`. `release.yml` passes only the gateway digest
  to the manifest. SBOM, Grype scanning, and Cosign signing cover the 17 Bake images, but
  the release manifest does not. A future multi-image manifest requires a schema upgrade
  and corresponding rollback-drill/test changes.
- The clone is intentionally a dirty candidate tree after materialisation, not a committed
  clean checkout. At the time of this checkpoint, the then-current HEAD's remote `release.yml` execution remained **NOT VERIFIED** (historical; superseded by Checkpoint 17);
  the old `v0.1.0` runs failed and do not prove the current revision.
- No commit, push, tag, or history rewrite was performed for this checkpoint.

The authoritative per-path status/bytes/SHA-256 records, canonical inventory digest,
tracked-diff-only patch hash, and verification-log hashes are maintained in the external
final11 evidence JSON. They are intentionally not embedded in this document to avoid a
self-referential candidate digest.

## Checkpoint 10: final12 17-Path Candidate Verification (2026-09-29)

This checkpoint supersedes Checkpoint 9 for the current candidate. Checkpoint 6-9 remain
historical evidence for the earlier final8, final9, and final11 snapshots.

- Current candidate: **17 paths** = **9 modified tracked files + 8 untracked files**.
- Current tracked-diff-only patch: `working-tree-final12.patch`; it excludes the 8 untracked
  candidate paths. The first final12 patch used short `index` headers, so an independent
  `git diff --binary --full-index` recomputation differed in those header lines only. The
  current patch is regenerated with `--full-index`; the superseded short-index file is retained
  as `working-tree-final12.short-index.patch` for provenance, but it is not current evidence.
- final12 clone: `C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-final12-20260929\clone`.
- final12 machine-readable evidence:
  `C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-current-20260929-01\current-worktree-evidence-final12.json`;
  independent verification log:
  `C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-current-20260929-01\independent-final12-verification.log`.
- Rebuild assertions are **24/24 True**. Independent verification is **48/48 checks True** and
  **24/24 computed assertions True**, ending in `ALL_CHECKS=True`; the full-index patch
  reverse-applies cleanly in the clone.
- Candidate-level tests remain **86 tests OK** for the Python release suite, including
  **24/24** in `test_release_workflow.py`. Maven is **19/19 reactor modules SUCCESS**.
  Frontend is **22 files / 175 tests PASS**, with typecheck and production build exit 0.
  Docker Bake exposes **17 targets**; actionlint **1.7.12** exits 0 for `ci.yml` and
  `release.yml`. `npm audit` reports **2 moderate / 0 high / 0 critical** in the dev-only
  Vitest chain and remains an expected non-zero audit exit, not an acceptance failure.
- The release manifest is byte-identical across two runs and `verify` passes. The clean-clone
  orchestration check (HEAD, not the dirty candidate) is **18/18 checks VERIFIED**, with 0
  required failures and 0 not-verified checks.
- **Materialisation limitation:** `.gitattributes` does not pin `*.py` to `eol=lf`. On Windows
  with `core.autocrlf=true`, a fresh clone checks out `tools/release/test_release_workflow.py`
  and `tools/release/test_verify_clean_clone.py` as CRLF, while the final12 candidate copies
  contain LF: the observed byte counts are 11414 vs 11653 and 2688 vs 2765 respectively.
  Therefore, byte-identical final12 materialisation used an explicit copy of all 17 candidate
  paths; it did **not** prove that fresh clone + apply patch alone reproduces the same bytes.
  The semantic build and test evidence remains valid. A future hard fix is to add an attribute
  such as `*.py text eol=lf` (making an 18th candidate path and requiring a full re-verification)
  or to enforce `core.autocrlf=false` before checkout and document that requirement. Until one
  is adopted and re-verified, do not claim strict fresh-clone byte reproducibility.
- The clone is intentionally a dirty candidate tree after materialisation, not a committed
  clean checkout. At the time of this checkpoint, the then-current HEAD's remote `release.yml` execution remained **NOT VERIFIED** (historical; superseded by Checkpoint 17); the
  old `v0.1.0` runs failed and do not prove the current revision.
- No commit, push, tag, or history rewrite was performed for this checkpoint.

The authoritative per-path status/bytes/SHA-256 records, canonical inventory digest,
tracked-diff-only patch bytes/hash, and verification-log hashes are maintained in the external
final12 evidence JSON. They are intentionally not embedded in this document to avoid a
self-referential candidate digest.

## Checkpoint 11: final13 17-Path Candidate Verification (2026-09-29)

This checkpoint supersedes Checkpoint 10 for the current candidate. Checkpoints 6–10 remain
historical evidence for the earlier final8, final9, final11, and final12 snapshots.

- Current candidate: **17 paths** = **9 modified tracked files + 8 untracked files**.
- Current tracked-diff-only patch: `working-tree-final13.patch`; it excludes the 8 untracked
  candidate paths and is generated with `git diff --binary --full-index`.
- final13 clone: `C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-final13-20260929\clone`.
- final13 machine-readable evidence:
  `C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-current-20260929-01\current-worktree-evidence-final13.json`;
  independent verification log:
  `C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-current-20260929-01\independent-final13-verification.log`.
- The pre-fix probe observed the main-worktree `docker-bake.hcl` as CRLF (6727 bytes) and the
  fresh-clone copy as LF (6456 bytes), producing `BYTE_IDENTICAL=False`. The file was
  normalised to LF-only without adding a new `.gitattributes` path. The repeat probe used
  `git clone --config core.autocrlf=false --config core.eol=lf`, applied the full-index patch,
  copied the 8 untracked files, and observed **17/17 paths byte-identical**
  (`BYTE_IDENTICAL=True`).
- Rebuild assertions are **24/24 True**. Independent verification is **48/48 checks True** and
  **24/24 computed assertions True**, ending in `ALL_CHECKS=True`; the full-index patch
  reverse-applies cleanly in the clone.
- Candidate-level tests are **87 tests OK** for the Python release suite, including **24/24**
  in `test_release_workflow.py`. Maven is **19/19 reactor modules SUCCESS**. Frontend is
  **22 files / 175 tests PASS**, with typecheck and production build exit 0. Docker Bake
  exposes **17 targets**; actionlint **1.7.12** exits 0 for `ci.yml` and `release.yml`.
  `npm audit` reports **2 moderate / 0 high / 0 critical** in the dev-only Vitest chain and
  remains an expected non-zero audit exit, not an acceptance failure.
- The release manifest is byte-identical across two runs and `verify` passes. The clean-clone
  orchestration check (HEAD, not the dirty candidate) is **18/18 checks VERIFIED**, with 0
  required failures and 0 not-verified checks.
- **Known limitation:** `.gitattributes` still does not pin `*.py` or `*.hcl` to `eol=lf`.
  The verified strict byte materialisation therefore depends on the explicit
  `--config core.autocrlf=false --config core.eol=lf` clone arguments in
  `verify_clean_clone.ps1`; a clone without those arguments on a Windows host whose effective
  `core.autocrlf=true` may still convert line endings. This is explicit execution-contract
  hardening, not repository-wide attribute hardening.
- The clone is intentionally a dirty candidate tree after materialisation, not a committed
  clean checkout. At the time of this checkpoint, the then-current HEAD's remote `release.yml` execution remained **NOT VERIFIED** (historical; superseded by Checkpoint 17);
  the old `v0.1.0` runs failed and do not prove the current revision.
- No commit, push, tag, or history rewrite was performed for this checkpoint.

The authoritative per-path status/bytes/SHA-256 records, canonical inventory digest,
tracked-diff-only patch bytes/hash, and verification-log hashes are maintained in the external
final13 evidence JSON. They are intentionally not embedded in this document to avoid a
self-referential candidate digest.

## Checkpoint 12: final14 17-Path Candidate Verification (2026-09-29)

This checkpoint superseded Checkpoint 11 for the then-current candidate and is retained as historical evidence after Checkpoint 13. Checkpoints 6–11 remain
historical evidence for the earlier final8, final9, final11, final12, and final13 snapshots.

- Then-current candidate: **17 paths** = **9 modified tracked files + 8 untracked files**.
- Then-current tracked-diff-only patch: `working-tree-final14.patch`; it excludes the 8 untracked
  candidate paths and is generated with `git diff --binary --full-index`.
- final14 clone: `C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-final14-20260929\clone`.
- final14 machine-readable evidence:
  `C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-current-20260929-01\current-worktree-evidence-final14.json`;
  independent verification log:
  `C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-current-20260929-01\independent-final14-verification.log`.
- The clone used `git clone --config core.autocrlf=false --config core.eol=lf`, applied the
  full-index patch, copied the 8 untracked files, and observed **17/17 paths byte-identical**
  (`BYTE_IDENTICAL=True`). This remains an explicit execution contract, not repository-wide
  `.gitattributes` hardening.
- Rebuild assertions are **24/24 True**. Independent verification is **48/48 checks True** and
  **24/24 computed assertions True**, ending in `ALL_CHECKS=True`; the full-index patch
  reverse-applies cleanly in the clone.
- Candidate-level tests are **88 tests OK** for the Python release suite, including **25/25**
  in `test_release_workflow.py`. The release workflow now includes
  `environment: production`, and the contract test verifies the release job requires it.
  Remote GitHub API evidence on 2026-09-29 confirms `environments = []`, no required
  reviewers, no `master` branch protection, and default workflow permissions `read`;
  the YAML declaration therefore provides no approval gate until repository settings are
  configured.
  Maven is **19/19 reactor modules SUCCESS**. Frontend is **22 files / 175 tests PASS**, with
  typecheck and production build exit 0. Docker Bake exposes **17 targets**; actionlint
  **1.7.12** exits 0 for `ci.yml` and `release.yml`. `npm audit` reports **2 moderate /
  0 high / 0 critical** in the dev-only Vitest chain and remains an expected non-zero audit
  exit, not an acceptance failure.
- The release manifest is byte-identical across two runs and `verify` passes. The clean-clone
  orchestration check (HEAD, not the dirty candidate) is **18/18 checks VERIFIED**, with 0
  required failures and 0 not-verified checks.
- **Known limitation:** `.gitattributes` still does not pin `*.py` or `*.hcl` to `eol=lf`.
  The verified strict byte materialisation therefore depends on the explicit
  `--config core.autocrlf=false --config core.eol=lf` clone arguments in
  `verify_clean_clone.ps1`; a clone without those arguments on a Windows host whose effective
  `core.autocrlf=true` may still convert line endings. This is explicit execution-contract
  hardening, not repository-wide attribute hardening.
- The clone is intentionally a dirty candidate tree after materialisation, not a committed
  clean checkout. At the time of this checkpoint, the then-current HEAD's remote `release.yml` execution remained **NOT VERIFIED** (historical; superseded by Checkpoint 17);
  the old `v0.1.0` runs failed and do not prove the current revision.
- No commit, push, tag, or history rewrite was performed for this checkpoint.

The authoritative per-path status/bytes/SHA-256 records, canonical inventory digest,
tracked-diff-only patch bytes/hash, and verification-log hashes are maintained in the external
final14 evidence JSON. They are intentionally not embedded in this document to avoid a
self-referential candidate digest.

## Checkpoint 13 (Historical): final15 17-Path Candidate Verification (2026-09-29)

This checkpoint superseded Checkpoint 12 for the then-current candidate and is retained as historical evidence after Checkpoint 14. Checkpoints 6–12 remain historical evidence for the earlier final8, final9, final11, final12, final13, and final14 snapshots.

- Then-current candidate: **17 paths** = **9 modified tracked files + 8 untracked files**.
- Then-current tracked-diff-only patch: `working-tree-final15.patch`; it excludes the 8 untracked candidate paths and is generated with `git diff --binary --full-index`.
- final15 clone: `C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-final15-20260929\clone`.
- final15 machine-readable evidence: `C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-current-20260929-01\current-worktree-evidence-final15.json`; independent verification log: `C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-current-20260929-01\independent-final15-verification.log`.
- Relative to final14, this checkpoint updated the three documentation files: the plan marks final12 historical, the handoff adds §18, and the verification document adds Checkpoint 13; no product code, build configuration, or test logic changed. Because candidate bytes changed, the final14 patch/evidence hashes are superseded and must not be reused.
- The clone used `git clone --config core.autocrlf=false --config core.eol=lf`, applied the full-index patch, copied the 8 untracked files, and observed **17/17 paths byte-identical** (`BYTE_IDENTICAL=True`). This remains an explicit execution contract, not repository-wide `.gitattributes` hardening.
- Rebuild assertions are **24/24 True**. Independent verification is **48/48 checks True** and **24/24 computed assertions True**, ending in `ALL_CHECKS=True`; the full-index patch reverse-applies cleanly in the clone.
- Candidate-level tests are **88 tests OK** for the Python release suite, including **25/25** in `test_release_workflow.py`. Maven is **19/19 reactor modules SUCCESS**. Frontend is **22 files / 175 tests PASS**, with typecheck and production build exit 0. Docker Bake exposes **17 targets**; actionlint **1.7.12** exits 0 for `ci.yml` and `release.yml`. `npm audit` reports **2 moderate / 0 high / 0 critical** in the dev-only Vitest chain and remains an expected non-zero audit exit, not an acceptance failure.
- The release manifest is byte-identical across two runs and `verify` passes. The clean-clone orchestration check (HEAD, not the dirty candidate) is **18/18 checks VERIFIED**, with 0 required failures and 0 not-verified checks.
- **Known limitation:** `.gitattributes` still does not pin `*.py` or `*.hcl` to `eol=lf`. The verified strict byte materialisation therefore depends on the explicit `--config core.autocrlf=false --config core.eol=lf` clone arguments in `verify_clean_clone.ps1`; a clone without those arguments on a Windows host whose effective `core.autocrlf=true` may still convert line endings.
- The clone is intentionally a dirty candidate tree after materialisation, not a committed clean checkout. At the time of this checkpoint, the then-current HEAD's remote `release.yml` execution remained **NOT VERIFIED** (historical; superseded by Checkpoint 17); the old `v0.1.0` runs failed and do not prove the current revision.
- No commit, push, tag, or history rewrite was performed for this checkpoint.

The authoritative per-path status/bytes/SHA-256 records, canonical inventory digest, tracked-diff-only patch bytes/hash, and verification-log hashes are maintained in the external final15 evidence JSON. They are intentionally not embedded in this document to avoid a self-referential candidate digest.

## Checkpoint 14 (Historical): final16 17-Path Candidate Verification (2026-09-29)

This checkpoint superseded Checkpoint 13 for the then-current candidate and is retained as historical evidence after Checkpoint 15. Checkpoints 6–13 remain historical evidence for the earlier final8, final9, final11, final12, final13, final14, and final15 snapshots.

- Current candidate: **17 paths** = **9 modified tracked files + 8 untracked files**.
- Current tracked-diff-only patch: `working-tree-final16.patch`; it excludes the 8 untracked candidate paths and is generated with `git diff --binary --full-index`.
- final16 clone: `C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-final16-20260929\clone`.
- final16 machine-readable evidence: `C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-current-20260929-01\current-worktree-evidence-final16.json`; independent verification log: `C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-current-20260929-01\independent-final16-verification.log`.
- Relative to final14, the final15/final16 documentation changes mark the final12 section historical and add the final15/final16 checkpoint/status sections to the plan, handoff, and verification documents; no product code, build configuration, or test logic changed. Because candidate bytes changed, the final14 and final15 patch/evidence hashes are superseded and must not be reused.
- The clone used `git clone --config core.autocrlf=false --config core.eol=lf`, applied the full-index patch, copied the 8 untracked files, and observed **17/17 paths byte-identical** (`BYTE_IDENTICAL=True`). This remains an explicit execution contract, not repository-wide `.gitattributes` hardening.
- Rebuild assertions are **24/24 True**. Independent verification is **48/48 checks True** and **24/24 computed assertions True**, ending in `ALL_CHECKS=True`; the full-index patch reverse-applies cleanly in the clone.
- Candidate-level tests are **88 tests OK** for the Python release suite, including **25/25** in `test_release_workflow.py`. Maven is **19/19 reactor modules SUCCESS**. Frontend is **22 files / 175 tests PASS**, with typecheck and production build exit 0. Docker Bake exposes **17 targets**; actionlint **1.7.12** exits 0 for `ci.yml` and `release.yml`. `npm audit` reports **2 moderate / 0 high / 0 critical** in the dev-only Vitest chain and remains an expected non-zero audit exit, not an acceptance failure.
- The release manifest is byte-identical across two runs and `verify` passes. The clean-clone orchestration check (HEAD, not the dirty candidate) is **18/18 checks VERIFIED**, with 0 required failures and 0 not-verified checks.
- **Known limitation:** `.gitattributes` still does not pin `*.py` or `*.hcl` to `eol=lf`. The verified strict byte materialisation therefore depends on the explicit `--config core.autocrlf=false --config core.eol=lf` clone arguments in `verify_clean_clone.ps1`; a clone without those arguments on a Windows host whose effective `core.autocrlf=true` may still convert line endings.
- The clone is intentionally a dirty candidate tree after materialisation, not a committed clean checkout. At the time of this checkpoint, the then-current HEAD's remote `release.yml` execution remained **NOT VERIFIED** (historical; superseded by Checkpoint 17); the old `v0.1.0` runs failed and do not prove the current revision.
- No commit, push, tag, or history rewrite was performed for this checkpoint.

The authoritative per-path status/bytes/SHA-256 records, canonical inventory digest, tracked-diff-only patch bytes/hash, and verification-log hashes are maintained in the external final16 evidence JSON. They are intentionally not embedded in this document to avoid a self-referential candidate digest.

## Checkpoint 15 (Historical): final17 18-Path Candidate Verification (2026-09-29)

This checkpoint superseded Checkpoint 14 for the then-current candidate and is retained as historical evidence after Checkpoint 16. Checkpoints 6–14 remain historical evidence for the earlier final8, final9, final11, final12, final13, final14, and final15 snapshots.

- Current candidate: **18 paths** = **10 modified tracked files + 8 untracked files**. Relative to final16, the candidate adds the `Dockerfile` POM-copy correctness fix and normalises the Dockerfile to LF-only.
- The previous `COPY amz-service/*/pom.xml ./amz-service/` flattened 15 child POMs into the same directory and overwrote the parent `amz-service/pom.xml`, invalidating the Maven dependency-cache layer. The Dockerfile now copies each child POM into its own module directory. This does not fix the historical remote `Bake images` GHCR `400 Bad Request`.
- Remote GitHub API evidence on 2026-09-29 confirms `environments = {"total_count":0,"environments":[]}`, no `production` environment or required reviewers, HTTP 404 for `branches/master/protection`, and `default_workflow_permissions: read`. The `environment: production` declaration is therefore only a contract and provides no release approval gate until repository settings are configured.
- Current tracked-diff-only patch: `working-tree-final17.patch`; it excludes the 8 untracked candidate paths and is generated with `git diff --binary --full-index`.
- final17 clone: `C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-final17-20260929\clone`.
- final17 machine-readable evidence: `C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-current-20260929-01\current-worktree-evidence-final17.json`; independent verification log: `C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-current-20260929-01\independent-final17-verification.log`.
- The clone used `git clone --config core.autocrlf=false --config core.eol=lf`, applied the full-index patch, copied the 8 untracked files, and observed **18/18 paths byte-identical** (`BYTE_IDENTICAL=True`). `.gitattributes` still does not pin `*.py`, `*.hcl`, or `Dockerfile` to `eol=lf`, so this remains an explicit execution contract rather than repository-wide attribute hardening.
- Rebuild assertions are **24/24 True**. Independent verification is **48/48 checks True** and **24/24 computed assertions True**, ending in `ALL_CHECKS=True`; the full-index patch reverse-applies cleanly in the clone.
- Candidate-level tests are **88 tests OK** for the Python release suite, including **25/25** in `test_release_workflow.py`. Maven is **19/19 reactor modules SUCCESS**. Frontend is **22 files / 175 tests PASS**, with typecheck and production build exit 0. Docker Bake exposes **17 targets**; actionlint **1.7.12** exits 0 for `ci.yml` and `release.yml`. `npm audit` reports **2 moderate / 0 high / 0 critical** in the dev-only Vitest chain and remains an expected non-zero audit exit, not an acceptance failure.
- The release manifest is byte-identical across two runs and `verify` passes. The clean-clone orchestration check (HEAD, not the dirty candidate) is **18/18 checks VERIFIED**, with 0 required failures and 0 not-verified checks.
- final16 patch/evidence hashes are invalidated by the Dockerfile change and must not be reused. At the time of this checkpoint, the then-current HEAD's remote `release.yml` execution remained **NOT VERIFIED** (historical; superseded by Checkpoint 17); the old `v0.1.0` runs failed and do not prove the current revision.
- No commit, push, tag, or history rewrite was performed for this checkpoint.

The authoritative per-path status/bytes/SHA-256 records, canonical inventory digest, tracked-diff-only patch bytes/hash, and verification-log hashes are maintained in the external final17 evidence JSON. They are intentionally not embedded in this document to avoid a self-referential candidate digest.

## Checkpoint 16: final18 18-Path Documentation Correction and Builder Verification (2026-09-29)

This checkpoint supersedes Checkpoint 15 for the current candidate. Checkpoints 6–15 remain historical evidence for the earlier final8, final9, final11, final12, final13, final14, final15, and final17 snapshots.

- Current candidate: **18 paths** = **10 modified tracked files + 8 untracked files**. Relative to final17, this is a documentation-only correction: the duplicate `Final16 status addendum` heading in the plan is removed and the retained Final16/Final17 sections are marked historical. No product code, build configuration, or test logic changed.
- Current tracked-diff-only patch: `working-tree-final18.patch`; it excludes the 8 untracked candidate paths and is generated with `git diff --binary --full-index`.
- final18 clone: `C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-final18-20260929-01\clone`.
- final18 machine-readable evidence: `C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-current-20260929-01\current-worktree-evidence-final18.json`; independent verification log: `C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-current-20260929-01\independent-final18-verification.log`.
- The clone used `git clone --config core.autocrlf=false --config core.eol=lf`, applied the full-index patch, copied the 8 untracked files, and observed **18/18 paths byte-identical** (`BYTE_IDENTICAL=True`). `.gitattributes` still does not pin `*.py`, `*.hcl`, or `Dockerfile` to `eol=lf`, so this remains an explicit execution contract rather than repository-wide attribute hardening.
- The final18 builder command `docker build --target builder --build-arg MODULE=amz-service/amz-service-spapi -t amazonerp-phase0-builder-test:final18 .` was executed in the materialised final18 clone and exited 0. The final18 log records the actual disposition of the `dependency:go-offline` layer; because the Dockerfile and all POM inputs are byte-identical to final17, the authoritative executed-layer evidence remains the final17 builder log, where that layer logged a non-blocking Maven Central SSL handshake failure and the final `mvn -B -q clean package -DskipTests -pl amz-service/amz-service-spapi -am` succeeded and the builder image exported. This covers the default SPAPI builder path only, not the runtime image, all 17 service build args, or the remote release workflow.
- Rebuild assertions are **24/24 True**. Independent verification is **48/48 checks True** and **24/24 computed assertions True**, ending in `ALL_CHECKS=True`; the full-index patch reverse-applies cleanly in the clone.
- Candidate-level tests are **88 tests OK** for the Python release suite, including **25/25** in `test_release_workflow.py`. Maven is **19/19 reactor modules SUCCESS**. Frontend is **22 files / 175 tests PASS**, with typecheck and production build exit 0. Docker Bake exposes **17 targets**; actionlint **1.7.12** exits 0 for `ci.yml` and `release.yml`. `npm audit` reports **2 moderate / 0 high / 0 critical** in the dev-only Vitest chain and remains an expected non-zero audit exit, not an acceptance failure.
- The release manifest is byte-identical across two runs and `verify` passes. The clean-clone orchestration check (HEAD, not the dirty candidate) is **18/18 checks VERIFIED**, with 0 required failures and 0 not-verified checks.
- final17 patch/evidence hashes are invalidated by the documentation correction and must not be reused. At the time of this checkpoint, the then-current HEAD's remote `release.yml` execution remained **NOT VERIFIED** (historical; superseded by Checkpoint 17); the old `v0.1.0` runs failed and do not prove the current revision.
- No commit, push, tag, or history rewrite was performed for this checkpoint.

The authoritative per-path status/bytes/SHA-256 records, canonical inventory digest, tracked-diff-only patch bytes/hash, and verification-log hashes are maintained in the external final18 evidence JSON. They are intentionally not embedded in this document to avoid a self-referential candidate digest.

## Checkpoint 17: v0.1.2 Remote Release Verification (2026-09-29)

This checkpoint supersedes all earlier `NOT VERIFIED` statements about the release commit's remote `release.yml` execution.

- Tag `v0.1.2` points at `6de12f7532ae57adb5381f0347f1b7bca7e22796`; workflow run `36560040245` completed with `conclusion=success`.
  - `quality-gate`: success, 2026-09-29T11:09:59Z → 11:13:51Z.
  - `release`: success, 2026-09-29T11:13:54Z → 11:36:28Z.
  - URL: https://github.com/cgs123456/AmazonERP/actions/runs/36560040245
- The run log contains 17 `0 violations, 0 stale` results, 17 `Pushing signature to: ghcr.io/...` lines, and 17 `tlog entry created with index:` lines. This covers 17/17 release images for the CVE gate and Cosign signing.
- GitHub Release `v0.1.2` is published (`isDraft=false`, `isPrerelease=false`) with 19 assets: `release-manifest.json`, `checksums.sha256`, and 17 `*.spdx.json` files.
- Downloaded release assets were independently checked:
  - `checksums.sha256` has 18 lines; all 18 hashes match when mapped by basename.
  - all 18 JSON files parse.
  - `python tools/release/release_manifest.py verify --manifest <downloaded>\release-manifest.json --root .` returns PASS.
  - gateway image digest matches the manifest: `sha256:80025d2f4be5b81b2e350dd71100995a1f56c77393432084b1ab89fce0f4193c`.
  - local evidence directory: `C:\Users\Administrator\AppData\Local\Temp\amazonerp-v0.1.2-release-verify-20260929-193811`.
  - run log: `C:\Users\Administrator\AppData\Local\Temp\amazonerp-release-run-36560040245.log`.
- Remaining P1 release findings:
  - At Checkpoint 17 time, `checksums.sha256` stored `sboms/<name>.spdx.json` paths while GitHub Release assets are flat; standard `sha256sum -c checksums.sha256` fails without path rewriting. Commit `80ec85e` fixes this for future releases, but the already-published v0.1.2 assets are unchanged and still require path rewriting.
  - At Checkpoint 17 time, GitHub Release `isImmutable=false`. Repository-level immutability was enabled on 2026-09-29 for future releases; v0.1.2 remains non-immutable.
  - At Checkpoint 17 time, GitHub environment `production` had `protection_rules=[]`; this is superseded by Checkpoint 18. The `master` branch protection API still returns 404.
- At Checkpoint 17 time, independent local `cosign verify` had not been performed; this is superseded by Checkpoint 18's 17/17 digest-level verification.

## Checkpoint 18: Post-release governance and independent Cosign verification (2026-09-29)

- Release immutability was enabled for the repository through the official REST endpoint `PUT /repos/cgs123456/AmazonERP/immutable-releases`; readback was `{"enabled":true,"enforced_by_owner":false}`. This applies only to future releases. `v0.1.2` remains `immutable=false` and cannot be made immutable retroactively.
- The `production` environment has one required reviewer, user `cgs123456` (`prevent_self_review=false`, `wait_timer=0`). This creates an approval pause, but it is not independent four-eyes review because the repository currently has only that one admin/user. Add a second reviewer and set `prevent_self_review=true` to obtain independent review.
- Commit `80ec85e` fixes the checksum path contract by staging `release-manifest.json` and flat `*.spdx.json` files under `release-assets/` and generating `checksums.sha256` there. Artifact upload and GitHub Release upload both consume `release-assets/*`. The local release suite is 88/88 PASS, including 25/25 workflow tests, and `git diff --check` passes. This is source-level verification only: no new remote tag run has executed the fix, and the published v0.1.2 assets still contain the old `sboms/<name>.spdx.json` paths.
- Independent Cosign verification used `ghcr.io/sigstore/cosign/cosign:v3.1.3`, image digest `sha256:9e5c2f2edc34351160407ca3416c61855bdf9403c3c5936e0f0be7fc261611b8`.
  - Certificate identity: `https://github.com/cgs123456/AmazonERP/.github/workflows/release.yml@refs/tags/v0.1.2`
  - OIDC issuer: `https://token.actions.githubusercontent.com`
  - Workflow claims: `GitHub Workflow SHA: 6de12f7532ae57adb5381f0347f1b7bca7e22796`; `GitHub Workflow Ref: refs/tags/v0.1.2`; `GitHub Workflow Name: Release`; repository `cgs123456/AmazonERP`.
  - Command template:
    ```powershell
    $ref = "ghcr.io/cgs123456/amazonerp-<name>:0.1.2-6de12f7532ae57adb5381f0347f1b7bca7e22796"
    $digest = docker buildx imagetools inspect $ref --format '{{.Manifest.Digest}}'
    docker run --rm ghcr.io/sigstore/cosign/cosign:v3.1.3 verify --output text `
      --certificate-identity "https://github.com/cgs123456/AmazonERP/.github/workflows/release.yml@refs/tags/v0.1.2" `
      --certificate-oidc-issuer "https://token.actions.githubusercontent.com" `
      "ghcr.io/cgs123456/amazonerp-<name>@${digest}"
    ```
  - Result: 17/17 PASS. Each verification log reports that cosign claims were validated, existence in the transparency log was verified offline, and the code-signing certificate was verified against trusted CA certificates.
  - Digest-level results:

| Image | Digest | Result |
|-------|--------|--------|
| gateway | `sha256:80025d2f4be5b81b2e350dd71100995a1f56c77393432084b1ab89fce0f4193c` | PASS |
| ad | `sha256:33ad595a005ace3fe0543c23b0a0ad1fd0513088ac9c22deaff2de641158a423` | PASS |
| ai | `sha256:07cb1352ad430d962ed7b8df57b2fc5d7ea0d9c901c52d05281e8c078e7c6af0` | PASS |
| customer | `sha256:45d2ced18dbd0d0b1d2ca82c559372bb8d7a96aabafc123468d5ed31ee9d1f2c` | PASS |
| finance | `sha256:8369fe26386eb45be697ae95f2a507bddac9bec6f544371cdb470f7ef463c560` | PASS |
| logistics | `sha256:2e112b2b3d7e022c98ed671315d1e0dbf47a1aa40eff587fac7c8894102f7cc6` | PASS |
| message | `sha256:a07ef92b2f3520b9b652736d035fd828da9dd875fdaa54864f1f89c442681c7e` | PASS |
| multiplatform | `sha256:dd28be440c7617e3ec53db9b86f74ed4405c7552e8fbf332b42404ec2914c71b` | PASS |
| ops | `sha256:c70657bfd0922379f496cc1599eb656a38e0f34499d89dd1a1b13dd09deb4c1d` | PASS |
| order | `sha256:dc1ded9056a63692790f334dbd37f61873524a8835037637dbab4f4f59e42930` | PASS |
| procurement | `sha256:30d459eac5478ca3bd2ef32a550923c7fa918a142bd38655e677b40613f18160` | PASS |
| product | `sha256:4e46c803a5e488ef082373ab6c9de74de19cbf1e06abfae6bda91fd5abd8d082` | PASS |
| report | `sha256:e9d9fb453ae23ffd3055ba84a31716c5344e07cd0b3c3e8a36d9011db44ebb34` | PASS |
| search | `sha256:63ba7b95e538c78e0b9feba147bc542a01f91b39c831bfe6f9f747ec8aa7f52d` | PASS |
| spapi | `sha256:be9a2252ac86ab30d0f444c4d57595239c10b858cacdfd0a80b43c2042ef2a1c` | PASS |
| user | `sha256:a1e72bfdb0c8394d12a75e1bd787ef4b175a8c2fad8837f7e24049339edda9f1` | PASS |
| frontend | `sha256:80895f2ec6196a43c26ba4c78fd54bb46f96683e89d0fc17364f7f6c41555150` | PASS |

  - Raw logs are committed at `docs/superpowers/evidence/2026-09-29-cosign-verify/` (17 `*.verify.log` files plus `summary.json`).
- `master` branch protection still returns HTTP 404. It was not enabled in this checkpoint because requiring PRs/status checks would change the current direct-push workflow and needs a separate user decision.
