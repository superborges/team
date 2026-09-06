package com.allen.worklog.operations;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobScheduler {
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private final JdbcClient jdbc;private final JobService jobs;private final ConfigService configs;
    public JobScheduler(JdbcClient jdbc,JobService jobs,ConfigService configs) {this.jdbc=jdbc;this.jobs=jobs;this.configs=configs;}
    @Transactional public void scan() {
        LocalDateTime now=jdbc.sql("SELECT UTC_TIMESTAMP(6)").query(LocalDateTime.class).single();
        LocalDate today=now.atOffset(ZoneOffset.UTC).atZoneSameInstant(ZONE).toLocalDate();
        jdbc.sql("INSERT IGNORE INTO worker_state(worker_key,heartbeat_at,last_scan_date) VALUES('scheduler',UTC_TIMESTAMP(6),?)").param(today.minusDays(7)).update();
        LocalDate last=jdbc.sql("SELECT last_scan_date FROM worker_state WHERE worker_key='scheduler' FOR UPDATE").query(LocalDate.class).single();
        // Process at most one month per tick; the persisted cursor catches up after long downtime.
        LocalDate end=last.plusDays(31).isBefore(today)?last.plusDays(31):today;
        for(LocalDate date=last;!date.isAfter(end);date=date.plusDays(1)) {
            var config=configs.forDate(date);
            for(var rule:config.rules()) {
                if(!rule.enabled()||rule.dayType().equals("EVENT"))continue;
                boolean automatic=rule.code().equals("AUTO_SUBMIT"),close=rule.code().equals("MONTH_CLOSE");
                LocalDate targetWeek=date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
                var p=automatic?configs.forDate(targetWeek).params():close?configs.forDate(date.withDayOfMonth(1).minusMonths(1)).params():config.params();
                boolean applies=automatic?date.getDayOfWeek().getValue()==p.submitWeekday():close?date.getDayOfMonth()==p.closeDay():switch(rule.dayType()) {
                    case "DAILY" -> true;case "WORKDAY" -> workday(date);case "WEEKLY" -> date.getDayOfWeek().getValue()==rule.dayOfWeek();case "MONTHLY" -> date.getDayOfMonth()==rule.dayOfMonth();default -> false;
                };
                if(!applies)continue;
                LocalDateTime due=date.atTime(LocalTime.parse(automatic?p.submitTime():close?"00:00":rule.time())).atZone(ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
                if(due.isAfter(now))continue;
                if(automatic) {
                    LocalDate week=date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
                    for(long user:jdbc.sql("""
                        SELECT DISTINCT u.id FROM app_user u JOIN reporting_enrollment e ON e.user_id=u.id AND e.voided=FALSE
                         WHERE u.status='ACTIVE' AND e.valid_from<? AND (e.valid_to IS NULL OR e.valid_to>?)
                        """).params(week.plusDays(7),week).query(Long.class).list())
                        jobs.enqueue("AUTO_SUBMIT","AUTO_SUBMIT:"+user+":"+week,Map.of("userId",Long.toString(user),"weekStart",week.toString()),null,due);
                } else if(close) {
                    LocalDate month=date.withDayOfMonth(1).minusMonths(1);
                    jobs.enqueue("MONTH_CLOSE","MONTH_CLOSE:"+month,Map.of("month",month.toString()),null,due);
                } else if(date.equals(today))jobs.enqueue("REMINDER","REMINDER:"+rule.code()+":"+date+":"+config.id(),Map.of("rule",rule.code(),"date",date.toString()),null,due);
            }
        }
        for(LocalDate month:jdbc.sql("SELECT month_start FROM accounting_period WHERE status='OPEN' AND scheduled_close_at<=UTC_TIMESTAMP(6) ORDER BY month_start").query(LocalDate.class).list())
            if(configs.forDate(month).rules().stream().anyMatch(r->r.code().equals("MONTH_CLOSE")&&r.enabled()))jobs.enqueue("MONTH_CLOSE","MONTH_CLOSE:"+month,Map.of("month",month.toString()),null,now);
        jdbc.sql("UPDATE worker_state SET heartbeat_at=UTC_TIMESTAMP(6),last_scan_date=? WHERE worker_key='scheduler'").param(end).update();
    }
    private boolean workday(LocalDate date) {return jdbc.sql("SELECT is_workday FROM work_calendar WHERE work_date=? AND is_override=TRUE").param(date).query(Boolean.class).optional().orElse(date.getDayOfWeek().getValue()<=5);}
}
