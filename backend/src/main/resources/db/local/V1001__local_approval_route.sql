-- Explicit local fixture routing; never loaded by the production migration entry point.
UPDATE work_item w JOIN app_user u ON u.employee_no='ZD23412' AND u.wecom_userid='local-manager'
SET w.default_approver_id=u.id
WHERE w.code='TEST-IDLE' AND w.source='LOCAL' AND w.default_approver_id IS NULL;
