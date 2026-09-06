CREATE TABLE import_batch (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    dataset VARCHAR(20) NOT NULL,
    state VARCHAR(20) NOT NULL DEFAULT 'PREVIEW',
    file_name VARCHAR(255) NOT NULL,
    file_sha256 CHAR(64) NOT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    FOREIGN KEY (created_by) REFERENCES app_user(id),
    CHECK (dataset IN ('PROJECT','RATE','LEAVE')),
    CHECK (state IN ('PREVIEW','APPLIED','PARTIAL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE source_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    dataset VARCHAR(20) NOT NULL,
    source_key VARCHAR(150) COLLATE utf8mb4_0900_as_cs NOT NULL,
    source_version VARCHAR(60) COLLATE utf8mb4_0900_as_cs NOT NULL,
    normalized_data JSON NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    application_state VARCHAR(20) NOT NULL DEFAULT 'RECEIVED',
    error_message VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    applied_at DATETIME(6) NULL,
    UNIQUE KEY uk_source_version(dataset,source_key,source_version),
    CHECK (application_state IN ('RECEIVED','APPLIED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Stable parent for serialization across batches, including a source not yet applied.
CREATE TABLE leave_source (
    source_key VARCHAR(150) COLLATE utf8mb4_0900_as_cs PRIMARY KEY,
    user_id BIGINT NULL,
    work_date DATE NULL,
    current_source_record_id BIGINT NULL,
    FOREIGN KEY (user_id) REFERENCES app_user(id),
    FOREIGN KEY (current_source_record_id) REFERENCES source_record(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE import_row (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    row_no INT NOT NULL,
    raw_data JSON NOT NULL,
    parse_error VARCHAR(1000) NULL,
    valid BOOLEAN NOT NULL,
    state VARCHAR(20) NOT NULL,
    error_message VARCHAR(1000) NULL,
    source_record_id BIGINT NULL,
    applied_at DATETIME(6) NULL,
    FOREIGN KEY (batch_id) REFERENCES import_batch(id),
    FOREIGN KEY (source_record_id) REFERENCES source_record(id),
    UNIQUE KEY uk_import_row(batch_id,row_no),
    CHECK (state IN ('VALID','INVALID','APPLIED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
