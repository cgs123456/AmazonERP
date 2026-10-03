-- 补齐 ReplenishmentSuggestion 已映射但建表脚本从未有过的三列。
--
-- 动因：HybridReplenishmentEngine 会真写 mlPredictedDemand / mlConfidence / blendStrategy
-- （纯规则路径留 NULL），ReplenishmentController、SpapiController、ReplenishmentScheduler
-- 都经 ReplenishmentSuggestionMapper 读写这张表。MyBatis-Plus 的 SELECT 字段表包含实体全部
-- 映射列，因此这三列不存在时任何一次列表读取都是 MySQL 1054，而不是「ML 字段为空」。
--
-- 口径：三列都可空且**不回填**存量行——历史建议当时确实没算过 ML，写默认值等于伪造历史。
-- 类型与相邻列对齐：需求量沿用 baseline_demand 的 DECIMAL(10,2)，置信度是 0~1 用 DECIMAL(4,3)，
-- 策略串最长 HYBRID_ML_70_RULE_30（20 字符）留到 VARCHAR(32)。

ALTER TABLE amz_replenishment_suggestion
    ADD COLUMN ml_predicted_demand DECIMAL(10,2) NULL COMMENT 'ML 预测需求量，NULL 表示本次未使用 ML' AFTER urgency_level,
    ADD COLUMN ml_confidence DECIMAL(4,3) NULL COMMENT 'ML 置信度 0.000-1.000，NULL 表示本次未使用 ML' AFTER ml_predicted_demand,
    ADD COLUMN blend_strategy VARCHAR(32) NULL COMMENT 'RULE_ONLY / HYBRID_ML_70_RULE_30' AFTER ml_confidence;
