package com.allen.worklog.approval;

import com.allen.worklog.closing.PeriodGate;
import com.allen.worklog.common.ApiException;
import com.allen.worklog.common.AuditService;
import com.allen.worklog.costing.CostingService;
import com.allen.worklog.identity.CurrentUser;
import com.allen.worklog.operations.ConfigService;
import com.allen.worklog.operations.NotificationService;
import com.allen.worklog.reporting.ReportingAccess;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Component
public class ApprovalData {
    final JdbcClient jdbc; final CurrentUser current; final PeriodGate periods; final AuditService audit;
    final ObjectMapper json; final CostingService costing; final ConfigService config; final NotificationService notifications;
    final ReportingAccess access;
    final TransactionTemplate tx;
    public ApprovalData(JdbcClient jdbc,CurrentUser current,PeriodGate periods,AuditService audit,ObjectMapper json,
                        CostingService costing,ConfigService config,NotificationService notifications,ReportingAccess access,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.current=current;this.periods=periods;this.audit=audit;this.json=json;this.costing=costing;
        this.config=config;this.notifications=notifications;this.access=access;this.tx=new TransactionTemplate(manager);
    }
    static final String TIME_SELECT="""
        SELECT 'TIME' record_kind,e.id record_id,r.id revision_id,d.id day_id,d.user_id,d.work_date,r.work_item_id,
        w.name item_name,w.type item_type,r.kind entry_kind,r.action,r.state,r.minutes,r.content,r.red_reason,
        (e.current_revision_id=r.id) is_current,r.dispute_open,r.approved_by,r.cost_amount,r.cost_daily_rate,
        r.cost_level_code,r.cost_policy_version,r.correction_request_id,r.cost_rate_id,r.cost_level_history_id,
        COALESCE(r.owner_department_id,(SELECT h.owner_department_id FROM work_item_history h WHERE h.work_item_id=w.id
          AND h.valid_from<=d.work_date AND (h.valid_to IS NULL OR h.valid_to>d.work_date)),w.owner_department_id) owner_department_id
        FROM time_entry_revision r JOIN time_entry e ON e.id=r.entry_id JOIN day_record d ON d.id=e.day_id
        JOIN work_item w ON w.id=r.work_item_id
        """;
    static final String ONSITE_SELECT="""
        SELECT 'ONSITE' record_kind,e.id record_id,r.id revision_id,d.id day_id,d.user_id,d.work_date,r.work_item_id,
        w.name item_name,w.type item_type,NULL entry_kind,r.action,r.state,0 minutes,r.reason content,'' red_reason,
        (e.current_revision_id=r.id) is_current,r.dispute_open,r.approved_by,r.cost_amount,r.cost_daily_rate,
        NULL cost_level_code,r.cost_policy_version,r.correction_request_id,r.cost_rate_id,NULL cost_level_history_id,
        COALESCE(r.owner_department_id,(SELECT h.owner_department_id FROM work_item_history h WHERE h.work_item_id=w.id
          AND h.valid_from<=d.work_date AND (h.valid_to IS NULL OR h.valid_to>d.work_date)),w.owner_department_id) owner_department_id
        FROM onsite_day_revision r JOIN onsite_day e ON e.id=r.onsite_id JOIN day_record d ON d.id=e.day_id
        JOIN work_item w ON w.id=r.work_item_id
        """;
    Revision revision(String kind,long id) {
        return jdbc.sql(select(kind)+" WHERE r.id=?").param(id).query((rs,n)->revision(rs)).optional().orElseThrow(()->missing("内容版本"));
    }
    Revision currentRevision(String kind,long recordId) {
        return jdbc.sql(select(kind)+" WHERE e.id=? AND r.id=e.current_revision_id").param(recordId).query((rs,n)->revision(rs)).optional().orElseThrow(()->missing("当前记录"));
    }
    List<Revision> dayRevisions(long user,LocalDate day,String kind) {
        return jdbc.sql(select(kind)+" WHERE d.user_id=? AND d.work_date=? AND r.id=e.current_revision_id ORDER BY e.id")
            .params(user,day).query((rs,n)->revision(rs)).list();
    }
    private String select(String kind) { checkKind(kind);return kind.equals("TIME")?TIME_SELECT:ONSITE_SELECT; }
    private Revision revision(ResultSet r) throws SQLException {
        return new Revision(r.getString("record_kind"),r.getLong("record_id"),r.getLong("revision_id"),r.getLong("day_id"),r.getLong("user_id"),
            r.getObject("work_date",LocalDate.class),r.getLong("work_item_id"),r.getString("item_name"),r.getString("item_type"),r.getString("entry_kind"),
            r.getString("action"),r.getString("state"),r.getInt("minutes"),r.getString("content"),r.getString("red_reason"),r.getBoolean("is_current"),
            r.getBoolean("dispute_open"),r.getObject("approved_by",Long.class),r.getBigDecimal("cost_amount"),r.getBigDecimal("cost_daily_rate"),
            r.getString("cost_level_code"),r.getString("cost_policy_version"),r.getObject("correction_request_id",Long.class),r.getLong("owner_department_id"),r.getObject("cost_rate_id",Long.class),r.getObject("cost_level_history_id",Long.class));
    }
    Pack pack(long id,boolean lock) {
        if(lock)jdbc.sql("SELECT id FROM approval_package WHERE id=? FOR UPDATE").param(id).query(Long.class).optional().orElseThrow(()->missing("审批单"));
        return jdbc.sql("""
            SELECT p.*,u.name user_name,u.employee_no,w.name item_name,w.type item_type,a.name approver_name
            FROM approval_package p JOIN app_user u ON u.id=p.user_id JOIN work_item w ON w.id=p.work_item_id JOIN app_user a ON a.id=p.approver_user_id WHERE p.id=?
            """).param(id).query((r,n)->new Pack(r.getLong("id"),r.getLong("user_id"),r.getString("employee_no"),r.getString("user_name"),r.getObject("week_start",LocalDate.class),
                r.getLong("work_item_id"),r.getString("item_name"),r.getString("item_type"),r.getLong("approver_user_id"),r.getString("approver_name"),
                r.getString("route_reason"),r.getObject("approval_deadline",LocalDateTime.class),r.getInt("row_version"))).optional().orElseThrow(()->missing("审批单"));
    }
    List<Item> items(long packageId) {
        return jdbc.sql("SELECT id FROM approval_item WHERE package_id=? ORDER BY id").param(packageId).query(Long.class).list().stream().map(id->item(id,false)).toList();
    }
    Item item(long id,boolean lock) {
        return jdbc.sql("SELECT * FROM approval_item WHERE id=?"+(lock?" FOR UPDATE":"")).param(id).query((r,n)->{
            Long time=r.getObject("time_revision_id",Long.class);String kind=time==null?"ONSITE":"TIME";
            return new Item(r.getLong("id"),r.getLong("package_id"),revision(kind,time==null?r.getLong("onsite_revision_id"):time),r.getString("status"),
                r.getObject("handled_by",Long.class),r.getObject("handled_at",LocalDateTime.class),r.getString("reason"),r.getObject("approval_deadline",LocalDateTime.class));
        }).optional().orElseThrow(()->missing("审批项"));
    }
    void requireVisible(Pack pack,CurrentUser.User actor) {
        if(actor.id()==pack.user()||actor.id()==pack.approver()||actor.canManage())return;
        if(jdbc.sql("SELECT COUNT(*) FROM approval_item WHERE package_id=? AND handled_by=?").params(pack.id(),actor.id()).query(Integer.class).single()==0)
            throw forbidden("无权查看该审批单");
    }
    boolean seesItem(Pack pack,Item item,CurrentUser.User actor) {
        return actor.canManage()||actor.id()==pack.user()||actor.id()==pack.approver()||Objects.equals(item.handledBy(),actor.id());
    }
    boolean canDecide(Pack pack,Item item,CurrentUser.User actor) {
        return actor.id()==pack.approver()&&actor.id()!=pack.user()&&item.status().equals("PENDING")&&item.rev().current()&&(!item.rev().disputeOpen()||!hasOpenDispute(item.rev()));
    }
    boolean hasOpenDispute(Revision r) {return dDisputeCount(r,"c.state IN ('OPEN','ESCALATED')")>0;}
    boolean correctionRequired(Revision r) {return r.disputeOpen()&&!hasOpenDispute(r)&&dDisputeCount(r,"c.state='RESOLVED' AND c.outcome='NEEDS_CORRECTION'")>0;}
    private int dDisputeCount(Revision r,String condition){return jdbc.sql("SELECT COUNT(*) FROM dispute_case c JOIN approval_item a ON a.id=c.approval_item_id WHERE "+
        (r.kind().equals("TIME")?"a.time_revision_id":"a.onsite_revision_id")+"=? AND "+condition).param(r.id()).query(Integer.class).single();}
    void lockActor(long actor) {
        jdbc.sql("SELECT id FROM app_user WHERE id=? FOR SHARE").param(actor).query(Long.class).single();current.require();
    }
    void lockDays(Collection<Revision> revisions,String action) {
        var ordered=revisions.stream().sorted(Comparator.comparing(Revision::date).thenComparingLong(Revision::user).thenComparingLong(Revision::dayId)).toList();
        for(Revision r:ordered)gate(r,action);
        for(LocalDate date:ordered.stream().map(Revision::date).distinct().toList())
            jdbc.sql("SELECT work_date FROM work_calendar WHERE work_date=? FOR SHARE").param(date).query(LocalDate.class).optional();
        var users=new TreeSet<Long>();users.add(current.require().id());ordered.forEach(r->users.add(r.user()));
        for(long user:users)jdbc.sql("SELECT id FROM app_user WHERE id=? FOR SHARE").param(user).query(Long.class).single();
        current.require();
        for(long day:ordered.stream().map(Revision::dayId).distinct().toList())jdbc.sql("SELECT id FROM day_record WHERE id=? FOR UPDATE").param(day).query(Long.class).single();
    }
    void gate(Revision r,String action) { periods.lockForAction(r.date(),r.user(),r.item(),r.kind(),r.recordId(),action); }
    void touch(Revision r) {jdbc.sql("UPDATE day_record SET row_version=row_version+1 WHERE id=?").param(r.dayId()).update();}
    void touchPack(long id) {jdbc.sql("UPDATE approval_package SET row_version=row_version+1 WHERE id=?").param(id).update();}
    String table(String kind) {checkKind(kind);return kind.equals("TIME")?"time_entry_revision":"onsite_day_revision";}
    void state(Revision r,String state) {jdbc.sql("UPDATE "+table(r.kind())+" SET state=? WHERE id=?").params(state,r.id()).update();}
    Revision draftCopy(Revision r,String action,Long correctionId) {
        if(r.kind().equals("TIME")) {
            jdbc.sql("""
                INSERT INTO time_entry_revision(entry_id,revision_no,action,work_item_id,kind,minutes,content,red_reason,department_id,state,correction_request_id)
                SELECT r.entry_id,(SELECT MAX(x.revision_no)+1 FROM time_entry_revision x WHERE x.entry_id=r.entry_id),?,r.work_item_id,r.kind,r.minutes,r.content,r.red_reason,r.department_id,'DRAFT',?
                FROM time_entry_revision r WHERE r.id=?
                """).params(action,correctionId,r.id()).update();
        } else {
            jdbc.sql("""
                INSERT INTO onsite_day_revision(onsite_id,revision_no,action,work_item_id,reason,state,correction_request_id)
                SELECT r.onsite_id,(SELECT MAX(x.revision_no)+1 FROM onsite_day_revision x WHERE x.onsite_id=r.onsite_id),?,r.work_item_id,r.reason,'DRAFT',?
                FROM onsite_day_revision r WHERE r.id=?
                """).params(action,correctionId,r.id()).update();
        }
        long next=lastId();
        jdbc.sql("UPDATE "+(r.kind().equals("TIME")?"time_entry":"onsite_day")+" SET current_revision_id=? WHERE id=?").params(next,r.recordId()).update();
        return revision(r.kind(),next);
    }
    static void checkKind(String kind) {if(!Set.of("TIME","ONSITE").contains(kind==null?"":kind))throw bad("INVALID_KIND","请选择工时记录或现场日记录");}
    long lastId(){return jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();}
    String name(Long user){return user==null?null:jdbc.sql("SELECT name FROM app_user WHERE id=?").param(user).query(String.class).optional().orElse(null);}
    boolean active(Long user) {return user!=null&&jdbc.sql("SELECT COUNT(*) FROM app_user WHERE id=? AND status='ACTIVE'").param(user).query(Integer.class).single()>0;}
    void requireOtherActive(long user,long employee) {if(user==employee)throw bad("SELF_APPROVAL_FORBIDDEN","不能审批自己填报的记录，请选择其他审批人");if(!active(user))throw bad("APPROVER_UNRESOLVED","指定接收人不存在或已停用");}
    Route route(long user,long item) {
        var w=jdbc.sql("SELECT type,default_approver_id,status FROM work_item WHERE id=?").param(item)
            .query((r,n)->new ObjectRoute(r.getString("type"),r.getObject("default_approver_id",Long.class),r.getString("status"))).optional().orElseThrow(()->missing("项目或事项"));
        if(!w.status().equals("ACTIVE"))throw bad("WORK_ITEM_INACTIVE","项目或事项已停用，请管理员核实后重新开放补报");
        Long candidate=w.approver();
        if(candidate!=null&&candidate!=user&&active(candidate))return new Route(candidate,w.approver(),"项目或事项的默认审批人");
        var dept=jdbc.sql("SELECT d.designated_manager_id,d.supervisor_user_id FROM app_user u JOIN department d ON d.id=u.department_id WHERE u.id=?")
            .param(user).query((r,n)->new DepartmentRoute(r.getObject(1,Long.class),r.getObject(2,Long.class))).single();
        if(candidate!=null&&candidate==user&&w.type().equals("PROJECT")&&dept.manager()!=null&&dept.manager()!=user&&active(dept.manager()))
            return new Route(dept.manager(),w.approver(),"本人不能审批，交由所属部门的指定负责人审批");
        if(candidate!=null&&candidate==user&&(!w.type().equals("PROJECT")||Objects.equals(dept.manager(),user))&&dept.supervisor()!=null&&dept.supervisor()!=user&&active(dept.supervisor()))
            return new Route(dept.supervisor(),w.approver(),"本人不能审批，交由所属部门的分管领导审批");
        Long designated=jdbc.sql("SELECT approver_user_id FROM approval_designation WHERE user_id=? AND work_item_id=?").params(user,item).query(Long.class).optional().orElse(null);
        if(designated!=null&&designated!=user&&active(designated))return new Route(designated,w.approver(),"由公司为该员工和项目或事项指定的审批人处理");
        throw bad("APPROVER_UNRESOLVED","尚未安排有效审批人，请联系管理员设置。审批人不能是填报人本人");
    }
    Route routeForSubmission(long user,LocalDate week,long item) {
        var rows=jdbc.sql("""
            SELECT p.approver_user_id,p.default_approver_id,p.route_reason FROM approval_package p
            WHERE p.user_id=? AND p.week_start=? AND p.work_item_id=? AND EXISTS(SELECT 1 FROM approval_item a WHERE a.package_id=p.id AND a.status='PENDING')
            """).params(user,week,item).query((r,n)->new Route(r.getLong(1),r.getObject(2,Long.class),r.getString(3))).list();
        if(rows.isEmpty())return route(user,item);
        Route route=rows.getFirst();requireOtherActive(route.approver(),user);return route;
    }
    boolean canSeeCost(long user,long item) {
        return access.canReadCost(user,item);
    }
    Map<String,Object> cost(Revision r,boolean estimate) {
        if(!estimate)return map("amount",decimal(r.amount()),"dailyRate",decimal(r.dailyRate()),"level",r.level(),"policyVersion",r.policy(),"rateId",str(r.rateId()),"levelHistoryId",str(r.levelHistoryId()));
        if(r.action().equals("CANCEL")||r.kind().equals("TIME")&&!r.itemType().equals("PROJECT"))return map("amount","0.00","dailyRate",null,"level",null,"policyVersion","NO_PROJECT_COST_V1");
        if(r.kind().equals("ONSITE")){var c=costing.resolveOnsite(r.date());return map("amount",decimal(c.amount()),"dailyRate",decimal(c.dailyRate()),"level",null,"policyVersion",c.policyVersion(),"rateId",str(c.rateId()),"levelHistoryId",null);}
        var c=costing.resolveLabor(r.user(),r.date(),r.minutes());return map("amount",decimal(c.amount()),"dailyRate",decimal(c.dailyRate()),"level",c.levelCode(),"policyVersion",c.policyVersion(),"rateId",str(c.rateId()),"levelHistoryId",str(c.levelHistoryId()));
    }
    void audit(long actor,String action,String type,long id,Object before,Object after,String reason){audit.record(actor,action,type,Long.toString(id),before==null?null:json.writeValueAsString(before),after==null?null:json.writeValueAsString(after),reason);}
    static Map<String,Object> map(Object... pairs){var map=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)map.put((String)pairs[i],pairs[i+1]);return map;}
    static String str(Long id){return id==null?null:id.toString();}
    static String instant(LocalDateTime time){return time==null?null:time.toInstant(ZoneOffset.UTC).toString();}
    static String decimal(BigDecimal value){return value==null?null:value.toPlainString();}
    static ApiException bad(String code,String message){return new ApiException(422,code,message);}
    static ApiException forbidden(String message){return new ApiException(403,"SCOPE_FORBIDDEN",message);}
    static ApiException missing(String label){return new ApiException(404,"NOT_FOUND",label+"不存在");}
    static String required(String value,int max,String name){if(value==null||value.isBlank()||value.length()>max)throw bad("INVALID_INPUT",name+"不能为空且最多"+max+"字");return value.strip();}
    static long id(String value){return com.allen.worklog.work.DayRules.id(value);}
    static void key(String value){if(value==null||!value.matches("[A-Za-z0-9_-]{1,64}"))throw bad("IDEMPOTENCY_REQUIRED","请提供有效 Idempotency-Key");}
    <T> T receipt(String command,String key,Object body,Class<T> type){
        key(key);var row=jdbc.sql("SELECT request_hash,response_json FROM command_receipt WHERE actor_id=? AND command_type=? AND idempotency_key=?")
            .params(current.require().id(),command,key).query((r,n)->new Receipt(r.getString(1),r.getString(2))).optional();
        if(row.isEmpty())return null;if(!row.get().hash().equals(hash(json.writeValueAsString(body))))throw new ApiException(409,"IDEMPOTENCY_CONFLICT","同一请求标识不能携带不同内容");
        return json.readValue(row.get().response(),type);
    }
    void saveReceipt(String command,String key,Object body,Object result){jdbc.sql("INSERT INTO command_receipt(actor_id,command_type,idempotency_key,request_hash,response_json) VALUES(?,?,?,?,?)")
        .params(current.require().id(),command,key,hash(json.writeValueAsString(body)),json.writeValueAsString(result)).update();}
    static String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    record Revision(String kind,long recordId,long id,long dayId,long user,LocalDate date,long item,String itemName,String itemType,String entryKind,
                    String action,String state,int minutes,String content,String redReason,boolean current,boolean disputeOpen,Long approvedBy,
                    BigDecimal amount,BigDecimal dailyRate,String level,String policy,Long correctionId,long ownerDepartment,Long rateId,Long levelHistoryId) {}
    record Pack(long id,long user,String employeeNo,String userName,LocalDate week,long item,String itemName,String itemType,long approver,
                String approverName,String routeReason,LocalDateTime deadline,int version) {}
    record Item(long id,long packageId,Revision rev,String status,Long handledBy,LocalDateTime handledAt,String reason,LocalDateTime deadline) {}
    record Route(long approver,Long defaultApprover,String reason) {}
    private record ObjectRoute(String type,Long approver,String status) {}
    record DepartmentRoute(Long manager,Long supervisor) {}
    private record Receipt(String hash,String response) {}
}
