package com.allen.worklog.operations;

import com.allen.worklog.common.ApiException;
import com.allen.worklog.work.DayRules;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="WORKLOG_INTEGRATION",matches="true")
@SpringBootTest(properties="spring.profiles.active=local")
@Transactional
class OperationsDatabaseTest {
    @Autowired JobService jobs;@Autowired ConfigService configs;@Autowired NotificationService notices;@Autowired JdbcClient jdbc;
    @BeforeEach void actor() {
        assertEquals("local-admin",jdbc.sql("SELECT wecom_userid FROM app_user WHERE id=4").query(String.class).single());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("4",null,List.of()));
    }
    @AfterEach void clear() {SecurityContextHolder.clearContext();}
    @Test void durableKeysAndLeaseOwnershipSurviveRetry() {
        String key="LOCAL-JOB-TEST-"+UUID.randomUUID();var time=LocalDateTime.of(2000,1,1,0,0);
        long id=jobs.enqueue("REMINDER",key,Map.of("rule","DAILY_MISSING","date","2000-01-01"),4L,time);
        assertEquals(id,jobs.enqueue("REMINDER",key,Map.of("rule","DAILY_MISSING","date","2000-01-01"),4L,time));
        var first=jobs.claim();assertNotNull(first);assertEquals(id,first.id());
        jdbc.sql("UPDATE job SET lease_until=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?").param(id).update();
        var next=jobs.claim();assertEquals(id,next.id());assertNotEquals(first.token(),next.token());
        jobs.finish(first,Map.of("stale",true));assertEquals("RUNNING",status(id));
        jobs.finish(next,Map.of("ok",true));assertEquals("COMPLETED",status(id));
        assertThrows(ApiException.class,()->jobs.retry(id));
    }
    @Test void futureConfigurationIsVersionedAndCannotRewriteExistingDates() {
        var today=configs.today();var old=configs.current();var p=old.params();
        var modified=new ConfigService.Parameters(1440,900,450,15,p.submitWeekday(),p.submitTime(),p.approveWeekday(),p.approveTime(),p.closeDay(),p.dailyCompletionTime());
        LocalDate start=today.plusDays(40);String expected=configs.latestVersion();
        var update=new ConfigService.Update(expected,start,modified,old.rules(),"本地配置事务回滚验证");
        assertEquals(start,configs.preview(update).get("effectiveFrom"));var saved=configs.save(update);
        assertEquals(old.params(),configs.forDate(today).params());assertEquals(450,configs.forDate(start).params().defaultDayMinutes());
        assertEquals(15,DayRules.minutes("0.25",saved.params().stepMinutes(),saved.params().dayLimitMinutes()));
        assertThrows(ApiException.class,()->configs.save(new ConfigService.Update(expected,start.plusDays(1),modified,old.rules(),"旧版本不能覆盖")));
        assertThrows(ApiException.class,()->configs.preview(new ConfigService.Update(saved.id(),today,modified,old.rules(),"不能改过去")));
    }
    @Test void outboxAndSystemProvenanceRemainInBusinessTransaction() {
        String key="LOCAL-OUTBOX-TEST-"+UUID.randomUUID();
        notices.enqueue(1L,null,"DECISION",key,"仅本地测试通知","/h5/time");
        notices.enqueue(1L,null,"DECISION",key,"仅本地测试通知","/h5/time");
        assertEquals(1,jdbc.sql("SELECT COUNT(*) FROM notification_outbox WHERE business_key=?").param(key).query(Integer.class).single());
        var before=SecurityContextHolder.getContext().getAuthentication();
        assertThrows(IllegalStateException.class,()->SystemExecution.runAs(1L,123L,()->{assertEquals(123L,SystemExecution.jobId());throw new IllegalStateException("restore context");}));
        assertNull(SystemExecution.jobId());assertEquals(before,SecurityContextHolder.getContext().getAuthentication());
    }
    @Test void failedJobsBackOffAndOnlyFailedWorkCanBeRequeued() {
        long id=jobs.enqueue("REMINDER","LOCAL-RETRY-"+UUID.randomUUID(),Map.of("rule","DAILY_MISSING","date","1990-01-01"),4L,LocalDateTime.of(1990,1,1,0,0));
        var claim=jobs.claim();assertEquals(id,claim.id());
        jobs.fail(claim,new ApiException(409,"TEST_FAILURE","本地测试失败原因"));
        assertEquals("RETRY",status(id));
        assertTrue(jdbc.sql("SELECT next_run_at>UTC_TIMESTAMP(6) FROM job WHERE id=?").param(id).query(Boolean.class).single());
        jobs.retry(id);assertEquals("PENDING",status(id));
        jdbc.sql("UPDATE job SET next_run_at='1990-01-01' WHERE id=?").param(id).update();
        var second=jobs.claim();assertEquals(id,second.id());
        jdbc.sql("UPDATE job SET attempts=5 WHERE id=?").param(id).update();
        jobs.fail(second,new IllegalStateException("not exposed"));assertEquals("FAILED",status(id));
        jobs.finish(claim,Map.of("stale",true));assertEquals("FAILED",status(id));
        jobs.retry(id);assertEquals("PENDING",status(id));
        assertEquals(0,jdbc.sql("SELECT attempts FROM job WHERE id=?").param(id).query(Integer.class).single());
    }
    @Test void configurationRejectsNonExecutableRuleCombinations() {
        var original=configs.current();var list=new ArrayList<>(original.rules());
        var rule=list.getFirst();
        list.set(0,new ConfigService.Rule(rule.code(),rule.label(),true,rule.time(),"EVENT",1,1,1,"SELF",rule.template()));
        assertThrows(ApiException.class,()->configs.preview(new ConfigService.Update(configs.latestVersion(),configs.today().plusDays(100),original.params(),list,"无事件来源的普通提醒不可配置为事件")));
        list.set(0,new ConfigService.Rule(rule.code(),rule.label(),true,rule.time(),"DAILY",1,1,1,"APPROVER",rule.template()));
        assertThrows(ApiException.class,()->configs.preview(new ConfigService.Update(configs.latestVersion(),configs.today().plusDays(100),original.params(),list,"未填记录还没有审批人")));
    }
    private String status(long id) {return jdbc.sql("SELECT status FROM job WHERE id=?").param(id).query(String.class).single();}
}
