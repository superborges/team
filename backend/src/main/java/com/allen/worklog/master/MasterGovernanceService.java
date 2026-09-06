package com.allen.worklog.master;

import com.allen.worklog.closing.PeriodGate;
import com.allen.worklog.common.ApiException;
import com.allen.worklog.common.AuditService;
import com.allen.worklog.identity.CurrentUser;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import static com.allen.worklog.master.MasterValidation.*;

@Service
public class MasterGovernanceService {
    public record ManagerInput(String userId,String title,LocalDate effectiveFrom,LocalDate effectiveTo,String reason) {}
    public record GrantInput(String role,String scopeType,String departmentId,LocalDate effectiveFrom,LocalDate effectiveTo,String reason) {}
    public record RevokeInput(String reason) {}
    private final JdbcClient jdbc;private final CurrentUser current;private final PeriodGate periods;private final AuditService audit;private final ObjectMapper json;
    public MasterGovernanceService(JdbcClient jdbc,CurrentUser current,PeriodGate periods,AuditService audit,ObjectMapper json){this.jdbc=jdbc;this.current=current;this.periods=periods;this.audit=audit;this.json=json;}

    public List<Map<String,Object>> managers(long department){current.requireAdmin();if(jdbc.sql("SELECT COUNT(*) FROM department WHERE id=?").param(department).query(Integer.class).single()==0)throw new ApiException(404,"NOT_FOUND","部门不存在");return managerRows("m.department_id=?",department);}
    public List<Map<String,Object>> grants(long user){current.requireAdmin();user(user);return grantRows("g.user_id=?",user);}
    @Transactional public Map<String,Object> addManager(long department,ManagerInput input){
        long actor=current.requireAdmin().id();if(input==null)throw bad("参数不能为空");long target=id(input.userId(),"负责人");
        String title=oneOf(input.title(),Set.of("HEAD","DEPUTY"),"任职类型"),reason=text(input.reason(),1000,"任职原因");interval(input.effectiveFrom(),input.effectiveTo());
        periods.lockHistory(input.effectiveFrom(),input.effectiveTo());lockUsers(actor,target);department(department);
        jdbc.sql("SELECT id FROM department WHERE id=? FOR UPDATE").param(department).query(Long.class).single();activeUser(target);
        boolean overlap=jdbc.sql("SELECT COUNT(*) FROM department_manager WHERE department_id=? AND user_id=? AND revoked_at IS NULL AND (? IS NULL OR valid_from<?) AND (valid_to IS NULL OR valid_to>?)")
            .params(department,target,input.effectiveTo(),input.effectiveTo(),input.effectiveFrom()).query(Integer.class).single()>0;
        if(overlap)throw new ApiException(422,"HISTORY_OVERLAP","该人员在本部门已有交叠的任职区间");
        // The original unique-start key also guards duplicate historical starts, including revoked appointments.
        if(jdbc.sql("SELECT COUNT(*) FROM department_manager WHERE department_id=? AND user_id=? AND valid_from=?").params(department,target,input.effectiveFrom()).query(Integer.class).single()>0)
            throw new ApiException(422,"HISTORY_START_EXISTS","该起日已有任职历史，请选择新的起日");
        jdbc.sql("INSERT INTO department_manager(department_id,user_id,title,valid_from,valid_to) VALUES(?,?,?,?,?)").params(department,target,title,input.effectiveFrom(),input.effectiveTo()).update();
        long key=lastId();var result=manager(key);record(actor,"MANAGER_APPOINTED","DEPARTMENT_MANAGER",key,null,result,reason);return result;
    }
    @Transactional public Map<String,Object> revokeManager(long key,RevokeInput input){
        long actor=current.requireAdmin().id();String reason=reason(input);var initial=manager(key);long target=Long.parseLong(initial.get("userId").toString());
        lockUsers(actor,target);jdbc.sql("SELECT id FROM department WHERE id=? FOR UPDATE").param(Long.parseLong(initial.get("departmentId").toString())).query(Long.class).single();
        jdbc.sql("SELECT id FROM department_manager WHERE id=? FOR UPDATE").param(key).query(Long.class).single();var before=manager(key);
        if(before.get("revokedAt")==null){jdbc.sql("UPDATE department_manager SET revoked_at=UTC_TIMESTAMP(6) WHERE id=?").param(key).update();var after=manager(key);record(actor,"MANAGER_REVOKED","DEPARTMENT_MANAGER",key,before,after,reason);return after;}return before;
    }
    @Transactional public Map<String,Object> addGrant(long user,GrantInput input){
        long actor=current.requireAdmin().id();if(input==null)throw bad("参数不能为空");String role=oneOf(input.role(),Set.of("EMPLOYEE","ADMIN","PM","DEPARTMENT_MANAGER","LEADER"),"角色");
        String scope=oneOf(input.scopeType(),Set.of("COMPANY","DEPARTMENT"),"范围"),reason=text(input.reason(),1000,"授权原因");
        Long department=optionalId(input.departmentId(),"范围部门");validateScope(role,scope,department);interval(input.effectiveFrom(),input.effectiveTo());
        periods.lockHistory(input.effectiveFrom(),input.effectiveTo());lockUsers(actor,user);activeUser(user);if(department!=null)department(department);
        boolean overlap=jdbc.sql("SELECT COUNT(*) FROM role_grant WHERE user_id=? AND role_code=? AND scope_type=? AND department_id<=>? AND revoked_at IS NULL AND (? IS NULL OR valid_from<?) AND (valid_to IS NULL OR valid_to>?)")
            .params(user,role,scope,department,input.effectiveTo(),input.effectiveTo(),input.effectiveFrom()).query(Integer.class).single()>0;
        if(overlap)throw new ApiException(422,"HISTORY_OVERLAP","相同角色与范围已有交叠的授权区间");
        jdbc.sql("INSERT INTO role_grant(user_id,role_code,scope_type,department_id,valid_from,valid_to) VALUES(?,?,?,?,?,?)").params(user,role,scope,department,input.effectiveFrom(),input.effectiveTo()).update();
        long key=lastId();var result=grant(key);record(actor,"ROLE_GRANTED","ROLE_GRANT",key,null,result,reason);return result;
    }
    @Transactional public Map<String,Object> revokeGrant(long key,RevokeInput input){
        long actor=current.requireAdmin().id();String reason=reason(input);var initial=grant(key);long user=Long.parseLong(initial.get("userId").toString());lockUsers(actor,user);
        jdbc.sql("SELECT id FROM role_grant WHERE id=? FOR UPDATE").param(key).query(Long.class).single();var before=grant(key);
        if(before.get("revokedAt")==null){jdbc.sql("UPDATE role_grant SET revoked_at=UTC_TIMESTAMP(6) WHERE id=?").param(key).update();var after=grant(key);record(actor,"ROLE_REVOKED","ROLE_GRANT",key,before,after,reason);return after;}return before;
    }
    public Map<String,Object> history(long user){
        current.requireAdmin();user(user);
        var departments=jdbc.sql("SELECT h.*,d.name FROM user_department_history h JOIN department d ON d.id=h.department_id WHERE h.user_id=? ORDER BY h.valid_from DESC,h.id DESC").param(user)
            .query((rs,n)->map("id",rs.getString("id"),"departmentId",rs.getString("department_id"),"departmentName",rs.getString("name"),"effectiveFrom",rs.getObject("valid_from",LocalDate.class),"effectiveTo",rs.getObject("valid_to",LocalDate.class))).list();
        var levels=jdbc.sql("SELECT * FROM user_level_history WHERE user_id=? ORDER BY valid_from DESC,id DESC").param(user)
            .query((rs,n)->map("id",rs.getString("id"),"level",rs.getString("level_code"),"effectiveFrom",rs.getObject("valid_from",LocalDate.class),"effectiveTo",rs.getObject("valid_to",LocalDate.class))).list();
        var enrollments=jdbc.sql("SELECT * FROM reporting_enrollment WHERE user_id=? ORDER BY valid_from DESC,id DESC").param(user)
            .query((rs,n)->map("id",rs.getString("id"),"effectiveFrom",rs.getObject("valid_from",LocalDate.class),"effectiveTo",rs.getObject("valid_to",LocalDate.class),"voided",rs.getBoolean("voided"))).list();
        return map("userId",Long.toString(user),"departments",departments,"levels",levels,"enrollments",enrollments);
    }
    static void validateScope(String role,String scope,Long department){
        if(scope.equals("COMPANY")&&department!=null||scope.equals("DEPARTMENT")&&department==null)throw bad("公司范围无需部门，部门范围必须指定部门");
        if(Set.of("EMPLOYEE","ADMIN","PM").contains(role)&&!scope.equals("COMPANY")||role.equals("DEPARTMENT_MANAGER")&&!scope.equals("DEPARTMENT"))throw bad("此角色不支持所选范围");
    }
    private void interval(LocalDate from,LocalDate to){if(from==null||to!=null&&!to.isAfter(from))throw bad("生效日起必填，结束日须晚于起日");}
    private String reason(RevokeInput input){if(input==null)throw bad("参数不能为空");return text(input.reason(),1000,"撤销原因");}
    private List<Map<String,Object>> managerRows(String predicate,long value){
        LocalDate today=today();return jdbc.sql("SELECT m.*,d.name department_name,u.name user_name FROM department_manager m JOIN department d ON d.id=m.department_id JOIN app_user u ON u.id=m.user_id WHERE "+predicate+" ORDER BY m.valid_from DESC,m.id DESC").param(value)
            .query((rs,n)->{LocalDate from=rs.getObject("valid_from",LocalDate.class),to=rs.getObject("valid_to",LocalDate.class);LocalDateTime revoked=rs.getObject("revoked_at",LocalDateTime.class);
                return map("id",rs.getString("id"),"departmentId",rs.getString("department_id"),"departmentName",rs.getString("department_name"),"userId",rs.getString("user_id"),"userName",rs.getString("user_name"),"title",rs.getString("title"),"effectiveFrom",from,"effectiveTo",to,"revokedAt",revoked==null?null:revoked.toInstant(ZoneOffset.UTC),"active",revoked==null&&!from.isAfter(today)&&(to==null||to.isAfter(today)),"canRevoke",revoked==null);}).list();
    }
    private List<Map<String,Object>> grantRows(String predicate,long value){
        LocalDate today=today();return jdbc.sql("SELECT g.*,d.name department_name FROM role_grant g LEFT JOIN department d ON d.id=g.department_id WHERE "+predicate+" ORDER BY g.valid_from DESC,g.id DESC").param(value)
            .query((rs,n)->{LocalDate from=rs.getObject("valid_from",LocalDate.class),to=rs.getObject("valid_to",LocalDate.class);LocalDateTime revoked=rs.getObject("revoked_at",LocalDateTime.class);
                return map("id",rs.getString("id"),"userId",rs.getString("user_id"),"role",rs.getString("role_code"),"scopeType",rs.getString("scope_type"),"departmentId",rs.getString("department_id"),"departmentName",rs.getString("department_name"),"effectiveFrom",from,"effectiveTo",to,"revokedAt",revoked==null?null:revoked.toInstant(ZoneOffset.UTC),"active",revoked==null&&!from.isAfter(today)&&(to==null||to.isAfter(today)),"canRevoke",revoked==null);}).list();
    }
    private Map<String,Object> manager(long key){return managerRows("m.id=?",key).stream().findFirst().orElseThrow(()->new ApiException(404,"NOT_FOUND","任职不存在"));}
    private Map<String,Object> grant(long key){return grantRows("g.id=?",key).stream().findFirst().orElseThrow(()->new ApiException(404,"NOT_FOUND","授权不存在"));}
    private void lockUsers(long actor,long target){for(long user:new TreeSet<>(List.of(actor,target)))jdbc.sql("SELECT id FROM app_user WHERE id=? FOR UPDATE").param(user).query(Long.class).optional().orElseThrow(()->new ApiException(404,"NOT_FOUND","人员不存在"));current.requireAdmin();}
    private void user(long id){if(jdbc.sql("SELECT COUNT(*) FROM app_user WHERE id=?").param(id).query(Integer.class).single()==0)throw new ApiException(404,"NOT_FOUND","人员不存在");}
    private void activeUser(long id){if(!jdbc.sql("SELECT status FROM app_user WHERE id=?").param(id).query(String.class).optional().orElse("").equals("ACTIVE"))throw bad("只能向启用人员授予权限或任职");}
    private void department(long id){if(!jdbc.sql("SELECT status FROM department WHERE id=?").param(id).query(String.class).optional().orElse("").equals("ACTIVE"))throw bad("部门不存在或已停用");}
    private LocalDate today(){return jdbc.sql("SELECT DATE(UTC_TIMESTAMP()+INTERVAL 8 HOUR)").query(LocalDate.class).single();}
    private long lastId(){return jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();}
    private void record(long actor,String action,String kind,long key,Object before,Object after,String reason){audit.record(actor,action,kind,Long.toString(key),before==null?null:json.writeValueAsString(before),json.writeValueAsString(after),reason);}
    private static Map<String,Object> map(Object... pairs){var result=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;}
    private static ApiException bad(String message){return new ApiException(422,"INVALID_GOVERNANCE",message);}
}
