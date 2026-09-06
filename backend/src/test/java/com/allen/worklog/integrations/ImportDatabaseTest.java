package com.allen.worklog.integrations;

import com.allen.worklog.common.ApiException;
import com.allen.worklog.master.MasterDataService;
import com.allen.worklog.master.MasterDtos.UserInput;
import com.allen.worklog.work.DayService;
import com.allen.worklog.work.DayModels.SaveDay;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static com.allen.worklog.integrations.ImportModels.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit local integration: keeps named verification sources and their audit history. */
@EnabledIfEnvironmentVariable(named="WORKLOG_INTEGRATION",matches="true")
@SpringBootTest(properties={"spring.profiles.active=local","spring.flyway.out-of-order=true"})
class ImportDatabaseTest {
    @Autowired ImportService imports;
    @Autowired MasterDataService master;
    @Autowired DayService days;
    @Autowired JdbcClient jdbc;

    private static void as(long id) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(Long.toString(id),null,List.of()));
    }
    private Batch preview(Dataset type,List<List<String>> values) throws Exception {
        return imports.preview(type.name(),"LOCAL-IMPORT-VERIFICATION-"+type+".xlsx",ImportWorkbookTest.workbook(type,values));
    }
    private Batch apply(Batch batch) { return imports.apply(Long.parseLong(batch.id())); }
    private List<String> leave(String key,String version,String employee,LocalDate day,String minutes,String start,String end,String state) {
        return List.of(key,version,employee,day.toString(),minutes,start,end,state);
    }
    @Test void previewsAreDryAndRowTransactionsPreserveSourceHistoryAndDeduplicateLeave() throws Exception {
        as(4);
        try {
            assertEquals("local-admin",jdbc.sql("SELECT wecom_userid FROM app_user WHERE id=4").query(String.class).single());
            LocalDate today=LocalDate.now(ZoneId.of("Asia/Shanghai"));
            String marker="IT-"+UUID.randomUUID().toString().substring(0,12);
            int employeeSuffix=(int)(System.nanoTime()%100000);String employee=String.format("IT%05d",Math.abs(employeeSuffix));
            while(jdbc.sql("SELECT COUNT(*) FROM app_user WHERE employee_no=?").param(employee).query(Integer.class).single()>0)employee=String.format("IT%05d",(++employeeSuffix)%100000);
            LocalDate lateDay=today.plusDays(30);while(lateDay.getDayOfWeek().getValue()>5)lateDay=lateDay.plusDays(1);
            String lateSource=marker+"-LATE";
            Batch earlyFailure=preview(Dataset.LEAVE,List.of(leave(lateSource,"original-failed",employee,lateDay,"240","540","780","APPROVED")));
            assertEquals(0,earlyFailure.validCount());assertEquals("PARTIAL",apply(earlyFailure).state());
            var user=master.saveUser(null,new UserInput(employee,"local-import-"+marker,"本地导入验证 "+marker,"1","MIDDLE","ACTIVE",today,List.of("EMPLOYEE")));
            long userId=Long.parseLong(user.id());

            int count=jdbc.sql("SELECT COUNT(*) FROM work_item").query(Integer.class).single();
            Batch project=preview(Dataset.PROJECT,List.of(
                List.of(marker,"本地导入验证项目 "+marker,"PROJECT","TEST-DELIVERY","00123",today.toString()),
                List.of(marker+"-ERR","本地错误部门行","PROJECT","UNKNOWN-DEPARTMENT","00123",today.toString())));
            assertEquals(1,project.validCount());assertEquals(1,project.errorCount());
            assertEquals(count,jdbc.sql("SELECT COUNT(*) FROM work_item").query(Integer.class).single());
            Batch partial=apply(project);assertEquals("PARTIAL",partial.state());assertEquals(1,partial.appliedCount());
            assertEquals(1,apply(project).appliedCount());assertEquals(count+1,jdbc.sql("SELECT COUNT(*) FROM work_item").query(Integer.class).single());
            var repeatedProject=preview(Dataset.PROJECT,List.of(List.of(marker,"重复项目","PROJECT","TEST-DELIVERY","00123",today.toString())));
            assertEquals(1,repeatedProject.validCount());assertEquals("APPLIED",apply(repeatedProject).state());
            assertEquals(count+1,jdbc.sql("SELECT COUNT(*) FROM work_item").query(Integer.class).single());
            assertEquals("本地导入验证项目 "+marker,jdbc.sql("SELECT name FROM work_item WHERE code=?").param(marker).query(String.class).single());

            LocalDate latest=jdbc.sql("SELECT MAX(valid_from) FROM rate_card WHERE level_code='JUNIOR'").query(LocalDate.class).single();
            LocalDate rateFrom=today.plusYears(1).isAfter(latest)?today.plusYears(1):latest.plusDays(1);
            Batch rate=preview(Dataset.RATE,List.of(List.of("JUNIOR","999.1234",rateFrom.toString(),"")));
            assertEquals(1,rate.validCount());assertEquals("APPLIED",apply(rate).state());assertEquals(1,apply(rate).appliedCount());

            LocalDate day=today.plusDays(20);while(day.getDayOfWeek().getValue()>5)day=day.plusDays(1);
            as(userId);days.save(day,new SaveDay(0,List.of(),null),null);as(4);
            String sourceA=marker+"-A",sourceB=marker+"-B";
            var firstValues=List.of(leave(sourceA,"version-01",employee,day,"240","540","780","APPROVED"));
            Batch first=preview(Dataset.LEAVE,firstValues);assertEquals(1,first.validCount());assertEquals("APPLIED",apply(first).state());
            int version=dayValue(userId,day,"row_version");assertEquals(240,dayValue(userId,day,"required_minutes"));
            assertEquals("APPLIED",apply(preview(Dataset.LEAVE,firstValues)).state());assertEquals(version,dayValue(userId,day,"row_version"));
            assertEquals(1,jdbc.sql("SELECT COUNT(*) FROM leave_record WHERE source_key=?").param(sourceA).query(Integer.class).single());

            Batch overlap=preview(Dataset.LEAVE,List.of(leave(sourceB,"opaque-next",employee,day,"240","600","840","APPROVED")));
            assertEquals("APPLIED",apply(overlap).state());assertEquals(180,dayValue(userId,day,"required_minutes"));
            version=dayValue(userId,day,"row_version");
            Batch ambiguous=preview(Dataset.LEAVE,List.of(leave(marker+"-UNKNOWN","1",employee,day,"240","","","APPROVED")));
            assertEquals(0,ambiguous.validCount());assertEquals("PARTIAL",apply(ambiguous).state());assertEquals(version,dayValue(userId,day,"row_version"));

            Batch conflict=preview(Dataset.LEAVE,List.of(leave(sourceA,"version-01",employee,day,"120","540","660","APPROVED")));
            assertEquals(0,conflict.validCount());assertEquals("PARTIAL",apply(conflict).state());assertEquals(version,dayValue(userId,day,"row_version"));

            Batch revoke=preview(Dataset.LEAVE,List.of(leave(sourceA,"opaque-revoke",employee,day,"240","540","780","REVOKED")));
            assertEquals("APPLIED",apply(revoke).state());assertEquals(240,dayValue(userId,day,"required_minutes"));
            version=dayValue(userId,day,"row_version");
            assertEquals("APPLIED",apply(preview(Dataset.LEAVE,firstValues)).state());assertEquals(version,dayValue(userId,day,"row_version"));
            assertEquals("REVOKED",jdbc.sql("SELECT status FROM leave_record WHERE source_key=?").param(sourceA).query(String.class).single());
            Batch move=preview(Dataset.LEAVE,List.of(leave(sourceA,"moved",employee,day.plusDays(1),"240","540","780","APPROVED")));
            assertEquals(0,move.validCount());assertEquals("PARTIAL",apply(move).state());

            LocalDate closed=today.withDayOfMonth(1).minusMonths(2);
            Batch old=preview(Dataset.LEAVE,List.of(leave(marker+"-CLOSED","1",employee,closed,"240","540","780","APPROVED")));
            assertEquals(0,old.validCount());assertEquals("PARTIAL",apply(old).state());
            assertEquals("FAILED",jdbc.sql("SELECT application_state FROM source_record WHERE dataset='LEAVE' AND source_key=?").param(marker+"-CLOSED").query(String.class).single());
            assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM leave_record WHERE source_key=?").param(marker+"-CLOSED").query(Integer.class).single());

            assertEquals("APPLIED",apply(preview(Dataset.LEAVE,List.of(leave(lateSource,"now-valid",employee,lateDay,"240","540","780","APPROVED")))).state());
            assertEquals("APPLIED",apply(preview(Dataset.LEAVE,List.of(leave(lateSource,"now-revoked",employee,lateDay,"240","540","780","REVOKED")))).state());
            Batch stale=apply(earlyFailure);
            assertEquals("PARTIAL",stale.state());assertTrue(stale.rows().getFirst().error().contains("已被之后接收"));
            assertEquals("REVOKED",jdbc.sql("SELECT status FROM leave_record WHERE source_key=?").param(lateSource).query(String.class).single());

            as(userId);assertThrows(ApiException.class,()->imports.list());assertThrows(ApiException.class,()->imports.template("LEAVE"));
        } finally { SecurityContextHolder.clearContext(); }
    }
    private int dayValue(long user,LocalDate date,String column) {
        return jdbc.sql("SELECT "+column+" FROM day_record WHERE user_id=? AND work_date=?").params(user,date).query(Integer.class).single();
    }
}
