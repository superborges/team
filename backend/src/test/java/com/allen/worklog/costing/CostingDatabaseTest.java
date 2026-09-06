package com.allen.worklog.costing;

import com.allen.worklog.common.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit local MySQL check. Fixture writes are rolled back; no existing rate is changed. */
@EnabledIfEnvironmentVariable(named="WORKLOG_INTEGRATION",matches="true")
class CostingDatabaseTest {
    @Test void effectiveDateQueriesUseHistoricalGradeAndRejectUncoveredDates() {
        var ds=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:13306/worklog?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true","worklog","worklog-local");
        var jdbc=JdbcClient.create(ds);
        new TransactionTemplate(new JdbcTransactionManager(ds)).executeWithoutResult(tx->{
            tx.setRollbackOnly();
            assertEquals("MySQL",jdbc.sql("SELECT IF(VERSION() LIKE '8.4.%','MySQL','other')").query(String.class).single());
            jdbc.sql("INSERT INTO app_user(employee_no,name,department_id,level_code) VALUES ('CT54321','本地计价事务验证',1,'SENIOR')").update();
            long user=jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
            jdbc.sql("INSERT INTO user_level_history(user_id,level_code,valid_from,valid_to) VALUES (?,'MIDDLE','2026-01-01','2026-09-01'),(?,'SENIOR','2026-09-01',NULL)").params(user,user).update();
            var service=new CostingService(jdbc);
            var before=service.resolveLabor(user,LocalDate.of(2026,8,31),240);
            var after=service.resolveLabor(user,LocalDate.of(2026,9,1),240);
            assertEquals("MIDDLE",before.levelCode());assertEquals(new BigDecimal("600.00"),before.amount());
            assertEquals("SENIOR",after.levelCode());assertEquals(new BigDecimal("800.00"),after.amount());
            assertNotEquals(before.levelHistoryId(),after.levelHistoryId());
            assertEquals(new BigDecimal("200.00"),service.resolveOnsite(LocalDate.of(2026,8,31)).amount());
            assertThrows(ApiException.class,()->service.resolveLabor(user,LocalDate.of(2025,12,31),240));
            assertThrows(ApiException.class,()->service.resolveOnsite(LocalDate.of(2025,12,31)));
        });
    }
}
