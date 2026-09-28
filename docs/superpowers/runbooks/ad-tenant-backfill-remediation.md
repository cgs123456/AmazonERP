# 广告素材与定向历史租户归属修复 Runbook

## 背景

Flyway `V5__ad_resource_tenant_scope.sql` 为 `amz_ad_creative` 和 `amz_ad_targeting` 增加了可空 `shop_id`。
迁移只回填 `campaign_id` 能唯一映射到一个 `shop_id` 的历史记录：

- 唯一映射：自动回填。
- 多店铺复用同一 `campaign_id`：保持 `shop_id IS NULL`，不猜测归属。
- 找不到对应广告活动的孤立记录：保持 `shop_id IS NULL`，不猜测归属。

应用层按 `shop_id` 做 fail-closed 过滤，因此 `NULL` 记录不会泄漏到任意租户，但也无法在正常业务页面使用。
本 Runbook 只用于人工确认并修复这些 `NULL` 记录。

> 禁止使用 `MIN(shop_id)`、`MAX(shop_id)` 或“先随便归属再修正”的方式处理歧义记录。
> 一旦错误归属，错误租户可能看到或操作该广告素材/定向数据。

## 适用范围

- 服务：`amz-service-ad`
- 表：`amz_ad_creative`、`amz_ad_targeting`
- 前提：V5 已成功执行，且以下查询返回待修复记录。

## 1. 只读审计

先导出所有 `shop_id IS NULL` 的记录及候选店铺分布：

```sql
SELECT
    'creative' AS resource_type,
    c.id,
    c.campaign_id,
    c.status,
    COUNT(DISTINCT m.shop_id) AS candidate_shop_count,
    GROUP_CONCAT(DISTINCT m.shop_id ORDER BY m.shop_id) AS candidate_shop_ids
FROM amz_ad_creative c
LEFT JOIN amz_ad_campaign_ext m ON m.campaign_id = c.campaign_id
WHERE c.shop_id IS NULL
GROUP BY c.id, c.campaign_id, c.status
ORDER BY candidate_shop_count DESC, c.id;

SELECT
    'targeting' AS resource_type,
    t.id,
    t.campaign_id,
    t.targeting_type,
    COUNT(DISTINCT m.shop_id) AS candidate_shop_count,
    GROUP_CONCAT(DISTINCT m.shop_id ORDER BY m.shop_id) AS candidate_shop_ids
FROM amz_ad_targeting t
LEFT JOIN amz_ad_campaign_ext m ON m.campaign_id = t.campaign_id
WHERE t.shop_id IS NULL
GROUP BY t.id, t.campaign_id, t.targeting_type
ORDER BY candidate_shop_count DESC, t.id;
```

分类规则：

| `candidate_shop_count` | 含义 | 处理方式 |
|---:|---|---|
| `1` | 只有一个候选店铺 | 仍需人工确认后按主键修复 |
| `>1` | 多店铺复用同一活动 ID | 必须由业务/数据负责人确认，禁止自动回填 |
| `0` | 找不到活动元数据 | 从广告平台、同步日志或历史备份确认归属；无法确认则保持 `NULL` |

建议在变更工单中保存审计查询的 CSV 导出、查询时间、数据库实例和操作人。

## 2. 人工确认

每条记录至少确认以下证据：

1. Amazon Ads 账号与本地 `shop_id` 的绑定关系。
2. 该 `campaign_id` 在 Amazon Ads 控制台/API 中的账号归属。
3. 该素材或定向记录来自哪次同步批次；必要时对照应用日志和 Outbox 记录。
4. 同一 `campaign_id` 是否在多个店铺中真实存在。
5. 由业务负责人和数据负责人双人复核；保存复核人、时间和证据链接。

只有确认唯一归属后，才生成按主键修复的 SQL。不要直接使用 `WHERE campaign_id = ...` 批量更新，因为同一活动 ID 可能跨店铺复用。

## 3. 备份与变更窗口

修复前至少执行：

```sql
SELECT id, campaign_id, shop_id
FROM amz_ad_creative
WHERE id IN (/* confirmed creative ids */);

SELECT id, campaign_id, shop_id
FROM amz_ad_targeting
WHERE id IN (/* confirmed targeting ids */);
```

保存结果作为回滚基线。生产变更应在已批准的维护窗口内执行，并记录变更单号。
如果应用正在写入这些表，先暂停相关广告同步/自动优化任务，避免与人工修复并发。

## 4. 按主键修复

对每条已确认记录，使用主键、`campaign_id` 和 `shop_id IS NULL` 三重条件：

```sql
START TRANSACTION;

UPDATE amz_ad_creative
SET shop_id = :confirmed_shop_id
WHERE id = :creative_id
  AND campaign_id = :campaign_id
  AND shop_id IS NULL;

-- 仅在受影响行数为 1 时继续。
UPDATE amz_ad_targeting
SET shop_id = :confirmed_shop_id
WHERE id = :targeting_id
  AND campaign_id = :campaign_id
  AND shop_id IS NULL;

-- 检查更新后的记录确实指向确认店铺，再提交。
SELECT id, campaign_id, shop_id
FROM amz_ad_creative
WHERE id = :creative_id;

SELECT id, campaign_id, shop_id
FROM amz_ad_targeting
WHERE id = :targeting_id;

COMMIT;
```

若任一 `UPDATE` 的影响行数不是 `1`，立即 `ROLLBACK`，重新审计，不要忽略并发变化。
修复 SQL 必须逐条审核，禁止把动态生成的条件直接拼接执行。

## 5. 修复后校验

检查目标记录：

```sql
SELECT COUNT(*) AS remaining_null_creatives
FROM amz_ad_creative
WHERE shop_id IS NULL;

SELECT COUNT(*) AS remaining_null_targeting
FROM amz_ad_targeting
WHERE shop_id IS NULL;
```

检查是否出现跨店铺错误归属：

```sql
SELECT c.id, c.campaign_id, c.shop_id
FROM amz_ad_creative c
LEFT JOIN amz_ad_campaign_ext m
  ON m.shop_id = c.shop_id
 AND m.campaign_id = c.campaign_id
WHERE c.shop_id IS NOT NULL
  AND m.id IS NULL;

SELECT t.id, t.campaign_id, t.shop_id
FROM amz_ad_targeting t
LEFT JOIN amz_ad_campaign_ext m
  ON m.shop_id = t.shop_id
 AND m.campaign_id = t.campaign_id
WHERE t.shop_id IS NOT NULL
  AND m.id IS NULL;
```

第二条查询对孤立但已人工确认归属的记录可能正常返回；此时必须保留人工证据，不能仅凭该查询自动判定失败。
唯一允许进入下一阶段的条件是：业务负责人确认所有 `NULL` 都已审计完毕，且不存在未处理的歧义记录。

## 6. 回滚

仅回滚本次变更单中的主键集合，并同时校验原值：

```sql
START TRANSACTION;

UPDATE amz_ad_creative
SET shop_id = NULL
WHERE id IN (/* changed creative ids */)
  AND shop_id = :expected_shop_id;

UPDATE amz_ad_targeting
SET shop_id = NULL
WHERE id IN (/* changed targeting ids */)
  AND shop_id = :expected_shop_id;

COMMIT;
```

回滚后重新运行第 5 节的校验，并恢复广告同步/自动优化任务。

## 7. 后续 V6 前置条件

只有在以下条件全部满足后，才能设计将 `shop_id` 改为 `NOT NULL` 的 V6：

- 两个表的 `shop_id IS NULL` 计数为 `0`。
- 所有人工修复均有可追溯证据和复核记录。
- 应用层、同步任务、数据修复任务均已使用租户范围。
- 已准备 V6 预检查 SQL 和回滚/恢复方案。
- 已在 staging 使用生产规模数据完成演练。