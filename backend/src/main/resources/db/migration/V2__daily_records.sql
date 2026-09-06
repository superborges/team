CREATE TABLE accounting_period (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    month_start DATE NOT NULL UNIQUE,
    scheduled_close_at DATETIME(6) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    actual_closed_at DATETIME(6) NULL,
    CHECK (DAY(month_start)=1),
    CHECK (status IN ('OPEN','CLOSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE day_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    work_date DATE NOT NULL,
    base_minutes INT NOT NULL,
    leave_minutes INT NOT NULL,
    required_minutes INT NOT NULL,
    is_workday BOOLEAN NOT NULL,
    department_id BIGINT NOT NULL,
    row_version INT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_day_user_date(user_id,work_date),
    FOREIGN KEY (user_id) REFERENCES app_user(id),
    FOREIGN KEY (department_id) REFERENCES department(id),
    CHECK (base_minutes BETWEEN 0 AND 1440),
    CHECK (leave_minutes BETWEEN 0 AND 1440),
    CHECK (required_minutes BETWEEN 0 AND 1440)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE time_entry (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    day_id BIGINT NOT NULL,
    current_revision_id BIGINT NULL,
    FOREIGN KEY (day_id) REFERENCES day_record(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE time_entry_revision (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    entry_id BIGINT NOT NULL,
    revision_no INT NOT NULL,
    action VARCHAR(10) NOT NULL,
    work_item_id BIGINT NOT NULL,
    kind VARCHAR(10) NOT NULL,
    minutes INT NOT NULL,
    content VARCHAR(200) NOT NULL,
    red_reason VARCHAR(500) NOT NULL DEFAULT '',
    department_id BIGINT NOT NULL,
    state VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_time_revision(entry_id,revision_no),
    UNIQUE KEY uk_time_revision_parent(entry_id,id),
    FOREIGN KEY (entry_id) REFERENCES time_entry(id),
    FOREIGN KEY (work_item_id) REFERENCES work_item(id),
    FOREIGN KEY (department_id) REFERENCES department(id),
    CHECK (action IN ('REPORT','CANCEL')),
    CHECK (kind IN ('WORK','TRAVEL','IDLE')),
    CHECK (minutes>0 AND minutes<=1440 AND MOD(minutes,30)=0),
    CHECK (state IN ('DRAFT','PENDING','APPROVED','REJECTED','CANCELED','LOCKED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE time_entry ADD CONSTRAINT fk_current_time_revision
    FOREIGN KEY (id,current_revision_id) REFERENCES time_entry_revision(entry_id,id);

CREATE TABLE onsite_day (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    day_id BIGINT NOT NULL UNIQUE,
    current_revision_id BIGINT NULL,
    FOREIGN KEY (day_id) REFERENCES day_record(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE onsite_day_revision (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    onsite_id BIGINT NOT NULL,
    revision_no INT NOT NULL,
    action VARCHAR(10) NOT NULL,
    work_item_id BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL DEFAULT '',
    state VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_onsite_revision(onsite_id,revision_no),
    UNIQUE KEY uk_onsite_revision_parent(onsite_id,id),
    FOREIGN KEY (onsite_id) REFERENCES onsite_day(id),
    FOREIGN KEY (work_item_id) REFERENCES work_item(id),
    CHECK (action IN ('REPORT','CANCEL')),
    CHECK (state IN ('DRAFT','PENDING','APPROVED','REJECTED','CANCELED','LOCKED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE onsite_day ADD CONSTRAINT fk_current_onsite_revision
    FOREIGN KEY (id,current_revision_id) REFERENCES onsite_day_revision(onsite_id,id);

CREATE TABLE audit_event (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    actor_id BIGINT NOT NULL,
    action VARCHAR(64) NOT NULL,
    object_type VARCHAR(64) NOT NULL,
    object_id VARCHAR(64) NOT NULL,
    before_data JSON NULL,
    after_data JSON NULL,
    reason VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX ix_audit_object(object_type,object_id,created_at),
    FOREIGN KEY(actor_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE command_receipt (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    actor_id BIGINT NOT NULL,
    command_type VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    response_json JSON NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_command(actor_id,command_type,idempotency_key),
    FOREIGN KEY(actor_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
