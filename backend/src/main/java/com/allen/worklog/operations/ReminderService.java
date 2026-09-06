package com.allen.worklog.operations;

import com.allen.worklog.work.DayService;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ReminderService {
    private final JdbcClient jdbc;private final DayService days;private final NotificationService notices;private final ConfigService configs;private final TransactionTemplate tx;
    public ReminderService(JdbcClient jdbc,DayService days,NotificationService notices,ConfigService configs,PlatformTransactionManager manager) {this.jdbc=jdbc;this.days=days;this.notices=notices;this.configs=configs;this.tx=new TransactionTemplate(manager);}
    public Map<String,Object> perform(String code,LocalDate date,long jobId) {
        var config=configs.forDate(date);var rule=config.rules().stream().filter(r->r.code().equals(code)).findFirst().orElseThrow();
        if(!rule.enabled())return Map.of("sent",0,"disabled",true);
        LocalDate week=date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
        int count=0;
        if(code.startsWith("APPROVAL_")) {
            var packages=jdbc.sql("""
                SELECT p.id,p.approver_user_id,u.name,COUNT(*) amount,MIN(i.approval_deadline)<=UTC_TIMESTAMP(6) overdue
                FROM approval_package p JOIN approval_item i ON i.package_id=p.id AND i.status='PENDING'
                JOIN app_user u ON u.id=p.approver_user_id AND u.status='ACTIVE'
                WHERE p.week_start<=? GROUP BY p.id,p.approver_user_id,u.name
                """).param(week).query((rs,n)->new Pending(rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getInt(4),rs.getBoolean(5))).list();
            for(var p:packages)if(p.count()>=rule.threshold()&&(!code.equals("APPROVAL_OVERDUE")||p.overdue())) {
                if(rule.recipients().equals("ADMIN"))for(long admin:admins())send(rule,admin,p.name(),null,date,week,p.count(),"/admin/approvals");
                else send(rule,p.user(),p.name(),p.id(),date,week,p.count(),"/h5/approvals/"+p.id());count++;
                if(rule.recipients().equals("APPROVER_SUPERVISOR"))for(long supervisor:supervisors(p.user()))send(rule,supervisor,"负责人",null,date,week,p.count(),"/admin/approvals");
            }
        } else {
            for(var user:jdbc.sql("SELECT u.id,u.name FROM app_user u WHERE u.status='ACTIVE' AND EXISTS(SELECT 1 FROM reporting_enrollment e WHERE e.user_id=u.id AND e.voided=FALSE AND e.valid_from<=? AND (e.valid_to IS NULL OR e.valid_to>?))").params(date,code.equals("WEEK_MISSING")?week:date.minusDays(1)).query((rs,n)->new Person(rs.getLong(1),rs.getString(2))).list()) {
                int missing=SystemExecution.runAs(user.id(),jobId,()->{
                    if(code.equals("WEEK_MISSING")) {
                        int n=0;for(LocalDate d=week;d.isBefore(week.plusDays(7));d=d.plusDays(1)) {
                            var day=days.get(d);
                            if(day.enrolled()&&(day.requiredMinutes()>day.totalMinutes()||day.entries().stream().anyMatch(e->Set.of("DRAFT","REJECTED").contains(e.state()))||day.onsite()!=null&&Set.of("DRAFT","REJECTED").contains(day.onsite().state())))n++;
                        }return n;
                    }
                    if(code.equals("CONSECUTIVE_MISSING")) {
                        int n=0;for(LocalDate d=date.minusDays(1);d.isAfter(date.minusDays(366))&&n<rule.threshold();d=d.minusDays(1)) {
                            var day=days.get(d);if(!day.enrolled())break;if(day.requiredMinutes()==0)continue;
                            if(day.totalMinutes()>=day.requiredMinutes())break;n++;
                        }return n>=rule.threshold()?n:0;
                    }
                    var day=days.get(code.equals("PREVIOUS_MISSING")?date.minusDays(1):date);
                    return day.enrolled()&&day.totalMinutes()<day.requiredMinutes()?1:0;
                });
                if(missing>=rule.threshold()) {
                    if(rule.recipients().equals("ADMIN"))for(long admin:admins())send(rule,admin,user.name(),null,date,week,missing,"/admin/reports/workload");
                    else send(rule,user.id(),user.name(),null,date,week,missing,"/h5/work");count++;
                    if(rule.recipients().equals("SELF_MANAGER"))for(long supervisor:supervisors(user.id()))send(rule,supervisor,"负责人",null,date,week,missing,"/admin/reports/workload");
                }
            }
        }
        return Map.of("notices",count,"rule",code);
    }
    private void send(ConfigService.Rule rule,long user,String name,Long packageId,LocalDate date,LocalDate week,int count,String path) {
        String body=rule.template().replace("{name}",name).replace("{date}",date.toString()).replace("{week}",week.toString()).replace("{count}",Integer.toString(count));
        tx.executeWithoutResult(s->notices.enqueue(user,packageId,rule.code(),rule.code()+":"+date+":"+user+":"+packageId+":"+Integer.toHexString(name.hashCode()),body,path));
    }
    private List<Long> admins() {return jdbc.sql("SELECT DISTINCT u.id FROM app_user u JOIN role_grant r ON r.user_id=u.id AND r.role_code='ADMIN' AND r.revoked_at IS NULL AND r.valid_from<=? AND (r.valid_to IS NULL OR r.valid_to>?) WHERE u.status='ACTIVE'").params(configs.today(),configs.today()).query(Long.class).list();}
    private List<Long> supervisors(long user) {return jdbc.sql("SELECT DISTINCT m.id FROM app_user u JOIN department d ON d.id=u.department_id JOIN app_user m ON m.id=COALESCE(NULLIF(d.designated_manager_id,u.id),d.supervisor_user_id) AND m.status='ACTIVE' WHERE u.id=? AND m.id<>?").params(user,user).query(Long.class).list();}
    private record Person(long id,String name) {}private record Pending(long id,long user,String name,int count,boolean overdue) {}
}
