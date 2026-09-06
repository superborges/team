-- Only the explicit local profile includes db/local. All identities and amounts below are TEST DATA.
INSERT INTO department(id,code,name) VALUES (1,'TEST-DELIVERY','测试交付部'),(2,'TEST-RD','测试研发部');
INSERT INTO app_user(id,employee_no,wecom_userid,name,department_id,level_code) VALUES
 (1,'98123','local-employee','测试员工',1,'MIDDLE'),
 (2,'XX12345','local-pm','测试项目经理',2,'SENIOR'),
 (3,'ZD23412','local-manager','测试部门负责人',1,'SENIOR'),
 (4,'00123','local-admin','测试管理员',1,'SENIOR'),
 (5,'AB12345','local-leader','测试分管领导',2,'SENIOR');
UPDATE department SET designated_manager_id=3,supervisor_user_id=5 WHERE id=1;
UPDATE department SET designated_manager_id=2,supervisor_user_id=5 WHERE id=2;
INSERT INTO user_department_history(user_id,department_id,valid_from) SELECT id,department_id,'2026-01-01' FROM app_user;
INSERT INTO user_level_history(user_id,level_code,valid_from) SELECT id,level_code,'2026-01-01' FROM app_user;
INSERT INTO reporting_enrollment(user_id,valid_from) SELECT id,'2026-01-01' FROM app_user;
INSERT INTO role_grant(user_id,role_code,scope_type,valid_from) SELECT id,'EMPLOYEE','COMPANY','2026-01-01' FROM app_user;
INSERT INTO role_grant(user_id,role_code,scope_type,department_id,valid_from) VALUES
 (2,'PM','COMPANY',NULL,'2026-01-01'),
 (3,'DEPARTMENT_MANAGER','DEPARTMENT',1,'2026-01-01'),
 (4,'ADMIN','COMPANY',NULL,'2026-01-01'),
 (5,'LEADER','COMPANY',NULL,'2026-01-01');
INSERT INTO department_manager(department_id,user_id,title,valid_from) VALUES (1,3,'HEAD','2026-01-01'),(2,2,'HEAD','2026-01-01');
INSERT INTO work_item(id,code,name,type,source,owner_department_id,default_approver_id) VALUES
 (1,'TEST-P001','测试 · 智能制造实施项目','PROJECT','LOCAL',1,2),
 (2,'TEST-P002','测试 · 研发协作项目','PROJECT','LOCAL',2,2),
 (3,'TEST-N001','测试 · 内部管理与协作','NON_PROJECT','LOCAL',1,3),
 (4,'TEST-IDLE','待分配','IDLE','LOCAL',1,NULL);
INSERT INTO work_item_history(work_item_id,owner_department_id,valid_from) SELECT id,owner_department_id,'2026-01-01' FROM work_item;
INSERT INTO rate_card(level_code,daily_rate,valid_from) VALUES ('JUNIOR',800.0000,'2026-01-01'),('MIDDLE',1200.0000,'2026-01-01'),('SENIOR',1600.0000,'2026-01-01');
INSERT INTO onsite_rate(daily_rate,valid_from) VALUES (200.0000,'2026-01-01');
INSERT INTO leave_record(user_id,work_date,source_key,source_version,leave_minutes,start_minute,end_minute,status)
VALUES (1,'2026-09-04','LOCAL-TEST-HALF-DAY','1',240,540,780,'APPROVED');
