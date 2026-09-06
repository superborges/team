package com.allen.worklog.operations;

import com.allen.worklog.common.ApiException;
import com.allen.worklog.master.MasterDataService;
import com.allen.worklog.master.MasterDtos.CalendarInput;
import com.allen.worklog.integrations.SourceCoverageService;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="WORKLOG_INTEGRATION",matches="true")
@SpringBootTest(properties="spring.profiles.active=local")
@Transactional
class PeriodBaseDatabaseTest {
    @Autowired JdbcClient jdbc;@Autowired MasterDataService master;@Autowired SourceCoverageService coverage;
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    @Test void sharedCalendarAndCoverageNeedEveryAffectedPersonsBaseGrant() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("4",null,List.of()));
        LocalDate month=LocalDate.of(1850,1,1);
        while(jdbc.sql("SELECT COUNT(*) FROM accounting_period WHERE month_start=?").param(month).query(Integer.class).single()>0)month=month.plusMonths(1);
        LocalDate day=month.plusDays(4);
        jdbc.sql("INSERT INTO accounting_period(month_start,scheduled_close_at,status) VALUES(?,?,'CLOSED')").params(month,month.plusMonths(1).atStartOfDay()).update();
        long period=jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        jdbc.sql("INSERT INTO amendment_batch(period_id,reason,created_by) VALUES(?,'本地共同基数授权回滚验证',4)").param(period).update();
        long amendment=jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        jdbc.sql("UPDATE accounting_period SET active_amendment_id=? WHERE id=?").params(amendment,period).update();
        for(long user:List.of(1L,2L)) {
            jdbc.sql("INSERT INTO reporting_enrollment(user_id,valid_from,valid_to) VALUES(?,?,?)").params(user,month,month.plusMonths(1)).update();
            jdbc.sql("INSERT INTO user_department_history(user_id,department_id,valid_from,valid_to) VALUES(?,1,?,?)").params(user,month,month.plusMonths(1)).update();
        }
        grant(amendment,1,day);
        assertThrows(ApiException.class,()->master.saveCalendar(day,new CalendarInput(false,0,"一人授权不能修改两人共同基数")));
        var input=new SourceCoverageService.Input("TRIP",day,day,"1","COMPLETE","全覆盖也须核实所有受影响人员",null);
        assertThrows(ApiException.class,()->coverage.save(input));
        grant(amendment,2,day);
        assertEquals(0,master.saveCalendar(day,new CalendarInput(false,0,"两人明确授权后可修改共同基数")).baseMinutes());
        assertEquals("COMPLETE",coverage.save(input).get("state"));
        jdbc.sql("UPDATE unlock_scope SET active=FALSE WHERE amendment_id=? AND user_id=2").param(amendment).update();
        assertThrows(ApiException.class,()->coverage.save(input));
    }
    private void grant(long amendment,long user,LocalDate day) {
        jdbc.sql("INSERT INTO unlock_scope(amendment_id,user_id,work_date,object_type,actions,reason,authorized_by) VALUES(?,?,?,'BASE',JSON_ARRAY('BASE'),'本地明确授权',4)").params(amendment,user,day).update();
    }
}
