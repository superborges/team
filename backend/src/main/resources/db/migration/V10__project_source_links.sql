CREATE TABLE project_source_link (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  source_code VARCHAR(60) COLLATE utf8mb4_0900_as_cs NOT NULL,
  work_item_id BIGINT NULL,
  import_row_id BIGINT NULL,
  linked_by BIGINT NULL,
  reason VARCHAR(1000) NULL,
  linked_at DATETIME(6) NULL,
  UNIQUE KEY uk_project_source_code (source_code),
  CONSTRAINT fk_project_link_item FOREIGN KEY (work_item_id) REFERENCES work_item(id),
  CONSTRAINT fk_project_link_row FOREIGN KEY (import_row_id) REFERENCES import_row(id),
  CONSTRAINT fk_project_link_actor FOREIGN KEY (linked_by) REFERENCES app_user(id),
  CONSTRAINT ck_project_source_binding CHECK ((work_item_id IS NULL AND linked_by IS NULL AND linked_at IS NULL) OR (work_item_id IS NOT NULL AND linked_by IS NOT NULL AND linked_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
