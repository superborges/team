-- Upgrade local departments created before automatic per-department IDLE provisioning.
-- Existing objects, approval assignments, and business history are not overwritten.
INSERT INTO work_item(code,name,type,source,owner_department_id,default_approver_id,status)
SELECT CONCAT('SYS-IDLE-',d.id),CONCAT(d.name,' · 待分配'),'IDLE','LOCAL',d.id,d.designated_manager_id,'ACTIVE'
FROM department d WHERE d.status='ACTIVE'
AND NOT EXISTS (SELECT 1 FROM work_item w WHERE w.owner_department_id=d.id AND w.type='IDLE');
INSERT INTO work_item_history(work_item_id,owner_department_id,valid_from)
SELECT w.id,w.owner_department_id,DATE(CONVERT_TZ(UTC_TIMESTAMP(),'+00:00','+08:00'))
FROM work_item w WHERE w.type='IDLE' AND w.code=CONCAT('SYS-IDLE-',w.owner_department_id)
AND NOT EXISTS (SELECT 1 FROM work_item_history h WHERE h.work_item_id=w.id);
