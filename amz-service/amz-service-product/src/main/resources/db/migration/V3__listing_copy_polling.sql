-- Feed 提交后的状态轮询改为数据库驱动、可跨重启恢复。
-- 存量 SUBMITTED 任务按最后更新时间回填：已超过默认 5 分钟窗口的任务会被标记 TIMEOUT。
ALTER TABLE amz_listing_copy_task
    ADD COLUMN poll_attempts INT NOT NULL DEFAULT 0 COMMENT 'Feed 状态轮询次数' AFTER feed_submission_id,
    ADD COLUMN next_poll_time DATETIME NULL COMMENT '下一次轮询时间' AFTER poll_attempts,
    ADD COLUMN last_polled_at DATETIME NULL COMMENT '最后一次轮询时间' AFTER next_poll_time,
    ADD COLUMN poll_deadline DATETIME NULL COMMENT '轮询截止时间，超时后标记 TIMEOUT' AFTER last_polled_at;

UPDATE amz_listing_copy_task
SET next_poll_time = COALESCE(update_time, create_time, NOW()),
    poll_deadline = DATE_ADD(COALESCE(update_time, create_time, NOW()), INTERVAL 5 MINUTE)
WHERE status = 'SUBMITTED'
  AND feed_submission_id IS NOT NULL;

CREATE INDEX idx_listing_copy_poll_due
    ON amz_listing_copy_task (status, next_poll_time);
