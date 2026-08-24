CREATE TABLE sys_role (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_code VARCHAR(32) NOT NULL UNIQUE,
    role_name VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE supplier (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    supplier_code VARCHAR(32) NOT NULL UNIQUE,
    supplier_name VARCHAR(128) NOT NULL,
    contact_name VARCHAR(64),
    contact_phone VARCHAR(32),
    level_code VARCHAR(16) NOT NULL DEFAULT 'B',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    on_time_rate DECIMAL(8,4) NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE sys_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    display_name VARCHAR(64) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role_code VARCHAR(32) NOT NULL,
    supplier_id BIGINT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    last_login_at TIMESTAMP NULL,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_user_role FOREIGN KEY (role_code) REFERENCES sys_role(role_code),
    CONSTRAINT fk_user_supplier FOREIGN KEY (supplier_id) REFERENCES supplier(id)
);

CREATE TABLE material (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    material_code VARCHAR(32) NOT NULL UNIQUE,
    material_name VARCHAR(128) NOT NULL,
    category VARCHAR(64) NOT NULL,
    unit VARCHAR(16) NOT NULL,
    safety_stock DECIMAL(18,4) NOT NULL DEFAULT 0,
    min_order_qty DECIMAL(18,4) NOT NULL DEFAULT 1,
    pack_size DECIMAL(18,4) NOT NULL DEFAULT 1,
    lead_time_days INT NOT NULL DEFAULT 7,
    standard_price DECIMAL(18,4) NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE warehouse (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    warehouse_code VARCHAR(32) NOT NULL UNIQUE,
    warehouse_name VARCHAR(128) NOT NULL,
    location_text VARCHAR(255),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE inventory (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    warehouse_id BIGINT NOT NULL,
    material_id BIGINT NOT NULL,
    on_hand_qty DECIMAL(18,4) NOT NULL DEFAULT 0,
    reserved_qty DECIMAL(18,4) NOT NULL DEFAULT 0,
    in_transit_qty DECIMAL(18,4) NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_inventory UNIQUE (warehouse_id, material_id),
    CONSTRAINT fk_inventory_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse(id),
    CONSTRAINT fk_inventory_material FOREIGN KEY (material_id) REFERENCES material(id)
);

CREATE TABLE inventory_transaction (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    transaction_no VARCHAR(64) NOT NULL UNIQUE,
    warehouse_id BIGINT NOT NULL,
    material_id BIGINT NOT NULL,
    transaction_type VARCHAR(32) NOT NULL,
    quantity DECIMAL(18,4) NOT NULL,
    before_qty DECIMAL(18,4) NOT NULL,
    after_qty DECIMAL(18,4) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_id BIGINT,
    operator_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_it_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse(id),
    CONSTRAINT fk_it_material FOREIGN KEY (material_id) REFERENCES material(id)
);

CREATE TABLE data_import_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no VARCHAR(64) NOT NULL UNIQUE,
    import_type VARCHAR(32) NOT NULL,
    source_system VARCHAR(64) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    file_hash VARCHAR(64) NOT NULL,
    template_version VARCHAR(16) NOT NULL DEFAULT 'v1',
    staged_payload LONGTEXT,
    as_of_time TIMESTAMP NULL,
    idempotency_key VARCHAR(80),
    status VARCHAR(24) NOT NULL,
    total_rows INT NOT NULL DEFAULT 0,
    success_rows INT NOT NULL DEFAULT 0,
    failed_rows INT NOT NULL DEFAULT 0,
    operator_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMP NULL,
    CONSTRAINT uk_import_hash_type UNIQUE (import_type, file_hash),
    CONSTRAINT uk_import_idem UNIQUE (idempotency_key)
);

CREATE TABLE data_import_error (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    row_number INT NOT NULL,
    field_name VARCHAR(64),
    error_code VARCHAR(64) NOT NULL,
    error_message VARCHAR(500) NOT NULL,
    raw_data TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_import_error_batch FOREIGN KEY (batch_id) REFERENCES data_import_batch(id)
);

CREATE TABLE demand_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    material_id BIGINT NOT NULL,
    demand_date DATE NOT NULL,
    quantity DECIMAL(18,4) NOT NULL,
    source_system VARCHAR(64) NOT NULL,
    import_batch_id BIGINT,
    data_label VARCHAR(32) NOT NULL DEFAULT 'BUSINESS',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_demand_material_date_source UNIQUE (material_id, demand_date, source_system),
    CONSTRAINT fk_demand_material FOREIGN KEY (material_id) REFERENCES material(id),
    CONSTRAINT fk_demand_batch FOREIGN KEY (import_batch_id) REFERENCES data_import_batch(id)
);

CREATE TABLE demand_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    material_id BIGINT NOT NULL,
    event_date DATE NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    quantity_delta DECIMAL(18,4),
    description VARCHAR(500),
    source_system VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_event_material FOREIGN KEY (material_id) REFERENCES material(id)
);

CREATE TABLE purchase_demand (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    demand_no VARCHAR(64) NOT NULL UNIQUE,
    material_id BIGINT NOT NULL,
    quantity DECIMAL(18,4) NOT NULL,
    expected_date DATE NOT NULL,
    priority VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    source_type VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
    source_ref VARCHAR(64),
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    notes VARCHAR(500),
    created_by BIGINT NOT NULL,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_pd_material FOREIGN KEY (material_id) REFERENCES material(id)
);

CREATE TABLE purchase_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_no VARCHAR(64) NOT NULL UNIQUE,
    plan_name VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    total_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
    rejection_reason VARCHAR(500),
    created_by BIGINT NOT NULL,
    approved_by BIGINT,
    approved_at TIMESTAMP NULL,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE purchase_plan_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id BIGINT NOT NULL,
    demand_id BIGINT,
    material_id BIGINT NOT NULL,
    supplier_id BIGINT,
    quantity DECIMAL(18,4) NOT NULL,
    unit_price DECIMAL(18,4) NOT NULL,
    expected_date DATE NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ppi_plan FOREIGN KEY (plan_id) REFERENCES purchase_plan(id),
    CONSTRAINT fk_ppi_demand FOREIGN KEY (demand_id) REFERENCES purchase_demand(id),
    CONSTRAINT fk_ppi_material FOREIGN KEY (material_id) REFERENCES material(id),
    CONSTRAINT fk_ppi_supplier FOREIGN KEY (supplier_id) REFERENCES supplier(id)
);

CREATE TABLE purchase_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no VARCHAR(64) NOT NULL UNIQUE,
    plan_id BIGINT NOT NULL,
    supplier_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING_CONFIRMATION',
    order_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
    expected_arrival_date DATE,
    cancel_reason VARCHAR(500),
    idempotency_key VARCHAR(80) NOT NULL UNIQUE,
    version INT NOT NULL DEFAULT 0,
    created_by BIGINT NOT NULL,
    confirmed_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_po_plan FOREIGN KEY (plan_id) REFERENCES purchase_plan(id),
    CONSTRAINT fk_po_supplier FOREIGN KEY (supplier_id) REFERENCES supplier(id)
);

CREATE TABLE purchase_order_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    material_id BIGINT NOT NULL,
    quantity DECIMAL(18,4) NOT NULL,
    received_qty DECIMAL(18,4) NOT NULL DEFAULT 0,
    unit_price DECIMAL(18,4) NOT NULL,
    amount DECIMAL(18,2) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_poi_order FOREIGN KEY (order_id) REFERENCES purchase_order(id),
    CONSTRAINT fk_poi_material FOREIGN KEY (material_id) REFERENCES material(id)
);

CREATE TABLE delivery_notice (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    notice_no VARCHAR(64) NOT NULL UNIQUE,
    order_id BIGINT NOT NULL,
    supplier_id BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    expected_arrival_at TIMESTAMP NOT NULL,
    carrier_name VARCHAR(128),
    tracking_no VARCHAR(128),
    exception_note VARCHAR(500),
    source_type VARCHAR(32) NOT NULL DEFAULT 'FORM',
    created_by BIGINT NOT NULL,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_dn_order FOREIGN KEY (order_id) REFERENCES purchase_order(id),
    CONSTRAINT fk_dn_supplier FOREIGN KEY (supplier_id) REFERENCES supplier(id)
);

CREATE TABLE delivery_notice_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    notice_id BIGINT NOT NULL,
    order_item_id BIGINT NOT NULL,
    quantity DECIMAL(18,4) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_dni_notice FOREIGN KEY (notice_id) REFERENCES delivery_notice(id),
    CONSTRAINT fk_dni_order_item FOREIGN KEY (order_item_id) REFERENCES purchase_order_item(id)
);

CREATE TABLE receipt (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    receipt_no VARCHAR(64) NOT NULL UNIQUE,
    order_id BIGINT NOT NULL,
    notice_id BIGINT,
    warehouse_id BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'COMPLETED',
    received_by BIGINT NOT NULL,
    received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    notes VARCHAR(500),
    idempotency_key VARCHAR(80) NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_receipt_order FOREIGN KEY (order_id) REFERENCES purchase_order(id),
    CONSTRAINT fk_receipt_notice FOREIGN KEY (notice_id) REFERENCES delivery_notice(id),
    CONSTRAINT fk_receipt_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse(id)
);

CREATE TABLE receipt_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    receipt_id BIGINT NOT NULL,
    order_item_id BIGINT NOT NULL,
    material_id BIGINT NOT NULL,
    ordered_qty DECIMAL(18,4) NOT NULL,
    received_qty DECIMAL(18,4) NOT NULL,
    qualified_qty DECIMAL(18,4) NOT NULL,
    rejected_qty DECIMAL(18,4) NOT NULL DEFAULT 0,
    difference_qty DECIMAL(18,4) NOT NULL,
    difference_reason VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ri_receipt FOREIGN KEY (receipt_id) REFERENCES receipt(id),
    CONSTRAINT fk_ri_order_item FOREIGN KEY (order_item_id) REFERENCES purchase_order_item(id),
    CONSTRAINT fk_ri_material FOREIGN KEY (material_id) REFERENCES material(id)
);

CREATE TABLE reconciliation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    reconciliation_no VARCHAR(64) NOT NULL UNIQUE,
    order_id BIGINT NOT NULL UNIQUE,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    order_amount DECIMAL(18,2) NOT NULL,
    received_amount DECIMAL(18,2) NOT NULL,
    difference_amount DECIMAL(18,2) NOT NULL,
    dispute_reason VARCHAR(500),
    resolution_note VARCHAR(500),
    buyer_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    supplier_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_recon_order FOREIGN KEY (order_id) REFERENCES purchase_order(id)
);

CREATE TABLE reconciliation_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    reconciliation_id BIGINT NOT NULL,
    order_item_id BIGINT NOT NULL,
    ordered_qty DECIMAL(18,4) NOT NULL,
    received_qty DECIMAL(18,4) NOT NULL,
    unit_price DECIMAL(18,4) NOT NULL,
    difference_amount DECIMAL(18,2) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_recon_item FOREIGN KEY (reconciliation_id) REFERENCES reconciliation(id),
    CONSTRAINT fk_recon_order_item FOREIGN KEY (order_item_id) REFERENCES purchase_order_item(id)
);

CREATE TABLE forecast_run (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_no VARCHAR(64) NOT NULL UNIQUE,
    material_id BIGINT NOT NULL,
    as_of_date DATE NOT NULL,
    horizon_days INT NOT NULL DEFAULT 14,
    model_name VARCHAR(64) NOT NULL,
    model_version VARCHAR(64),
    data_hash VARCHAR(64) NOT NULL,
    data_label VARCHAR(32) NOT NULL DEFAULT 'DEMO_SYNTHETIC',
    mae DECIMAL(18,6),
    rmse DECIMAL(18,6),
    mape DECIMAL(18,6),
    fallback_reason VARCHAR(255),
    validation_details TEXT,
    split_details VARCHAR(500),
    feature_version VARCHAR(64),
    random_seed INT,
    status VARCHAR(24) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_forecast_material FOREIGN KEY (material_id) REFERENCES material(id)
);

CREATE TABLE forecast_result (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id BIGINT NOT NULL,
    forecast_date DATE NOT NULL,
    predicted_qty DECIMAL(18,4) NOT NULL,
    raw_predicted_qty DECIMAL(18,4),
    lower_bound DECIMAL(18,4),
    upper_bound DECIMAL(18,4),
    suggested_order_qty DECIMAL(18,4) NOT NULL DEFAULT 0,
    warning_code VARCHAR(64),
    postprocess_note VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_forecast_run_date UNIQUE (run_id, forecast_date),
    CONSTRAINT fk_forecast_result_run FOREIGN KEY (run_id) REFERENCES forecast_run(id)
);

CREATE TABLE ai_parse_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    parse_no VARCHAR(64) NOT NULL UNIQUE,
    task_type VARCHAR(32) NOT NULL,
    schema_version VARCHAR(32) NOT NULL,
    input_text TEXT NOT NULL,
    context_snapshot TEXT,
    provider VARCHAR(64) NOT NULL,
    model_name VARCHAR(128),
    prompt_version VARCHAR(64) NOT NULL,
    raw_response TEXT,
    normalized_json TEXT,
    schema_valid BOOLEAN NOT NULL DEFAULT FALSE,
    business_valid BOOLEAN NOT NULL DEFAULT FALSE,
    validation_errors TEXT,
    target_type VARCHAR(32),
    target_id BIGINT,
    final_status VARCHAR(24) NOT NULL DEFAULT 'PREVIEW',
    preview_version INT NOT NULL DEFAULT 0,
    expires_at TIMESTAMP NOT NULL,
    confirm_idempotency_key VARCHAR(80) UNIQUE,
    confirmed_by BIGINT,
    confirmed_at TIMESTAMP NULL,
    request_id VARCHAR(80),
    latency_ms BIGINT,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE warning_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    warning_no VARCHAR(64) NOT NULL UNIQUE,
    warning_type VARCHAR(32) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    target_id BIGINT,
    title VARCHAR(255) NOT NULL,
    reason_text VARCHAR(500) NOT NULL,
    suggestion_text VARCHAR(500) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
    handled_by BIGINT,
    handled_result VARCHAR(500),
    handled_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE operation_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(80) NOT NULL,
    operator_id BIGINT,
    operator_name VARCHAR(64),
    role_code VARCHAR(32),
    action_code VARCHAR(64) NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    target_id BIGINT,
    before_state VARCHAR(64),
    after_state VARCHAR(64),
    detail_text TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_demand_material_date ON demand_history(material_id, demand_date);
CREATE INDEX idx_plan_status ON purchase_plan(status);
CREATE INDEX idx_order_supplier_status ON purchase_order(supplier_id, status);
CREATE INDEX idx_warning_status_severity ON warning_record(status, severity);
CREATE INDEX idx_operation_target ON operation_log(target_type, target_id);
