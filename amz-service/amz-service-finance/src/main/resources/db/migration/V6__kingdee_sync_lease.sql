-- V6: 金蝶同步租约索引。
-- SYNCING 超时后允许重新认领；复合索引覆盖认领条件中的状态与更新时间。
CREATE INDEX idx_kingdee_claim
    ON amz_accounting_voucher (kingdee_sync_status, update_time)
    COMMENT '金蝶同步认领：PENDING/FAILED 或超时 SYNCING';
