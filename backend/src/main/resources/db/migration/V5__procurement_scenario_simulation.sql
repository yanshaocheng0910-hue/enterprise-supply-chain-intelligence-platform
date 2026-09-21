CREATE TABLE procurement_scenario (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    scenario_no VARCHAR(64) NOT NULL UNIQUE,
    scenario_name VARCHAR(128) NOT NULL,
    material_id BIGINT NOT NULL,
    forecast_run_id BIGINT NOT NULL,
    parameters_json TEXT NOT NULL,
    input_snapshot TEXT NOT NULL,
    result_snapshot TEXT NOT NULL,
    data_fingerprint VARCHAR(64) NOT NULL,
    baseline_order_qty DECIMAL(18,4) NOT NULL DEFAULT 0,
    simulated_order_qty DECIMAL(18,4) NOT NULL DEFAULT 0,
    simulated_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
    risk_level VARCHAR(16) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PREVIEW',
    version INT NOT NULL DEFAULT 0,
    simulation_idempotency_key VARCHAR(80) NOT NULL,
    adoption_idempotency_key VARCHAR(80),
    adopted_demand_id BIGINT,
    created_by BIGINT NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    adopted_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_scenario_simulation_idem UNIQUE (created_by, simulation_idempotency_key),
    CONSTRAINT uk_scenario_adoption_idem UNIQUE (adoption_idempotency_key),
    CONSTRAINT fk_scenario_material FOREIGN KEY (material_id) REFERENCES material(id),
    CONSTRAINT fk_scenario_forecast FOREIGN KEY (forecast_run_id) REFERENCES forecast_run(id),
    CONSTRAINT fk_scenario_demand FOREIGN KEY (adopted_demand_id) REFERENCES purchase_demand(id),
    CONSTRAINT fk_scenario_creator FOREIGN KEY (created_by) REFERENCES sys_user(id)
);

CREATE INDEX idx_scenario_material_created ON procurement_scenario(material_id, created_at);
CREATE INDEX idx_scenario_status_expiry ON procurement_scenario(status, expires_at);
