CREATE TABLE supply_chain_analysis_report (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    report_no VARCHAR(64) NOT NULL UNIQUE,
    as_of_time TIMESTAMP NOT NULL,
    provider VARCHAR(64) NOT NULL,
    model_name VARCHAR(128) NOT NULL,
    prompt_version VARCHAR(64) NOT NULL,
    fallback_reason VARCHAR(500),
    data_fingerprint VARCHAR(64) NOT NULL,
    context_json LONGTEXT NOT NULL,
    report_json LONGTEXT NOT NULL,
    idempotency_key VARCHAR(80) NOT NULL UNIQUE,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_analysis_report_creator FOREIGN KEY (created_by) REFERENCES sys_user(id)
);

CREATE INDEX idx_analysis_report_created_at ON supply_chain_analysis_report(created_at);
