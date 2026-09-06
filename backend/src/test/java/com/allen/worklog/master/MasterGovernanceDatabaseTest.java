package com.allen.worklog.master;

import com.allen.worklog.common.ApiException;
import com.allen.worklog.identity.CurrentUser;
import com.allen.worklog.reporting.ReportingAccess;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import static com.allen.worklog.master.MasterGovernanceService.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="WORKLOG_INTEGRATION",matches="true")
@SpringBootTest(properties={"spring.profiles.active=local","spring.flyway.out-of-order=true"})
@Transactional
class MasterGovernanceDatabaseTest {
    @Autowired MasterGovernanceService governance;@Autowired JdbcClient jdbc;@Autowired CurrentUser current;@Autowired ReportingAccess access;@Autowired MasterBatchService batch;@Autowired MasterDataService master;
    long user,department,other;LocalDate today;
    static void as(long id){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(Long.toString(id),null,List.of()));}
    long id(){return jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();}
    @BeforeEach void fixture(){
        as(4);today=jdbc.sql("SELECT DATE(UTC_TIMESTAMP()+INTERVAL 8 HOUR)").query(LocalDate.class).single();String marker="GOV-IT-"+UUID.randomUUID().toString().substring(0,8);
        jdbc.sql("INSERT INTO department(code,name) VALUES(?,?)").params(marker,marker).update();department=id();jdbc.sql("INSERT INTO department(code,name) VALUES(?,?)").params(marker+"-2",marker+" 跨部门").update();other=id();
        String employee="GV"+String.format("%05d",Math.abs((int)(System.nanoTime()%100000)));while(jdbc.sql("SELECT COUNT(*) FROM app_user WHERE employee_no=?").param(employee).query(Integer.class).single()>0)employee="GV"+String.format("%05d",new Random().nextInt(100000));
        jdbc.sql("INSERT INTO app_user(employee_no,name,department_id,level_code) VALUES(?,?,?,'MIDDLE')").params(employee,marker,department).update();user=id();
        jdbc.sql("INSERT INTO user_department_history(user_id,department_id,valid_from) VALUES(?,?,?)").params(user,department,today).update();jdbc.sql("INSERT INTO user_level_history(user_id,level_code,valid_from) VALUES(?,'MIDDLE',?)").params(user,today).update();jdbc.sql("INSERT INTO reporting_enrollment(user_id,valid_from) VALUES(?,?)").params(user,today).update();
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    @Test void multipleManagersOverlapAndSameDayRevocationPreserveSeparateAuthority(){
        var appointment=governance.addManager(other,new ManagerInput(Long.toString(user),"DEPUTY",today,null,"跨部门副职任命"));assertEquals(true,appointment.get("active"));
        as(user);assertTrue(current.require().roles().contains("DEPARTMENT_MANAGER"));assertTrue(access.current().departments().contains(other));assertFalse(access.current().departments().contains(department));
        as(4);assertEquals("HISTORY_OVERLAP",assertThrows(ApiException.class,()->governance.addManager(other,new ManagerInput(Long.toString(user),"HEAD",today.plusDays(1),null,"冲突任命"))).code());
        var explicit=governance.addGrant(user,new GrantInput("DEPARTMENT_MANAGER","DEPARTMENT",Long.toString(other),today,null,"独立范围授权"));governance.revokeManager(Long.parseLong(appointment.get("id").toString()),new RevokeInput("当天撤销任职，保留独立授权"));
        as(user);assertTrue(access.current().departments().contains(other));as(4);governance.revokeGrant(Long.parseLong(explicit.get("id").toString()),new RevokeInput("随后撤销独立范围"));as(user);assertFalse(current.require().roles().contains("DEPARTMENT_MANAGER"));assertFalse(access.current().departments().contains(other));
        as(4);var historical=governance.managers(other).getFirst();assertNotNull(historical.get("revokedAt"));assertEquals(false,historical.get("active"));assertEquals(today,historical.get("effectiveFrom"));
    }
    @Test void scopeValidationFutureEffectAndImmediateAdminRevocation(){
        assertThrows(ApiException.class,()->governance.addGrant(user,new GrantInput("ADMIN","DEPARTMENT",Long.toString(other),today,null,"错误范围")));
        var future=governance.addGrant(user,new GrantInput("LEADER","DEPARTMENT",Long.toString(other),today.plusDays(1),null,"未来生效"));assertEquals(false,future.get("active"));assertFalse(current.load(user).roles().contains("LEADER"));
        var grant=governance.addGrant(user,new GrantInput("ADMIN","COMPANY",null,today,null,"隔离测试管理员"));as(user);assertTrue(current.requireAdmin().canManage());governance.revokeGrant(Long.parseLong(grant.get("id").toString()),new RevokeInput("即时撤销本人管理员权限"));assertEquals(403,assertThrows(ApiException.class,current::requireAdmin).status());
        assertEquals(403,assertThrows(ApiException.class,()->governance.history(user)).status());as(4);assertEquals(1,((List<?>)governance.history(user).get("departments")).size());assertEquals(1,((List<?>)governance.history(user).get("levels")).size());assertEquals(1,((List<?>)governance.history(user).get("enrollments")).size());
    }
    @Test void batchAdjustmentsRequireFreshImpactPreviewAndPreserveIdentityAndScopes(){
        jdbc.sql("UPDATE user_department_history SET valid_from=? WHERE user_id=?").params(today.minusDays(1),user).update();jdbc.sql("UPDATE user_level_history SET valid_from=? WHERE user_id=?").params(today.minusDays(1),user).update();
        var grant=governance.addGrant(user,new GrantInput("DEPARTMENT_MANAGER","DEPARTMENT",Long.toString(other),today,null,"独立跨部门授权"));
        String employee=current.load(user).employeeNo();var input=new MasterBatchService.UsersInput(List.of(Long.toString(user)),Long.toString(other),"SENIOR",today,"批量调整核实依据",null);var preview=batch.previewUsers(input);assertEquals(true,preview.get("canApply"));
        jdbc.sql("UPDATE app_user SET name='预览后姓名被管理员更正' WHERE id=?").param(user).update();
        assertEquals("BATCH_PREVIEW_CHANGED",assertThrows(ApiException.class,()->batch.applyUsers(new MasterBatchService.UsersInput(input.userIds(),input.departmentId(),input.level(),input.effectiveFrom(),input.reason(),preview.get("expectedFingerprint").toString()))).code());
        var refreshed=batch.previewUsers(input);var result=batch.applyUsers(new MasterBatchService.UsersInput(input.userIds(),input.departmentId(),input.level(),input.effectiveFrom(),input.reason(),refreshed.get("expectedFingerprint").toString()));assertEquals(1,result.get("affectedUsers"));
        assertEquals(employee,current.load(user).employeeNo());assertEquals(other,current.load(user).departmentId());assertEquals("SENIOR",jdbc.sql("SELECT level_code FROM app_user WHERE id=?").param(user).query(String.class).single());
        assertNull(governance.grants(user).stream().filter(g->g.get("id").equals(grant.get("id"))).findFirst().orElseThrow().get("revokedAt"));assertEquals(2,((List<?>)governance.history(user).get("departments")).size());
    }
    @Test void managerBatchPreviewsOverlapAndCreatesIndependentAppointments(){
        var input=new MasterBatchService.ManagersInput(List.of(Long.toString(user)),Long.toString(other),"DEPUTY",today,null,"多人任职核实",null);var preview=batch.previewManagers(input);assertEquals(true,preview.get("canApply"));
        var result=batch.applyManagers(new MasterBatchService.ManagersInput(input.userIds(),input.departmentId(),input.title(),input.effectiveFrom(),input.effectiveTo(),input.reason(),preview.get("expectedFingerprint").toString()));assertEquals(1,result.get("affectedUsers"));assertTrue(current.load(user).roles().contains("DEPARTMENT_MANAGER"));
        assertEquals(false,batch.previewManagers(input).get("canApply"));
    }
    @Test void scopedManagersMaintainOnlyLocalNonProjectsAndNeverGainAuthorityFromLeaderScope(){
        var manager=governance.addGrant(user,new GrantInput("DEPARTMENT_MANAGER","DEPARTMENT",Long.toString(other),today,null,"授予指定部门非项目管理"));
        var leader=governance.addGrant(user,new GrantInput("LEADER","COMPANY",null,today,null,"报表公司范围不能扩大对象写权限"));
        as(user);var options=master.nonProjectOptions();assertEquals(1,((List<?>)options.get("departments")).size());assertTrue(((List<?>)options.get("approvers")).toString().contains("id="+user));assertFalse(options.toString().contains("wecomUserid"));
        String code="DM-IT-"+UUID.randomUUID().toString().substring(0,8);var input=new MasterDtos.WorkItemInput(code,"范围内本地非项目","NON_PROJECT",Long.toString(other),Long.toString(user),"LOCAL","ACTIVE",today);
        var created=master.saveWorkItem(null,input);assertEquals(1,master.workItems().size());assertEquals(created.id(),master.workItems().getFirst().id());
        assertEquals(403,assertThrows(ApiException.class,()->master.saveWorkItem(null,new MasterDtos.WorkItemInput(code+"-P","不能建项目","PROJECT",Long.toString(other),Long.toString(user),"LOCAL","ACTIVE",today))).status());
        assertEquals(403,assertThrows(ApiException.class,()->master.saveWorkItem(null,new MasterDtos.WorkItemInput(code+"-OUT","不能跨范围","NON_PROJECT",Long.toString(department),Long.toString(user),"LOCAL","ACTIVE",today))).status());
        assertEquals(403,assertThrows(ApiException.class,()->master.saveWorkItem(1L,new MasterDtos.WorkItemInput("TEST-P001","不能改他人项目","PROJECT","1","2","LOCAL","ACTIVE",today))).status());
        assertEquals(403,assertThrows(ApiException.class,master::users).status());assertEquals(403,assertThrows(ApiException.class,master::departments).status());
        as(4);governance.revokeGrant(Long.parseLong(manager.get("id").toString()),new RevokeInput("撤销部门对象写权限"));as(user);assertTrue(current.require().roles().contains("LEADER"));assertEquals(403,assertThrows(ApiException.class,master::nonProjectOptions).status());
    }
}
