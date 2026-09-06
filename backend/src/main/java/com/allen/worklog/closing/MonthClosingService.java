package com.allen.worklog.closing;

import com.allen.worklog.common.*;
import com.allen.worklog.identity.CurrentUser;
import com.allen.worklog.reporting.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import static com.allen.worklog.reporting.ReportModels.*;

@Service
public class MonthClosingService {
    public record ScopeInput(String userId,LocalDate date,String workItemId,String objectType,String recordId,List<String> actions,String reason) {}
    public record RequestInput(String reason,List<ScopeInput> scopes) {}
    public record AmendmentInput(String reason,String requestId) {}
    public record PublishInput(String reason) {}
    private final JdbcClient jdbc;private final CurrentUser current;private final ReportingAccess access;private final PeriodGate gate;private final ReportFacts facts;private final AuditService audit;private final ObjectMapper json;
    public MonthClosingService(JdbcClient jdbc,CurrentUser current,ReportingAccess access,PeriodGate gate,ReportFacts facts,AuditService audit,ObjectMapper json) {this.jdbc=jdbc;this.current=current;this.access=access;this.gate=gate;this.facts=facts;this.audit=audit;this.json=json;}
    public Map<String,Object> get(LocalDate month) {
        month=month.withDayOfMonth(1);var s=access.current();var rows=jdbc.sql("SELECT * FROM accounting_period WHERE month_start=?").param(month).query().listOfRows();
        var result=map("month",YearMonth.from(month).toString(),"status",gate.status(month),"scheduledCloseAt",gate.configuredClose(month),"actualClosedAt",null,"latestVersionId",null,"activeAmendment",null,"versions",List.of(),"requests",List.of(),"scopes",List.of(),"canClose",false,"canUnlock",false,"canPublish",false);
        if(rows.isEmpty()) {result.put("canClose",s.canClose()&&!now().isBefore(gate.configuredClose(month)));return result;}
        var p=rows.getFirst();long id=((Number)p.get("id")).longValue();result.put("scheduledCloseAt",p.get("scheduled_close_at"));result.put("actualClosedAt",p.get("actual_closed_at"));result.put("latestVersionId",string(p.get("latest_version_id")));
        result.put("versions",versions(id));
        Object active=p.get("active_amendment_id");
        if(active!=null) {long aid=((Number)active).longValue();result.put("activeAmendment",amendment(aid));result.put("scopes",scopeRows(aid).stream().filter(r->s.canClose()||s.unlockItems().contains(longOrNull(r.get("workItemId")))||Objects.equals(r.get("userId"),Long.toString(s.userId()))).toList());}
        result.put("requests",requestRows(id).stream().filter(r->s.canClose()||Objects.equals(r.get("requestedBy"),Long.toString(s.userId()))||requestInScope(r,s)).toList());
        result.put("canClose",s.canClose()&&p.get("latest_version_id")==null&&!now().isBefore(dateTime(p.get("scheduled_close_at"))));
        result.put("canUnlock",s.canUnlock()&&"CLOSED".equals(p.get("status")));result.put("canPublish",s.canPublish()&&active!=null);return result;
    }
    @Transactional public Version closeScheduled(LocalDate month) {return close(month);}
    @Transactional public Version close(LocalDate month) {
        long actor=current.requireAdmin().id();month=month.withDayOfMonth(1);var period=lock(month);lockActor(actor);current.requireAdmin();
        var prior=findOperation("CLOSE:"+month);if(prior!=null)return prior;
        if(now().isBefore(dateTime(period.get("scheduled_close_at"))))throw error(409,"PERIOD_NOT_DUE","尚未到预定封账时点");
        return freeze(period,month,null,actor,"首次月度封账","CLOSE:"+month);
    }
    @Transactional public Map<String,Object> request(LocalDate month,RequestInput input) {
        var s=access.current();reason(input.reason());if(input.scopes()==null||input.scopes().isEmpty()||input.scopes().size()>100)throw error(422,"AMENDMENT_SCOPE","请列出1至100个具体授权范围");
        var p=lock(month);lockActor(s.userId());s=access.current();closed(p);
        for(var scope:input.scopes()) {shape(month,scope);if(!s.canUnlock()&&Long.parseLong(scope.userId())!=s.userId())throw forbidden();if(s.canUnlock()&&!s.canClose()&&!s.fullPerson(Long.valueOf(scope.userId()))&&!s.unlockItems().contains(longOrNull(scope.workItemId())))throw forbidden();}
        jdbc.sql("INSERT INTO amendment_request(period_id,requested_by,reason,scopes_json) VALUES(?,?,?,?)").params(p.get("id"),s.userId(),input.reason(),json.writeValueAsString(input.scopes())).update();long id=lastId();
        audit.record(s.userId(),"AMENDMENT_REQUEST","AMENDMENT_REQUEST",Long.toString(id),null,json.writeValueAsString(input),shortReason(input.reason()));
        return requestRows(((Number)p.get("id")).longValue()).stream().filter(r->r.get("id").equals(Long.toString(id))).findFirst().orElseThrow();
    }
    @Transactional public Map<String,Object> open(LocalDate month,AmendmentInput input) {
        var s=access.current();if(!s.canUnlock())throw forbidden();reason(input.reason());var p=lock(month);lockActor(s.userId());s=access.current();if(!s.canUnlock())throw forbidden();closed(p);
        if(p.get("active_amendment_id")!=null)return amendment(((Number)p.get("active_amendment_id")).longValue());
        if(input.requestId()!=null) {
            var req=requestRows(((Number)p.get("id")).longValue()).stream().filter(r->r.get("id").equals(input.requestId())).findFirst().orElseThrow(()->error(404,"AMENDMENT_REQUEST","申请不属于该月份"));
            if(!s.canClose()&&!requestInScope(req,s))throw forbidden();
        }
        jdbc.sql("INSERT INTO amendment_batch(period_id,reason,created_by) VALUES(?,?,?)").params(p.get("id"),input.reason(),s.userId()).update();long id=lastId();
        jdbc.sql("UPDATE accounting_period SET active_amendment_id=? WHERE id=?").params(id,p.get("id")).update();
        if(input.requestId()!=null)jdbc.sql("UPDATE amendment_request SET state='GRANTED' WHERE id=?").param(input.requestId()).update();
        audit.record(s.userId(),"AMENDMENT_OPEN","AMENDMENT",Long.toString(id),null,null,shortReason(input.reason()));return amendment(id);
    }
    @Transactional public Map<String,Object> grant(LocalDate month,long amendmentId,ScopeInput input) {
        var s=access.current();if(!s.canUnlock())throw forbidden();var p=lock(month);lockActor(s.userId());s=access.current();if(!s.canUnlock())throw forbidden();active(p,amendmentId);shape(month,input);
        long user=Long.parseLong(input.userId());Long item=longOrNull(input.workItemId()),record=longOrNull(input.recordId());Long after=null;
        if(jdbc.sql("SELECT COUNT(*) FROM app_user WHERE id=?").param(user).query(Integer.class).single()!=1)throw error(422,"SCOPE_USER","人员不存在");
        if(input.objectType().equals("BASE")) {if(!s.canClose())throw forbidden();}
        else {
            jdbc.sql("SELECT id FROM work_item WHERE id=? FOR SHARE").param(item).query(Long.class).optional().orElseThrow(()->error(422,"SCOPE_OBJECT","对象不存在"));
            s=access.current();if(!s.unlockItems().contains(item))throw forbidden();String table=input.objectType().equals("TIME")?"time_entry":"onsite_day";
            if(record!=null) {
                if(jdbc.sql("SELECT COUNT(*) FROM "+table+" e JOIN day_record d ON d.id=e.day_id WHERE e.id=? AND d.user_id=? AND d.work_date=?").params(record,user,input.date()).query(Integer.class).single()!=1)throw error(422,"SCOPE_RECORD","记录不属于指定人员与日期");
                // A destination project grant may differ from the original project, but never from the exact record/person/date.
            } else after=jdbc.sql("SELECT COALESCE(MAX(id),0) FROM "+table).query(Long.class).single();
        }
        jdbc.sql("INSERT INTO unlock_scope(amendment_id,user_id,work_date,work_item_id,object_type,record_id,new_after_record_id,actions,reason,authorized_by) VALUES(?,?,?,?,?,?,?,?,?,?)")
            .params(amendmentId,user,input.date(),item,input.objectType(),record,after,json.writeValueAsString(input.actions()),input.reason(),s.userId()).update();long id=lastId();
        audit.record(s.userId(),"UNLOCK_SCOPE_GRANTED","UNLOCK_SCOPE",Long.toString(id),null,json.writeValueAsString(input),shortReason(input.reason()));
        return scopeRows(amendmentId).stream().filter(r->r.get("id").equals(Long.toString(id))).findFirst().orElseThrow();
    }
    @Transactional public Version publish(LocalDate month,long amendmentId,PublishInput input) {
        long actor=current.requireAdmin().id();reason(input.reason());var p=lock(month);lockActor(actor);current.requireAdmin();var old=findOperation("AMENDMENT:"+amendmentId);if(old!=null){if(!old.month().equals(YearMonth.from(month).toString()))throw forbidden();return old;}
        active(p,amendmentId);Version result=freeze(p,month,amendmentId,actor,input.reason(),"AMENDMENT:"+amendmentId);
        jdbc.sql("UPDATE unlock_scope SET active=FALSE WHERE amendment_id=?").param(amendmentId).update();
        jdbc.sql("UPDATE amendment_batch SET state='PUBLISHED',published_at=UTC_TIMESTAMP(6) WHERE id=?").param(amendmentId).update();
        jdbc.sql("UPDATE accounting_period SET active_amendment_id=NULL WHERE id=?").param(p.get("id")).update();return result;
    }
    public Map<String,Object> diff(LocalDate month,long versionId) {
        var s=access.current();var target=jdbc.sql("SELECT v.previous_version_id,p.month_start FROM monthly_report_version v JOIN accounting_period p ON p.id=v.period_id WHERE v.id=?").param(versionId).query().listOfRows().stream().findFirst().orElseThrow(()->error(404,"VERSION_NOT_FOUND","月报版本不存在"));
        if(!Objects.equals(target.get("month_start").toString(),month.toString()))throw error(404,"VERSION_NOT_FOUND","月报版本不属于该月份");Long before=longOrNull(target.get("previous_version_id"));
        return difference(before==null?List.of():facts.frozen(before),facts.frozen(versionId),before,versionId,s);
    }
    @Transactional public Map<String,Object> previewDiff(LocalDate month,long amendmentId) {
        var s=access.current();var p=lock(month);active(p,amendmentId);Long before=longOrNull(p.get("latest_version_id"));
        return difference(facts.frozen(before),facts.current(month,month.plusMonths(1).minusDays(1)),before,null,s);
    }
    private Map<String,Object> difference(List<ReportFacts.Fact> before,List<ReportFacts.Fact> after,Long from,Long to,ReportingAccess.Scope s) {
        Map<String,ReportFacts.Fact> a=index(before),b=index(after);Set<String> keys=new LinkedHashSet<>(a.keySet());keys.addAll(b.keySet());List<Map<String,Object>> rows=new ArrayList<>();
        for(String key:keys) {var old=a.get(key);var next=b.get(key);var f=next==null?old:next;
            var od=old!=null&&s.detail(old.userId(),old.workItemId())?ReportService.publicFact(old,s):null;var nd=next!=null&&s.detail(next.userId(),next.workItemId())?ReportService.publicFact(next,s):null;
            if(od==null&&nd==null||Objects.equals(od,nd))continue;
            f=nd!=null?next:old;
            rows.add(map("kind",f.kind(),"recordId",string(f.recordId()),"userId",string(f.userId()),"workItemId",string(f.workItemId()),"change",old==null?"ADD":next==null||"CANCEL".equals(next.data().get("action"))?"CANCEL":"CHANGE","before",od,"after",nd));
        }
        return map("fromVersionId",string(from),"toVersionId",string(to),"rows",rows);
    }
    private static Map<String,ReportFacts.Fact> index(List<ReportFacts.Fact> list) {var result=new LinkedHashMap<String,ReportFacts.Fact>();for(var f:list)if(Set.of("TIME","ONSITE","DAY").contains(f.kind()))result.put(f.kind()+":"+(f.kind().equals("DAY")?f.userId()+":"+f.date():f.recordId()),f);return result;}
    private Version freeze(Map<String,Object> p,LocalDate month,Long amendment,long actor,String reason,String operation) {
        long period=((Number)p.get("id")).longValue();Long previous=longOrNull(p.get("latest_version_id"));int next=jdbc.sql("SELECT COALESCE(MAX(version_no),0)+1 FROM monthly_report_version WHERE period_id=?").param(period).query(Integer.class).single();
        // Lock states share the period transaction; unresolved/canceled records keep their own states and snapshot exclusions.
        for(String[] tables:List.of(new String[]{"time_entry","time_entry_revision"},new String[]{"onsite_day","onsite_day_revision"}))jdbc.sql("UPDATE "+tables[1]+" r JOIN "+tables[0]+" e ON e.current_revision_id=r.id JOIN day_record d ON d.id=e.day_id SET r.state='LOCKED' WHERE d.work_date>=? AND d.work_date<? AND r.state='APPROVED' AND r.dispute_open=FALSE").params(month,month.plusMonths(1)).update();
        jdbc.sql("INSERT INTO monthly_report_version(period_id,version_no,operation_key,previous_version_id,amendment_id,reason,cutoff_at,policy_version) VALUES(?,?,?,?,?,?,UTC_TIMESTAMP(6),'STANDARD_DAILY_480_HALF_UP_V1')")
            .params(period,next,operation,previous,amendment,reason).update();long id=lastId();facts.freeze(id,month,month.plusMonths(1).minusDays(1));
        jdbc.sql("UPDATE accounting_period SET status='CLOSED',actual_closed_at=COALESCE(actual_closed_at,UTC_TIMESTAMP(6)),latest_version_id=? WHERE id=?").params(id,period).update();
        audit.record(actor,amendment==null?"MONTH_CLOSED":"MONTH_REPUBLISHED","MONTH_REPORT",Long.toString(id),null,json.writeValueAsString(map("month",month,"version",next)),shortReason(reason));return findOperation(operation);
    }
    private Map<String,Object> lock(LocalDate month) {
        if(month.getDayOfMonth()!=1)throw error(422,"MONTH_FORMAT","月份格式无效");
        jdbc.sql("INSERT INTO accounting_period(month_start,scheduled_close_at) VALUES(?,?) ON DUPLICATE KEY UPDATE id=id").params(month,gate.configuredClose(month)).update();
        return jdbc.sql("SELECT * FROM accounting_period WHERE month_start=? FOR UPDATE").param(month).query().singleRow();
    }
    private List<Version> versions(long period) {return jdbc.sql("SELECT v.*,p.month_start FROM monthly_report_version v JOIN accounting_period p ON p.id=v.period_id WHERE p.id=? ORDER BY v.version_no").param(period).query((rs,n)->new Version(rs.getString("id"),YearMonth.from(rs.getObject("month_start",LocalDate.class)).toString(),rs.getInt("version_no"),rs.getObject("cutoff_at",LocalDateTime.class),rs.getObject("published_at",LocalDateTime.class),rs.getString("reason"),rs.getString("previous_version_id"))).list();}
    private Version findOperation(String op) {Long period=jdbc.sql("SELECT period_id FROM monthly_report_version WHERE operation_key=?").param(op).query(Long.class).optional().orElse(null);if(period==null)return null;return versions(period).stream().filter(v->jdbc.sql("SELECT operation_key FROM monthly_report_version WHERE id=?").param(v.id()).query(String.class).single().equals(op)).findFirst().orElseThrow();}
    private Map<String,Object> amendment(long id) {return jdbc.sql("SELECT CAST(b.id AS CHAR) id,DATE_FORMAT(p.month_start,'%Y-%m') month,b.state,b.reason,CAST(b.created_by AS CHAR) createdBy,b.created_at createdAt FROM amendment_batch b JOIN accounting_period p ON p.id=b.period_id WHERE b.id=?").param(id).query().singleRow();}
    private List<Map<String,Object>> scopeRows(long id) {return jdbc.sql("SELECT CAST(id AS CHAR) id,CAST(amendment_id AS CHAR) amendmentId,CAST(user_id AS CHAR) userId,work_date date,CAST(work_item_id AS CHAR) workItemId,object_type objectType,CAST(record_id AS CHAR) recordId,CAST(new_after_record_id AS CHAR) newAfterRecordId,actions,reason,CAST(authorized_by AS CHAR) authorizedBy,authorized_at authorizedAt,active FROM unlock_scope WHERE amendment_id=? ORDER BY unlock_scope.id").param(id).query().listOfRows().stream().map(r->{var row=new LinkedHashMap<>(r);row.put("actions",json.readValue(r.get("actions").toString(),List.class));return (Map<String,Object>)row;}).toList();}
    private List<Map<String,Object>> requestRows(long period) {return jdbc.sql("SELECT CAST(r.id AS CHAR) id,DATE_FORMAT(p.month_start,'%Y-%m') month,r.state,r.reason,CAST(r.requested_by AS CHAR) requestedBy,r.created_at createdAt,r.scopes_json FROM amendment_request r JOIN accounting_period p ON p.id=r.period_id WHERE period_id=? ORDER BY r.id DESC").param(period).query().listOfRows().stream().map(r->{var row=new LinkedHashMap<>(r);row.put("scopes",json.readValue(row.remove("scopes_json").toString(),new TypeReference<List<ScopeInput>>(){}));return (Map<String,Object>)row;}).toList();}
    @SuppressWarnings("unchecked") private boolean requestInScope(Map<String,Object> r,ReportingAccess.Scope s) {return ((List<ScopeInput>)r.get("scopes")).stream().allMatch(i->!"BASE".equals(i.objectType())&&s.unlockItems().contains(longOrNull(i.workItemId())));}
    private static void shape(LocalDate month,ScopeInput i) {
        if(i==null||i.date()==null||!i.date().withDayOfMonth(1).equals(month)||i.userId()==null||!i.userId().matches("[1-9][0-9]*")||i.objectType()==null||!Set.of("TIME","ONSITE","BASE").contains(i.objectType()))throw error(422,"SCOPE_SHAPE","请指定本月单个人员、日期与对象");
        reason(i.reason());if(i.actions()==null||i.actions().isEmpty()||new HashSet<>(i.actions()).size()!=i.actions().size()||!Set.of("ADD","SAVE","SUBMIT","APPROVE","REJECT","CORRECT","DISPUTE","TRANSFER","BASE").containsAll(i.actions()))throw error(422,"SCOPE_ACTION","授权动作无效");
        if(i.objectType().equals("BASE")) {if(i.workItemId()!=null||i.recordId()!=null||!i.actions().equals(List.of("BASE")))throw error(422,"SCOPE_BASE","基数授权只允许BASE动作且无对象ID");}
        else if(i.workItemId()==null||!i.workItemId().matches("[1-9][0-9]*")||(i.recordId()!=null&&!i.recordId().matches("[1-9][0-9]*"))||i.actions().contains("BASE"))throw error(422,"SCOPE_OBJECT","请指定项目/非项目对象和合法记录ID");
    }
    private static void closed(Map<String,Object> p) {if(!"CLOSED".equals(p.get("status")))throw error(409,"PERIOD_NOT_CLOSED","月报正式封账后才能申请修订");}
    private static void active(Map<String,Object> p,long id) {closed(p);if(!Objects.equals(longOrNull(p.get("active_amendment_id")),id))throw error(409,"AMENDMENT_NOT_OPEN","该修订批次已结束或不属于本月");}
    private void lockActor(long actor) {jdbc.sql("SELECT id FROM app_user WHERE id=? FOR SHARE").param(actor).query(Long.class).single();}
    private LocalDateTime now() {return jdbc.sql("SELECT UTC_TIMESTAMP(6)").query(LocalDateTime.class).single();}
    private long lastId() {return jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();}
    private static LocalDateTime dateTime(Object value) {return value instanceof java.sql.Timestamp t?t.toLocalDateTime():value instanceof LocalDateTime t?t:LocalDateTime.parse(value.toString().replace(' ','T'));}
    private static Long longOrNull(Object value) {return value==null?null:Long.valueOf(value.toString());}
    private static String string(Object value) {return value==null?null:value.toString();}
    private static void reason(String value) {if(value==null||value.isBlank()||value.length()>1000)throw error(422,"REASON_REQUIRED","请填写1至1000字原因");}
    private static String shortReason(String s) {return s.substring(0,Math.min(500,s.length()));}
    private static ApiException forbidden() {return error(403,"AMENDMENT_SCOPE","无权授权该范围");}
    private static ApiException error(int status,String code,String message) {return new ApiException(status,code,message);}
}
