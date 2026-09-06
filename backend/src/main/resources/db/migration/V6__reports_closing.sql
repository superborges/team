ALTER TABLE accounting_period ADD COLUMN latest_version_id BIGINT NULL, ADD COLUMN active_amendment_id BIGINT NULL;

CREATE TABLE daily_completion_fact (
    user_id BIGINT NOT NULL,
    work_date DATE NOT NULL,
    first_ready_at DATETIME(6) NOT NULL,
    deadline_at DATETIME(6) NOT NULL,
    config_version_id VARCHAR(30) NOT NULL,
    PRIMARY KEY(user_id,work_date),
    FOREIGN KEY(user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE amendment_request (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    period_id BIGINT NOT NULL,
    requested_by BIGINT NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    scopes_json JSON NOT NULL,
    state VARCHAR(20) NOT NULL DEFAULT 'REQUESTED',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    FOREIGN KEY(period_id) REFERENCES accounting_period(id),
    FOREIGN KEY(requested_by) REFERENCES app_user(id),
    CHECK(state IN ('REQUESTED','GRANTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE amendment_batch (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    period_id BIGINT NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    state VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    created_by BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    published_at DATETIME(6) NULL,
    FOREIGN KEY(period_id) REFERENCES accounting_period(id),
    FOREIGN KEY(created_by) REFERENCES app_user(id),
    CHECK(state IN ('OPEN','PUBLISHED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE accounting_period ADD CONSTRAINT fk_active_amendment FOREIGN KEY(active_amendment_id) REFERENCES amendment_batch(id);

CREATE TABLE unlock_scope (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    amendment_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    work_date DATE NOT NULL,
    work_item_id BIGINT NULL,
    object_type VARCHAR(10) NOT NULL,
    record_id BIGINT NULL,
    new_after_record_id BIGINT NULL,
    actions JSON NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    authorized_by BIGINT NOT NULL,
    authorized_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    FOREIGN KEY(amendment_id) REFERENCES amendment_batch(id),
    FOREIGN KEY(user_id) REFERENCES app_user(id),
    FOREIGN KEY(work_item_id) REFERENCES work_item(id),
    FOREIGN KEY(authorized_by) REFERENCES app_user(id),
    INDEX ix_unlock_match(amendment_id,user_id,work_date,object_type,work_item_id,active),
    CHECK(object_type IN ('TIME','ONSITE','BASE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE monthly_report_version (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    period_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    operation_key VARCHAR(100) NOT NULL UNIQUE,
    previous_version_id BIGINT NULL,
    amendment_id BIGINT NULL,
    reason VARCHAR(1000) NOT NULL,
    cutoff_at DATETIME(6) NOT NULL,
    published_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    policy_version VARCHAR(100) NOT NULL,
    UNIQUE KEY uk_monthly_version(period_id,version_no),
    FOREIGN KEY(period_id) REFERENCES accounting_period(id),
    FOREIGN KEY(previous_version_id) REFERENCES monthly_report_version(id),
    FOREIGN KEY(amendment_id) REFERENCES amendment_batch(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE accounting_period ADD CONSTRAINT fk_latest_report FOREIGN KEY(latest_version_id) REFERENCES monthly_report_version(id);

CREATE TABLE monthly_snapshot_row (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    version_id BIGINT NOT NULL,
    row_kind VARCHAR(20) NOT NULL,
    user_id BIGINT NULL,
    work_date DATE NULL,
    work_item_id BIGINT NULL,
    record_id BIGINT NULL,
    revision_id BIGINT NULL,
    payload JSON NOT NULL,
    FOREIGN KEY(version_id) REFERENCES monthly_report_version(id),
    INDEX ix_snapshot_people(version_id,row_kind,user_id,work_date),
    INDEX ix_snapshot_projects(version_id,row_kind,work_item_id,work_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE export_request (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    requested_by BIGINT NOT NULL,
    report_kind VARCHAR(30) NOT NULL,
    request_json JSON NOT NULL,
    requested_scope JSON NOT NULL,
    actual_scope JSON NULL,
    state VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    generation_token CHAR(36) NULL,
    private_file VARCHAR(100) NULL,
    error_message VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    finished_at DATETIME(6) NULL,
    expires_at DATETIME(6) NULL,
    FOREIGN KEY(requested_by) REFERENCES app_user(id),
    CHECK(state IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
    INDEX ix_export_owner(requested_by,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
