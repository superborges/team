package com.allen.worklog.closing;

import com.allen.worklog.common.ApiException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Every caller must hold this lock in the same transaction as its business write. */
@Service
public class PeriodGate {
    private final JdbcClient jdbc;
    private final com.allen.worklog.operations.ConfigService configs;
    public PeriodGate(JdbcClient jdbc,com.allen.worklog.operations.ConfigService configs) { this.jdbc=jdbc;this.configs=configs; }
    public void lockForWrite(LocalDate date) {
        rejectClosed(lockMonth(date));
    }
    private State lockMonth(LocalDate date) {
        requireTransaction();
        LocalDate month=date.withDayOfMonth(1);
        boolean exists=jdbc.sql("SELECT COUNT(*) FROM accounting_period WHERE month_start=:month")
            .param("month",month).query(Integer.class).single()>0;
        if(!exists) jdbc.sql("INSERT IGNORE INTO accounting_period(month_start,scheduled_close_at) VALUES(:month,:close)")
            .param("month",month).param("close",configuredClose(month)).update();
        var period=jdbc.sql("""
            SELECT status, scheduled_close_at<=UTC_TIMESTAMP(6) due FROM accounting_period
            WHERE month_start=:month FOR SHARE
            """).param("month",month).query((rs,n)->new State(rs.getString("status"),rs.getBoolean("due"))).single();
        return period;
    }
    public void lockForAction(LocalDate date,long userId,long workItemId,String objectType,Long recordId,String action) {
        State state=lockMonth(date);
        if(!"CLOSED".equals(state.status())&&!state.due())return;
        if(!scope(date,userId,workItemId,objectType,recordId,action))rejectClosed(state);
    }
    public void lockForDay(LocalDate date,long userId) {
        State state=lockMonth(date);
        if(!"CLOSED".equals(state.status())&&!state.due())return;
        if(!canEditDay(date,userId))rejectClosed(state);
    }
    public boolean canEditDay(LocalDate date,long userId) {
        if(status(date).equals("OPEN"))return true;
        return jdbc.sql("""
            SELECT COUNT(*) FROM accounting_period p JOIN amendment_batch b ON b.id=p.active_amendment_id AND b.state='OPEN'
            JOIN unlock_scope s ON s.amendment_id=b.id AND s.active=TRUE WHERE p.month_start=? AND s.user_id=? AND s.work_date=?
              AND (JSON_CONTAINS(s.actions,'\"SAVE\"') OR JSON_CONTAINS(s.actions,'\"ADD\"') OR JSON_CONTAINS(s.actions,'\"CORRECT\"'))
            """).params(date.withDayOfMonth(1),userId,date).query(Integer.class).single()>0;
    }
    public boolean scope(LocalDate date,long userId,long item,String type,Long record,String action) {
        return jdbc.sql("""
            SELECT COUNT(*) FROM accounting_period p JOIN amendment_batch b ON b.id=p.active_amendment_id AND b.state='OPEN'
            JOIN unlock_scope s ON s.amendment_id=b.id AND s.active=TRUE
            WHERE p.month_start=? AND s.user_id=? AND s.work_date=? AND s.object_type=?
             AND (?='BASE' OR s.work_item_id=?) AND JSON_CONTAINS(s.actions,JSON_QUOTE(?))
             AND ((?='BASE' AND s.record_id IS NULL) OR (? IS NULL AND s.record_id IS NULL)
               OR s.record_id=? OR (s.record_id IS NULL AND ?>s.new_after_record_id))
            """).params(date.withDayOfMonth(1),userId,date,type,type,item,action,type,record,record,record).query(Integer.class).single()>0;
    }
    /** A shared calendar/coverage change requires BASE permission for every affected person. */
    public void lockBaseForPopulation(LocalDate date,Long departmentId) {
        State state=lockMonth(date);
        if(!"CLOSED".equals(state.status())&&!state.due())return;
        var users=jdbc.sql("""
            SELECT DISTINCT e.user_id FROM reporting_enrollment e
            JOIN user_department_history h ON h.user_id=e.user_id AND h.valid_from<=? AND (h.valid_to IS NULL OR h.valid_to>?)
            WHERE e.voided=FALSE AND e.valid_from<=? AND (e.valid_to IS NULL OR e.valid_to>?) AND (? IS NULL OR h.department_id=?)
            UNION SELECT d.user_id FROM day_record d WHERE d.work_date=? AND (? IS NULL OR d.department_id=?)
            """).params(date,date,date,date,departmentId,departmentId,date,departmentId,departmentId).query(Long.class).list();
        if(users.isEmpty())rejectClosed(state);
        for(long user:users)if(!scope(date,user,0,"BASE",null,"BASE"))rejectClosed(state);
    }
    public void lockHistory(LocalDate from,LocalDate to) {
        requireTransaction();
        // An absent month row must not make an already elapsed cutoff disappear.
        lockForWrite(from);
        List<State> states=jdbc.sql("""
            SELECT status,scheduled_close_at<=UTC_TIMESTAMP(6) due FROM accounting_period
            WHERE LAST_DAY(month_start)>=:fromDate AND (:toDate IS NULL OR month_start<:toDate)
            ORDER BY month_start FOR SHARE
            """).param("fromDate",from).param("toDate",to,java.sql.Types.DATE)
            .query((rs,n)->new State(rs.getString("status"),rs.getBoolean("due"))).list();
        states.forEach(this::rejectClosed);
    }
    public String status(LocalDate date) {
        return jdbc.sql("""
            SELECT CASE WHEN status='CLOSED' THEN 'CLOSED'
                WHEN scheduled_close_at<=UTC_TIMESTAMP(6) THEN 'CLOSING' ELSE 'OPEN' END
            FROM accounting_period WHERE month_start=:month
            """).param("month",date.withDayOfMonth(1)).query(String.class).optional()
            .orElseGet(()->{
                LocalDateTime now=jdbc.sql("SELECT UTC_TIMESTAMP(6)").query(LocalDateTime.class).single();
                return now.isBefore(configuredClose(date.withDayOfMonth(1)))?"OPEN":"CLOSING";
            });
    }
    public static LocalDateTime scheduledClose(LocalDate month) {
        return month.plusMonths(1).withDayOfMonth(10).atStartOfDay(ZoneId.of("Asia/Shanghai"))
            .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
    public LocalDateTime configuredClose(LocalDate month) {
        return month.plusMonths(1).withDayOfMonth(configs.forDate(month).params().closeDay()).atStartOfDay(ZoneId.of("Asia/Shanghai"))
            .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
    private void rejectClosed(State state) {
        if("CLOSED".equals(state.status()))throw new ApiException(409,"PERIOD_CLOSED","该月份已封账，需要按授权流程处理");
        if(state.due())throw new ApiException(409,"PERIOD_CLOSING","已到该月份封账时点，不能新增或修改历史数据");
    }
    private void requireTransaction() {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Period gate requires a business transaction");
    }
    private record State(String status,boolean due) {}
}
