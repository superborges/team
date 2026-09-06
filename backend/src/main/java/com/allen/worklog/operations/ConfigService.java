package com.allen.worklog.operations;

import com.allen.worklog.common.ApiException;
import com.allen.worklog.common.AuditService;
import com.allen.worklog.identity.CurrentUser;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class ConfigService {
    public record Parameters(int dayLimitMinutes,int redFlagMinutes,int defaultDayMinutes,int stepMinutes,
        int submitWeekday,String submitTime,int approveWeekday,String approveTime,int closeDay,String dailyCompletionTime) {}
    public record Rule(String code,String label,boolean enabled,String time,String dayType,int dayOfWeek,int dayOfMonth,
        int threshold,String recipients,String template) {}
    public record Snapshot(String id,LocalDate effectiveFrom,Parameters params,List<Rule> rules) {}
    public record Update(String expectedVersion,LocalDate effectiveFrom,Parameters params,List<Rule> rules,String reason) {}
    private final JdbcClient jdbc;private final CurrentUser current;private final AuditService audit;private final ObjectMapper json;
    public ConfigService(JdbcClient jdbc,CurrentUser current,AuditService audit,ObjectMapper json) {this.jdbc=jdbc;this.current=current;this.audit=audit;this.json=json;}
    public LocalDate today() {return jdbc.sql("SELECT DATE(CONVERT_TZ(UTC_TIMESTAMP(),'+00:00','+08:00'))").query(LocalDate.class).single();}
    public Snapshot current() {return forDate(today());}
    public Snapshot forDate(LocalDate date) {
        return jdbc.sql("SELECT id,effective_from,config_json FROM system_config_version WHERE effective_from<=? ORDER BY effective_from DESC LIMIT 1")
            .param(date).query((rs,n)->{var value=json.readValue(rs.getString("config_json"),Snapshot.class);return new Snapshot(rs.getString("id"),rs.getObject("effective_from",LocalDate.class),value.params(),value.rules());}).optional().orElse(defaults());
    }
    public List<Snapshot> history() {current.requireAdmin();return jdbc.sql("SELECT effective_from FROM system_config_version ORDER BY effective_from DESC LIMIT 50").query(LocalDate.class).list().stream().map(this::forDate).toList();}
    public Map<String,Object> preview(Update input) {
        current.requireAdmin();validate(input);
        int days=jdbc.sql("SELECT COUNT(*) FROM day_record WHERE work_date>=?").param(input.effectiveFrom()).query(Integer.class).single();
        return Map.of("effectiveFrom",input.effectiveFrom(),"existingFutureDays",days,"notes",List.of("新参数从指定日期适用；已发布月报和既有提交截止不改变","显式维护的日历优先于默认工作日基数","已建立月份的预定封账时间保持不变；新月份使用其首日参数"));
    }
    @Transactional public Snapshot save(Update input) {
        var actor=current.requireAdmin();validate(input);
        jdbc.sql("SELECT id FROM system_config_guard WHERE id=1 FOR UPDATE").query(Integer.class).single();current.requireAdmin();
        var latest=jdbc.sql("SELECT CAST(id AS CHAR) FROM system_config_version ORDER BY id DESC LIMIT 1 FOR UPDATE").query(String.class).optional().orElse("0");
        if(!Objects.equals(input.expectedVersion(),latest))throw new ApiException(409,"CONFIG_CHANGED","参数版本已变化，请重新读取并预览");
        if(jdbc.sql("SELECT COUNT(*) FROM system_config_version WHERE effective_from>=?").param(input.effectiveFrom()).query(Integer.class).single()>0)
            throw new ApiException(422,"CONFIG_ORDER","新版本起日须晚于已有所有版本，不能覆盖历史或已排定版本");
        var snapshot=new Snapshot("0",input.effectiveFrom(),input.params(),List.copyOf(input.rules()));
        jdbc.sql("INSERT INTO system_config_version(effective_from,config_json,created_by,reason) VALUES(?,?,?,?)")
            .params(input.effectiveFrom(),json.writeValueAsString(snapshot),actor.id(),input.reason().strip()).update();
        long id=jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        jdbc.sql("SELECT work_date FROM work_calendar WHERE work_date>=? ORDER BY work_date FOR UPDATE").param(input.effectiveFrom()).query(LocalDate.class).list();
        jdbc.sql("SELECT id FROM day_record WHERE work_date>=? ORDER BY user_id,work_date FOR UPDATE").param(input.effectiveFrom()).query(Long.class).list();
        jdbc.sql("UPDATE day_record SET row_version=row_version+1 WHERE work_date>=?").param(input.effectiveFrom()).update();
        audit.record(actor.id(),"CONFIG_VERSION_CREATED","CONFIG",Long.toString(id),null,json.writeValueAsString(snapshot),input.reason());
        return forDate(input.effectiveFrom());
    }
    public String latestVersion() {return jdbc.sql("SELECT CAST(COALESCE(MAX(id),0) AS CHAR) FROM system_config_version").query(String.class).single();}
    private void validate(Update input) {
        if(input==null||input.params()==null||input.rules()==null||input.effectiveFrom()==null||!input.effectiveFrom().isAfter(today())||input.effectiveFrom().isAfter(today().plusYears(2)))throw bad("请指定明天至两年内的生效日期");
        if(input.reason()==null||input.reason().isBlank()||input.reason().length()>500)throw bad("请填写不超过500字的调整原因");
        var p=input.params();
        if(!Set.of(15,30,60).contains(p.stepMinutes())||p.dayLimitMinutes()<480||p.dayLimitMinutes()>1440||p.dayLimitMinutes()%p.stepMinutes()!=0||p.redFlagMinutes()<480||p.redFlagMinutes()>p.dayLimitMinutes()||p.defaultDayMinutes()<0||p.defaultDayMinutes()>480||p.defaultDayMinutes()%p.stepMinutes()!=0||p.submitWeekday()<1||p.submitWeekday()>7||p.approveWeekday()<p.submitWeekday()||p.approveWeekday()>7||p.closeDay()<1||p.closeDay()>28)throw bad("分钟、步长、周截止或封账日超出允许范围");
        time(p.submitTime());time(p.approveTime());time(p.dailyCompletionTime());
        if(p.approveWeekday()==p.submitWeekday()&&!LocalTime.parse(p.approveTime()).isAfter(LocalTime.parse(p.submitTime())))throw bad("同一天的审批截止须晚于提交截止");
        Set<String> codes=new HashSet<>();
        for(var r:input.rules()) {
            if(r==null||!codes.add(r.code())||defaults().rules().stream().noneMatch(d->d.code().equals(r.code())))throw bad("提醒规则重复或未知");
            time(r.time());if(!Set.of("WORKDAY","DAILY","WEEKLY","MONTHLY","EVENT").contains(r.dayType())||r.dayOfWeek()<1||r.dayOfWeek()>7||r.dayOfMonth()<1||r.dayOfMonth()>28||r.threshold()<1||r.threshold()>90||!Set.of("SELF","SELF_MANAGER","APPROVER","APPROVER_SUPERVISOR","ADMIN").contains(r.recipients())||r.template()==null||r.template().isBlank()||r.template().length()>1000)throw bad("请检查提醒日类型、阈值、接收范围和模板");
            boolean automatic=r.code().equals("AUTO_SUBMIT"),close=r.code().equals("MONTH_CLOSE"),event=r.code().equals("REJECTED");
            if((automatic&&!r.dayType().equals("WEEKLY"))||(close&&!r.dayType().equals("MONTHLY"))||(event&&!r.dayType().equals("EVENT"))||(!automatic&&!close&&!event&&r.dayType().equals("EVENT")))throw bad("自动提交按周、封账按月、驳回实时触发；普通提醒请选择周期");
            Set<String> recipients=close?Set.of("ADMIN"):r.code().startsWith("APPROVAL_")?Set.of("APPROVER","APPROVER_SUPERVISOR","ADMIN"):Set.of("SELF","SELF_MANAGER","ADMIN");
            if(!recipients.contains(r.recipients()))throw bad("接收范围须适合该业务规则");
            String clean=r.template().replace("{date}","").replace("{name}","").replace("{week}","").replace("{count}","");
            if(clean.contains("{")||clean.contains("}"))throw bad("模板仅支持 {date}、{name}、{week}、{count}");
        }
        if(codes.size()!=defaults().rules().size())throw bad("须保留所有内置规则，可通过开关暂停提醒");
    }
    private static void time(String value) {try {if(value==null||!value.matches("\\d{2}:\\d{2}"))throw new IllegalArgumentException();LocalTime.parse(value);}catch(Exception e){throw bad("时间须为 HH:mm");}}
    private static ApiException bad(String message) {return new ApiException(422,"INVALID_CONFIG",message);}
    public static Snapshot defaults() {
        return new Snapshot("0",LocalDate.of(1900,1,1),new Parameters(1440,960,480,30,1,"12:00",3,"18:00",10,"12:00"),List.of(
            rule("DAILY_MISSING","当日未填","17:30","WORKDAY",1,"SELF"),rule("PREVIOUS_MISSING","前日补填","10:00","DAILY",1,"SELF"),
            rule("CONSECUTIVE_MISSING","连续未填","10:00","WORKDAY",3,"SELF_MANAGER"),rule("WEEK_MISSING","上周未提交","10:00","WEEKLY",1,"SELF"),
            rule("AUTO_SUBMIT","截止自动提交","12:00","WEEKLY",1,"SELF"),rule("APPROVAL_PENDING","审批待办","14:00","WEEKLY",1,"APPROVER"),
            new Rule("APPROVAL_DUE","审批临期",true,"15:00","WEEKLY",3,1,1,"APPROVER","{name}：仍有{count}项待处理，请及时核实。"),
            new Rule("APPROVAL_OVERDUE","审批超时",true,"09:00","WEEKLY",4,1,1,"APPROVER_SUPERVISOR","{name}：有{count}项审批已超时，请处理。"),
            rule("REJECTED","驳回通知","00:00","EVENT",1,"SELF"),new Rule("MONTH_CLOSE","月度封账",true,"00:00","MONTHLY",1,10,1,"ADMIN","{date}月度封账结果已更新。")));
    }
    private static Rule rule(String code,String label,String time,String type,int threshold,String recipient) {return new Rule(code,label,true,time,type,1,1,threshold,recipient,"{name}：{date}尚有{count}项记录需要处理，请进入系统核对。");}
}
