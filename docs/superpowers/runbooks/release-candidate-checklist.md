# Release Candidate Checklist

## Tag Rules
- Tag format: `v{MAJOR}.{MINOR}.{PATCH}` (semver). No pre-release or build metadata.
- Tag must be pushed from `main` after all CI checks pass.
- Deleting an incorrect tag does NOT invalidate already-published GHCR digests.

## Branch Protection
- `main` requires: passing `checkstyle`, `hygiene`, `release-manifest`, `test`, `frontend`, `synthetic-data`, `mysql-import`, `docker` CI jobs.
- Require pull-request reviews before merge.

## Environment Approval
- Configure GitHub Environments: `production` with required reviewers.
- Release workflow `release` job must target `production` environment.

## GHCR Permissions
- Package visibility: Private (or internal for org).
- Bot/service-account token: `packages:write` scope only.
- No `GITHUB_TOKEN` override with broader scopes.

## OIDC Subject
- Cosign keyless signing uses GitHub OIDC. Subject pattern: `https://github.com/{owner}/{repo}/.github/workflows/release.yml@refs/tags/{tag}`.

## Verification Commands

### SBOM
```bash
cosign download attestation {image}@{digest} | jq '.[0].payload | @base64d | fromjson'
```

### Grype Scan
```bash
grype {image}@{digest} -o table --fail-on high
```

### Cosign Signature
```bash
cosign verify \
  --certificate-identity-regexp "^https://github\.com/{owner}/" \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  {image}@{digest}
```

## Release Manifest Backfill
- CI artifact is NOT the release manifest. Do not fill in digest from CI.
- Use `tools/release/release_manifest.py` with the actual digest from `bake-metadata` step output.

## Failure Handling
- If Grype finds HIGH/CRITICAL: stop. Patch, bump version, re-tag.
- If Cosign fails: stop. Verify OIDC configuration.
- If manifest generation fails: verify `release_manifest.py` CLI args match actual image/digest.
- Never use `latest` tag in production. Always reference by digest.

## VERIFIED Status
- Do NOT mark any step VERIFIED until the workflow has actually passed on GitHub Actions.
- Local static test results are NOT equivalent to CI success.
