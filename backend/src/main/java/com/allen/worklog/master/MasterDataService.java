package com.allen.worklog.master;

import com.allen.worklog.closing.PeriodGate;
import com.allen.worklog.common.ApiException;
import com.allen.worklog.common.AuditService;
import com.allen.worklog.identity.CurrentUser;
import com.allen.worklog.work.DayRules;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static com.allen.worklog.master.MasterDtos.*;
import static com.allen.worklog.master.MasterValidation.*;

@Service
public class MasterDataService {
    private static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE");
    private static final Set<String> LEVELS = Set.of("JUNIOR", "MIDDLE", "SENIOR");
    private static final Set<String> ROLES = Set.of("EMPLOYEE", "ADMIN", "PM", "DEPARTMENT_MANAGER", "LEADER");
    private final JdbcClient jdbc;
    private final CurrentUser current;
    private final PeriodGate periods;
    private final AuditService audit;
    private final ObjectMapper json;
    private final com.allen.worklog.operations.ConfigService configs;

    public MasterDataService(JdbcClient jdbc, CurrentUser current, PeriodGate periods, AuditService audit, ObjectMapper json,com.allen.worklog.operations.ConfigService configs) {
        this.jdbc = jdbc; this.current = current; this.periods = periods; this.audit = audit; this.json=json;this.configs=configs;
    }

    public Catalog catalog() {
        current.require();
        var items = jdbc.sql("""
            SELECT w.*,u.name approver_name FROM work_item w
            LEFT JOIN app_user u ON u.id=w.default_approver_id WHERE w.status='ACTIVE' ORDER BY w.id
            """).query((rs,n) -> new CatalogItem(rs.getString("id"),rs.getString("code"),rs.getString("name"),
                rs.getString("type"),rs.getString("owner_department_id"),rs.getString("approver_name"))).list();
        var departments = jdbc.sql("SELECT id,code,name FROM department WHERE status='ACTIVE' ORDER BY id")
            .query((rs,n) -> new CatalogDepartment(rs.getString("id"),rs.getString("code"),rs.getString("name"))).list();
        return new Catalog(items,departments);
    }

    public List<UserView> users() {
        current.requireAdmin();
        return jdbc.sql("""
            SELECT u.*,d.name department_name,
              GREATEST((SELECT MAX(valid_from) FROM user_department_history h WHERE h.user_id=u.id),
                       (SELECT MAX(valid_from) FROM user_level_history h WHERE h.user_id=u.id)) effective_from
            FROM app_user u JOIN department d ON d.id=u.department_id ORDER BY u.id
            """).query((rs,n) -> user(rs)).list();
    }

    private UserView user(ResultSet rs) throws SQLException {
        return new UserView(rs.getString("id"),rs.getString("employee_no"),rs.getString("wecom_userid"),
            rs.getString("name"),rs.getString("department_id"),rs.getString("department_name"),rs.getString("level_code"),
            rs.getString("status"),activeRoles(rs.getLong("id")),rs.getObject("effective_from",LocalDate.class),pendingApprovals(rs.getLong("id")));
    }
    private int pendingApprovals(long user){return jdbc.sql("SELECT (SELECT COUNT(*) FROM approval_item a JOIN approval_package p ON p.id=a.package_id WHERE p.approver_user_id=? AND a.status='PENDING')+(SELECT COUNT(*) FROM correction_request c WHERE c.approver_user_id=? AND c.state='REQUESTED')").params(user,user).query(Integer.class).single();}

    private List<String> activeRoles(long userId) {
        return jdbc.sql("SELECT DISTINCT role_code FROM role_grant WHERE user_id=? AND revoked_at IS NULL AND valid_from<=? AND (valid_to IS NULL OR valid_to>?) ORDER BY role_code")
            .params(userId,today(),today()).query(String.class).list();
    }

    @Transactional
    public UserView saveUser(Long userId, UserInput input) {
        long actorId=current.requireAdmin().id();
        String employee = employeeNo(input.employeeNo());
        String name = text(input.name(),100,"姓名");
        String wecom = input.wecomUserid() == null || input.wecomUserid().isBlank() ? null : text(input.wecomUserid(),128,"企微 UserID");
        String status = oneOf(input.status(),STATUSES,"人员状态");
        String level = oneOf(input.level(),LEVELS,"职级");
        long departmentId = id(input.departmentId(),"部门");
        LocalDate from = currentEffectiveDate(input.effectiveFrom());
        Set<String> roles = userId!=null&&input.roles()==null?null:checkedRoles(input.roles());
        UserView initial=userId==null?null:getUser(userId);
        boolean historicalChange=initial==null || !initial.departmentId().equals(input.departmentId()) || !initial.level().equals(level);
        if (historicalChange) periods.lockHistory(from,null);
        else if (!initial.status().equals(status)) periods.lockHistory(today(),null);
        lockAdmin(actorId);
        requireDepartment(departmentId);
        UserView before = null;
        if (userId == null) {
            jdbc.sql("INSERT INTO app_user(employee_no,wecom_userid,name,status,department_id,level_code) VALUES (?,?,?,?,?,?)")
                .params(employee,wecom,name,status,departmentId,level).update();
            userId = lastId();
        } else {
            lockUser(userId);
            before = getUser(userId);
            if (!before.equals(initial)) throw new ApiException(409,"STALE_VERSION","人员信息刚被其他管理员修改，请刷新后重试");
        }
        // Employee number updates retain this same internal ID and every foreign key.
        appendUserHistory("user_department_history","department_id",userId,String.valueOf(departmentId),from);
        appendUserHistory("user_level_history","level_code",userId,level,from);
        LocalDate identityDate=initial==null?from:today();
        updateEnrollment(userId,status,identityDate);
        if(roles!=null)updateRoles(userId,roles,departmentId,identityDate);
        jdbc.sql("UPDATE app_user SET employee_no=?,wecom_userid=?,name=?,status=?,department_id=?,level_code=? WHERE id=?")
            .params(employee,wecom,name,status,departmentId,level,userId).update();
        if (initial!=null && historicalChange) {
            if (!initial.departmentId().equals(input.departmentId())) {
                jdbc.sql("UPDATE day_record SET department_id=?,row_version=row_version+1 WHERE user_id=? AND work_date>=? ORDER BY work_date,id")
                    .params(departmentId,userId,from).update();
            } else {
                jdbc.sql("UPDATE day_record SET row_version=row_version+1 WHERE user_id=? AND work_date>=? ORDER BY work_date,id")
                    .params(userId,from).update();
            }
        }
        UserView after = getUser(userId);
        record(actorId,"USER_SAVED","USER",userId,before,after,"管理员维护人员及生效历史；现有人员启停及权限变更立即生效");
        return after;
    }

    private UserView getUser(long id) {
        return jdbc.sql("""
            SELECT u.*,d.name department_name,
              GREATEST((SELECT MAX(valid_from) FROM user_department_history h WHERE h.user_id=u.id),
                       (SELECT MAX(valid_from) FROM user_level_history h WHERE h.user_id=u.id)) effective_from
            FROM app_user u JOIN department d ON d.id=u.department_id WHERE u.id=?
            """).param(id).query((rs,n) -> user(rs)).optional().orElseThrow(() -> missing("人员"));
    }

    private void appendUserHistory(String table,String column,long userId,String value,LocalDate from) {
        var rows = jdbc.sql("SELECT id,"+column+" current_value,valid_from,valid_to FROM "+table+" WHERE user_id=? ORDER BY valid_from DESC LIMIT 1")
            .param(userId).query((rs,n) -> new History(rs.getLong("id"),rs.getString("current_value"),rs.getObject("valid_from",LocalDate.class),rs.getObject("valid_to",LocalDate.class))).list();
        if (!rows.isEmpty()) {
            History previous = rows.getFirst();
            if (previous.value().equals(value) && previous.to()==null) return;
            closePrevious(table,previous,from);
        }
        jdbc.sql("INSERT INTO "+table+"(user_id,"+column+",valid_from) VALUES (?,?,?)").params(userId,value,from).update();
    }

    private void closePrevious(String table,History previous,LocalDate from) {
        if (!from.isAfter(previous.from()) || (previous.to()!=null && previous.to().isAfter(from))) {
            throw new ApiException(422,"HISTORY_OVERLAP","新增生效历史必须晚于最后起日，且不能与已有区间交叠");
        }
        if (previous.to()==null) jdbc.sql("UPDATE "+table+" SET valid_to=? WHERE id=?").params(from,previous.id()).update();
    }

    private void updateEnrollment(long userId,String status,LocalDate from) {
        var rows = jdbc.sql("SELECT id,valid_from,valid_to,voided FROM reporting_enrollment WHERE user_id=? ORDER BY valid_from DESC LIMIT 1")
            .param(userId).query((rs,n) -> new Enrollment(rs.getLong("id"),rs.getObject("valid_from",LocalDate.class),rs.getObject("valid_to",LocalDate.class),rs.getBoolean("voided"))).list();
        if (rows.isEmpty()) {
            if (status.equals("ACTIVE")) jdbc.sql("INSERT INTO reporting_enrollment(user_id,valid_from) VALUES (?,?)").params(userId,from).update();
            return;
        }
        Enrollment previous = rows.getFirst();
        if (status.equals("INACTIVE") && previous.to()==null && !previous.voided()) {
            if (previous.from().equals(from)) jdbc.sql("UPDATE reporting_enrollment SET voided=TRUE WHERE id=?").param(previous.id()).update();
            else closePrevious("reporting_enrollment",new History(previous.id(),"",previous.from(),previous.to()),from);
        } else if (status.equals("ACTIVE") && previous.voided()) {
            if (previous.from().equals(from)) jdbc.sql("UPDATE reporting_enrollment SET voided=FALSE WHERE id=?").param(previous.id()).update();
            else jdbc.sql("INSERT INTO reporting_enrollment(user_id,valid_from) VALUES (?,?)").params(userId,from).update();
        } else if (status.equals("ACTIVE") && previous.to()!=null) {
            if (previous.to().isAfter(from)) throw new ApiException(422,"HISTORY_OVERLAP","重新纳入日期不能与既有填报适用区间交叠");
            jdbc.sql("INSERT INTO reporting_enrollment(user_id,valid_from) VALUES (?,?)").params(userId,from).update();
        }
    }

    private void updateRoles(long userId,Set<String> roles,long departmentId,LocalDate from) {
        var rows = jdbc.sql("SELECT id,role_code,department_id,valid_from FROM role_grant WHERE user_id=? AND valid_to IS NULL AND revoked_at IS NULL")
            .param(userId).query((rs,n) -> new Grant(rs.getLong("id"),rs.getString("role_code"),rs.getObject("department_id",Long.class),rs.getObject("valid_from",LocalDate.class))).list();
        Set<String> retained = new HashSet<>();
        for (Grant grant : rows) {
            if (roles.contains(grant.role())) { retained.add(grant.role()); continue; }
            if (from.isBefore(grant.from())) throw new ApiException(422,"HISTORY_OVERLAP","权限撤销不能早于原授权起日");
            if (from.equals(grant.from())) jdbc.sql("UPDATE role_grant SET revoked_at=UTC_TIMESTAMP(6) WHERE id=?").param(grant.id()).update();
            else jdbc.sql("UPDATE role_grant SET valid_to=?,revoked_at=UTC_TIMESTAMP(6) WHERE id=?").params(from,grant.id()).update();
        }
        for (String role : roles) {
            if (retained.contains(role)) continue;
            boolean departmentScope = role.equals("DEPARTMENT_MANAGER");
            jdbc.sql("INSERT INTO role_grant(user_id,role_code,scope_type,department_id,valid_from) VALUES (?,?,?,?,?)")
                .params(userId,role,departmentScope?"DEPARTMENT":"COMPANY",departmentScope?departmentId:null,from).update();
        }
    }

    public List<DepartmentView> departments() {
        current.requireAdmin();
        return jdbc.sql("SELECT * FROM department ORDER BY id").query((rs,n) -> department(rs)).list();
    }

    private DepartmentView department(ResultSet rs) throws SQLException {
        return new DepartmentView(rs.getString("id"),rs.getString("code"),rs.getString("name"),rs.getString("parent_id"),
            rs.getString("designated_manager_id"),rs.getString("supervisor_user_id"),rs.getString("status"));
    }

    @Transactional
    public DepartmentView saveDepartment(Long departmentId, DepartmentInput input) {
        long actorId=current.requireAdmin().id();
        String code=text(input.code(),40,"部门编码"), name=text(input.name(),100,"部门名称"), status=oneOf(input.status(),STATUSES,"部门状态");
        Long parent=optionalId(input.parentId(),"上级部门"), manager=optionalId(input.approverUserId(),"部门负责人"), supervisor=optionalId(input.supervisorUserId(),"分管领导");
        periods.lockForWrite(today());
        lockAdmin(actorId);
        // A small fixed organization tree can be locked together to prevent concurrent A→B / B→A edits.
        var all = jdbc.sql("SELECT * FROM department ORDER BY id FOR UPDATE").query((rs,n) -> department(rs)).list();
        Long existingId=departmentId;
        DepartmentView before = existingId==null ? null : all.stream().filter(d -> d.id().equals(existingId.toString())).findFirst().orElseThrow(() -> missing("部门"));
        if (parent!=null) {
            requireDepartment(parent);
            Long cursor=parent;
            Set<Long> seen=new HashSet<>();
            while (cursor!=null) {
                if (cursor.equals(departmentId) || !seen.add(cursor)) throw new ApiException(422,"DEPARTMENT_CYCLE","组织树不能形成循环");
                Long currentId=cursor;
                DepartmentView node=all.stream().filter(d -> d.id().equals(currentId.toString())).findFirst().orElseThrow(() -> missing("上级部门"));
                cursor=optionalId(node.parentId(),"上级部门");
            }
        }
        if (manager!=null) requireActiveUser(manager);
        if (supervisor!=null) requireActiveUser(supervisor);
        if (departmentId==null) {
            jdbc.sql("INSERT INTO department(code,name,parent_id,designated_manager_id,supervisor_user_id,status) VALUES (?,?,?,?,?,?)")
                .params(code,name,parent,manager,supervisor,status).update();
            departmentId=lastId();
        } else {
            jdbc.sql("UPDATE department SET code=?,name=?,parent_id=?,designated_manager_id=?,supervisor_user_id=?,status=? WHERE id=?")
                .params(code,name,parent,manager,supervisor,status,departmentId).update();
        }
        DepartmentView after=jdbc.sql("SELECT * FROM department WHERE id=?").param(departmentId).query((rs,n) -> department(rs)).single();
        if(jdbc.sql("SELECT COUNT(*) FROM work_item WHERE owner_department_id=? AND type='IDLE'").param(departmentId).query(Integer.class).single()==0) {
            jdbc.sql("INSERT INTO work_item(code,name,type,source,owner_department_id,default_approver_id,status) VALUES(?,?,'IDLE','LOCAL',?,?,?)")
                .params("SYS-IDLE-"+departmentId,name+" · 待分配",departmentId,manager,status).update();
            long idle=lastId();
            jdbc.sql("INSERT INTO work_item_history(work_item_id,owner_department_id,valid_from) VALUES(?,?,?)").params(idle,departmentId,today()).update();
            record(actorId,"DEPARTMENT_IDLE_CREATED","WORK_ITEM",idle,null,Map.of("departmentId",departmentId,"type","IDLE"),"为部门预置待分配对象");
        } else {
            jdbc.sql("UPDATE work_item SET default_approver_id=? WHERE owner_department_id=? AND type='IDLE'").params(manager,departmentId).update();
        }
        record(actorId,"DEPARTMENT_SAVED","DEPARTMENT",departmentId,before,after,"管理员维护组织与新送审默认负责人");
        return after;
    }

    public List<WorkItemView> workItems() {
        var actor=current.require();Set<Long> scope=actor.canManage()?Set.of():nonProjectScope(actor.id());
        if(!actor.canManage()&&scope.isEmpty())throw new ApiException(403,"NON_PROJECT_SCOPE","没有非项目对象维护权限");
        return jdbc.sql(itemSelect()+" ORDER BY w.id").query((rs,n) -> workItem(rs)).list().stream()
            .filter(w->actor.canManage()||w.type().equals("NON_PROJECT")&&w.source().equals("LOCAL")&&scope.contains(Long.parseLong(w.ownerDepartmentId()))).toList();
    }

    public Map<String,Object> nonProjectOptions(){
        var actor=current.require();Set<Long> scope=actor.canManage()?new HashSet<>(jdbc.sql("SELECT id FROM department WHERE status='ACTIVE'").query(Long.class).list()):nonProjectScope(actor.id());
        if(!actor.canManage()&&scope.isEmpty())throw new ApiException(403,"NON_PROJECT_SCOPE","没有非项目对象维护权限");
        var departments=jdbc.sql("SELECT id,name FROM department WHERE status='ACTIVE' ORDER BY id").query((rs,n)->Map.of("id",rs.getString("id"),"name",rs.getString("name"))).list().stream().filter(row->scope.contains(Long.parseLong(row.get("id")))).toList();
        Set<Long> approvers=allowedNonProjectApprovers(scope);
        var users=jdbc.sql("SELECT id,name FROM app_user WHERE status='ACTIVE' ORDER BY id").query((rs,n)->Map.of("id",rs.getString("id"),"name",rs.getString("name"))).list().stream().filter(row->actor.canManage()||approvers.contains(Long.parseLong(row.get("id")))).toList();
        return Map.of("departments",departments,"approvers",users,"canManageProjects",actor.canManage());
    }
    private Set<Long> nonProjectScope(long actor){
        Set<Long> scope=new HashSet<>(jdbc.sql("""
            SELECT department_id FROM role_grant WHERE user_id=? AND role_code='DEPARTMENT_MANAGER' AND scope_type='DEPARTMENT' AND revoked_at IS NULL
             AND valid_from<=? AND (valid_to IS NULL OR valid_to>?)
            UNION SELECT department_id FROM department_manager WHERE user_id=? AND revoked_at IS NULL AND valid_from<=? AND (valid_to IS NULL OR valid_to>?)
            """).params(actor,today(),today(),actor,today(),today()).query(Long.class).list());
        var all=jdbc.sql("SELECT id,parent_id,status FROM department").query((rs,n)->new Object[]{rs.getLong("id"),rs.getObject("parent_id",Long.class),rs.getString("status")}).list();
        boolean changed=true;while(changed){changed=false;for(var row:all)if(row[1]!=null&&scope.contains((Long)row[1])&&scope.add((Long)row[0]))changed=true;}
        Set<Long> active=new HashSet<>();for(var row:all)if(row[2].equals("ACTIVE"))active.add((Long)row[0]);scope.retainAll(active);return scope;
    }
    private Set<Long> allowedNonProjectApprovers(Set<Long> scope){
        Set<Long> users=new HashSet<>();for(var row:jdbc.sql("SELECT id,department_id FROM app_user WHERE status='ACTIVE'").query((rs,n)->new long[]{rs.getLong(1),rs.getLong(2)}).list())if(scope.contains(row[1]))users.add(row[0]);
        for(var row:jdbc.sql("SELECT user_id,department_id FROM department_manager WHERE revoked_at IS NULL AND valid_from<=? AND (valid_to IS NULL OR valid_to>?) UNION SELECT user_id,department_id FROM role_grant WHERE role_code='DEPARTMENT_MANAGER' AND scope_type='DEPARTMENT' AND revoked_at IS NULL AND valid_from<=? AND (valid_to IS NULL OR valid_to>?)")
            .params(today(),today(),today(),today()).query((rs,n)->new long[]{rs.getLong(1),rs.getLong(2)}).list())if(scope.contains(row[1]))users.add(row[0]);
        for(var row:jdbc.sql("SELECT id,designated_manager_id,supervisor_user_id FROM department WHERE status='ACTIVE'").query((rs,n)->new Long[]{rs.getLong(1),rs.getObject(2,Long.class),rs.getObject(3,Long.class)}).list())if(scope.contains(row[0])){if(row[1]!=null)users.add(row[1]);if(row[2]!=null)users.add(row[2]);}return users;
    }
    private void authorizeNonProject(CurrentUser.User actor,String type,String source,long department,Long approver,WorkItemView old){
        if(actor.canManage())return;Set<Long> scope=nonProjectScope(actor.id());
        if(!type.equals("NON_PROJECT")||!source.equals("LOCAL")||!scope.contains(department)||old!=null&&(!old.type().equals("NON_PROJECT")||!old.source().equals("LOCAL")||!scope.contains(Long.parseLong(old.ownerDepartmentId()))))
            throw new ApiException(403,"NON_PROJECT_SCOPE","部门负责人只能维护有效管理范围内的本地非项目对象");
        if(approver!=null&&!allowedNonProjectApprovers(scope).contains(approver))throw new ApiException(403,"NON_PROJECT_APPROVER_SCOPE","请选择管理范围内人员或本部门已指定的负责人/分管领导");
    }

    private String itemSelect() { return """
        SELECT w.*,u.name approver_name,
          (SELECT MAX(valid_from) FROM work_item_history h WHERE h.work_item_id=w.id) effective_from
        FROM work_item w LEFT JOIN app_user u ON u.id=w.default_approver_id
        """; }

    private WorkItemView workItem(ResultSet rs) throws SQLException {
        return new WorkItemView(rs.getString("id"),rs.getString("code"),rs.getString("name"),rs.getString("type"),rs.getString("owner_department_id"),
            rs.getString("default_approver_id"),rs.getString("approver_name"),rs.getString("source"),rs.getString("status"),rs.getObject("effective_from",LocalDate.class));
    }

    @Transactional
    public WorkItemView saveWorkItem(Long itemId,WorkItemInput input) {
        var operator=current.require();long actorId=operator.id();
        String code=text(input.code(),60,"归集编码"), name=text(input.name(),150,"归集名称"), type=oneOf(input.type(),Set.of("PROJECT","NON_PROJECT","IDLE"),"对象类型");
        if(itemId==null&&code.startsWith("SYS-IDLE-"))throw new ApiException(422,"RESERVED_CODE","SYS-IDLE- 前缀保留给部门预置待分配对象");
        String source=input.source()==null ? "LOCAL" : oneOf(input.source(),Set.of("LOCAL","OA"),"来源");
        String status=oneOf(input.status(),STATUSES,"对象状态");
        long departmentId=id(input.ownerDepartmentId(),"主责部门");
        Long approver=optionalId(input.approverUserId(),"默认审批人");
        LocalDate from=currentEffectiveDate(input.effectiveFrom());
        WorkItemView initial=itemId==null?null:jdbc.sql(itemSelect()+" WHERE w.id=?").param(itemId)
            .query((rs,n) -> workItem(rs)).optional().orElseThrow(() -> missing("归集对象"));
        authorizeNonProject(operator,type,source,departmentId,approver,initial);
        if (initial==null || !initial.ownerDepartmentId().equals(input.ownerDepartmentId())) periods.lockHistory(from,null);
        lockUser(actorId);operator=current.require();
        if(!operator.canManage())jdbc.sql("SELECT id FROM department ORDER BY id FOR SHARE").query(Long.class).list();
        authorizeNonProject(operator,type,source,departmentId,approver,initial);
        requireDepartment(departmentId);
        if (approver!=null) requireActiveUser(approver);
        WorkItemView before=null;
        if (itemId==null) {
            if (!source.equals("LOCAL")) throw new ApiException(422,"SOURCE_READ_ONLY","OA 对象只能由核实的来源导入创建");
            jdbc.sql("INSERT INTO work_item(code,name,type,source,owner_department_id,default_approver_id,status) VALUES (?,?,?,?,?,?,?)")
                .params(code,name,type,source,departmentId,approver,status).update();
            itemId=lastId();
        } else {
            if (jdbc.sql("SELECT id FROM work_item WHERE id=? FOR UPDATE").param(itemId).query(Long.class).optional().isEmpty()) throw missing("归集对象");
            before=jdbc.sql(itemSelect()+" WHERE w.id=?").param(itemId).query((rs,n) -> workItem(rs)).single();
            if (!before.equals(initial)) throw new ApiException(409,"STALE_VERSION","归集对象刚被其他管理员修改，请刷新后重试");
            if (!before.source().equals("LOCAL") || !source.equals("LOCAL")) throw new ApiException(422,"SOURCE_READ_ONLY","OA 权威对象不能通过手工维护覆盖");
            if (!before.type().equals(type)) throw new ApiException(422,"ITEM_TYPE_IMMUTABLE","对象类型创建后不可改写，请停用旧对象并创建新对象");
        }
        var histories=jdbc.sql("SELECT id,owner_department_id,valid_from,valid_to FROM work_item_history WHERE work_item_id=? ORDER BY valid_from DESC LIMIT 1")
            .param(itemId).query((rs,n) -> new History(rs.getLong("id"),rs.getString("owner_department_id"),rs.getObject("valid_from",LocalDate.class),rs.getObject("valid_to",LocalDate.class))).list();
        if (histories.isEmpty() || !histories.getFirst().value().equals(String.valueOf(departmentId))) {
            if (!histories.isEmpty()) closePrevious("work_item_history",histories.getFirst(),from);
            jdbc.sql("INSERT INTO work_item_history(work_item_id,owner_department_id,valid_from) VALUES (?,?,?)").params(itemId,departmentId,from).update();
        }
        jdbc.sql("UPDATE work_item SET code=?,name=?,owner_department_id=?,default_approver_id=?,status=? WHERE id=?")
            .params(code,name,departmentId,approver,status,itemId).update();
        WorkItemView after=jdbc.sql(itemSelect()+" WHERE w.id=?").param(itemId).query((rs,n) -> workItem(rs)).single();
        record(actorId,"WORK_ITEM_SAVED","WORK_ITEM",itemId,before,after,"有权人员维护归集对象及主责部门历史");
        return after;
    }

    public List<RateView> rates() {
        var reader=current.require();
        if(!reader.canManage()&&reader.roles().stream().noneMatch(r->Set.of("PM","DEPARTMENT_MANAGER","LEADER").contains(r)))
            throw new ApiException(403,"FORBIDDEN","统一标准仅供有成本核实职责的人员查看");
        return jdbc.sql("""
            SELECT id,level_code dimension,daily_rate,valid_from,valid_to FROM rate_card
            UNION ALL SELECT id,'ONSITE' dimension,daily_rate,valid_from,valid_to FROM onsite_rate
            ORDER BY dimension,valid_from
            """).query((rs,n) -> rate(rs)).list();
    }

    private RateView rate(ResultSet rs) throws SQLException {
        return new RateView(rs.getString("id"),rs.getString("dimension"),rs.getBigDecimal("daily_rate").toPlainString(),
            rs.getObject("valid_from",LocalDate.class),rs.getObject("valid_to",LocalDate.class));
    }

    @Transactional
    public RateView addRate(RateInput input) {
        long actorId=current.requireAdmin().id();
        String dimension=oneOf(input.dimension(),Set.of("JUNIOR","MIDDLE","SENIOR","ONSITE"),"计价维度");
        interval(input.effectiveFrom(),input.effectiveTo());
        BigDecimal amount;
        try { amount=new BigDecimal(input.dailyRate()); }
        catch (NumberFormatException | NullPointerException ex) { throw new ApiException(422,"INVALID_RATE","日标准须为精确十进制字符串"); }
        if (amount.signum()<0 || amount.scale()>4 || amount.compareTo(new BigDecimal("9999999999.9999"))>0)
            throw new ApiException(422,"INVALID_RATE","日标准须非负，最多四位小数且不超过数据库范围");
        LocalDate from=input.effectiveFrom(), to=input.effectiveTo();
        periods.lockHistory(from,to);
        lockAdmin(actorId);
        jdbc.sql("SELECT code FROM rate_dimension WHERE code=? FOR UPDATE").param(dimension).query(String.class).single();
        String table=dimension.equals("ONSITE")?"onsite_rate":"rate_card";
        String column=dimension.equals("ONSITE")?"dimension_code":"level_code";
        var existing=jdbc.sql("SELECT id,daily_rate,valid_from,valid_to FROM "+table+" WHERE "+column+"=? ORDER BY valid_from")
            .param(dimension).query((rs,n) -> new History(rs.getLong("id"),rs.getString("daily_rate"),rs.getObject("valid_from",LocalDate.class),rs.getObject("valid_to",LocalDate.class))).list();
        History close=null;
        for (History old : existing) {
            if (old.from().equals(from)) throw new ApiException(422,"RATE_OVERLAP","同一维度已存在该起日的标准");
            boolean overlap=(to==null || old.from().isBefore(to)) && (old.to()==null || from.isBefore(old.to()));
            if (overlap) {
                if (old.to()==null && old.from().isBefore(from)) close=old;
                else throw new ApiException(422,"RATE_OVERLAP","标准生效区间不能交叠");
            }
        }
        if (close!=null) jdbc.sql("UPDATE "+table+" SET valid_to=? WHERE id=?").params(from,close.id()).update();
        jdbc.sql("INSERT INTO "+table+"("+column+",daily_rate,valid_from,valid_to) VALUES (?,?,?,?)").params(dimension,amount,from,to).update();
        RateView after=new RateView(String.valueOf(lastId()),dimension,amount.toPlainString(),from,to);
        record(actorId,"RATE_ADDED","RATE",Long.parseLong(after.id()),close,after,"新增标准版本；开放前版在新起日结束，历史金额不覆盖");
        return after;
    }

    public List<CalendarView> calendar(LocalDate from,LocalDate to) {
        current.requireAdmin();
        if (from==null || to==null || to.isBefore(from) || ChronoUnit.DAYS.between(from,to)>366) throw new ApiException(422,"INVALID_DATE_RANGE","日历查询范围为最多 367 个自然日");
        var configured=jdbc.sql("SELECT * FROM work_calendar WHERE work_date BETWEEN ? AND ? AND is_override=TRUE ORDER BY work_date")
            .params(from,to).query((rs,n) -> new CalendarView(rs.getObject("work_date",LocalDate.class),rs.getBoolean("is_workday"),rs.getInt("base_minutes"),rs.getString("reason"))).list();
        List<CalendarView> result=new ArrayList<>();
        for (LocalDate day=from;!day.isAfter(to);day=day.plusDays(1)) {
            LocalDate date=day;
            result.add(configured.stream().filter(c -> c.date().equals(date)).findFirst().orElseGet(() -> defaultCalendar(date)));
        }
        return result;
    }

    @Transactional
    public CalendarView saveCalendar(LocalDate date,CalendarInput input) {
        long actorId=current.requireAdmin().id();
        if (input.baseMinutes()<0 || input.baseMinutes()>480 || input.baseMinutes()%configs.forDate(date).params().stepMinutes()!=0 || input.isWorkday()!=(input.baseMinutes()>0))
            throw new ApiException(422,"INVALID_CALENDAR","工作日基数不超过480分钟且符合当日配置步长，休息日为0");
        String note=text(input.note(),200,"日历依据");
        periods.lockBaseForPopulation(date,null);
        CalendarView defaultDay=defaultCalendar(date);
        if (jdbc.sql("SELECT COUNT(*) FROM work_calendar WHERE work_date=?").param(date).query(Integer.class).single()==0) {
            jdbc.sql("INSERT IGNORE INTO work_calendar(work_date,is_workday,base_minutes,reason) VALUES (?,?,?,?)")
                .params(date,defaultDay.isWorkday(),defaultDay.baseMinutes(),defaultDay.note()).update();
        }
        jdbc.sql("SELECT work_date FROM work_calendar WHERE work_date=? FOR UPDATE").param(date).query(LocalDate.class).single();
        lockAdmin(actorId);
        CalendarView before=calendar(date,date).getFirst();
        jdbc.sql("""
            INSERT INTO work_calendar(work_date,is_workday,base_minutes,reason) VALUES (?,?,?,?)
            ON DUPLICATE KEY UPDATE is_workday=?,base_minutes=?,reason=?,is_override=TRUE
            """).params(date,input.isWorkday(),input.baseMinutes(),note,input.isWorkday(),input.baseMinutes(),note).update();
        var days=jdbc.sql("SELECT id,user_id FROM day_record WHERE work_date=? ORDER BY user_id,id FOR UPDATE")
            .param(date).query((rs,n) -> new CalendarDay(rs.getLong("id"),rs.getLong("user_id"))).list();
        // A rest-day save may clamp cached leave to zero; the source still holds the approved leave.
        for (CalendarDay day : days) {
            var leaves=jdbc.sql("SELECT leave_minutes,start_minute,end_minute FROM leave_record WHERE user_id=? AND work_date=? AND status='APPROVED'")
                .params(day.userId(),date).query((rs,n) -> new DayRules.LeaveSegment(rs.getInt("leave_minutes"),
                    rs.getObject("start_minute",Integer.class),rs.getObject("end_minute",Integer.class))).list();
            int leave=DayRules.leaveMinutes(leaves,input.baseMinutes());
            jdbc.sql("""
                UPDATE day_record SET base_minutes=?,is_workday=?,leave_minutes=?,required_minutes=?,row_version=row_version+1 WHERE id=?
                """).params(input.baseMinutes(),input.isWorkday(),leave,Math.max(input.baseMinutes()-leave,0),day.id()).update();
        }
        CalendarView after=new CalendarView(date,input.isWorkday(),input.baseMinutes(),note);
        record(actorId,"CALENDAR_SAVED","WORK_CALENDAR",date.toString(),before,after,"管理员维护日历 "+date);
        return after;
    }

    private CalendarView defaultCalendar(LocalDate day) {
        boolean working=day.getDayOfWeek()!=DayOfWeek.SATURDAY && day.getDayOfWeek()!=DayOfWeek.SUNDAY;
        int base=working?configs.forDate(day).params().defaultDayMinutes():0;
        return new CalendarView(day,base>0,base,"默认周历；法定节假日和调休须按公司日历配置");
    }
    private long lastId() { return jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single(); }
    private void lockAdmin(long actorId) {
        jdbc.sql("SELECT id FROM app_user WHERE id=? FOR SHARE").param(actorId).query(Long.class).single();
        current.requireAdmin();
    }
    private void lockUser(long id) {
        if (jdbc.sql("SELECT id FROM app_user WHERE id=? FOR UPDATE").param(id).query(Long.class).optional().isEmpty()) throw missing("人员");
    }
    private void requireActiveUser(long id) {
        if (jdbc.sql("SELECT id FROM app_user WHERE id=? AND status='ACTIVE'").param(id).query(Long.class).optional().isEmpty()) throw missing("有效人员");
    }
    private void requireDepartment(long id) {
        if (jdbc.sql("SELECT id FROM department WHERE id=? AND status='ACTIVE'").param(id).query(Long.class).optional().isEmpty()) throw missing("有效部门");
    }
    private static ApiException missing(String label) { return new ApiException(422,"MASTER_NOT_FOUND",label+"不存在或不可用"); }
    private static LocalDate today() { return LocalDate.now(ZoneId.of("Asia/Shanghai")); }
    private static LocalDate currentEffectiveDate(LocalDate from) {
        interval(from,null);
        if (from.isAfter(today())) throw new ApiException(422,"FUTURE_MASTER_CHANGE","首批人员和项目维护只接收已生效变更；未来标准可在费率页追加");
        return from;
    }
    private static Set<String> checkedRoles(List<String> input) {
        Set<String> roles=new HashSet<>(input==null?List.of("EMPLOYEE"):input);
        roles.add("EMPLOYEE");
        for (String role : roles) oneOf(role,ROLES,"角色");
        return roles;
    }
    private void record(long actorId,String action,String type,Object id,Object before,Object after,String reason) {
        audit.record(actorId,action,type,id==null?null:id.toString(),snapshot(before),snapshot(after),reason);
    }
    private String snapshot(Object value) { return value==null ? null : json.writeValueAsString(value); }
    private record History(long id,String value,LocalDate from,LocalDate to) {}
    private record Enrollment(long id,LocalDate from,LocalDate to,boolean voided) {}
    private record CalendarDay(long id,long userId) {}
    private record Grant(long id,String role,Long departmentId,LocalDate from) {}
}
