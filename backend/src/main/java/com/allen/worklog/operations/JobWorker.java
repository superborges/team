package com.allen.worklog.operations;

import com.allen.worklog.approval.ApprovalService;
import com.allen.worklog.closing.MonthClosingService;
import com.allen.worklog.reporting.ExportService;
import com.allen.worklog.common.ApiException;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("worker")
@EnableScheduling
public class JobWorker {
    private static final Logger LOG=LoggerFactory.getLogger(JobWorker.class);
    private final JobService jobs;private final JobScheduler scheduler;private final ReminderService reminders;
    private final NotificationService notices;private final ApprovalService approvals;private final ExportService exports;private final MonthClosingService closing;private final JdbcClient jdbc;
    private final AtomicReference<JobService.Claimed> active=new AtomicReference<>();
    private long nextCleanup;
    private long nextScan;
    public JobWorker(JobService jobs,JobScheduler scheduler,ReminderService reminders,NotificationService notices,
        ApprovalService approvals,ExportService exports,MonthClosingService closing,JdbcClient jdbc) {
        this.jobs=jobs;this.scheduler=scheduler;this.reminders=reminders;this.notices=notices;this.approvals=approvals;this.exports=exports;this.closing=closing;this.jdbc=jdbc;
    }
    @Scheduled(fixedDelay=1000,initialDelay=3000) public void tick() {
        try {
            if(System.nanoTime()>=nextScan) {scheduler.scan();nextScan=System.nanoTime()+java.time.Duration.ofMinutes(1).toNanos();}
            for(int i=0;i<4;i++) {
                var job=jobs.claim();if(job==null)break;active.set(job);
                try {jobs.finish(job,execute(job));}
                catch(Exception e) {LOG.warn("Job {} ({}) failed",job.id(),job.type(),e);jobs.fail(job,e);}
                finally {active.set(null);}
            }
            for(int i=0;i<30&&notices.deliverNext();i++) { /* bounded queue drain */ }
            if(System.nanoTime()>=nextCleanup) {exports.cleanupExpired();nextCleanup=System.nanoTime()+java.time.Duration.ofHours(1).toNanos();}
        } catch(Exception e) {LOG.warn("Worker tick failed; persistent work will retry",e);}
    }
    public Object execute(JobService.Claimed job) {
        var p=job.payload();
        return switch(job.type()) {
            case "AUTO_SUBMIT" -> {
                long user=Long.parseLong(p.get("userId").toString());var result=approvals.submitSystem(user,LocalDate.parse(p.get("weekStart").toString()),job.id());
                if(!result.failed().isEmpty())notices.automaticFailure(user,job.id(),LocalDate.parse(p.get("weekStart").toString()),result.failed().size(),"上周自动送审部分未通过，请核对："+result.failed().stream().map(f->f.date()+" "+f.message()).collect(java.util.stream.Collectors.joining("；")));
                yield result;
            }
            case "EXPORT" -> {exports.generate(Long.parseLong(p.get("exportId").toString()));yield Map.of("generated",true);}
            case "MONTH_CLOSE" -> SystemExecution.runAs(admin(),job.id(),()->{closing.closeScheduled(LocalDate.parse(p.get("month").toString()));return Map.of("closed",true);});
            case "REMINDER" -> reminders.perform(p.get("rule").toString(),LocalDate.parse(p.get("date").toString()),job.id());
            default -> throw new IllegalArgumentException("Unsupported persistent job type");
        };
    }
    @Scheduled(fixedDelay=30000,initialDelay=1000) public void heartbeat() {
        try {
            jdbc.sql("INSERT INTO worker_state(worker_key,heartbeat_at) VALUES('worker',UTC_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE heartbeat_at=UTC_TIMESTAMP(6)").update();
            var job=active.get();if(job!=null)jdbc.sql("UPDATE job SET lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 5 MINUTE) WHERE id=? AND lease_token=? AND status='RUNNING'").params(job.id(),job.token()).update();
        }catch(Exception e){LOG.warn("Worker heartbeat failed",e);}
    }
    private long admin() {return jdbc.sql("""
        SELECT u.id FROM app_user u JOIN role_grant r ON r.user_id=u.id AND r.role_code='ADMIN' AND r.revoked_at IS NULL
        WHERE u.status='ACTIVE' AND r.valid_from<=DATE(CONVERT_TZ(UTC_TIMESTAMP(),'+00:00','+08:00'))
         AND (r.valid_to IS NULL OR r.valid_to>DATE(CONVERT_TZ(UTC_TIMESTAMP(),'+00:00','+08:00'))) ORDER BY u.id LIMIT 1
        """).query(Long.class).optional().orElseThrow(()->new ApiException(409,"NO_SYSTEM_CLOSING_CUSTODIAN","没有有效管理员承担封账任务，请恢复管理配置后重试"));}
}
