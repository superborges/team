package com.allen.worklog.operations;

import com.allen.worklog.common.ApiException;
import com.allen.worklog.identity.CurrentUser;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class NotificationService {
    private final JdbcClient jdbc;private final CurrentUser current;private final Environment environment;private final ConfigService configs;
    public NotificationService(JdbcClient jdbc,CurrentUser current,Environment environment,ConfigService configs) {this.jdbc=jdbc;this.current=current;this.environment=environment;this.configs=configs;}
    public void enqueue(long recipientId,Long packageId,String eventType,String key,String body,String path) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Outbox requires business transaction");
        if(body==null||body.length()>2000||path==null||!path.startsWith("/"))throw new IllegalArgumentException("Invalid notification");
        ConfigService.Rule rule=configs.current().rules().stream().filter(r->r.code().equals(eventType)&&!key.startsWith(eventType+":")).findFirst().orElse(null);
        if(rule!=null&&!rule.enabled())return;
        java.time.LocalDate date=configs.today();
        java.time.LocalDate week=date.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        if(packageId!=null)week=jdbc.sql("SELECT week_start FROM approval_package WHERE id=?").param(packageId).query(java.time.LocalDate.class).single();
        if(rule!=null) {
            String name=jdbc.sql("SELECT name FROM app_user WHERE id=?").param(recipientId).query(String.class).single();
            body=rule.template().replace("{name}",name).replace("{date}",date.toString()).replace("{week}",week.toString()).replace("{count}","1");
        }
        java.time.LocalDateTime due=jdbc.sql("SELECT UTC_TIMESTAMP(6)").query(java.time.LocalDateTime.class).single();
        if(eventType.equals("APPROVAL_PENDING")&&rule!=null) {
            java.time.LocalDate first=week.plusWeeks(1).isAfter(date)?week.plusWeeks(1):date;
            for(int i=0;i<35;i++) {
                java.time.LocalDate day=first.plusDays(i);
                boolean applies=switch(rule.dayType()) {
                    case "DAILY" -> true;
                    case "WORKDAY" -> jdbc.sql("SELECT is_workday FROM work_calendar WHERE work_date=? AND is_override=TRUE").param(day).query(Boolean.class).optional().orElse(day.getDayOfWeek().getValue()<=5);
                    case "WEEKLY" -> day.getDayOfWeek().getValue()==rule.dayOfWeek();
                    case "MONTHLY" -> day.getDayOfMonth()==rule.dayOfMonth();
                    default -> false;
                };
                if(applies){due=day.atTime(java.time.LocalTime.parse(rule.time())).atZone(java.time.ZoneId.of("Asia/Shanghai")).withZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime();break;}
            }
        }
        if(rule!=null&&rule.recipients().equals("ADMIN")) {
            for(long admin:jdbc.sql("SELECT DISTINCT u.id FROM app_user u JOIN role_grant r ON r.user_id=u.id AND r.role_code='ADMIN' AND r.revoked_at IS NULL AND r.valid_from<=? AND (r.valid_to IS NULL OR r.valid_to>?) WHERE u.status='ACTIVE'").params(date,date).query(Long.class).list())
                insert(admin,null,eventType,key+":"+admin,body,eventType.startsWith("APPROVAL_")?"/admin/approvals":"/admin/reports/workload",due);
        } else {
            insert(recipientId,packageId,eventType,key,body,path,due);
            if(rule!=null&&Set.of("SELF_MANAGER","APPROVER_SUPERVISOR").contains(rule.recipients()))for(long manager:jdbc.sql("SELECT DISTINCT m.id FROM app_user u JOIN department d ON d.id=u.department_id JOIN app_user m ON m.id=COALESCE(NULLIF(d.designated_manager_id,u.id),d.supervisor_user_id) AND m.status='ACTIVE' WHERE u.id=? AND m.id<>?").params(recipientId,recipientId).query(Long.class).list())
                insert(manager,null,eventType,key+":"+manager,body,"/admin/reports/workload",due);
        }
    }
    private void insert(long recipient,Long pack,String event,String key,String body,String path,java.time.LocalDateTime due) {
        jdbc.sql("INSERT IGNORE INTO notification_outbox(recipient_id,package_id,event_type,business_key,body,target_path,next_run_at) VALUES(?,?,?,?,?,?,?)").params(recipient,pack,event,key,body,path,due).update();
    }

    /** Local delivery is explicit. No external message is sent without real integration configuration. */
    @Transactional public boolean deliverNext() {
        var row=jdbc.sql("SELECT id,recipient_id,package_id,event_type FROM notification_outbox WHERE status='PENDING' AND next_run_at<=UTC_TIMESTAMP(6) ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED")
            .query((rs,n)->new Pending(rs.getLong(1),rs.getLong(2),rs.getObject(3,Long.class),rs.getString(4))).optional();
        if(row.isEmpty())return false;var item=row.get();
        boolean active=configs.current().rules().stream().noneMatch(r->r.code().equals(item.event())&&!r.enabled());
        active &= jdbc.sql("SELECT COUNT(*) FROM app_user WHERE id=? AND status='ACTIVE'").param(item.recipient()).query(Integer.class).single()>0;
        if(item.packageId()!=null && !Set.of("REJECTED","DECISION","CORRECTION","DISPUTE").contains(item.event())) {
            // A transferred package invalidates already queued messages to its old recipient.
            active &= jdbc.sql("SELECT COUNT(*) FROM approval_package p WHERE p.id=? AND p.approver_user_id=? AND EXISTS(SELECT 1 FROM approval_item i WHERE i.package_id=p.id AND i.status='PENDING')")
                .params(item.packageId(),item.recipient()).query(Integer.class).single()>0;
        }
        boolean local=Arrays.asList(environment.getActiveProfiles()).contains("local");
        String state=!active?"SUPERSEDED":local?"LOCAL":"FAILED";
        jdbc.sql("UPDATE notification_outbox SET status=?,attempts=attempts+1,delivered_at=IF(?='LOCAL',UTC_TIMESTAMP(6),NULL),error_message=? WHERE id=?")
            .params(state,state,state.equals("FAILED")?"真实企微应用消息尚未配置；未发送外部消息":null,item.id()).update();return true;
    }
    public List<Map<String,Object>> mine() {long user=current.require().id();return jdbc.sql("SELECT CAST(id AS CHAR) id,event_type eventType,body,target_path path,status,created_at createdAt FROM notification_outbox WHERE recipient_id=? AND status<>'SUPERSEDED' ORDER BY notification_outbox.id DESC LIMIT 100").param(user).query().listOfRows();}
    @Transactional public void automaticFailure(long user,long jobId,java.time.LocalDate week,int count,String detail) {
        var rule=configs.current().rules().stream().filter(r->r.code().equals("AUTO_SUBMIT")).findFirst().orElseThrow();
        if(!rule.enabled())return;
        var date=configs.today();String name=jdbc.sql("SELECT name FROM app_user WHERE id=?").param(user).query(String.class).single();
        String body=rule.template().replace("{name}",name).replace("{date}",date.toString()).replace("{week}",week.toString()).replace("{count}",Integer.toString(count))+"\n"+detail;
        if(body.length()>1900)body=body.substring(0,1900);
        var due=jdbc.sql("SELECT UTC_TIMESTAMP(6)").query(java.time.LocalDateTime.class).single();
        var recipients=new LinkedHashSet<Long>();
        if(rule.recipients().equals("ADMIN"))recipients.addAll(jdbc.sql("SELECT DISTINCT u.id FROM app_user u JOIN role_grant r ON r.user_id=u.id AND r.role_code='ADMIN' AND r.revoked_at IS NULL AND r.valid_from<=? AND (r.valid_to IS NULL OR r.valid_to>?) WHERE u.status='ACTIVE'").params(date,date).query(Long.class).list());
        else {
            recipients.add(user);
            if(rule.recipients().equals("SELF_MANAGER"))recipients.addAll(jdbc.sql("SELECT DISTINCT m.id FROM app_user u JOIN department d ON d.id=u.department_id JOIN app_user m ON m.id=COALESCE(NULLIF(d.designated_manager_id,u.id),d.supervisor_user_id) AND m.status='ACTIVE' WHERE u.id=? AND m.id<>?").params(user,user).query(Long.class).list());
        }
        for(long recipient:recipients)insert(recipient,null,"AUTO_SUBMIT_FAILED","AUTO_SUBMIT_FAILED:"+jobId+":"+recipient,body,recipient==user?"/h5/work":"/admin/reports/workload",due);
    }
    public List<Map<String,Object>> all() {current.requireAdmin();return jdbc.sql("SELECT CAST(n.id AS CHAR) id,u.name recipient,n.event_type eventType,n.body,n.status,n.error_message error,n.created_at createdAt FROM notification_outbox n JOIN app_user u ON u.id=n.recipient_id ORDER BY n.id DESC LIMIT 100").query().listOfRows();}
    @Transactional public void retry(long id) {current.requireAdmin();if(jdbc.sql("UPDATE notification_outbox SET status='PENDING',next_run_at=UTC_TIMESTAMP(6) WHERE id=? AND status='FAILED'").param(id).update()==0)throw new ApiException(409,"NOT_RETRYABLE","仅失败通知可以重试");}
    private record Pending(long id,long recipient,Long packageId,String event) {}
}
