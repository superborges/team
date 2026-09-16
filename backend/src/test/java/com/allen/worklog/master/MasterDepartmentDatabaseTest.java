package com.allen.worklog.master;

import com.allen.worklog.identity.CurrentUser;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import static com.allen.worklog.master.MasterDtos.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="WORKLOG_INTEGRATION",matches="true")
@SpringBootTest(properties={"spring.profiles.active=local","spring.flyway.out-of-order=true"})
@Transactional
class MasterDepartmentDatabaseTest {
    @Autowired MasterDataService master;@Autowired JdbcClient jdbc;@Autowired CurrentUser current;
    static void as(long id){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(Long.toString(id),null,java.util.List.of()));}
    @BeforeEach void admin(){as(4);}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}

    private String idleStatus(long departmentId){
        return jdbc.sql("SELECT status FROM work_item WHERE owner_department_id=? AND type='IDLE'").param(departmentId).query(String.class).single();
    }
    private boolean idleInCatalog(long departmentId){
        return master.catalog().workItems().stream().anyMatch(i->i.code().equals("SYS-IDLE-"+departmentId));
    }

    @Test void departmentIdleObjectTracksDepartmentActiveState(){
        String marker="IDLE-IT-"+UUID.randomUUID().toString().substring(0,8);
        var created=master.saveDepartment(null,new DepartmentInput(marker,marker,null,null,null,"ACTIVE"));
        long departmentId=Long.parseLong(created.id());
        // Creating an ACTIVE department provisions an ACTIVE, fileable idle object.
        assertEquals("ACTIVE",idleStatus(departmentId),"新建有效部门的待分配对象应为有效");
        assertTrue(idleInCatalog(departmentId),"有效部门的待分配对象应出现在填报目录");

        // Deactivating the department must deactivate its idle object so it is no longer fileable.
        master.saveDepartment(departmentId,new DepartmentInput(marker,marker,null,null,null,"INACTIVE"));
        assertEquals("INACTIVE",idleStatus(departmentId),"停用部门后其待分配对象应同步停用");
        assertFalse(idleInCatalog(departmentId),"停用部门的待分配对象不应再出现在填报目录");

        // Reactivating the department restores its idle object.
        master.saveDepartment(departmentId,new DepartmentInput(marker,marker,null,null,null,"ACTIVE"));
        assertEquals("ACTIVE",idleStatus(departmentId),"重新启用部门后其待分配对象应恢复有效");
        assertTrue(idleInCatalog(departmentId),"重新启用部门的待分配对象应重新出现在填报目录");
    }
}
