ALTER TABLE forecast_run ADD COLUMN suggestion_status VARCHAR(24) NOT NULL DEFAULT 'PENDING';
ALTER TABLE forecast_run ADD COLUMN suggestion_decision_note VARCHAR(500);
ALTER TABLE forecast_run ADD COLUMN suggestion_decided_by BIGINT;
ALTER TABLE forecast_run ADD COLUMN suggestion_decided_at TIMESTAMP NULL;
ALTER TABLE forecast_run ADD COLUMN optimization_note VARCHAR(500);
ALTER TABLE forecast_run ADD COLUMN version INT NOT NULL DEFAULT 0;

ALTER TABLE purchase_demand ADD COLUMN forecast_run_id BIGINT;
ALTER TABLE purchase_demand ADD CONSTRAINT fk_pd_forecast_run FOREIGN KEY (forecast_run_id) REFERENCES forecast_run(id);
CREATE UNIQUE INDEX uk_purchase_demand_forecast_run ON purchase_demand(forecast_run_id);

ALTER TABLE warning_record ADD COLUMN source_key VARCHAR(160);
ALTER TABLE warning_record ADD COLUMN condition_active BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE warning_record ADD COLUMN last_detected_at TIMESTAMP NULL;
ALTER TABLE warning_record ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
UPDATE warning_record
SET source_key = CONCAT(warning_type, ':', target_type, ':', target_id)
WHERE source_key IS NULL AND target_id IS NOT NULL;
CREATE UNIQUE INDEX uk_warning_source_key ON warning_record(source_key);

UPDATE purchase_demand
SET status = 'PLANNED'
WHERE status = 'DRAFT'
  AND id IN (SELECT demand_id FROM purchase_plan_item WHERE demand_id IS NOT NULL);

UPDATE purchase_plan
SET status = 'ORDER_CREATED', updated_at = CURRENT_TIMESTAMP
WHERE status = 'APPROVED'
  AND id IN (SELECT plan_id FROM purchase_order);

CREATE UNIQUE INDEX uk_purchase_order_plan ON purchase_order(plan_id);
