package com.allen.worklog.approval;

import com.allen.worklog.common.ApiException;
import com.allen.worklog.work.DayService;
import com.allen.worklog.work.DayModels.*;
import com.allen.worklog.reporting.ReportingAccess;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import static com.allen.worklog.approval.ApprovalModels.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="WORKLOG_INTEGRATION",matches="true")
@SpringBootTest(properties={"spring.profiles.active=local","spring.flyway.out-of-order=true"})
class ApprovalDatabaseTest {
    @Autowired JdbcClient jdbc;@Autowired TransactionTemplate tx;@Autowired ApprovalService approvals;@Autowired ApprovalData data;
    @Autowired CorrectionService corrections;@Autowired DisputeService disputes;@Autowired DayService days;@Autowired ReportingAccess access;@Autowired com.allen.worklog.reporting.ReportService reports;
    final List<Long> users=new ArrayList<>(),items=new ArrayList<>(),departments=new ArrayList<>();
    record Fixture(LocalDate week,long department,long employee,long pm,long head,long leader,long temporary,long project,long other,long nonproject,long idle,long unresolved) {}
    static void as(long id){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(Long.toString(id),null,List.of()));}
    static String key(){return UUID.randomUUID().toString();} static long number(Object value){return Long.parseLong(value.toString());}
    long last(){return jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();}
    Fixture fixture(){return tx.execute(status->{
        assertEquals("local-admin",jdbc.sql("SELECT wecom_userid FROM app_user WHERE id=4").query(String.class).single());as(4);
        LocalDate today=jdbc.sql("SELECT DATE(UTC_TIMESTAMP()+INTERVAL 8 HOUR)").query(LocalDate.class).single(),week=today.with(DayOfWeek.MONDAY);
        String marker="APP-IT-"+key().substring(0,8);jdbc.sql("INSERT INTO department(code,name) VALUES(?,?)").params(marker,marker+" 审批验证部").update();long dept=last();departments.add(dept);
        long employee=user(dept,"EMPLOYEE",week),pm=user(dept,"PM",week),head=user(dept,"DEPARTMENT_MANAGER",week),leader=user(dept,"LEADER",week),temporary=user(dept,"EMPLOYEE",week);
        jdbc.sql("UPDATE department SET designated_manager_id=?,supervisor_user_id=? WHERE id=?").params(head,leader,dept).update();
        return new Fixture(week,dept,employee,pm,head,leader,temporary,item(dept,"PROJECT",pm,marker),item(dept,"PROJECT",pm,marker),item(dept,"NON_PROJECT",head,marker),item(dept,"IDLE",head,marker),item(dept,"PROJECT",null,marker));
    });}
    long user(long dept,String role,LocalDate week){
        int suffix=Math.abs((int)(System.nanoTime()%100000));String employee=String.format("AP%05d",suffix);
        while(jdbc.sql("SELECT COUNT(*) FROM app_user WHERE employee_no=?").param(employee).query(Integer.class).single()>0)employee=String.format("AP%05d",(++suffix)%100000);
        jdbc.sql("INSERT INTO app_user(employee_no,wecom_userid,name,department_id,level_code) VALUES(?,?,?,?,'MIDDLE')").params(employee,"approval-it-"+key(),"本地审批验证 "+role,dept).update();long user=last();users.add(user);
        jdbc.sql("INSERT INTO user_department_history(user_id,department_id,valid_from) VALUES(?,?,?)").params(user,dept,week).update();
        jdbc.sql("INSERT INTO user_level_history(user_id,level_code,valid_from) VALUES(?,'MIDDLE',?)").params(user,week).update();
        jdbc.sql("INSERT INTO reporting_enrollment(user_id,valid_from) VALUES(?,?)").params(user,week).update();
        jdbc.sql("INSERT INTO role_grant(user_id,role_code,scope_type,department_id,valid_from) VALUES(?,?,?,?,?)")
            .params(user,role,role.equals("DEPARTMENT_MANAGER")?"DEPARTMENT":"COMPANY",role.equals("DEPARTMENT_MANAGER")?dept:null,week).update();return user;
    }
    long item(long dept,String type,Long approver,String marker){jdbc.sql("INSERT INTO work_item(code,name,type,owner_department_id,default_approver_id) VALUES(?,?,?,?,?)")
        .params(marker+"-"+key().substring(0,8),"本地审批验证 "+type,type,dept,approver).update();long id=last();items.add(id);
        jdbc.sql("INSERT INTO work_item_history(work_item_id,owner_department_id,valid_from) VALUES(?,?,'2026-01-01')").params(id,dept).update();return id;}
    @AfterEach void cleanup(){
        tx.executeWithoutResult(status->{for(long id:users)jdbc.sql("UPDATE app_user SET status='INACTIVE' WHERE id=?").param(id).update();for(long id:items)jdbc.sql("UPDATE work_item SET status='INACTIVE' WHERE id=?").param(id).update();for(long id:departments)jdbc.sql("UPDATE department SET status='INACTIVE' WHERE id=?").param(id).update();});SecurityContextHolder.clearContext();
    }
    EntryInput entry(long item,String kind,String hours){return new EntryInput(null,Long.toString(item),kind,hours,"核实交付工作和现场事实","");}
    Day save(Fixture f,long user,List<EntryInput> inputs,boolean onsite){as(user);return days.save(f.week(),new SaveDay(days.get(f.week()).version(),inputs,onsite?new OnsiteInput(null,Long.toString(f.project()),"客户现场驻留核实"):null),key());}
    BatchResult submit(Fixture f,long user){as(user);return approvals.submit(f.week(),key());}
    long pkg(long user,long item){return jdbc.sql("SELECT id FROM approval_package WHERE user_id=? AND work_item_id=? ORDER BY id DESC LIMIT 1").params(user,item).query(Long.class).single();}
    List<ApprovalData.Item> pending(long pkg){return data.items(pkg).stream().filter(i->i.status().equals("PENDING")).toList();}
    BatchResult decide(long actor,long pkg,String decision,List<ApprovalData.Item> selected){as(actor);return approvals.decide(pkg,new Decide(data.pack(pkg,false).version(),selected.stream().map(i->Long.toString(i.id())).toList(),decision,decision.equals("REJECT")?"请核实后更正该项":"已核实"),key());}
    @SuppressWarnings("unchecked") List<Map<String,Object>> views(long actor,long pkg){as(actor);return (List<Map<String,Object>>)approvals.detail(pkg).get("items");}

    @Test void objectPackagesPartialApprovalTransferAndCostRedactionAreIndependent(){
        var f=fixture();save(f,f.employee(),List.of(entry(f.project(),"WORK","4"),entry(f.nonproject(),"WORK","1"),entry(f.idle(),"IDLE","3")),true);
        String request=key();as(f.employee());var result=approvals.submit(f.week(),request);assertEquals(4,result.succeeded().size());assertTrue(result.failed().isEmpty());assertEquals(result,approvals.submit(f.week(),request));
        long project=pkg(f.employee(),f.project());assertEquals(2,pending(project).size());
        assertTrue(views(f.employee(),project).stream().noneMatch(i->i.containsKey("cost")));as(f.employee());assertEquals(403,assertThrows(ApiException.class,()->approvals.decide(project,new Decide(data.pack(project,false).version(),List.of(result.succeeded().getFirst().id()),"APPROVE",""),key())).status());
        as(f.temporary());assertEquals(403,assertThrows(ApiException.class,()->approvals.detail(project)).status());
        var labor=pending(project).stream().filter(i->i.rev().kind().equals("TIME")).toList();assertEquals(1,decide(f.pm(),project,"APPROVE",labor).succeeded().size());
        assertEquals(new BigDecimal("600.00"),data.revision("TIME",labor.getFirst().rev().id()).amount());
        for(long item:List.of(f.nonproject(),f.idle())){long pack=pkg(f.employee(),item);var chosen=pending(pack);assertEquals(1,decide(f.head(),pack,"APPROVE",chosen).succeeded().size());assertEquals(new BigDecimal("0.00"),data.revision("TIME",chosen.getFirst().rev().id()).amount());}
        var before=data.pack(project,false);as(4);approvals.transfer(project,new Transfer(before.version(),Long.toString(f.temporary()),"原审批人临时外出","业务负责人指定本周待办"),key());
        assertEquals(before.deadline(),data.pack(project,false).deadline());assertEquals(f.pm(),data.item(labor.getFirst().id(),false).handledBy());
        as(f.pm());assertThrows(ApiException.class,()->approvals.decide(project,new Decide(data.pack(project,false).version(),List.of(Long.toString(pending(project).getFirst().id())),"APPROVE",""),key()));
        assertTrue(views(f.temporary(),project).stream().noneMatch(i->i.containsKey("cost")));as(f.temporary());assertFalse(access.canReadCost(f.project()));
        var onsite=pending(project);assertEquals(1,decide(f.temporary(),project,"APPROVE",onsite).succeeded().size());assertEquals(new BigDecimal("200.00"),data.revision("ONSITE",onsite.getFirst().rev().id()).amount());
        assertEquals(4,jdbc.sql("SELECT COUNT(*) FROM approval_item a JOIN approval_package p ON p.id=a.package_id WHERE p.user_id=?").param(f.employee()).query(Integer.class).single());
    }

    @Test void rejectedRevisionResubmitsAloneAndApprovedCorrectionKeepsOldSnapshot(){
        var f=fixture();var day=save(f,f.employee(),List.of(entry(f.project(),"WORK","6"),entry(f.other(),"WORK","2")),false);assertEquals(2,submit(f,f.employee()).succeeded().size());
        long first=pkg(f.employee(),f.project()),second=pkg(f.employee(),f.other());var approved=pending(first).getFirst();var rejected=pending(second).getFirst();
        decide(f.pm(),first,"APPROVE",List.of(approved));decide(f.pm(),second,"REJECT",List.of(rejected));
        as(f.employee());var current=days.get(f.week());var inputs=current.entries().stream().map(e->new EntryInput(e.id(),e.workItemId(),e.kind(),e.hours(),e.state().equals("REJECTED")?"经双方核实后修正项目工作说明":e.content(),e.redReason())).toList();
        days.save(f.week(),new SaveDay(current.version(),inputs,null),key());assertEquals(1,submit(f,f.employee()).succeeded().size());assertEquals("REJECTED",data.item(rejected.id(),false).status());assertEquals(1,pending(second).size());decide(f.pm(),second,"APPROVE",pending(second));
        long stable=approved.rev().recordId();as(f.employee());var request=corrections.create(new CorrectionInput("TIME",Long.toString(stable),"EDIT","原项目归属需要更正"),key());
        long editRequest=number(request.get("id"));as(4);assertEquals(403,assertThrows(ApiException.class,()->corrections.decide(editRequest,new CorrectionDecision("APPROVE","管理员无代批资格"),key())).status());
        as(f.pm());corrections.decide(number(request.get("id")),new CorrectionDecision("APPROVE","原事实已核实可更正"),key());
        assertEquals(new BigDecimal("900.00"),data.revision("TIME",approved.rev().id()).amount());assertNull(data.currentRevision("TIME",stable).amount());assertEquals("DRAFT",data.currentRevision("TIME",stable).state());
        as(f.employee());current=days.get(f.week());inputs=current.entries().stream().map(e->new EntryInput(e.id(),e.id().equals(Long.toString(stable))?Long.toString(f.other()):e.workItemId(),e.kind(),e.hours(),e.content(),e.redReason())).toList();
        days.save(f.week(),new SaveDay(current.version(),inputs,null),key());assertEquals(1,submit(f,f.employee()).succeeded().size());decide(f.pm(),second,"APPROVE",pending(second));assertEquals(f.other(),data.currentRevision("TIME",stable).item());
        // Cancel is a separately approved version and can be submitted when it removes the day's base hours.
        as(f.employee());request=corrections.create(new CorrectionInput("TIME",Long.toString(stable),"CANCEL","撤销错误记录"),key());as(f.pm());corrections.decide(number(request.get("id")),new CorrectionDecision("APPROVE","同意按取消流程核实"),key());
        var submitted=submit(f,f.employee());assertEquals(1,submitted.succeeded().size());assertFalse(submitted.failed().isEmpty());decide(f.pm(),second,"APPROVE",pending(second));
        var cancelled=data.currentRevision("TIME",stable);assertEquals("APPROVED",cancelled.state());assertEquals("CANCEL",cancelled.action());assertEquals(new BigDecimal("0.00"),cancelled.amount());assertEquals(new BigDecimal("900.00"),data.revision("TIME",approved.rev().id()).amount());
    }

    @Test void routingSelfAvoidanceUnresolvedDayAtomicityAndZeroWorkOnsite(){
        var f=fixture();save(f,f.pm(),List.of(entry(f.project(),"WORK","8")),false);assertEquals(1,submit(f,f.pm()).succeeded().size());assertEquals(f.head(),data.pack(pkg(f.pm(),f.project()),false).approver());
        save(f,f.head(),List.of(entry(f.nonproject(),"WORK","8")),false);assertEquals(1,submit(f,f.head()).succeeded().size());assertEquals(f.leader(),data.pack(pkg(f.head(),f.nonproject()),false).approver());
        long leaderItem=tx.execute(status->item(f.department(),"NON_PROJECT",f.leader(),"SELF-"+key().substring(0,8)));save(f,f.leader(),List.of(entry(leaderItem,"WORK","8")),false);assertTrue(submit(f,f.leader()).succeeded().isEmpty());
        as(4);approvals.saveDesignation(new DesignationInput(Long.toString(f.leader()),Long.toString(leaderItem),Long.toString(f.head()),"本人回避","公司明确指定"),key());assertEquals(1,submit(f,f.leader()).succeeded().size());
        save(f,f.employee(),List.of(entry(f.project(),"WORK","6"),entry(f.unresolved(),"WORK","2")),true);var partial=submit(f,f.employee());assertEquals(1,partial.succeeded().size());assertEquals("ONSITE",partial.succeeded().getFirst().kind());assertTrue(partial.failed().stream().anyMatch(i->"APPROVER_UNRESOLVED".equals(i.code())));
        assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM approval_item a JOIN approval_package p ON p.id=a.package_id WHERE p.user_id=? AND a.time_revision_id IS NOT NULL").param(f.employee()).query(Integer.class).single());
        var zero=save(f,f.temporary(),List.of(),true);as(f.temporary());var preview=approvals.preview(f.week()).days().getFirst();assertTrue(preview.onsiteReady());assertFalse(preview.workReady());var onsite=approvals.submitOnsite(number(zero.onsite().id()),key());assertEquals(1,onsite.succeeded().size());long pack=pkg(f.temporary(),f.project());assertEquals(1,decide(f.pm(),pack,"APPROVE",pending(pack)).succeeded().size());
    }

    @Test void disputeOnlyBlocksItsRowAndIndependentRulingDoesNotApproveIt(){
        var f=fixture();save(f,f.employee(),List.of(entry(f.project(),"WORK","4"),entry(f.project(),"TRAVEL","4")),false);submit(f,f.employee());long pack=pkg(f.employee(),f.project());var selected=pending(pack);var disputed=selected.getFirst();
        as(f.employee());var opened=disputes.create(new DisputeInput(Long.toString(disputed.id()),"实际工作归属存在分歧"),key());long id=number(opened.get("id"));assertEquals(Long.toString(f.head()),opened.get("coordinatorId"));
        var partial=decide(f.pm(),pack,"APPROVE",selected);assertEquals(1,partial.succeeded().size());assertEquals(1,partial.failed().size());assertEquals("PENDING",data.item(disputed.id(),false).status());
        as(4);assertEquals(403,assertThrows(ApiException.class,()->disputes.resolve(id,new ResolveDispute("RESOLVE","CONFIRM_FACTS","管理员不能直接裁定"),key())).status());
        as(f.pm());disputes.statement(id,new Statement("补充项目核实依据"),key());as(f.head());assertEquals(403,assertThrows(ApiException.class,()->disputes.statement(id,new Statement("不得代写员工说明"),key())).status());
        var escalated=disputes.resolve(id,new ResolveDispute("ESCALATE",null,"双方未达成一致"),key());assertEquals("ESCALATED",escalated.get("state"));assertEquals("RULING",escalated.get("stage"));assertNotNull(disputes.get(id));
        as(f.leader());disputes.resolve(id,new ResolveDispute("RESOLVE","CONFIRM_FACTS","按双方事实核实无误"),key());assertEquals("PENDING",data.item(disputed.id(),false).status());assertNull(data.revision("TIME",disputed.rev().id()).amount());assertEquals(1,decide(f.pm(),pack,"APPROVE",pending(pack)).succeeded().size());
        as(f.employee());opened=disputes.create(new DisputeInput(Long.toString(disputed.id()),"通过后发现归属需要更正"),key());long secondId=number(opened.get("id"));as(f.head());disputes.resolve(secondId,new ResolveDispute("RESOLVE","NEEDS_CORRECTION","确认需要走更正流程"),key());assertTrue(data.revision("TIME",disputed.rev().id()).disputeOpen());
        as(f.employee());var correction=corrections.create(new CorrectionInput("TIME",Long.toString(disputed.rev().recordId()),"EDIT","按协调结论修正"),key());as(f.pm());corrections.decide(number(correction.get("id")),new CorrectionDecision("APPROVE","同意按结论更正"),key());assertNull(data.currentRevision("TIME",disputed.rev().recordId()).amount());
    }

    @Test void missingRateFailsOnlyLaborWhileOnsiteStillApproves(){
        var f=fixture();save(f,f.employee(),List.of(entry(f.project(),"WORK","8")),true);submit(f,f.employee());long pack=pkg(f.employee(),f.project());
        tx.executeWithoutResult(status->jdbc.sql("UPDATE user_level_history SET valid_from=? WHERE user_id=?").params(f.week().plusDays(1),f.employee()).update());
        var result=decide(f.pm(),pack,"APPROVE",pending(pack));assertEquals(1,result.succeeded().size());assertEquals(1,result.failed().size());assertEquals("ONSITE",result.succeeded().getFirst().kind());assertEquals("PENDING",pending(pack).getFirst().rev().state());assertNull(pending(pack).getFirst().rev().amount());
    }

    @Test void concurrentTransferAndDecisionHaveOneWinnerAndNoMixedHandler() throws Exception {
        var f=fixture();save(f,f.employee(),List.of(entry(f.project(),"WORK","8")),false);submit(f,f.employee());long pack=pkg(f.employee(),f.project());int version=data.pack(pack,false).version();long item=pending(pack).getFirst().id();
        try(var pool=Executors.newFixedThreadPool(2)){
            var start=new CountDownLatch(1);Callable<String> approve=()->{as(f.pm());start.await();try{approvals.decide(pack,new Decide(version,List.of(Long.toString(item)),"APPROVE","已核实"),key());return "SUCCESS";}catch(ApiException e){return e.code();}finally{SecurityContextHolder.clearContext();}};
            Callable<String> transfer=()->{as(4);start.await();try{approvals.transfer(pack,new Transfer(version,Long.toString(f.temporary()),"并发转交测试","隔离测试依据"),key());return "SUCCESS";}catch(ApiException e){return e.code();}finally{SecurityContextHolder.clearContext();}};
            var a=pool.submit(approve);var b=pool.submit(transfer);start.countDown();List<String> outcomes=List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));assertEquals(1,outcomes.stream().filter("SUCCESS"::equals).count(),outcomes.toString());
        }
        var actual=data.item(item,false);if(actual.status().equals("APPROVED")){assertEquals(f.pm(),actual.handledBy());assertEquals(f.pm(),data.pack(pack,false).approver());}else{assertEquals("PENDING",actual.status());assertNull(actual.handledBy());assertEquals(f.temporary(),data.pack(pack,false).approver());}
    }
    @Test void crossMonthPreviewAndApprovalHonorExactRecordActionsWithoutOpeningWholeMonth(){
        var f=fixture();tx.executeWithoutResult(status->{
            status.setRollbackOnly();LocalDate month=LocalDate.of(2000,1,1);
            while(month.plusMonths(1).minusDays(1).getDayOfWeek()!=DayOfWeek.MONDAY||jdbc.sql("SELECT COUNT(*) FROM accounting_period WHERE month_start IN (?,?)").params(month,month.plusMonths(1)).query(Integer.class).single()>0)month=month.plusMonths(1);
            LocalDate monday=month.plusMonths(1).minusDays(1),next=monday.plusDays(1);long period;
            jdbc.sql("INSERT INTO accounting_period(month_start,status,scheduled_close_at) VALUES(?,'CLOSED',UTC_TIMESTAMP(6))").param(month).update();period=last();
            jdbc.sql("INSERT INTO accounting_period(month_start,scheduled_close_at) VALUES(?,DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 DAY))").param(month.plusMonths(1)).update();
            for(String table:List.of("user_department_history","user_level_history","reporting_enrollment"))jdbc.sql("UPDATE "+table+" SET valid_from=? WHERE user_id=?").params(monday,f.employee()).update();
            List<Long> records=new ArrayList<>();
            for(LocalDate date:List.of(monday,next)){
                jdbc.sql("INSERT INTO day_record(user_id,work_date,base_minutes,leave_minutes,required_minutes,is_workday,department_id) VALUES(?,?,480,0,480,TRUE,?)").params(f.employee(),date,f.department()).update();long day=last();
                jdbc.sql("INSERT INTO time_entry(day_id) VALUES(?)").param(day).update();long entry=last();records.add(entry);
                jdbc.sql("INSERT INTO time_entry_revision(entry_id,revision_no,action,work_item_id,kind,minutes,content,department_id,state) VALUES(?,1,'REPORT',?,'WORK',480,?,?,'DRAFT')").params(entry,f.nonproject(),"跨月权限核实当天实际工作 "+date,f.department()).update();long revision=last();jdbc.sql("UPDATE time_entry SET current_revision_id=? WHERE id=?").params(revision,entry).update();
            }
            as(f.employee());assertFalse(approvals.preview(monday).days().getFirst().workReady());
            jdbc.sql("INSERT INTO amendment_batch(period_id,reason,created_by) VALUES(?,'本地精确授权回滚验证',4)").param(period).update();long amendment=last();jdbc.sql("UPDATE accounting_period SET active_amendment_id=? WHERE id=?").params(amendment,period).update();
            jdbc.sql("INSERT INTO unlock_scope(amendment_id,user_id,work_date,work_item_id,object_type,record_id,actions,reason,authorized_by) VALUES(?,?,?,?,'TIME',?,JSON_ARRAY('SUBMIT'),'仅允许原记录送审',4)")
                .params(amendment,f.employee(),monday,f.nonproject(),records.getFirst()).update();
            assertTrue(approvals.preview(monday).days().getFirst().workReady());assertEquals(2,approvals.submit(monday,key()).succeeded().size());long pack=pkg(f.employee(),f.nonproject());
            var result=decide(f.head(),pack,"APPROVE",pending(pack));assertEquals(1,result.succeeded().size());assertEquals(next,result.succeeded().getFirst().date());assertEquals(1,result.failed().size());assertEquals(monday,result.failed().getFirst().date());assertEquals("PENDING",pending(pack).getFirst().status());
            jdbc.sql("INSERT INTO unlock_scope(amendment_id,user_id,work_date,work_item_id,object_type,record_id,actions,reason,authorized_by) VALUES(?,?,?,?,'TIME',?,JSON_ARRAY('APPROVE'),'追加原记录补审授权',4)")
                .params(amendment,f.employee(),monday,f.nonproject(),records.getFirst()).update();
            assertEquals(1,decide(f.head(),pack,"APPROVE",pending(pack)).succeeded().size());assertEquals("CLOSED",jdbc.sql("SELECT status FROM accounting_period WHERE id=?").param(period).query(String.class).single());
        });
    }
    @Test void approvedBusinessExamplesMatchWorkloadTravelAndOnsiteRatioWithoutDiscountingCost(){
        var f=fixture();save(f,f.employee(),List.of(entry(f.project(),"WORK","4"),entry(f.nonproject(),"WORK","1"),entry(f.idle(),"IDLE","3")),false);submit(f,f.employee());
        for(long item:List.of(f.project(),f.nonproject(),f.idle())){long pack=pkg(f.employee(),item);decide(item==f.project()?f.pm():f.head(),pack,"APPROVE",pending(pack));}
        as(f.head());var load=reports.read(new com.allen.worklog.reporting.ReportModels.Query("workload",f.week(),f.week(),"CURRENT",List.of(),Long.toString(f.employee()),null,null,null,"DAY")).rows().getFirst();assertEquals("62.50",load.get("loadPercent"));assertEquals(180,load.get("confirmedIdleMinutes"));assertEquals(240,load.get("confirmedProjectMinutes"));
        save(f,f.temporary(),List.of(entry(f.project(),"TRAVEL","4"),entry(f.other(),"WORK","2"),entry(f.project(),"WORK","2")),true);submit(f,f.temporary());for(long item:List.of(f.project(),f.other())){long pack=pkg(f.temporary(),item);decide(f.pm(),pack,"APPROVE",pending(pack));}
        as(f.head());var travel=reports.read(new com.allen.worklog.reporting.ReportModels.Query("onsite",f.week(),f.week(),"CURRENT",List.of(),Long.toString(f.temporary()),null,null,null,"DAY")).rows().getFirst();assertEquals(360,travel.get("projectMinutes"));assertEquals(480,travel.get("totalWorkMinutes"));assertEquals("75.00",travel.get("ratioPercent"));
        save(f,f.pm(),List.of(entry(f.project(),"WORK","2"),entry(f.other(),"WORK","6")),true);submit(f,f.pm());for(long item:List.of(f.project(),f.other())){long pack=pkg(f.pm(),item);decide(f.head(),pack,"APPROVE",pending(pack));}
        as(f.head());var ratio=reports.read(new com.allen.worklog.reporting.ReportModels.Query("onsite",f.week(),f.week(),"CURRENT",List.of(),Long.toString(f.pm()),null,null,null,"DAY")).rows().getFirst();assertEquals("25.00",ratio.get("ratioPercent"));assertEquals(true,ratio.get("reviewRequired"));assertEquals("200.00",ratio.get("onsiteCost"));
        LocalDate sunday=f.week().minusDays(1);tx.executeWithoutResult(status->{for(String table:List.of("user_department_history","user_level_history","reporting_enrollment"))jdbc.sql("UPDATE "+table+" SET valid_from=? WHERE user_id=?").params(sunday,f.leader()).update();});
        as(f.leader());var zero=days.save(sunday,new SaveDay(0,List.of(),new OnsiteInput(null,Long.toString(f.project()),"周末项目必要驻留")),key());assertEquals(1,approvals.submitOnsite(number(zero.onsite().id()),key()).succeeded().size());long pack=pkg(f.leader(),f.project());decide(f.pm(),pack,"APPROVE",pending(pack));
        as(f.head());var weekend=reports.read(new com.allen.worklog.reporting.ReportModels.Query("onsite",sunday,sunday,"CURRENT",List.of(),Long.toString(f.leader()),null,null,null,"DAY")).rows().getFirst();assertEquals("NOT_APPLICABLE",weekend.get("ratioStatus"));assertNull(weekend.get("ratioPercent"));assertEquals("200.00",weekend.get("onsiteCost"));
    }
}
