package com.allen.worklog.integrations;

import com.allen.worklog.common.ApiException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import static com.allen.worklog.integrations.ImportModels.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="WORKLOG_INTEGRATION",matches="true")
@SpringBootTest(properties="spring.profiles.active=local")
@Transactional
class ExtendedImportDatabaseTest {
    @Autowired ImportService imports;@Autowired SourceCoverageService coverage;@Autowired JdbcClient jdbc;
    @BeforeEach void actor() {SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("4",null,List.of()));}
    @AfterEach void clear() {SecurityContextHolder.clearContext();}
    private Batch apply(Dataset type,List<List<String>> rows) throws Exception {
        var preview=imports.preview(type.name(),"LOCAL-EXTENDED-TEST.xlsx",ImportWorkbookTest.workbook(type,rows));
        assertEquals(0,preview.errorCount(),preview.rows().toString());return imports.apply(Long.parseLong(preview.id()));
    }
    @Test void verifiedTripDoesNotCreateOnsiteAndReplayCannotUndoRevocation() throws Exception {
        LocalDate date=LocalDate.now(ZoneId.of("Asia/Shanghai"));String key="LOCAL-TRIP-"+UUID.randomUUID();
        int before=jdbc.sql("SELECT COUNT(*) FROM onsite_day").query(Integer.class).single();
        var approved=List.of(List.of(key,"001","98123",date.toString(),"TEST-P001","APPROVED"));
        var batch=apply(Dataset.TRIP,approved);assertEquals("APPLIED",batch.state());
        assertEquals(before,jdbc.sql("SELECT COUNT(*) FROM onsite_day").query(Integer.class).single());
        assertEquals("APPLIED",apply(Dataset.TRIP,List.of(List.of(key,"opaque-revoked","98123",date.toString(),"TEST-P001","REVOKED"))).state());
        assertEquals("APPLIED",apply(Dataset.TRIP,approved).state());
        assertEquals("REVOKED",jdbc.sql("SELECT status FROM trip_day WHERE source_key=?").param(key).query(String.class).single());
        assertEquals(1,jdbc.sql("SELECT COUNT(*) FROM trip_day WHERE source_key=?").param(key).query(Integer.class).single());
        var covered=coverage.save(new SourceCoverageService.Input("TRIP",date,date,"1","COMPLETE","本地事务回滚核实",batch.id()));
        assertEquals("COMPLETE",covered.get("state"));
    }
    @Test void personnelAndDepartmentTemplatesReuseMasterValidation() throws Exception {
        String code="LOCAL-DEPT-"+UUID.randomUUID().toString().substring(0,8);
        assertEquals("APPLIED",apply(Dataset.DEPARTMENT,List.of(List.of(code,"本地回滚部门","","ZD23412","AB12345"))).state());
        assertEquals(1,jdbc.sql("SELECT COUNT(*) FROM work_item w JOIN department d ON d.id=w.owner_department_id WHERE d.code=? AND w.type='IDLE' AND w.default_approver_id=3").param(code).query(Integer.class).single());
        String employee="EX"+String.format("%05d",Math.floorMod(System.nanoTime(),100000));
        while(jdbc.sql("SELECT COUNT(*) FROM app_user WHERE employee_no=?").param(employee).query(Integer.class).single()>0)employee="EX"+String.format("%05d",Math.floorMod(System.nanoTime(),100000));
        assertEquals("APPLIED",apply(Dataset.USER,List.of(List.of(employee,"","本地人员导入回滚",code,"MIDDLE",LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()))).state());
        long id=jdbc.sql("SELECT id FROM app_user WHERE employee_no=?").param(employee).query(Long.class).single();
        assertEquals(List.of("EMPLOYEE"),jdbc.sql("SELECT role_code FROM role_grant WHERE user_id=? AND revoked_at IS NULL").param(id).query(String.class).list());
    }
    @Test void failedBatchCannotClaimFullCoverageAndErrorWorkbookIsText() throws Exception {
        LocalDate date=LocalDate.now(ZoneId.of("Asia/Shanghai"));
        var preview=imports.preview("TRIP","LOCAL-ERROR.xlsx",ImportWorkbookTest.workbook(Dataset.TRIP,List.of(List.of("=1+1","v1","NO99999",date.toString(),"","APPROVED"))));
        assertEquals(1,preview.errorCount());
        assertThrows(ApiException.class,()->coverage.save(new SourceCoverageService.Input("TRIP",date,date,null,"COMPLETE","失败不能标完整",preview.id())));
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(ImportWorkbook.errors(preview)))) {
            var cell=book.getSheetAt(0).getRow(1).getCell(0);
            assertEquals(org.apache.poi.ss.usermodel.CellType.STRING,cell.getCellType());assertEquals("=1+1",cell.getStringCellValue());
        }
    }
}
