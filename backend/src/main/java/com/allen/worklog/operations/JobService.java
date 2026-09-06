package com.allen.worklog.operations;

import com.allen.worklog.common.ApiException;
import com.allen.worklog.common.AuditService;
import com.allen.worklog.identity.CurrentUser;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class JobService {
    public record Claimed(long id,String type,Map<String,Object> payload,Long actorId,String token) {}
    private final JdbcClient jdbc;private final ObjectMapper json;private final CurrentUser current;private final AuditService audit;
    public JobService(JdbcClient jdbc,ObjectMapper json,CurrentUser current,AuditService audit) {this.jdbc=jdbc;this.json=json;this.current=current;this.audit=audit;}
    public long enqueue(String type,String key,Map<String,Object> payload,Long actorId,LocalDateTime runAtUtc) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Enqueue must share business transaction");
        if(!Set.of("EXPORT","MONTH_CLOSE","AUTO_SUBMIT","REMINDER").contains(type)||key.length()>180)throw new IllegalArgumentException("Unknown job");
        jdbc.sql("INSERT IGNORE INTO job(type,business_key,payload,actor_id,planned_at,next_run_at) VALUES(?,?,?,?,?,?)")
            .params(type,key,json.writeValueAsString(payload),actorId,runAtUtc,runAtUtc).update();
        return jdbc.sql("SELECT id FROM job WHERE business_key=?").param(key).query(Long.class).single();
    }
    @Transactional public Claimed claim() {
        var found=jdbc.sql("""
            SELECT * FROM job WHERE (status IN ('PENDING','RETRY') AND next_run_at<=UTC_TIMESTAMP(6))
              OR (status='RUNNING' AND lease_until<UTC_TIMESTAMP(6))
            ORDER BY next_run_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
            """).query((rs,n)->new Claimed(rs.getLong("id"),rs.getString("type"),json.readValue(rs.getString("payload"),new TypeReference<Map<String,Object>>(){}),rs.getObject("actor_id",Long.class),UUID.randomUUID().toString())).optional();
        if(found.isEmpty())return null;var job=found.get();
        jdbc.sql("UPDATE job SET status='RUNNING',attempts=attempts+1,lease_token=?,claimed_at=UTC_TIMESTAMP(6),lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 5 MINUTE) WHERE id=?")
            .params(job.token(),job.id()).update();return job;
    }
    @Transactional public void finish(Claimed job,Object result) {
        jdbc.sql("UPDATE job SET status='COMPLETED',result_json=?,completed_at=UTC_TIMESTAMP(6),lease_token=NULL,lease_until=NULL,error_message=NULL WHERE id=? AND lease_token=?")
            .params(json.writeValueAsString(result),job.id(),job.token()).update();
    }
    @Transactional public void fail(Claimed job,Exception error) {
        String message=error instanceof ApiException?error.getMessage():"任务执行失败，请检查服务日志并重试";
        jdbc.sql("UPDATE job SET status=IF(attempts>=5,'FAILED','RETRY'),next_run_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL LEAST(3600,POW(2,attempts)*30) SECOND),error_message=?,lease_token=NULL,lease_until=NULL WHERE id=? AND lease_token=?")
            .params(message,job.id(),job.token()).update();
    }
    public List<Map<String,Object>> list() {current.requireAdmin();return jdbc.sql("SELECT CAST(id AS CHAR) id,type,business_key businessKey,status,planned_at plannedAt,next_run_at nextRunAt,attempts,error_message error,completed_at completedAt FROM job ORDER BY job.id DESC LIMIT 100").query().listOfRows();}
    @Transactional public void retry(long id) {
        var actor=current.requireAdmin();var state=jdbc.sql("SELECT status FROM job WHERE id=? FOR UPDATE").param(id).query(String.class).optional().orElseThrow(()->new ApiException(404,"JOB_NOT_FOUND","任务不存在"));
        if(!Set.of("FAILED","RETRY").contains(state))throw new ApiException(409,"JOB_NOT_RETRYABLE","仅失败或待重试任务可以重试");
        jdbc.sql("UPDATE job SET status='PENDING',attempts=0,next_run_at=UTC_TIMESTAMP(6),error_message=NULL WHERE id=?").param(id).update();
        audit.record(actor.id(),"JOB_RETRY","JOB",Long.toString(id),null,null,"管理员重试失败任务");
    }
    public Map<String,Object> health() {
        current.requireAdmin();var result=new LinkedHashMap<String,Object>();
        result.put("worker",jdbc.sql("SELECT worker_key workerKey,heartbeat_at heartbeatAt,last_scan_date lastScanDate FROM worker_state ORDER BY worker_key").query().listOfRows());
        result.put("pendingJobs",jdbc.sql("SELECT COUNT(*) FROM job WHERE status IN ('PENDING','RETRY','RUNNING')").query(Integer.class).single());
        result.put("failedJobs",jdbc.sql("SELECT COUNT(*) FROM job WHERE status='FAILED'").query(Integer.class).single());
        result.put("pendingNotifications",jdbc.sql("SELECT COUNT(*) FROM notification_outbox WHERE status IN ('PENDING','FAILED')").query(Integer.class).single());
        return result;
    }
}
