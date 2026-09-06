package com.allen.worklog.reporting;

import com.allen.worklog.closing.*;
import com.allen.worklog.common.ApiException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import static com.allen.worklog.reporting.ReportModels.*;
import static com.allen.worklog.closing.MonthClosingService.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="WORKLOG_INTEGRATION",matches="true")
@SpringBootTest(properties={"spring.profiles.active=local","spring.flyway.out-of-order=true"})
class ReportingDatabaseTest {
    @Autowired JdbcClient jdbc;@Autowired ReportService reports;@Autowired ReportFacts facts;@Autowired ReportingAccess access;
    @Autowired MonthClosingService closing;@Autowired ExportService exports;@Autowired PeriodGate gate;@Autowired TransactionTemplate tx;
    record Fixture(LocalDate month,LocalDate date,long user,long project,long day,long entry,long revision,long onsite,String marker) {}
    @AfterEach void clear() {SecurityContextHolder.clearContext();}
    static void as(long id) {SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(Long.toString(id),null,List.of()));}
    long id() {return jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();}
    Fixture fixture() {
        assertEquals("local-admin",jdbc.sql("SELECT wecom_userid FROM app_user WHERE id=4").query(String.class).single());as(4);
        return tx.execute(status->{
            LocalDate month=LocalDate.of(1901,1,1);while(jdbc.sql("SELECT COUNT(*) FROM accounting_period WHERE month_start=?").param(month).query(Integer.class).single()>0)month=month.plusMonths(1);
            jdbc.sql("INSERT INTO accounting_period(month_start,scheduled_close_at) VALUES(?,?)").params(month,LocalDateTime.now(ZoneOffset.UTC).plusDays(1)).update();
            LocalDate date=month.withDayOfMonth(3);while(date.getDayOfWeek().getValue()>5)date=date.plusDays(1);
            String marker="REPORT-IT-"+UUID.randomUUID().toString().substring(0,8);int suffix=Math.abs((int)(System.nanoTime()%100000));String employee=String.format("RP%05d",suffix);
            while(jdbc.sql("SELECT COUNT(*) FROM app_user WHERE employee_no=?").param(employee).query(Integer.class).single()>0)employee=String.format("RP%05d",(++suffix)%100000);
            jdbc.sql("INSERT INTO app_user(employee_no,wecom_userid,name,department_id,level_code) VALUES(?,?,?,1,'MIDDLE')").params(employee,marker,marker+" 本地报表验证").update();long user=id();
            jdbc.sql("INSERT INTO role_grant(user_id,role_code,scope_type,valid_from) VALUES(?,'EMPLOYEE','COMPANY','1900-01-01')").param(user).update();
            jdbc.sql("INSERT INTO reporting_enrollment(user_id,valid_from,valid_to) VALUES(?,?,?)").params(user,date,date.plusDays(1)).update();
            jdbc.sql("INSERT INTO work_item(code,name,type,owner_department_id,default_approver_id) VALUES(?,?,'PROJECT',1,2)").params(marker,marker+" 项目").update();long project=id();
            jdbc.sql("INSERT INTO day_record(user_id,work_date,base_minutes,leave_minutes,required_minutes,is_workday,department_id) VALUES(?,?,480,0,480,TRUE,1)").params(user,date).update();long day=id();
            jdbc.sql("INSERT INTO time_entry(day_id) VALUES(?)").param(day).update();long entry=id();
            jdbc.sql("INSERT INTO time_entry_revision(entry_id,revision_no,action,work_item_id,kind,minutes,content,department_id,state,approved_at,approved_by,cost_amount,cost_daily_rate,cost_policy_version,cost_level_code,owner_department_id) VALUES(?,1,'REPORT',?,'WORK',600,?,1,'APPROVED',UTC_TIMESTAMP(6),2,1250.00,1000.0000,'STANDARD_DAILY_480_HALF_UP_V1','MIDDLE',1)").params(entry,project,marker).update();long revision=id();
            jdbc.sql("UPDATE time_entry SET current_revision_id=? WHERE id=?").params(revision,entry).update();
            jdbc.sql("INSERT INTO onsite_day(day_id) VALUES(?)").param(day).update();long onsite=id();
            jdbc.sql("INSERT INTO onsite_day_revision(onsite_id,revision_no,action,work_item_id,reason,state,approved_at,approved_by,cost_amount,cost_daily_rate,cost_policy_version,owner_department_id) VALUES(?,1,'REPORT',?,?,'APPROVED',UTC_TIMESTAMP(6),2,300.00,300.0000,'STANDARD_DAILY_480_HALF_UP_V1',1)").params(onsite,project,marker).update();long onsiteRev=id();
            jdbc.sql("UPDATE onsite_day SET current_revision_id=? WHERE id=?").params(onsiteRev,onsite).update();
            jdbc.sql("INSERT INTO daily_completion_fact(user_id,work_date,first_ready_at,deadline_at,config_version_id) VALUES(?,?,?,?, '0')").params(user,date,date.atTime(10,0),date.plusDays(1).atTime(4,0)).update();
            return new Fixture(month,date,user,project,day,entry,revision,onsite,marker);
        });
    }
    Version close(Fixture f) {
        return tx.execute(status->{
            due(f);
            return closing.close(f.month());
        });
    }
    void due(Fixture f) {jdbc.sql("UPDATE accounting_period SET scheduled_close_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE month_start=?").param(f.month()).update();}
    Map<String,Object> createExport(Query query) {
        return tx.execute(status->{
            var request=exports.create(query);
            // Keep manual generator tests isolated from a simultaneously running real worker until commit.
            jdbc.sql("UPDATE job SET next_run_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 DAY) WHERE business_key=?")
                .param("export:"+request.get("id")).update();
            return request;
        });
    }
    Query q(Fixture f,String kind,String mode) {return new Query(kind,f.month(),f.month().plusMonths(1).minusDays(1),mode,List.of(),null,null,null,null,"DAY");}
    @Test void fiveReportsUseConfirmedCostsCurrentScopesAndExplicitOaCoverage() {
        Fixture f=fixture();as(4);
        var cost=reports.read(q(f,"project-costs","CURRENT"));assertEquals("1550.00",cost.summary().get("totalCost"));assertEquals(1,cost.rows().getFirst().get("participants"));
        var work=reports.read(q(f,"workload","CURRENT"));var row=work.rows().stream().filter(r->r.get("userId").equals(Long.toString(f.user()))).findFirst().orElseThrow();assertEquals(120,row.get("overtimeMinutes"));assertEquals("125.00",row.get("loadPercent"));
        assertEquals("100.00",reports.read(q(f,"compliance","CURRENT")).summary().get("timelyRatePercent"));
        var onsite=reports.read(q(f,"onsite","CURRENT")).rows().getFirst();assertEquals("100.00",onsite.get("ratioPercent"));assertEquals("NOT_COMPARED",onsite.get("oaStatus"));
        tx.executeWithoutResult(s->jdbc.sql("INSERT INTO source_coverage(dataset,from_date,to_date,state,verified_by,note) VALUES('TRIP',?,?,'COMPLETE',4,?)").params(f.date(),f.date(),f.marker()).update());
        assertEquals("OA_NO_RECORD",reports.read(q(f,"onsite","CURRENT")).rows().getFirst().get("oaStatus"));
        as(f.user());assertThrows(ApiException.class,()->reports.read(q(f,"project-costs","CURRENT")));assertFalse(reports.read(q(f,"details","CURRENT")).rows().getFirst().containsKey("costAmount"));
        as(2);assertTrue(access.current().projects().contains(f.project()));assertEquals("1550.00",reports.read(q(f,"project-costs","CURRENT")).summary().get("totalCost"));assertTrue(reports.read(q(f,"workload","CURRENT")).rows().isEmpty());
        tx.executeWithoutResult(s->jdbc.sql("UPDATE work_item SET owner_department_id=2 WHERE id=?").param(f.project()).update());
        as(3);assertEquals("0.00",reports.read(q(f,"project-costs","CURRENT")).summary().get("totalCost"));assertFalse(reports.read(q(f,"details","CURRENT")).rows().getFirst().containsKey("costAmount"));
        as(2);assertEquals("1550.00",reports.read(q(f,"project-costs","CURRENT")).summary().get("totalCost"));
        // A new unresolved head removes the old confirmed amount immediately, preserving its historical version.
        tx.executeWithoutResult(s->{jdbc.sql("INSERT INTO time_entry_revision(entry_id,revision_no,action,work_item_id,kind,minutes,content,department_id,state) VALUES(?,2,'REPORT',?,'WORK',480,?,1,'PENDING')").params(f.entry(),f.project(),f.marker()+" 待重审").update();long next=id();jdbc.sql("UPDATE time_entry SET current_revision_id=? WHERE id=?").params(next,f.entry()).update();});
        as(4);assertEquals("300.00",reports.read(q(f,"project-costs","CURRENT")).summary().get("totalCost"));assertEquals("INCOMPLETE",reports.read(q(f,"workload","CURRENT")).rows().getFirst().get("status"));assertNull(reports.read(q(f,"onsite","CURRENT")).rows().getFirst().get("ratioPercent"));
        tx.executeWithoutResult(s->{
            LocalDate week=f.date().with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            jdbc.sql("INSERT INTO approval_package(user_id,week_start,work_item_id,approver_user_id,default_approver_id,route_reason,submit_deadline,approval_deadline,config_version_id) VALUES(?,?,?,2,2,'本地报表轮次验证',?,?, '0')").params(f.user(),week,f.project(),f.date().plusDays(1).atStartOfDay(),f.date().plusDays(3).atStartOfDay()).update();long pack=id();
            long tr=jdbc.sql("SELECT current_revision_id FROM time_entry WHERE id=?").param(f.entry()).query(Long.class).single();long sr=jdbc.sql("SELECT current_revision_id FROM onsite_day WHERE id=?").param(f.onsite()).query(Long.class).single();
            jdbc.sql("INSERT INTO approval_item(package_id,time_revision_id,status,submitted_at,submit_deadline,approval_deadline) VALUES(?,?,'PENDING',?,?,?)").params(pack,tr,f.date().plusDays(2).atStartOfDay(),f.date().plusDays(1).atStartOfDay(),f.date().plusDays(3).atStartOfDay()).update();
            jdbc.sql("INSERT INTO approval_item(package_id,onsite_revision_id,status,submitted_at,submit_deadline,approval_deadline,handled_at,handled_by) VALUES(?,?,'APPROVED',?,?,?,?,2)").params(pack,sr,f.date().atStartOfDay(),f.date().plusDays(1).atStartOfDay(),f.date().plusDays(3).atStartOfDay(),f.date().plusDays(2).atStartOfDay()).update();
        });
        var filtered=new Query("compliance",f.month(),f.month().plusMonths(1).minusDays(1),"CURRENT",List.of(),Long.toString(f.user()),null,"1","MIDDLE","DAY");
        var compliance=reports.read(filtered).summary();assertEquals(2,compliance.get("approvalSubmitted"));assertEquals(1,compliance.get("approvalHandled"));assertEquals(1,compliance.get("lateSubmitPeople"));assertEquals(1,compliance.get("overduePackages"));assertEquals("50.00",compliance.get("approvalCompletionPercent"));
        tx.executeWithoutResult(status->jdbc.sql("UPDATE approval_package SET approver_user_id=3 WHERE user_id=? AND week_start=?")
            .params(f.user(),f.date().with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))).update());
        var version=close(f);
        var approvalFacts=facts.frozen(Long.parseLong(version.id())).stream().filter(fact->fact.kind().equals("APPROVAL")).toList();
        var handled=approvalFacts.stream().filter(fact->"APPROVED".equals(fact.data().get("status"))).findFirst().orElseThrow();
        assertEquals("2",handled.data().get("approverId"));
        assertEquals(jdbc.sql("SELECT name FROM app_user WHERE id=2").query(String.class).single(),handled.data().get("approverName"));
        assertEquals("3",approvalFacts.stream().filter(fact->"PENDING".equals(fact.data().get("status"))).findFirst().orElseThrow().data().get("approverId"));


    }
    @Test void wholeMonthFreezeIsImmutableIdempotentAndScopesExpireOnRepublish() {
        Fixture f=fixture();as(4);var v1=close(f);assertEquals(v1.id(),close(f).id());assertEquals("LOCKED",jdbc.sql("SELECT state FROM time_entry_revision WHERE id=?").param(f.revision()).query(String.class).single());
        assertThrows(ApiException.class,()->tx.executeWithoutResult(s->gate.lockForWrite(f.date())));
        var batch=closing.open(f.month(),new AmendmentInput("本地修订验证",null));long batchId=Long.parseLong(batch.get("id").toString());assertEquals(batch.get("id"),closing.open(f.month(),new AmendmentInput("同月重试",null)).get("id"));
        closing.grant(f.month(),batchId,new ScopeInput(Long.toString(f.user()),f.date(),Long.toString(f.project()),"TIME",Long.toString(f.entry()),List.of("SAVE","APPROVE"),"本地精确授权"));
        tx.executeWithoutResult(s->{gate.lockForAction(f.date(),f.user(),f.project(),"TIME",f.entry(),"SAVE");assertThrows(ApiException.class,()->gate.lockForAction(f.date(),f.user(),f.project(),"TIME",f.entry(),"ADD"));
            jdbc.sql("INSERT INTO time_entry_revision(entry_id,revision_no,action,work_item_id,kind,minutes,content,department_id,state,approved_at,approved_by,cost_amount,cost_daily_rate,cost_policy_version,cost_level_code,owner_department_id) VALUES(?,2,'REPORT',?,'WORK',480,?,1,'APPROVED',UTC_TIMESTAMP(6),2,1000,1000,'STANDARD_DAILY_480_HALF_UP_V1','MIDDLE',1)").params(f.entry(),f.project(),f.marker()+" V2").update();long revision=id();jdbc.sql("UPDATE time_entry SET current_revision_id=? WHERE id=?").params(revision,f.entry()).update();
            jdbc.sql("UPDATE work_item SET name=? WHERE id=?").params(f.marker()+" 新名称",f.project()).update();});
        assertEquals("1550.00",reports.read(q(f,"project-costs","FORMAL")).summary().get("totalCost"));assertTrue(closing.previewDiff(f.month(),batchId).get("rows") instanceof List<?> diff&&!diff.isEmpty());
        var v2=closing.publish(f.month(),batchId,new PublishInput("本地整月V2"));assertEquals(2,v2.versionNo());assertEquals(v2.id(),closing.publish(f.month(),batchId,new PublishInput("发布重试")).id());
        assertEquals("1300.00",reports.read(q(f,"project-costs","FORMAL")).summary().get("totalCost"));
        Query old=new Query("project-costs",f.month(),f.month().plusMonths(1).minusDays(1),"FORMAL",List.of(v1.id()),null,null,null,null,"DAY");assertEquals("1550.00",reports.read(old).summary().get("totalCost"));assertFalse(reports.read(old).rows().getFirst().get("name").toString().contains("新名称"));
        assertThrows(ApiException.class,()->tx.executeWithoutResult(s->gate.lockForAction(f.date(),f.user(),f.project(),"TIME",f.entry(),"SAVE")));
        assertFalse(((List<?>)closing.diff(f.month(),Long.parseLong(v2.id())).get("rows")).isEmpty());
    }
    @Test void asynchronousXlsxPinsScopeAndDownloadRechecksCurrentAuthority() throws Exception {
        Fixture f=fixture();as(2);var requested=createExport(q(f,"project-costs","CURRENT"));long exportId=Long.parseLong(requested.get("id").toString());assertEquals("QUEUED",requested.get("state"));exports.generate(exportId);exports.generate(exportId);
        var file=exports.download(exportId);try(var workbook=new XSSFWorkbook(file.toFile())) {assertEquals("1550.00",workbook.getSheet("数据").getRow(1).getCell(12).getStringCellValue());assertNotNull(workbook.getSheet("口径与版本"));}
        as(f.user());assertThrows(ApiException.class,()->exports.download(exportId));as(2);
        tx.executeWithoutResult(s->jdbc.sql("UPDATE work_item SET default_approver_id=4 WHERE id=?").param(f.project()).update());
        assertThrows(ApiException.class,()->exports.download(exportId));
        // No published month at enqueue remains missing even if V1 is published before generation.
        as(4);long empty=Long.parseLong(createExport(q(f,"project-costs","FORMAL")).get("id").toString());close(f);exports.generate(empty);
        try(var workbook=new XSSFWorkbook(exports.download(empty).toFile())) {assertEquals(0,workbook.getSheet("数据").getLastRowNum());}
    }
    @Test void concurrentClosePublishesOneVersionAndUnprivilegedActorsCannotGrant() throws Exception {
        Fixture f=fixture();tx.executeWithoutResult(status->due(f));
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start=new java.util.concurrent.CountDownLatch(1);
            var a=pool.submit(()->{as(4);start.await();try{return closing.close(f.month()).id();}finally{SecurityContextHolder.clearContext();}});
            var b=pool.submit(()->{as(4);start.await();try{return closing.close(f.month()).id();}finally{SecurityContextHolder.clearContext();}});
            start.countDown();assertEquals(a.get(15,java.util.concurrent.TimeUnit.SECONDS),b.get(15,java.util.concurrent.TimeUnit.SECONDS));
        }
        as(f.user());assertEquals(403,assertThrows(ApiException.class,()->closing.open(f.month(),new AmendmentInput("不能自授权",null))).status());
        var input=new ScopeInput(Long.toString(f.user()),f.date(),Long.toString(f.project()),"TIME",Long.toString(f.entry()),List.of("SAVE"),"本人申请待审核");
        assertEquals("REQUESTED",closing.request(f.month(),new RequestInput("本人申请",List.of(input))).get("state"));
        assertThrows(ApiException.class,()->tx.executeWithoutResult(s->gate.lockForAction(f.date(),f.user(),f.project(),"TIME",f.entry(),"SAVE")));
        tx.executeWithoutResult(s->jdbc.sql("INSERT INTO role_grant(user_id,role_code,scope_type,valid_from) VALUES(?,'PM','COMPANY','1900-01-01')").param(f.user()).update());
        as(f.user());assertThrows(ApiException.class,()->closing.open(f.month(),new AmendmentInput("纯PM不能自行解锁",null)));
        as(4);assertEquals(1,((List<?>)closing.get(f.month()).get("versions")).size());
    }
    @Test void missingSourcesStayIncompleteAndCalendarCacheNeverOverridesAnExplicitCalendar() {
        Fixture f=fixture();as(4);
        tx.executeWithoutResult(s->jdbc.sql("INSERT INTO work_calendar(work_date,base_minutes,is_workday,reason,is_override) VALUES(?,0,FALSE,'默认周历过期缓存',FALSE)").param(f.date()).update());
        assertEquals(480,reports.read(q(f,"workload","CURRENT")).rows().getFirst().get("requiredMinutes"));
        tx.executeWithoutResult(s->jdbc.sql("UPDATE work_calendar SET base_minutes=240,is_workday=TRUE,is_override=TRUE WHERE work_date=?").param(f.date()).update());
        assertEquals(240,reports.read(q(f,"workload","CURRENT")).rows().getFirst().get("requiredMinutes"));
        String employee=jdbc.sql("SELECT employee_no FROM app_user WHERE id=?").param(f.user()).query(String.class).single();
        tx.executeWithoutResult(s->jdbc.sql("INSERT INTO source_record(dataset,source_key,source_version,normalized_data,content_sha256,application_state,error_message) VALUES('LEAVE',?,'failed',JSON_OBJECT('employeeNo',?,'date',?),REPEAT('a',64),'FAILED','本地未知来源验证')").params(f.marker(),employee,f.date().toString()).update());
        assertEquals("INCOMPLETE",reports.read(q(f,"workload","CURRENT")).rows().getFirst().get("status"));
        tx.executeWithoutResult(s->jdbc.sql("INSERT INTO source_record(dataset,source_key,source_version,normalized_data,content_sha256,application_state) VALUES('LEAVE',?,'received',JSON_OBJECT('employeeNo',?,'date',?),REPEAT('b',64),'RECEIVED')").params(f.marker(),employee,f.date().toString()).update());
        assertEquals("INCOMPLETE",reports.read(q(f,"workload","CURRENT")).rows().getFirst().get("status"));
        tx.executeWithoutResult(s->jdbc.sql("UPDATE source_record SET application_state='APPLIED' WHERE source_key=? AND source_version='received'").param(f.marker()).update());
        assertEquals("CONFIRMED",reports.read(q(f,"workload","CURRENT")).rows().getFirst().get("status"));
        long export=Long.parseLong(createExport(q(f,"workload","CURRENT")).get("id").toString());exports.generate(export);var path=exports.download(export);
        tx.executeWithoutResult(s->jdbc.sql("UPDATE export_request SET expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?").param(export).update());
        assertEquals(410,assertThrows(ApiException.class,()->exports.download(export)).status());assertTrue(exports.cleanupExpired()>0);assertFalse(java.nio.file.Files.exists(path));assertEquals("SUCCEEDED",exports.get(export).get("state"));
    }

}
