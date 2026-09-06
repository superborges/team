package com.allen.worklog.master;

import com.allen.worklog.closing.PeriodGate;
import com.allen.worklog.common.ApiException;
import com.allen.worklog.common.AuditService;
import com.allen.worklog.identity.CurrentUser;
import com.allen.worklog.work.DayRules;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import static com.allen.worklog.master.MasterDtos.*;
import static com.allen.worklog.master.MasterGovernanceService.*;

@Service
public class MasterBatchService {
    public record UsersInput(List<String> userIds,String departmentId,String level,LocalDate effectiveFrom,String reason,String expectedFingerprint) {}
    public record ManagersInput(List<String> userIds,String departmentId,String title,LocalDate effectiveFrom,LocalDate effectiveTo,String reason,String expectedFingerprint) {}
    private final JdbcClient jdbc;private final CurrentUser current;private final PeriodGate periods;private final AuditService audit;private final ObjectMapper json;private final MasterDataService master;private final MasterGovernanceService governance;
    public MasterBatchService(JdbcClient jdbc,CurrentUser current,PeriodGate periods,AuditService audit,ObjectMapper json,MasterDataService master,MasterGovernanceService governance){this.jdbc=jdbc;this.current=current;this.periods=periods;this.audit=audit;this.json=json;this.master=master;this.governance=governance;}

    public Map<String,Object> previewUsers(UsersInput input){
        current.requireAdmin();if(input==null)throw bad("参数不能为空");var users=users(input.userIds());date(input.effectiveFrom(),null,false);reason(input.reason());
        if(input.departmentId()==null&&input.level()==null)throw bad("请选择目标部门或职级");
        if(input.level()!=null&&!Set.of("JUNIOR","MIDDLE","SENIOR").contains(input.level()))throw bad("职级无效");
        String department=input.departmentId()==null?null:department(DayRules.id(input.departmentId()));List<String> errors=new ArrayList<>();historyOpen(input.effectiveFrom(),null,errors);
        var rows=new ArrayList<Map<String,Object>>();
        for(var user:users){
            if(input.departmentId()!=null&&!input.departmentId().equals(user.departmentId()))checkStart("user_department_history",user,input.effectiveFrom(),errors);
            if(input.level()!=null&&!input.level().equals(user.level()))checkStart("user_level_history",user,input.effectiveFrom(),errors);
            int days=jdbc.sql("SELECT COUNT(*) FROM day_record WHERE user_id=? AND work_date>=?").params(user.id(),input.effectiveFrom()).query(Integer.class).single();
            int approved=jdbc.sql("SELECT COUNT(*) FROM day_record d JOIN time_entry e ON e.day_id=d.id JOIN time_entry_revision r ON r.id=e.current_revision_id WHERE d.user_id=? AND d.work_date>=? AND r.state IN ('APPROVED','LOCKED')").params(user.id(),input.effectiveFrom()).query(Integer.class).single();
            rows.add(map("userId",user.id(),"employeeNo",user.employeeNo(),"name",user.name(),"fromDepartmentId",user.departmentId(),"fromDepartmentName",user.departmentName(),"toDepartmentId",input.departmentId()==null?user.departmentId():input.departmentId(),"toDepartmentName",department==null?user.departmentName():department,"fromLevel",user.level(),"toLevel",input.level()==null?user.level():input.level(),"affectedDayCount",days,"approvedRevisionCount",approved));
        }
        return map("expectedFingerprint",fingerprint(map("users",users,"rows",rows,"departmentId",input.departmentId(),"level",input.level(),"effectiveFrom",input.effectiveFrom(),"reason",input.reason())),"canApply",errors.isEmpty(),"errors",errors,"rows",rows,"effectiveFrom",input.effectiveFrom(),"reason",input.reason());
    }
    @Transactional public Map<String,Object> applyUsers(UsersInput input){
        long actor=current.requireAdmin().id();if(input==null)throw bad("参数不能为空");date(input.effectiveFrom(),null,false);var ids=ids(input.userIds());periods.lockHistory(input.effectiveFrom(),null);lockUsers(actor,ids);
        var preview=previewUsers(input);check(preview,input.expectedFingerprint());var before=users(input.userIds());var updated=new ArrayList<UserView>();
        for(var user:before)updated.add(master.saveUser(Long.parseLong(user.id()),new UserInput(user.employeeNo(),user.wecomUserid(),user.name(),input.departmentId()==null?user.departmentId():input.departmentId(),input.level()==null?user.level():input.level(),user.status(),input.effectiveFrom(),null)));
        var result=map("updated",updated,"affectedUsers",updated.size(),"reason",input.reason());record(actor,"USERS_BATCH_ADJUSTED",before,result,input.reason());return result;
    }
    public Map<String,Object> previewManagers(ManagersInput input){
        current.requireAdmin();if(input==null)throw bad("参数不能为空");var users=users(input.userIds());date(input.effectiveFrom(),input.effectiveTo(),true);reason(input.reason());long department=DayRules.id(input.departmentId());String name=department(department);
        if(!Set.of("HEAD","DEPUTY").contains(input.title()==null?"":input.title()))throw bad("请选择正职或副职");List<String> errors=new ArrayList<>();historyOpen(input.effectiveFrom(),input.effectiveTo(),errors);
        var existing=governance.managers(department);
        for(var user:users){if(!user.status().equals("ACTIVE"))errors.add(user.employeeNo()+"：人员已停用");
            boolean overlap=jdbc.sql("SELECT COUNT(*) FROM department_manager WHERE department_id=? AND user_id=? AND (valid_from=? OR (revoked_at IS NULL AND (? IS NULL OR valid_from<?) AND (valid_to IS NULL OR valid_to>?)))")
                .params(department,user.id(),input.effectiveFrom(),input.effectiveTo(),input.effectiveTo(),input.effectiveFrom()).query(Integer.class).single()>0;
            if(overlap)errors.add(user.employeeNo()+"：已有交叠区间或同起日的任职历史");}
        return map("expectedFingerprint",fingerprint(map("users",users,"managers",existing,"departmentId",input.departmentId(),"title",input.title(),"effectiveFrom",input.effectiveFrom(),"effectiveTo",input.effectiveTo(),"reason",input.reason())),"canApply",errors.isEmpty(),"errors",errors,
            "users",users.stream().map(u->map("userId",u.id(),"employeeNo",u.employeeNo(),"name",u.name())).toList(),"departmentId",input.departmentId(),"departmentName",name,"title",input.title(),"effectiveFrom",input.effectiveFrom(),"effectiveTo",input.effectiveTo(),"reason",input.reason());
    }
    @Transactional public Map<String,Object> applyManagers(ManagersInput input){
        long actor=current.requireAdmin().id();if(input==null)throw bad("参数不能为空");date(input.effectiveFrom(),input.effectiveTo(),true);var ids=ids(input.userIds());periods.lockHistory(input.effectiveFrom(),input.effectiveTo());lockUsers(actor,ids);
        jdbc.sql("SELECT id FROM department WHERE id=? FOR UPDATE").param(DayRules.id(input.departmentId())).query(Long.class).single();var preview=previewManagers(input);check(preview,input.expectedFingerprint());
        var appointments=new ArrayList<Map<String,Object>>();for(long user:ids)appointments.add(governance.addManager(DayRules.id(input.departmentId()),new ManagerInput(Long.toString(user),input.title(),input.effectiveFrom(),input.effectiveTo(),input.reason())));
        var result=map("appointments",appointments,"affectedUsers",appointments.size(),"reason",input.reason());record(actor,"MANAGERS_BATCH_APPOINTED",preview,result,input.reason());return result;
    }
    private List<Long> ids(List<String> inputs){if(inputs==null||inputs.isEmpty()||inputs.size()>50)throw bad("请选择1至50人");var ids=inputs.stream().map(DayRules::id).sorted().toList();if(new HashSet<>(ids).size()!=ids.size())throw bad("人员不能重复选择");return ids;}
    private void checkStart(String table,UserView user,LocalDate from,List<String> errors){var last=jdbc.sql("SELECT MAX(valid_from) FROM "+table+" WHERE user_id=?").param(user.id()).query(LocalDate.class).optional();if(last.isPresent()&&!from.isAfter(last.get()))errors.add(user.employeeNo()+"：对应变更起日必须晚于原历史起日 "+last.get());}
    private List<UserView> users(List<String> input){var ids=ids(input);var rows=master.users().stream().filter(u->ids.contains(Long.parseLong(u.id()))).sorted(Comparator.comparingLong(u->Long.parseLong(u.id()))).toList();if(rows.size()!=ids.size())throw bad("有人员不存在，请刷新名单");return rows;}
    private void lockUsers(long actor,List<Long> ids){jdbc.sql("SELECT id FROM app_user WHERE id=? FOR UPDATE").param(actor).query(Long.class).single();current.requireAdmin();for(long user:ids)jdbc.sql("SELECT id FROM app_user WHERE id=? FOR UPDATE").param(user).query(Long.class).optional().orElseThrow(()->bad("人员不存在"));}
    private String department(long id){return jdbc.sql("SELECT name FROM department WHERE id=? AND status='ACTIVE'").param(id).query(String.class).optional().orElseThrow(()->bad("目标部门不存在或已停用"));}
    private void date(LocalDate from,LocalDate to,boolean future){if(from==null||to!=null&&!to.isAfter(from))throw bad("生效区间无效");if(!future&&from.isAfter(jdbc.sql("SELECT DATE(UTC_TIMESTAMP()+INTERVAL 8 HOUR)").query(LocalDate.class).single()))throw bad("人员调整起日必须已发生");}
    private void historyOpen(LocalDate from,LocalDate to,List<String> errors){if(!periods.status(from).equals("OPEN")||jdbc.sql("SELECT COUNT(*) FROM accounting_period WHERE LAST_DAY(month_start)>=? AND (? IS NULL OR month_start<?) AND (status='CLOSED' OR scheduled_close_at<=UTC_TIMESTAMP(6))").params(from,to,to).query(Integer.class).single()>0)errors.add("影响期间已有截止或封账月份，不能批量修改历史主数据");}
    private void check(Map<String,Object> preview,String expected){if(!Objects.equals(preview.get("expectedFingerprint"),expected))throw new ApiException(409,"BATCH_PREVIEW_CHANGED","人员、任职或调整内容已变化，请重新预览核实");if(!Boolean.TRUE.equals(preview.get("canApply")))throw bad(String.join("；",(List<String>)preview.get("errors")));}
    private String fingerprint(Object data){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(data).getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static void reason(String reason){if(reason==null||reason.isBlank()||reason.length()>1000)throw bad("请填写1至1000字核实依据");}
    private void record(long actor,String action,Object before,Object after,String reason){audit.record(actor,action,"MASTER_BATCH",UUID.randomUUID().toString(),json.writeValueAsString(before),json.writeValueAsString(after),reason);}
    private static Map<String,Object> map(Object... pairs){var result=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;}
    private static ApiException bad(String message){return new ApiException(422,"MASTER_BATCH_INVALID",message);}
}
