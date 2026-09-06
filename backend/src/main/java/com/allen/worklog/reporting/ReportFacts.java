package com.allen.worklog.reporting;

import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** One SQL statement captures facts AND labels, so READ COMMITTED never mixes snapshot cutoffs. */
@Service
public class ReportFacts {
    public record Fact(String kind,Long userId,LocalDate date,Long workItemId,Long recordId,Long revisionId,Map<String,Object> data) {}
    private final JdbcClient jdbc;private final ObjectMapper json;
    public ReportFacts(JdbcClient jdbc,ObjectMapper json) {this.jdbc=jdbc;this.json=json;}
    public List<Fact> current(LocalDate from,LocalDate to) {
        return jdbc.sql(SQL+" SELECT * FROM facts").param("from",from).param("to",to).query(this::map).list();
    }
    public void freeze(long versionId,LocalDate from,LocalDate to) {
        jdbc.sql("INSERT INTO monthly_snapshot_row(version_id,row_kind,user_id,work_date,work_item_id,record_id,revision_id,payload) "+SQL+
            " SELECT :version,row_kind,user_id,work_date,work_item_id,record_id,revision_id,JSON_SET(payload,'$._capturedAt',CAST(UTC_TIMESTAMP(6) AS CHAR)) FROM facts")
            .param("version",versionId).param("from",from).param("to",to).update();
        jdbc.sql("UPDATE monthly_report_version SET cutoff_at=COALESCE((SELECT CAST(JSON_UNQUOTE(JSON_EXTRACT(payload,'$._capturedAt')) AS DATETIME(6)) FROM monthly_snapshot_row WHERE version_id=? LIMIT 1),cutoff_at) WHERE id=?")
            .params(versionId,versionId).update();
    }
    public List<Fact> frozen(long versionId) {
        return jdbc.sql("SELECT row_kind,user_id,work_date,work_item_id,record_id,revision_id,payload FROM monthly_snapshot_row WHERE version_id=? ORDER BY id")
            .param(versionId).query(this::map).list();
    }
    private Fact map(java.sql.ResultSet rs,int n) throws java.sql.SQLException {
        return new Fact(rs.getString("row_kind"),rs.getObject("user_id",Long.class),rs.getObject("work_date",LocalDate.class),
            rs.getObject("work_item_id",Long.class),rs.getObject("record_id",Long.class),rs.getObject("revision_id",Long.class),
            payload(rs.getString("payload")));
    }
    private Map<String,Object> payload(String source) {
        Map<String,Object> result=json.readValue(source,new TypeReference<Map<String,Object>>(){});result.remove("_capturedAt");return result;
    }
    private static final String SQL="""
      WITH RECURSIVE dates AS (
        SELECT CAST(:from AS DATE) dt UNION ALL SELECT DATE_ADD(dt,INTERVAL 1 DAY) FROM dates WHERE dt<:to
      ), people_dates AS (
        SELECT DISTINCT e.user_id,d.dt FROM dates d JOIN reporting_enrollment e ON e.voided=FALSE AND e.valid_from<=d.dt AND (e.valid_to IS NULL OR e.valid_to>d.dt)
        UNION SELECT user_id,work_date FROM day_record WHERE work_date BETWEEN :from AND :to
      ), leave_slots AS (
        SELECT l.*,MAX(end_minute) OVER(PARTITION BY user_id,work_date ORDER BY start_minute,end_minute,id ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING) previous_end
        FROM leave_record l WHERE status='APPROVED' AND work_date BETWEEN :from AND :to
      ), leaves AS (
        SELECT user_id,work_date,CASE WHEN COUNT(*)=1 THEN MAX(leave_minutes) WHEN COUNT(start_minute)<COUNT(*) THEN NULL
          ELSE SUM(GREATEST(0,end_minute-GREATEST(start_minute,COALESCE(previous_end,start_minute)))) END leave_minutes,
          COUNT(*)>1 AND COUNT(start_minute)<COUNT(*) unknown_leave FROM leave_slots GROUP BY user_id,work_date
      ), people_base AS (
        SELECT p.user_id,p.dt,u.employee_no,u.name,COALESCE(dr.department_id,uh.department_id,u.department_id) department_id,
          COALESCE(lh.level_code,u.level_code) level_code,dr.id day_id,
          COALESCE(IF(c.is_override,c.base_minutes,NULL),IF(WEEKDAY(p.dt)<5,COALESCE(CAST(JSON_UNQUOTE(JSON_EXTRACT(cfg.config_json,'$.params.defaultDayMinutes')) AS UNSIGNED),480),0)) base_minutes,
          COALESCE(IF(c.is_override,c.is_workday,NULL),WEEKDAY(p.dt)<5) is_workday,
          COALESCE(l.leave_minutes,0) leave_minutes,COALESCE(l.unknown_leave,FALSE) unknown_leave,
          EXISTS(SELECT 1 FROM reporting_enrollment en WHERE en.user_id=p.user_id AND en.voided=FALSE AND en.valid_from<=p.dt AND (en.valid_to IS NULL OR en.valid_to>p.dt)) enrolled,
          f.first_ready_at,f.deadline_at,
          CONVERT_TZ(TIMESTAMP(DATE_ADD(p.dt,INTERVAL (6-WEEKDAY(p.dt)+COALESCE(CAST(JSON_UNQUOTE(JSON_EXTRACT(cfg.config_json,'$.params.submitWeekday')) AS UNSIGNED),1)) DAY),COALESCE(JSON_UNQUOTE(JSON_EXTRACT(cfg.config_json,'$.params.submitTime')),'12:00')),'+08:00','+00:00') submit_deadline
        FROM people_dates p JOIN app_user u ON u.id=p.user_id
        LEFT JOIN system_config_version cfg ON cfg.effective_from=(SELECT MAX(cv.effective_from) FROM system_config_version cv WHERE cv.effective_from<=p.dt)
        LEFT JOIN day_record dr ON dr.user_id=p.user_id AND dr.work_date=p.dt LEFT JOIN work_calendar c ON c.work_date=p.dt
        LEFT JOIN user_department_history uh ON uh.user_id=p.user_id AND uh.valid_from<=p.dt AND (uh.valid_to IS NULL OR uh.valid_to>p.dt)
        LEFT JOIN user_level_history lh ON lh.user_id=p.user_id AND lh.valid_from<=p.dt AND (lh.valid_to IS NULL OR lh.valid_to>p.dt)
        LEFT JOIN leaves l ON l.user_id=p.user_id AND l.work_date=p.dt
        LEFT JOIN daily_completion_fact f ON f.user_id=p.user_id AND f.work_date=p.dt
      ), people AS (
        SELECT pb.*,dep.name department_name,GREATEST(base_minutes-LEAST(base_minutes,leave_minutes),0) required_minutes FROM people_base pb JOIN department dep ON dep.id=pb.department_id
      ), facts AS (
        SELECT 'DAY' row_kind,p.user_id,p.dt work_date,CAST(NULL AS SIGNED) work_item_id,p.day_id record_id,CAST(NULL AS SIGNED) revision_id,
          JSON_OBJECT('userId',CAST(p.user_id AS CHAR),'date',CAST(p.dt AS CHAR),'employeeNo',p.employee_no,'name',p.name,
           'departmentId',CAST(p.department_id AS CHAR),'departmentName',p.department_name,'levelCode',p.level_code,
           'baseMinutes',p.base_minutes,'leaveMinutes',LEAST(p.base_minutes,p.leave_minutes),'requiredMinutes',p.required_minutes,
           'isWorkday',p.is_workday,'unknownLeave',p.unknown_leave,'enrolled',p.enrolled,'firstReadyAt',p.first_ready_at,'deadlineAt',p.deadline_at,'submitDeadline',p.submit_deadline) payload FROM people p
        UNION ALL
        SELECT 'TIME',p.user_id,p.dt,w.id,e.id,r.id,JSON_OBJECT(
          'kind','TIME','userId',CAST(p.user_id AS CHAR),'date',CAST(p.dt AS CHAR),'employeeNo',p.employee_no,'name',p.name,
          'departmentId',CAST(r.department_id AS CHAR),'departmentName',dep.name,'levelCode',COALESCE(r.cost_level_code,p.level_code),
          'workItemId',CAST(w.id AS CHAR),'workItemName',w.name,'code',w.code,'type',w.type,
          'ownerDepartmentId',CAST(COALESCE(r.owner_department_id,wh.owner_department_id,w.owner_department_id) AS CHAR),'ownerDepartmentName',od.name,
          'pmName',pm.name,'recordId',CAST(e.id AS CHAR),'revisionId',CAST(r.id AS CHAR),'action',r.action,'state',r.state,'workKind',r.kind,
          'minutes',r.minutes,'content',r.content,'redReason',r.red_reason,'disputeOpen',r.dispute_open,
          'approvedAt',r.approved_at,'approvedByName',au.name,'submittedAt',r.submitted_at,
          'costAmount',CAST(r.cost_amount AS CHAR),'dailyRate',CAST(r.cost_daily_rate AS CHAR),'rateId',CAST(r.cost_rate_id AS CHAR),'policyVersion',r.cost_policy_version)
        FROM people p JOIN time_entry e ON e.day_id=p.day_id JOIN time_entry_revision r ON r.id=e.current_revision_id
          JOIN work_item w ON w.id=r.work_item_id JOIN department dep ON dep.id=r.department_id
          LEFT JOIN work_item_history wh ON wh.work_item_id=w.id AND wh.valid_from<=p.dt AND (wh.valid_to IS NULL OR wh.valid_to>p.dt)
          JOIN department od ON od.id=COALESCE(r.owner_department_id,wh.owner_department_id,w.owner_department_id)
          LEFT JOIN app_user pm ON pm.id=w.default_approver_id LEFT JOIN app_user au ON au.id=r.approved_by
        UNION ALL
        SELECT 'ONSITE',p.user_id,p.dt,w.id,e.id,r.id,JSON_OBJECT(
          'kind','ONSITE','userId',CAST(p.user_id AS CHAR),'date',CAST(p.dt AS CHAR),'employeeNo',p.employee_no,'name',p.name,
          'departmentId',CAST(p.department_id AS CHAR),'departmentName',p.department_name,'levelCode',p.level_code,
          'workItemId',CAST(w.id AS CHAR),'workItemName',w.name,'code',w.code,'type',w.type,
          'ownerDepartmentId',CAST(COALESCE(r.owner_department_id,wh.owner_department_id,w.owner_department_id) AS CHAR),'ownerDepartmentName',od.name,'pmName',pm.name,
          'recordId',CAST(e.id AS CHAR),'revisionId',CAST(r.id AS CHAR),'action',r.action,'state',r.state,'reason',r.reason,'disputeOpen',r.dispute_open,
          'approvedAt',r.approved_at,'approvedByName',au.name,'submittedAt',r.submitted_at,
          'costAmount',CAST(r.cost_amount AS CHAR),'dailyRate',CAST(r.cost_daily_rate AS CHAR),'rateId',CAST(r.cost_rate_id AS CHAR),'policyVersion',r.cost_policy_version)
        FROM people p JOIN onsite_day e ON e.day_id=p.day_id JOIN onsite_day_revision r ON r.id=e.current_revision_id JOIN work_item w ON w.id=r.work_item_id
          LEFT JOIN work_item_history wh ON wh.work_item_id=w.id AND wh.valid_from<=p.dt AND (wh.valid_to IS NULL OR wh.valid_to>p.dt)
          JOIN department od ON od.id=COALESCE(r.owner_department_id,wh.owner_department_id,w.owner_department_id)
          LEFT JOIN app_user pm ON pm.id=w.default_approver_id LEFT JOIN app_user au ON au.id=r.approved_by
        UNION ALL
        SELECT 'APPROVAL',ap.user_id,COALESCE(td.work_date,sd.work_date),ap.work_item_id,ai.id,COALESCE(ai.time_revision_id,ai.onsite_revision_id),
          JSON_OBJECT('userId',CAST(ap.user_id AS CHAR),'name',p.name,'employeeNo',p.employee_no,'departmentId',CAST(p.department_id AS CHAR),'departmentName',p.department_name,'levelCode',p.level_code,'packageId',CAST(ap.id AS CHAR),'approvalItemId',CAST(ai.id AS CHAR),
            'workItemId',CAST(ap.work_item_id AS CHAR),'status',ai.status,'submittedAt',ai.submitted_at,'submitDeadline',ai.submit_deadline,
            'approvalDeadline',ai.approval_deadline,'handledAt',ai.handled_at,'approverId',CAST(COALESCE(ai.handled_by,ap.approver_user_id) AS CHAR),'approverName',approver.name,
            'disputeOpen',COALESCE(tr.dispute_open,sr.dispute_open,FALSE))
        FROM approval_item ai JOIN approval_package ap ON ap.id=ai.package_id JOIN app_user approver ON approver.id=COALESCE(ai.handled_by,ap.approver_user_id)
          LEFT JOIN time_entry_revision tr ON tr.id=ai.time_revision_id LEFT JOIN time_entry te ON te.id=tr.entry_id LEFT JOIN day_record td ON td.id=te.day_id
          LEFT JOIN onsite_day_revision sr ON sr.id=ai.onsite_revision_id LEFT JOIN onsite_day so ON so.id=sr.onsite_id LEFT JOIN day_record sd ON sd.id=so.day_id
        JOIN people p ON p.user_id=ap.user_id AND p.dt=COALESCE(td.work_date,sd.work_date)
        WHERE COALESCE(td.work_date,sd.work_date) BETWEEN :from AND :to
        UNION ALL
        SELECT 'TRIP',t.user_id,t.work_date,t.work_item_id,t.id,NULL,JSON_OBJECT(
          'userId',CAST(t.user_id AS CHAR),'date',CAST(t.work_date AS CHAR),'employeeNo',u.employee_no,'name',u.name,
          'departmentId',CAST(COALESCE(uh.department_id,u.department_id) AS CHAR),'departmentName',dep.name,
          'workItemId',CAST(t.work_item_id AS CHAR),'workItemName',w.name,'state',t.status,'sourceKey',t.source_key,'sourceVersion',t.source_version)
        FROM trip_day t JOIN app_user u ON u.id=t.user_id LEFT JOIN work_item w ON w.id=t.work_item_id
          LEFT JOIN user_department_history uh ON uh.user_id=u.id AND uh.valid_from<=t.work_date AND (uh.valid_to IS NULL OR uh.valid_to>t.work_date)
          JOIN department dep ON dep.id=COALESCE(uh.department_id,u.department_id)
        WHERE t.work_date BETWEEN :from AND :to
        UNION ALL
        SELECT 'COVERAGE',NULL,NULL,NULL,id,NULL,JSON_OBJECT('id',CAST(id AS CHAR),'dataset',dataset,'from',CAST(from_date AS CHAR),'to',CAST(to_date AS CHAR),
          'departmentId',CAST(department_id AS CHAR),'state',state,'verifiedAt',verified_at,'note',note)
        FROM source_coverage WHERE from_date<=:to AND to_date>=:from
        UNION ALL
        SELECT 'SOURCE_ERROR',u.id,NULL,NULL,s.id,NULL,JSON_OBJECT('dataset',s.dataset,'sourceKey',s.source_key,'sourceVersion',s.source_version,
          'employeeNo',JSON_UNQUOTE(JSON_EXTRACT(s.normalized_data,'$.employeeNo')),'date',JSON_UNQUOTE(JSON_EXTRACT(s.normalized_data,'$.date')),'error',s.error_message)
        FROM source_record s LEFT JOIN app_user u ON u.employee_no=JSON_UNQUOTE(JSON_EXTRACT(s.normalized_data,'$.employeeNo'))
        WHERE s.dataset IN ('LEAVE','TRIP') AND s.application_state='FAILED'
          AND JSON_UNQUOTE(JSON_EXTRACT(s.normalized_data,'$.date')) BETWEEN CAST(:from AS CHAR) AND CAST(:to AS CHAR)
          AND NOT EXISTS(SELECT 1 FROM source_record newer WHERE newer.dataset=s.dataset AND newer.source_key=s.source_key AND newer.id>s.id AND newer.application_state='APPLIED')
      )
      """;
}
