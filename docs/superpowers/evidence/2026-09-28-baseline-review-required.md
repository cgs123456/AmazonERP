# Phase 0 Baseline Review-Required Paths (2026-09-28)

Source of truth: `docs/superpowers/evidence/2026-09-28-baseline-split-map.json`
(652 entries; `group = review-required`). This document is derived from that JSON;
regenerate it rather than editing paths by hand.

## Why these paths are review-required

The Task 8 group allowlist is `common`, `gateway`, `frontend`, `spapi`, `ad`, `ai`,
`finance`, `order`, `product`, `synthetic-data`, `deploy/k8s`, `docs`. Every path below
falls outside that allowlist and therefore cannot be claimed as a completed subsystem split.

## Summary

| Bucket | Count |
|--------|-------|
| review-required total | 182 |
| non-temporary paths needing owner review | 111 |
| temporary artifacts (must not enter Git) | 71 |

## A. Non-temporary paths (owner review required)

### (repository root) (7)

| Classification | Status | Path |
|----------------|--------|------|
| config | tracked-changed | `.env.demo.example` |
| config | tracked-changed | `.env.example` |
| config | tracked-changed | `.gitignore` |
| docs | tracked-changed | `README.md` |
| config | untracked | `docker-compose.bootstrap.yml` |
| config | tracked-changed | `docker-compose.yml` |
| config | tracked-changed | `pom.xml` |

### amz-service-customer (9)

| Classification | Status | Path |
|----------------|--------|------|
| source | tracked-changed | `amz-service/amz-service-customer/src/main/java/com/amz/controller/CustomerController.java` |
| source | tracked-changed | `amz-service/amz-service-customer/src/main/java/com/amz/controller/CustomerEmailController.java` |
| source | tracked-changed | `amz-service/amz-service-customer/src/main/java/com/amz/service/CustomerEmailService.java` |
| source | tracked-changed | `amz-service/amz-service-customer/src/main/java/com/amz/service/CustomerService.java` |
| source | tracked-changed | `amz-service/amz-service-customer/src/main/java/com/amz/service/impl/CustomerEmailServiceImpl.java` |
| source | tracked-changed | `amz-service/amz-service-customer/src/main/java/com/amz/service/impl/CustomerServiceImpl.java` |
| test | untracked | `amz-service/amz-service-customer/src/test/java/com/amz/controller/CustomerPagingControllerContractTest.java` |
| test | tracked-changed | `amz-service/amz-service-customer/src/test/java/com/amz/service/impl/CustomerEmailServiceImplTest.java` |
| test | untracked | `amz-service/amz-service-customer/src/test/java/com/amz/service/impl/CustomerPagingContractTest.java` |

### amz-service-logistics (47)

| Classification | Status | Path |
|----------------|--------|------|
| source | tracked-changed | `amz-service/amz-service-logistics/src/main/java/com/amz/client/LogisticsTrackingClient.java` |
| source | tracked-changed | `amz-service/amz-service-logistics/src/main/java/com/amz/client/LogisticsTrackingProperties.java` |
| source | tracked-changed | `amz-service/amz-service-logistics/src/main/java/com/amz/client/LogisticsTrackingRealClient.java` |
| source | tracked-changed | `amz-service/amz-service-logistics/src/main/java/com/amz/controller/InboundController.java` |
| source | tracked-changed | `amz-service/amz-service-logistics/src/main/java/com/amz/controller/LogisticsController.java` |
| source | tracked-changed | `amz-service/amz-service-logistics/src/main/java/com/amz/controller/LogisticsUpgradeController.java` |
| source | tracked-changed | `amz-service/amz-service-logistics/src/main/java/com/amz/controller/MultiWarehouseController.java` |
| source | tracked-changed | `amz-service/amz-service-logistics/src/main/java/com/amz/controller/OutboundController.java` |
| source | tracked-changed | `amz-service/amz-service-logistics/src/main/java/com/amz/controller/WarehouseController.java` |
| source | tracked-changed | `amz-service/amz-service-logistics/src/main/java/com/amz/dto/IngestOutcome.java` |
| source | tracked-changed | `amz-service/amz-service-logistics/src/main/java/com/amz/scheduler/LogisticsTrackingSyncScheduler.java` |
| source | tracked-changed | `amz-service/amz-service-logistics/src/main/java/com/amz/service/InboundService.java` |
| source | tracked-changed | `amz-service/amz-service-logistics/src/main/java/com/amz/service/LogisticsService.java` |
| source | tracked-changed | `amz-service/amz-service-logistics/src/main/java/com/amz/service/LogisticsUpgradeService.java` |
| source | tracked-changed | `amz-service/amz-service-logistics/src/main/java/com/amz/service/MultiWarehouseService.java` |
| source | tracked-changed | `amz-service/amz-service-logistics/src/main/java/com/amz/service/OutboundService.java` |
| source | tracked-changed | `amz-service/amz-service-logistics/src/main/java/com/amz/service/WarehouseService.java` |
| source | tracked-changed | `amz-service/amz-service-logistics/src/main/java/com/amz/service/impl/InboundServiceImpl.java` |
| source | tracked-changed | `amz-service/amz-service-logistics/src/main/java/com/amz/service/impl/LogisticsImportServiceImpl.java` |
| source | tracked-changed | `amz-service/amz-service-logistics/src/main/java/com/amz/service/impl/LogisticsOpsDashboardServiceImpl.java` |
| source | tracked-changed | `amz-service/amz-service-logistics/src/main/java/com/amz/service/impl/LogisticsServiceImpl.java` |
| source | tracked-changed | `amz-service/amz-service-logistics/src/main/java/com/amz/service/impl/LogisticsUpgradeServiceImpl.java` |
| source | tracked-changed | `amz-service/amz-service-logistics/src/main/java/com/amz/service/impl/MultiWarehouseServiceImpl.java` |
| source | tracked-changed | `amz-service/amz-service-logistics/src/main/java/com/amz/service/impl/OutboundServiceImpl.java` |
| source | tracked-changed | `amz-service/amz-service-logistics/src/main/java/com/amz/service/impl/TrackingIngestServiceImpl.java` |
| source | tracked-changed | `amz-service/amz-service-logistics/src/main/java/com/amz/service/impl/WarehouseServiceImpl.java` |
| config | tracked-changed | `amz-service/amz-service-logistics/src/main/resources/application.yml` |
| source | tracked-changed | `amz-service/amz-service-logistics/src/main/resources/db/migration/V1__init.sql` |
| source | untracked | `amz-service/amz-service-logistics/src/main/resources/db/migration/V4__tracking_timeline_index.sql` |
| source | untracked | `amz-service/amz-service-logistics/src/main/resources/db/migration/V5__shipment_tracking_lookup_index.sql` |
| test | untracked | `amz-service/amz-service-logistics/src/test/java/com/amz/client/LogisticsTrackingRealClientTest.java` |
| test | untracked | `amz-service/amz-service-logistics/src/test/java/com/amz/controller/LogisticsControllerGuardTest.java` |
| test | untracked | `amz-service/amz-service-logistics/src/test/java/com/amz/service/impl/InboundServiceImplPagingTest.java` |
| test | untracked | `amz-service/amz-service-logistics/src/test/java/com/amz/service/impl/InboundServiceImplTest.java` |
| test | tracked-changed | `amz-service/amz-service-logistics/src/test/java/com/amz/service/impl/LogisticsOpsDashboardServiceImplTest.java` |
| test | untracked | `amz-service/amz-service-logistics/src/test/java/com/amz/service/impl/LogisticsServiceImplPagingTest.java` |
| test | untracked | `amz-service/amz-service-logistics/src/test/java/com/amz/service/impl/LogisticsServiceImplTenantTest.java` |
| test | untracked | `amz-service/amz-service-logistics/src/test/java/com/amz/service/impl/LogisticsUpgradeServiceImplPagingTest.java` |
| test | tracked-changed | `amz-service/amz-service-logistics/src/test/java/com/amz/service/impl/LogisticsUpgradeServiceImplTest.java` |
| test | untracked | `amz-service/amz-service-logistics/src/test/java/com/amz/service/impl/MultiWarehouseServiceImplPagingTest.java` |
| test | untracked | `amz-service/amz-service-logistics/src/test/java/com/amz/service/impl/MultiWarehouseServiceImplTest.java` |
| test | untracked | `amz-service/amz-service-logistics/src/test/java/com/amz/service/impl/OutboundServiceImplPagingTest.java` |
| test | untracked | `amz-service/amz-service-logistics/src/test/java/com/amz/service/impl/OutboundServiceImplTest.java` |
| test | tracked-changed | `amz-service/amz-service-logistics/src/test/java/com/amz/service/impl/TrackingIngestServiceImplTest.java` |
| test | untracked | `amz-service/amz-service-logistics/src/test/java/com/amz/service/impl/WarehouseInventoryPagingTest.java` |
| test | tracked-changed | `amz-service/amz-service-logistics/src/test/java/com/amz/service/impl/WarehouseServiceImplTest.java` |
| test | untracked | `amz-service/amz-service-logistics/src/test/java/com/amz/service/impl/WarehouseTenantIsolationTest.java` |

### amz-service-message (1)

| Classification | Status | Path |
|----------------|--------|------|
| source | tracked-changed | `amz-service/amz-service-message/src/main/java/com/amz/controller/MessageNotifyController.java` |

### amz-service-multiplatform (7)

| Classification | Status | Path |
|----------------|--------|------|
| source | tracked-changed | `amz-service/amz-service-multiplatform/src/main/java/com/amz/client/SheinRealClient.java` |
| source | tracked-changed | `amz-service/amz-service-multiplatform/src/main/java/com/amz/client/TemuRealClient.java` |
| source | tracked-changed | `amz-service/amz-service-multiplatform/src/main/java/com/amz/client/TikTokRealClient.java` |
| source | tracked-changed | `amz-service/amz-service-multiplatform/src/main/java/com/amz/model/PlatformMessage.java` |
| source | tracked-changed | `amz-service/amz-service-multiplatform/src/main/java/com/amz/service/impl/MultiplatformServiceImpl.java` |
| source | untracked | `amz-service/amz-service-multiplatform/src/main/resources/db/migration/V3__platform_message_order_no.sql` |
| test | tracked-changed | `amz-service/amz-service-multiplatform/src/test/java/com/amz/client/ParseOrdersTest.java` |

### amz-service-ops (2)

| Classification | Status | Path |
|----------------|--------|------|
| source | tracked-changed | `amz-service/amz-service-ops/src/main/java/com/amz/model/KeywordRankRecord.java` |
| source | tracked-changed | `amz-service/amz-service-ops/src/main/resources/db/migration/V1__init.sql` |

### amz-service-procurement (27)

| Classification | Status | Path |
|----------------|--------|------|
| source | tracked-changed | `amz-service/amz-service-procurement/src/main/java/com/amz/client/Alibaba1688RealClient.java` |
| source | tracked-changed | `amz-service/amz-service-procurement/src/main/java/com/amz/controller/ProcurementController.java` |
| source | untracked | `amz-service/amz-service-procurement/src/main/java/com/amz/dto/BatchCostSummary.java` |
| source | tracked-changed | `amz-service/amz-service-procurement/src/main/java/com/amz/mapper/InventoryBatchMapper.java` |
| source | tracked-changed | `amz-service/amz-service-procurement/src/main/java/com/amz/model/InventoryBatch.java` |
| source | tracked-changed | `amz-service/amz-service-procurement/src/main/java/com/amz/service/FbaShipmentService.java` |
| source | tracked-changed | `amz-service/amz-service-procurement/src/main/java/com/amz/service/ProcurementService.java` |
| source | tracked-changed | `amz-service/amz-service-procurement/src/main/java/com/amz/service/PurchasePlanService.java` |
| source | tracked-changed | `amz-service/amz-service-procurement/src/main/java/com/amz/service/SupplierService.java` |
| source | tracked-changed | `amz-service/amz-service-procurement/src/main/java/com/amz/service/impl/FbaShipmentServiceImpl.java` |
| source | tracked-changed | `amz-service/amz-service-procurement/src/main/java/com/amz/service/impl/ProcurementServiceImpl.java` |
| source | tracked-changed | `amz-service/amz-service-procurement/src/main/java/com/amz/service/impl/PurchasePlanServiceImpl.java` |
| source | tracked-changed | `amz-service/amz-service-procurement/src/main/java/com/amz/service/impl/SupplierServiceImpl.java` |
| source | tracked-changed | `amz-service/amz-service-procurement/src/main/resources/db/migration/V1__init.sql` |
| source | untracked | `amz-service/amz-service-procurement/src/main/resources/db/migration/V2__fba_receipt_idempotency.sql` |
| test | untracked | `amz-service/amz-service-procurement/src/test/java/com/amz/client/Alibaba1688CloseOrderSemanticsTest.java` |
| test | untracked | `amz-service/amz-service-procurement/src/test/java/com/amz/controller/ProcurementControllerGuardTest.java` |
| test | untracked | `amz-service/amz-service-procurement/src/test/java/com/amz/mapper/InventoryBatchMapperContractTest.java` |
| test | untracked | `amz-service/amz-service-procurement/src/test/java/com/amz/migration/FbaReceiptIdempotencyMigrationContractTest.java` |
| test | untracked | `amz-service/amz-service-procurement/src/test/java/com/amz/service/impl/FbaShipmentFifoAtomicDeductionTest.java` |
| test | untracked | `amz-service/amz-service-procurement/src/test/java/com/amz/service/impl/FbaShipmentServiceImplPagingTest.java` |
| test | tracked-changed | `amz-service/amz-service-procurement/src/test/java/com/amz/service/impl/FbaShipmentServiceImplTest.java` |
| test | untracked | `amz-service/amz-service-procurement/src/test/java/com/amz/service/impl/InventoryBatchCostSummaryServiceTest.java` |
| test | tracked-changed | `amz-service/amz-service-procurement/src/test/java/com/amz/service/impl/ProcurementServiceImplTest.java` |
| test | untracked | `amz-service/amz-service-procurement/src/test/java/com/amz/service/impl/PurchasePlanServiceImplTest.java` |
| test | untracked | `amz-service/amz-service-procurement/src/test/java/com/amz/service/impl/SupplierServiceImplPagingTest.java` |
| test | untracked | `amz-service/amz-service-procurement/src/test/java/com/amz/service/impl/SupplierServiceImplTest.java` |

### amz-service-report (4)

| Classification | Status | Path |
|----------------|--------|------|
| source | tracked-changed | `amz-service/amz-service-report/src/main/java/com/amz/controller/RealtimeProfitController.java` |
| source | tracked-changed | `amz-service/amz-service-report/src/main/java/com/amz/service/RealtimeProfitService.java` |
| source | tracked-changed | `amz-service/amz-service-report/src/main/java/com/amz/service/impl/RealtimeProfitServiceImpl.java` |
| test | tracked-changed | `amz-service/amz-service-report/src/test/java/com/amz/service/impl/RealtimeProfitServiceImplTest.java` |

### amz-service-user (2)

| Classification | Status | Path |
|----------------|--------|------|
| source | tracked-changed | `amz-service/amz-service-user/src/main/java/com/amz/model/pojo/User.java` |
| source | tracked-changed | `amz-service/amz-service-user/src/main/resources/db/migration/V1__init.sql` |

### docker (2)

| Classification | Status | Path |
|----------------|--------|------|
| config | tracked-changed | `docker/init-sql-legacy/19-init-tables-field-permission.sql` |
| config | tracked-changed | `docker/init-sql-legacy/23-init-tables-procurement-upgrade.sql` |

### tools/connector-acceptance (2)

| Classification | Status | Path |
|----------------|--------|------|
| source | tracked-changed | `tools/connector-acceptance/acceptance_runner.py` |
| source | tracked-changed | `tools/connector-acceptance/fake-service.py` |

### .github (1)

| Classification | Status | Path |
|----------------|--------|------|
| config | tracked-changed | `.github/workflows/ci.yml` |

## B. Temporary artifacts (do not commit)

All 71 entries below have `classification = temporary`. They are scratch scripts,
probe logs and round artifacts created before the freeze. They are preserved only in the
external recovery snapshot and are absent from the published Git tree.

| Status | Path |
|--------|------|
| untracked | `.add_flyway.py` |
| untracked | `.add_linkage_test.py` |
| untracked | `.cmp_schema.py` |
| untracked | `.cmp_seed.py` |
| untracked | `.commitmsg.txt` |
| untracked | `.commitmsg71.txt` |
| untracked | `.cov.py` |
| untracked | `.docs57.py` |
| untracked | `.fix_comments.py` |
| untracked | `.fix_doc.py` |
| untracked | `.fix_readme.py` |
| untracked | `.gen_test.py` |
| untracked | `.keys.py` |
| untracked | `.mkmsg.py` |
| untracked | `.mutate.py` |
| untracked | `.patch_710.py` |
| untracked | `.patch_79.py` |
| untracked | `.patch_aws_httpclient5.py` |
| untracked | `.patch_doc70.py` |
| untracked | `.patch_doc70b.py` |
| untracked | `.patch_doc70c.py` |
| untracked | `.patch_k8s_schema.py` |
| untracked | `.patch_layouts.py` |
| untracked | `.patch_p058.py` |
| untracked | `.patch_p058_meta.py` |
| untracked | `.patch_plan_task11.py` |
| untracked | `.patch_profile.py` |
| untracked | `.patch_profile2.py` |
| untracked | `.patch_rs.py` |
| untracked | `.patch_sqs_test.py` |
| untracked | `.probe.py` |
| untracked | `.restore.py` |
| untracked | `.scan_downgrades.py` |
| untracked | `.scan_flyway.py` |
| untracked | `.scan_mock.py` |
| untracked | `.scan_profiles.py` |
| untracked | `.scan_tables.py` |
| untracked | `.schema_diff.py` |
| untracked | `.schema_diff2.py` |
| untracked | `.tmp_fix_quotes.py` |
| untracked | `.tmp_fix_rule.py` |
| untracked | `.tmp_patch_pom.py` |
| untracked | `.tmp_patch_props.py` |
| untracked | `.tmp_patch_yml.py` |
| untracked | `.tree-spapi.txt` |
| untracked | `.tree-verbose.txt` |
| untracked | `ProbeUpdateWrapperTest.java` |
| untracked | `_cp_spapi.txt` |
| untracked | `_r82_edit1.py` |
| untracked | `_r82_edit2.py` |
| untracked | `_r82_edit3.py` |
| untracked | `_r82_edit4.py` |
| untracked | `_r82_mint.py` |
| untracked | `_r82_t1.json` |
| untracked | `_r82_t2.json` |
| untracked | `_r82_t3.json` |
| untracked | `_r82_t4.json` |
| untracked | `_r82_t5.json` |
| untracked | `_r82_tokens.txt` |
| untracked | `_r83_edit_plan.py` |
| untracked | `_r83_edit_plan2.py` |
| untracked | `_r83_edit_spec.py` |
| untracked | `_sp_args.txt` |
| untracked | `_sp_args2.txt` |
| untracked | `_sp_args3.txt` |
| untracked | `round33-cred-delete-ok.json` |
| untracked | `round33-cred-put-after-delete-ok.json` |
| untracked | `round33-cred-put-blocked-by-corrupt-ciphertext.json` |
| untracked | `round33-cred-status-after-fix.json` |
| untracked | `round33-cred-status-after-put.json` |
| untracked | `round33-nio-selector-afunix-probe.java` |
