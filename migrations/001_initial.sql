CREATE TABLE base_tc_system (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(200) NOT NULL UNIQUE CHECK (length(btrim(name)) > 0),
    maintainer_name VARCHAR(100),
    status VARCHAR(16) NOT NULL DEFAULT 'enabled' CHECK (status IN ('enabled', 'disabled')),
    created_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE base_division (
    LIKE base_tc_system INCLUDING ALL
);
CREATE TABLE base_partner_system (
    LIKE base_tc_system INCLUDING ALL
);

CREATE TABLE integration_ledger (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tc_system_id BIGINT NOT NULL REFERENCES base_tc_system(id) ON DELETE RESTRICT,
    partner_system_id BIGINT NOT NULL REFERENCES base_partner_system(id) ON DELETE RESTRICT,
    test_ip VARCHAR(45),
    prod_ip VARCHAR(45),
    integration_status VARCHAR(16) NOT NULL DEFAULT 'active'
        CHECK (integration_status IN ('active', 'invalid')),
    preferences_text TEXT,
    handle_text TEXT,
    remark TEXT,
    maintainer_name VARCHAR(100),
    version INTEGER NOT NULL DEFAULT 1 CHECK (version > 0),
    created_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE integration_ledger_division (
    ledger_id BIGINT NOT NULL REFERENCES integration_ledger(id) ON DELETE CASCADE,
    division_id BIGINT NOT NULL REFERENCES base_division(id) ON DELETE RESTRICT,
    PRIMARY KEY (ledger_id, division_id)
);
CREATE TABLE integration_ledger_contact (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ledger_id BIGINT NOT NULL REFERENCES integration_ledger(id) ON DELETE CASCADE,
    contact_side VARCHAR(16) NOT NULL CHECK (contact_side IN ('tc', 'partner')),
    contact_name VARCHAR(100) NOT NULL CHECK (length(btrim(contact_name)) > 0),
    sort_no INTEGER NOT NULL DEFAULT 0 CHECK (sort_no >= 0),
    created_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE integration_interface (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ledger_id BIGINT NOT NULL REFERENCES integration_ledger(id) ON DELETE CASCADE,
    provider_side VARCHAR(16) NOT NULL CHECK (provider_side IN ('tc', 'non_tc')),
    interface_name VARCHAR(200) NOT NULL CHECK (length(btrim(interface_name)) > 0),
    integration_status VARCHAR(16) NOT NULL DEFAULT 'active'
        CHECK (integration_status IN ('active', 'invalid')),
    test_nacos_service_name VARCHAR(255),
    prod_nacos_service_name VARCHAR(255),
    test_url VARCHAR(2048),
    prod_url VARCHAR(2048),
    message_sample TEXT,
    test_log_path VARCHAR(2048),
    prod_log_path VARCHAR(2048),
    remark TEXT,
    sort_no INTEGER NOT NULL DEFAULT 0 CHECK (sort_no >= 0),
    created_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE integration_attachment (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ledger_id BIGINT NOT NULL REFERENCES integration_ledger(id) ON DELETE CASCADE,
    file_name VARCHAR(255) NOT NULL CHECK (length(btrim(file_name)) > 0),
    storage_source VARCHAR(32) NOT NULL CHECK (storage_source IN ('dimou', 'local')),
    storage_ref VARCHAR(1024) NOT NULL CHECK (length(btrim(storage_ref)) > 0),
    file_size BIGINT CHECK (file_size >= 0),
    content_type VARCHAR(127),
    sort_no INTEGER NOT NULL DEFAULT 0 CHECK (sort_no >= 0),
    created_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_tc_updated ON base_tc_system(updated_at DESC, id DESC);
CREATE INDEX idx_division_updated ON base_division(updated_at DESC, id DESC);
CREATE INDEX idx_partner_updated ON base_partner_system(updated_at DESC, id DESC);
CREATE INDEX idx_ledger_tc ON integration_ledger(tc_system_id);
CREATE INDEX idx_ledger_partner ON integration_ledger(partner_system_id);
CREATE INDEX idx_ledger_updated ON integration_ledger(updated_at DESC, id DESC);
CREATE INDEX idx_division_reverse ON integration_ledger_division(division_id, ledger_id);
CREATE INDEX idx_contact_ledger ON integration_ledger_contact(ledger_id, contact_side, sort_no, id);
CREATE INDEX idx_contact_name ON integration_ledger_contact(contact_name, ledger_id);
CREATE INDEX idx_interface_ledger ON integration_interface(ledger_id, sort_no, id);
CREATE INDEX idx_interface_provider ON integration_interface(provider_side, ledger_id);
CREATE INDEX idx_attachment_ledger ON integration_attachment(ledger_id, sort_no, id);

COMMENT ON TABLE integration_ledger IS '系统集成台账主表';
COMMENT ON TABLE integration_ledger_division IS '台账事业部多对多关联';
COMMENT ON TABLE integration_ledger_contact IS 'TC方及集成方负责人';
COMMENT ON TABLE integration_interface IS '台账接口明细；日志字段仅登记路径';
COMMENT ON TABLE integration_attachment IS '关联文档元数据；不存储文件内容';

CREATE TABLE demo_seed_runs (
    seed_key VARCHAR(100) PRIMARY KEY,
    seeded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
