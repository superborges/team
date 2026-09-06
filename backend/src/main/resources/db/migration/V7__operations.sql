CREATE TABLE system_config_guard(id INT PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO system_config_guard VALUES(1);
CREATE TABLE system_config_version (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 effective_from DATE NOT NULL UNIQUE,
 config_json JSON NOT NULL,
 created_by BIGINT NULL,
 reason VARCHAR(500) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(created_by) REFERENCES app_user(id)
) ENGINE=InnoDB;
CREATE TABLE job (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 type VARCHAR(40) NOT NULL,
 business_key VARCHAR(180) NOT NULL UNIQUE,
 payload JSON NOT NULL,
 actor_id BIGINT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
 planned_at DATETIME(6) NOT NULL,
 next_run_at DATETIME(6) NOT NULL,
 attempts INT NOT NULL DEFAULT 0,
 lease_token VARCHAR(36) NULL,
 lease_until DATETIME(6) NULL,
 claimed_at DATETIME(6) NULL,
 completed_at DATETIME(6) NULL,
 result_json JSON NULL,
 error_message VARCHAR(1000) NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX ix_job_due(status,next_run_at),
 FOREIGN KEY(actor_id) REFERENCES app_user(id)
) ENGINE=InnoDB;
CREATE TABLE notification_outbox (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 recipient_id BIGINT NOT NULL,
 package_id BIGINT NULL,
 event_type VARCHAR(40) NOT NULL,
 business_key VARCHAR(180) NOT NULL UNIQUE,
 body VARCHAR(2000) NOT NULL,
 target_path VARCHAR(300) NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
 attempts INT NOT NULL DEFAULT 0,
 next_run_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 delivered_at DATETIME(6) NULL,
 error_message VARCHAR(1000) NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX ix_outbox_due(status,next_run_at),
 FOREIGN KEY(recipient_id) REFERENCES app_user(id)
) ENGINE=InnoDB;
CREATE TABLE worker_state (
 worker_key VARCHAR(50) PRIMARY KEY,
 heartbeat_at DATETIME(6) NOT NULL,
 last_scan_date DATE NULL
) ENGINE=InnoDB;
ALTER TABLE work_calendar ADD is_override BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE work_calendar DROP CHECK ck_calendar_base,
 ADD CONSTRAINT ck_calendar_base CHECK(base_minutes BETWEEN 0 AND 480 AND MOD(base_minutes,15)=0);
UPDATE work_calendar SET is_override=FALSE WHERE reason LIKE '默认周历%';
ALTER TABLE audit_event MODIFY actor_id BIGINT NULL,
 ADD actor_type VARCHAR(10) NOT NULL DEFAULT 'USER',
 ADD job_id BIGINT NULL,
 ADD target_user_id BIGINT NULL;
ALTER TABLE time_entry_revision DROP CHECK time_entry_revision_chk_3,
 ADD CONSTRAINT ck_time_minutes CHECK(minutes>0 AND minutes<=1440);
