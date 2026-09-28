# Release Rollback Runbook

## Scope
- Phase 0 validates application image rollback only.
- Flyway does NOT support automatic down-migrations. Database rollback requires manual review per-migration.

## Image Rollback (Automated)
```bash
python tools/release/rollback_drill.py plan \
  --current release-manifest-current.json \
  --previous release-manifest-previous.json \
  --namespace production \
  --output rollback-plan.json

python tools/release/rollback_drill.py execute --plan rollback-plan.json  # dry-run
python tools/release/rollback_drill.py execute --plan rollback-plan.json --apply --context <ctx>  # real
```

## Database Rollback (Manual)
- If migration count or tree hash differs between manifests, the plan sets `databaseAction=MANUAL_REVIEW_REQUIRED` and blocks `--apply`.
- Steps:
  1. Stop writes (scale deployments to 0 or enable maintenance mode).
  2. Backup database or take PITR snapshot.
  3. Review migration scripts manually for backward compatibility.
  4. Write and test a data repair migration if needed.
- NEVER use `flyway undo` as a default operation.

## Post-Rollback Verification
```bash
kubectl -n production rollout status deployment/amz-gateway
kubectl -n production get pods -l app=amz-gateway
```
