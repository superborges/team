package com.allen.worklog.integrations;

import com.allen.worklog.closing.PeriodGate;
import com.allen.worklog.common.ApiException;
import com.allen.worklog.common.AuditService;
import com.allen.worklog.identity.CurrentUser;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class SourceCoverageService {
    public record Input(String dataset,LocalDate fromDate,LocalDate toDate,String departmentId,String state,String note,String importBatchId) {}
    private final JdbcClient jdbc;private final CurrentUser current;private final PeriodGate gate;private final AuditService audit;private final ObjectMapper json;
    public SourceCoverageService(JdbcClient jdbc,CurrentUser current,PeriodGate gate,AuditService audit,ObjectMapper json) {this.jdbc=jdbc;this.current=current;this.gate=gate;this.audit=audit;this.json=json;}
    public List<Map<String,Object>> list() {current.requireAdmin();return jdbc.sql("""
        SELECT CAST(c.id AS CHAR) id,c.dataset,c.from_date fromDate,c.to_date toDate,CAST(c.department_id AS CHAR) departmentId,
         d.name departmentName,c.state,c.note,u.name verifiedBy,c.verified_at verifiedAt,CAST(c.import_batch_id AS CHAR) importBatchId
        FROM source_coverage c LEFT JOIN department d ON d.id=c.department_id JOIN app_user u ON u.id=c.verified_by ORDER BY c.id DESC LIMIT 100
        """).query().listOfRows();}
    @Transactional public Map<String,Object> save(Input input) {
        long actor=current.requireAdmin().id();
        if(input==null||!Set.of("LEAVE","TRIP").contains(Objects.requireNonNullElse(input.dataset(),""))||!Set.of("COMPLETE","PARTIAL","FAILED").contains(Objects.requireNonNullElse(input.state(),""))||input.fromDate()==null||input.toDate()==null||input.toDate().isBefore(input.fromDate())||input.toDate().isAfter(input.fromDate().plusYears(1))||input.note()==null||input.note().isBlank()||input.note().length()>1000)throw new ApiException(422,"INVALID_COVERAGE","请填写不超过一年的核实范围、状态与说明");
        Long department=input.departmentId()==null?null:com.allen.worklog.work.DayRules.id(input.departmentId());
        if(department!=null&&jdbc.sql("SELECT COUNT(*) FROM department WHERE id=? AND status='ACTIVE'").param(department).query(Integer.class).single()==0)throw new ApiException(422,"UNKNOWN_DEPARTMENT","部门不存在或已停用");
        for(LocalDate day=input.fromDate();!day.isAfter(input.toDate());day=day.plusDays(1))gate.lockBaseForPopulation(day,department);
        jdbc.sql("SELECT id FROM app_user WHERE id=? FOR SHARE").param(actor).query(Long.class).single();current.requireAdmin();
        Long batch=input.importBatchId()==null?null:com.allen.worklog.work.DayRules.id(input.importBatchId());
        if(batch!=null) {
            String state=jdbc.sql("SELECT state FROM import_batch WHERE id=? AND dataset=? FOR UPDATE").params(batch,input.dataset()).query(String.class).optional().orElseThrow(()->new ApiException(422,"BATCH_MISMATCH","批次不存在或数据集不匹配"));
            if(input.state().equals("COMPLETE")&&!state.equals("APPLIED"))throw new ApiException(422,"COVERAGE_HAS_ERRORS","批次仍有未应用行，不能标记覆盖完整");
        }
        jdbc.sql("INSERT INTO source_coverage(dataset,from_date,to_date,department_id,state,verified_by,note,import_batch_id) VALUES(?,?,?,?,?,?,?,?)")
            .params(input.dataset(),input.fromDate(),input.toDate(),department,input.state(),actor,input.note().strip(),batch).update();
        long id=jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        audit.record(actor,"SOURCE_COVERAGE_VERIFIED","SOURCE_COVERAGE",Long.toString(id),null,json.writeValueAsString(input),input.note());
        return list().stream().filter(row->row.get("id").equals(Long.toString(id))).findFirst().orElseThrow();
    }
}
