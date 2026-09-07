-- Flyway Migration V2: Agent 评估运行日志（A-2 双轨评估落库）
-- Service: amz-service-ai

CREATE TABLE IF NOT EXISTS amz_agent_eval_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_time DATETIME NOT NULL COMMENT '运行时间',
    mode VARCHAR(20) NOT NULL DEFAULT 'keyword' COMMENT '评估模式：keyword/both',
    total_cases INT NOT NULL DEFAULT 0,
    passed_count INT NOT NULL DEFAULT 0,
    pass_rate DECIMAL(5,4) NOT NULL DEFAULT 0 COMMENT '通过率 0-1',
    llm_evaluated_count INT NOT NULL DEFAULT 0 COMMENT '完成 LLM 评分的用例数',
    report_json MEDIUMTEXT COMMENT '完整报告 JSON',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_run_time (run_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 评估运行日志';
