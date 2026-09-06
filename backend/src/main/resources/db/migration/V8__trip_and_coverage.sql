ALTER TABLE import_batch DROP CHECK import_batch_chk_1,
 ADD CONSTRAINT ck_import_dataset CHECK(dataset IN ('PROJECT','RATE','LEAVE','USER','DEPARTMENT','TRIP'));
CREATE TABLE trip_day (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 source_key VARCHAR(150) COLLATE utf8mb4_0900_as_cs NOT NULL UNIQUE,
 source_version VARCHAR(60) COLLATE utf8mb4_0900_as_cs NOT NULL,
 user_id BIGINT NOT NULL,
 work_date DATE NOT NULL,
 work_item_id BIGINT NULL,
 status VARCHAR(20) NOT NULL,
 source_record_id BIGINT NOT NULL,
 INDEX ix_trip_user_date(user_id,work_date),
 FOREIGN KEY(user_id) REFERENCES app_user(id),
 FOREIGN KEY(work_item_id) REFERENCES work_item(id),
 FOREIGN KEY(source_record_id) REFERENCES source_record(id),
 CHECK(status IN ('APPROVED','REVOKED'))
) ENGINE=InnoDB;
CREATE TABLE source_coverage (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 dataset VARCHAR(20) NOT NULL,
 from_date DATE NOT NULL,
 to_date DATE NOT NULL,
 department_id BIGINT NULL,
 state VARCHAR(20) NOT NULL,
 verified_by BIGINT NOT NULL,
 verified_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 note VARCHAR(1000) NOT NULL,
 import_batch_id BIGINT NULL,
 INDEX ix_coverage_date(dataset,from_date,to_date,department_id),
 FOREIGN KEY(department_id) REFERENCES department(id),
 FOREIGN KEY(verified_by) REFERENCES app_user(id),
 FOREIGN KEY(import_batch_id) REFERENCES import_batch(id),
 CHECK(dataset IN ('LEAVE','TRIP')),
 CHECK(state IN ('COMPLETE','PARTIAL','FAILED')),
 CHECK(from_date<=to_date)
) ENGINE=InnoDB;
