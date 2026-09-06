CREATE TABLE department (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  code VARCHAR(40) COLLATE utf8mb4_0900_as_cs NOT NULL,
  name VARCHAR(100) NOT NULL,
  parent_id BIGINT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  designated_manager_id BIGINT NULL,
  supervisor_user_id BIGINT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_department_code (code),
  CONSTRAINT fk_department_parent FOREIGN KEY (parent_id) REFERENCES department(id),
  CONSTRAINT ck_department_status CHECK (status IN ('ACTIVE','INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE app_user (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  employee_no VARCHAR(20) COLLATE utf8mb4_0900_as_cs NOT NULL,
  wecom_userid VARCHAR(128) COLLATE utf8mb4_0900_as_cs NULL,
  name VARCHAR(100) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  department_id BIGINT NOT NULL,
  level_code VARCHAR(20) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_user_employee_no (employee_no),
  UNIQUE KEY uk_user_wecom_userid (wecom_userid),
  CONSTRAINT fk_user_department FOREIGN KEY (department_id) REFERENCES department(id),
  CONSTRAINT ck_user_status CHECK (status IN ('ACTIVE','INACTIVE')),
  CONSTRAINT ck_user_level CHECK (level_code IN ('JUNIOR','MIDDLE','SENIOR'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE department ADD CONSTRAINT fk_department_manager FOREIGN KEY (designated_manager_id) REFERENCES app_user(id);
ALTER TABLE department ADD CONSTRAINT fk_department_supervisor FOREIGN KEY (supervisor_user_id) REFERENCES app_user(id);

CREATE TABLE user_department_history (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  department_id BIGINT NOT NULL,
  valid_from DATE NOT NULL,
  valid_to DATE NULL,
  CONSTRAINT fk_ud_history_user FOREIGN KEY (user_id) REFERENCES app_user(id),
  CONSTRAINT fk_ud_history_department FOREIGN KEY (department_id) REFERENCES department(id),
  CONSTRAINT ck_ud_history_interval CHECK (valid_to IS NULL OR valid_to > valid_from),
  UNIQUE KEY uk_ud_history_start (user_id,valid_from),
  KEY idx_ud_history_effective (user_id,valid_from,valid_to)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE user_level_history (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  level_code VARCHAR(20) NOT NULL,
  valid_from DATE NOT NULL,
  valid_to DATE NULL,
  CONSTRAINT fk_ul_history_user FOREIGN KEY (user_id) REFERENCES app_user(id),
  CONSTRAINT ck_ul_history_interval CHECK (valid_to IS NULL OR valid_to > valid_from),
  CONSTRAINT ck_ul_history_level CHECK (level_code IN ('JUNIOR','MIDDLE','SENIOR')),
  UNIQUE KEY uk_ul_history_start (user_id,valid_from),
  KEY idx_ul_history_effective (user_id,valid_from,valid_to)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE reporting_enrollment (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  valid_from DATE NOT NULL,
  valid_to DATE NULL,
  CONSTRAINT fk_enrollment_user FOREIGN KEY (user_id) REFERENCES app_user(id),
  CONSTRAINT ck_enrollment_interval CHECK (valid_to IS NULL OR valid_to > valid_from),
  UNIQUE KEY uk_enrollment_start (user_id,valid_from),
  KEY idx_enrollment_effective (user_id,valid_from,valid_to)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE department_manager (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  department_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  title VARCHAR(20) NOT NULL,
  valid_from DATE NOT NULL,
  valid_to DATE NULL,
  CONSTRAINT fk_dept_manager_department FOREIGN KEY (department_id) REFERENCES department(id),
  CONSTRAINT fk_dept_manager_user FOREIGN KEY (user_id) REFERENCES app_user(id),
  CONSTRAINT ck_dept_manager_interval CHECK (valid_to IS NULL OR valid_to > valid_from),
  CONSTRAINT ck_dept_manager_title CHECK (title IN ('HEAD','DEPUTY')),
  UNIQUE KEY uk_dept_manager_start (department_id,user_id,valid_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE role_grant (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  role_code VARCHAR(30) NOT NULL,
  scope_type VARCHAR(20) NOT NULL,
  department_id BIGINT NULL,
  valid_from DATE NOT NULL,
  valid_to DATE NULL,
  CONSTRAINT fk_role_user FOREIGN KEY (user_id) REFERENCES app_user(id),
  CONSTRAINT fk_role_department FOREIGN KEY (department_id) REFERENCES department(id),
  CONSTRAINT ck_role_interval CHECK (valid_to IS NULL OR valid_to > valid_from),
  CONSTRAINT ck_role_scope CHECK ((scope_type='COMPANY' AND department_id IS NULL) OR (scope_type='DEPARTMENT' AND department_id IS NOT NULL)),
  CONSTRAINT ck_role_code CHECK (role_code IN ('EMPLOYEE','ADMIN','PM','DEPARTMENT_MANAGER','LEADER')),
  KEY idx_role_effective (user_id,valid_from,valid_to)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE work_item (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  code VARCHAR(60) COLLATE utf8mb4_0900_as_cs NOT NULL,
  name VARCHAR(150) NOT NULL,
  type VARCHAR(20) NOT NULL,
  source VARCHAR(20) NOT NULL DEFAULT 'LOCAL',
  owner_department_id BIGINT NOT NULL,
  default_approver_id BIGINT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_work_item_code (code),
  CONSTRAINT fk_item_department FOREIGN KEY (owner_department_id) REFERENCES department(id),
  CONSTRAINT fk_item_approver FOREIGN KEY (default_approver_id) REFERENCES app_user(id),
  CONSTRAINT ck_item_type CHECK (type IN ('PROJECT','NON_PROJECT','IDLE')),
  CONSTRAINT ck_item_source CHECK (source IN ('LOCAL','OA')),
  CONSTRAINT ck_item_status CHECK (status IN ('ACTIVE','INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE work_item_history (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  work_item_id BIGINT NOT NULL,
  owner_department_id BIGINT NOT NULL,
  valid_from DATE NOT NULL,
  valid_to DATE NULL,
  CONSTRAINT fk_item_history_item FOREIGN KEY (work_item_id) REFERENCES work_item(id),
  CONSTRAINT fk_item_history_department FOREIGN KEY (owner_department_id) REFERENCES department(id),
  CONSTRAINT ck_item_history_interval CHECK (valid_to IS NULL OR valid_to > valid_from),
  UNIQUE KEY uk_item_history_start (work_item_id,valid_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE rate_dimension (
  code VARCHAR(20) NOT NULL PRIMARY KEY,
  name VARCHAR(50) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO rate_dimension(code,name) VALUES ('JUNIOR','初级'),('MIDDLE','中级'),('SENIOR','高级'),('ONSITE','现场日');

CREATE TABLE rate_card (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  level_code VARCHAR(20) NOT NULL,
  daily_rate DECIMAL(14,4) NOT NULL,
  valid_from DATE NOT NULL,
  valid_to DATE NULL,
  CONSTRAINT fk_rate_dimension FOREIGN KEY (level_code) REFERENCES rate_dimension(code),
  CONSTRAINT ck_rate_labor_level CHECK (level_code IN ('JUNIOR','MIDDLE','SENIOR')),
  CONSTRAINT ck_rate_amount CHECK (daily_rate >= 0),
  CONSTRAINT ck_rate_interval CHECK (valid_to IS NULL OR valid_to > valid_from),
  UNIQUE KEY uk_rate_start (level_code,valid_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE onsite_rate (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  dimension_code VARCHAR(20) NOT NULL DEFAULT 'ONSITE',
  daily_rate DECIMAL(14,4) NOT NULL,
  valid_from DATE NOT NULL,
  valid_to DATE NULL,
  CONSTRAINT fk_onsite_dimension FOREIGN KEY (dimension_code) REFERENCES rate_dimension(code),
  CONSTRAINT ck_onsite_dimension CHECK (dimension_code = 'ONSITE'),
  CONSTRAINT ck_onsite_amount CHECK (daily_rate >= 0),
  CONSTRAINT ck_onsite_rate_interval CHECK (valid_to IS NULL OR valid_to > valid_from),
  UNIQUE KEY uk_onsite_rate_start (valid_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE work_calendar (
  work_date DATE NOT NULL PRIMARY KEY,
  base_minutes INT NOT NULL,
  is_workday BOOLEAN NOT NULL,
  reason VARCHAR(200) NOT NULL,
  CONSTRAINT ck_calendar_base CHECK (base_minutes BETWEEN 0 AND 480 AND MOD(base_minutes,30)=0),
  CONSTRAINT ck_calendar_workday CHECK ((is_workday=TRUE AND base_minutes>0) OR (is_workday=FALSE AND base_minutes=0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE leave_record (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  work_date DATE NOT NULL,
  source_key VARCHAR(150) COLLATE utf8mb4_0900_as_cs NOT NULL,
  source_version VARCHAR(60) NOT NULL,
  leave_minutes INT NOT NULL,
  start_minute INT NULL,
  end_minute INT NULL,
  status VARCHAR(20) NOT NULL,
  CONSTRAINT fk_leave_user FOREIGN KEY (user_id) REFERENCES app_user(id),
  CONSTRAINT ck_leave_minutes CHECK (leave_minutes BETWEEN 0 AND 480),
  CONSTRAINT ck_leave_status CHECK (status IN ('APPROVED','REVOKED')),
  CONSTRAINT ck_leave_slot CHECK ((start_minute IS NULL AND end_minute IS NULL) OR (start_minute>=0 AND end_minute>start_minute AND end_minute<=1440)),
  UNIQUE KEY uk_leave_source (source_key,work_date),
  KEY idx_leave_user_date (user_id,work_date,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
