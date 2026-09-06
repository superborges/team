package com.allen.worklog.integrations;

import com.allen.worklog.common.ApiException;
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
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="WORKLOG_INTEGRATION",matches="true")
@SpringBootTest(properties={"spring.profiles.active=local","spring.flyway.out-of-order=true"})
@Transactional
class ProjectSourceLinkDatabaseTest {
    @Autowired ProjectSourceLinkService links;@Autowired ImportService imports;@Autowired JdbcClient jdbc;@Autowired ObjectMapper json;
    static void as(long id){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(Long.toString(id),null,List.of()));}
    long id(){return jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();}
    @BeforeEach void login(){as(4);assertEquals("local-admin",jdbc.sql("SELECT wecom_userid FROM app_user WHERE id=4").query(String.class).single());}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    long item(String marker){jdbc.sql("INSERT INTO work_item(code,name,type,owner_department_id,default_approver_id) VALUES(?,?,'PROJECT',1,2)").params(marker,"本地源关联验证旧名称").update();long id=id();jdbc.sql("INSERT INTO work_item_history(work_item_id,owner_department_id,valid_from) VALUES(?,1,'2026-01-01')").param(id).update();return id;}
    long batch(String code,String type){
        jdbc.sql("INSERT INTO import_batch(dataset,file_name,file_sha256,created_by) VALUES('PROJECT','source-link-it.xlsx',REPEAT('1',64),4)").update();long batch=id();
        // Explicit linking does not apply unverified historical names, organizational labels or source approvers.
        var raw=Map.of("code",code,"name","与历史不一致的外部名称","type",type,"departmentCode","待核实来源部门","approverEmployeeNo","尚未关联来源工号","effectiveFrom","2001-01-01");
        jdbc.sql("INSERT INTO import_row(batch_id,row_no,raw_data,valid,state,error_message) VALUES(?,2,?,FALSE,'INVALID','需核实对应既有项目')").params(batch,json.writeValueAsString(raw)).update();return batch;
    }
    @Test void explicitAliasKeepsStableTargetAndAppliesSourceWithoutOverwritingHistoricalFields(){
        String marker="LINK-IT-"+UUID.randomUUID().toString().substring(0,8);long target=item(marker),batch=batch(marker+"-EXT","PROJECT");
        var before=jdbc.sql("SELECT * FROM work_item WHERE id=?").param(target).query().singleRow();var history=jdbc.sql("SELECT * FROM work_item_history WHERE work_item_id=?").param(target).query().listOfRows();
        assertNull(links.find(marker+"-EXT"));var linked=links.link(batch,2,new ProjectSourceLinkService.LinkInput(Long.toString(target),"项目负责人核对合同编号确认同一项目"));
        assertEquals(Long.toString(target),linked.get("workItemId"));assertEquals(target,links.find(marker+"-EXT"));assertEquals(linked,links.link(batch,2,new ProjectSourceLinkService.LinkInput(Long.toString(target),"重复点击同一对应关系")));
        assertEquals("APPLIED",imports.apply(batch).state());assertEquals("APPLIED",imports.apply(batch).state());
        assertEquals(before,jdbc.sql("SELECT * FROM work_item WHERE id=?").param(target).query().singleRow());assertEquals(history,jdbc.sql("SELECT * FROM work_item_history WHERE work_item_id=?").param(target).query().listOfRows());
        assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM work_item WHERE code=?").param(marker+"-EXT").query(Integer.class).single());
        assertEquals(1,jdbc.sql("SELECT COUNT(*) FROM audit_event WHERE action='PROJECT_SOURCE_LINKED' AND object_id=?").param(linked.get("id")).query(Integer.class).single());
    }
    @Test void aliasesCannotRebindDirectCodesCannotHijackAndOnlyAdminsCanLink(){
        String marker="LINK-IT-"+UUID.randomUUID().toString().substring(0,8);long first=item(marker),second=item(marker+"-2"),batch=batch(marker+"-EXT","PROJECT");
        links.link(batch,2,new ProjectSourceLinkService.LinkInput(Long.toString(first),"已核实关联"));assertEquals(409,assertThrows(ApiException.class,()->links.link(batch,2,new ProjectSourceLinkService.LinkInput(Long.toString(second),"禁止改指向"))).status());
        long direct=batch(marker,"PROJECT");assertEquals("PROJECT_CODE_BOUND",assertThrows(ApiException.class,()->links.link(direct,2,new ProjectSourceLinkService.LinkInput(Long.toString(second),"禁止劫持已有编码"))).code());
        long mismatch=batch(marker+"-NON","NON_PROJECT");assertEquals(422,assertThrows(ApiException.class,()->links.link(mismatch,2,new ProjectSourceLinkService.LinkInput(Long.toString(first),"不能偷改类型"))).status());
        as(1);assertEquals(403,assertThrows(ApiException.class,links::list).status());assertEquals(403,assertThrows(ApiException.class,()->links.link(batch,2,new ProjectSourceLinkService.LinkInput(Long.toString(first),"员工无此权限"))).status());
    }
}
