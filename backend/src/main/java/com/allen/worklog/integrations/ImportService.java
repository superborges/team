package com.allen.worklog.integrations;

import com.allen.worklog.closing.PeriodGate;
import com.allen.worklog.common.ApiException;
import com.allen.worklog.common.AuditService;
import com.allen.worklog.identity.CurrentUser;
import com.allen.worklog.master.MasterDataService;
import com.allen.worklog.master.MasterDtos.*;
import com.allen.worklog.master.MasterValidation;
import com.allen.worklog.work.DayRules;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import static com.allen.worklog.integrations.ImportModels.*;

@Service
public class ImportService {
    private final JdbcClient jdbc;
    private final CurrentUser current;
    private final MasterDataService master;
    private final PeriodGate periods;
    private final AuditService audit;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final com.allen.worklog.operations.ConfigService configs;
    private final ProjectSourceLinkService projectLinks;
    public ImportService(JdbcClient jdbc,CurrentUser current,MasterDataService master,PeriodGate periods,
                         AuditService audit,ObjectMapper json,PlatformTransactionManager manager,com.allen.worklog.operations.ConfigService configs,ProjectSourceLinkService projectLinks) {
        this.jdbc=jdbc;this.current=current;this.master=master;this.periods=periods;this.audit=audit;this.json=json;
        this.tx=new TransactionTemplate(manager);this.configs=configs;this.projectLinks=projectLinks;
    }

    public byte[] template(String dataset) { current.requireAdmin();return ImportWorkbook.template(Dataset.parse(dataset)); }

    public Batch preview(String dataset,String fileName,byte[] bytes) {
        long actor=current.requireAdmin().id();Dataset type=Dataset.parse(dataset);
        if(fileName==null||!fileName.toLowerCase().endsWith(".xlsx"))throw error("IMPORT_TYPE","只接受 .xlsx 模板文件");
        var parsed=ImportWorkbook.read(type,bytes);
        List<RowView> rows=new ArrayList<>();
        for(var row:parsed) {
            String issue=row.parseError();
            if(issue==null)try { validate(type,row.data()); } catch(ApiException e) { issue=e.getMessage(); }
            rows.add(new RowView(row.rowNumber(),row.data(),issue==null,issue,issue==null?"VALID":"INVALID"));
        }
        long id=tx.execute(status->{
            current.requireAdmin();
            jdbc.sql("INSERT INTO import_batch(dataset,file_name,file_sha256,created_by) VALUES(?,?,?,?)")
                .params(type.name(),safeName(fileName),hash(bytes),actor).update();
            long batch=lastId();
            for(int i=0;i<rows.size();i++) {
                var row=rows.get(i);
                jdbc.sql("INSERT INTO import_row(batch_id,row_no,raw_data,parse_error,valid,state,error_message) VALUES(?,?,?,?,?,?,?)")
                    .params(batch,row.rowNumber(),json.writeValueAsString(row.data()),parsed.get(i).parseError(),row.valid(),row.state(),row.error()).update();
            }
            audit.record(actor,"IMPORT_PREVIEW","IMPORT_BATCH",Long.toString(batch),null,json.writeValueAsString(Map.of("dataset",dataset,"rows",rows.size())),"上传固定 XLSX 模板并预览；尚未应用业务数据");
            return batch;
        });
        return get(id);
    }

    public List<BatchSummary> list() {
        current.requireAdmin();
        return jdbc.sql(summarySql()+" GROUP BY b.id ORDER BY b.id DESC LIMIT 50").query((rs,n)->new BatchSummary(
            rs.getString("id"),rs.getString("dataset"),rs.getString("state"),rs.getString("file_name"),rs.getTimestamp("created_at").toInstant().toString(),
            rs.getInt("valid_count"),rs.getInt("error_count"),rs.getInt("applied_count"))).list();
    }
    public Batch get(long id) {
        current.requireAdmin();
        var summary=jdbc.sql(summarySql()+" WHERE b.id=? GROUP BY b.id").param(id).query((rs,n)->new BatchSummary(
            rs.getString("id"),rs.getString("dataset"),rs.getString("state"),rs.getString("file_name"),rs.getTimestamp("created_at").toInstant().toString(),
            rs.getInt("valid_count"),rs.getInt("error_count"),rs.getInt("applied_count"))).optional()
            .orElseThrow(()->new ApiException(404,"IMPORT_NOT_FOUND","导入批次不存在"));
        var rows=jdbc.sql("SELECT * FROM import_row WHERE batch_id=? ORDER BY row_no").param(id)
            .query((rs,n)->new RowView(rs.getInt("row_no"),data(rs.getString("raw_data")),rs.getBoolean("valid"),rs.getString("error_message"),rs.getString("state"))).list();
        return new Batch(summary.id(),summary.dataset(),summary.state(),summary.fileName(),summary.createdAt(),summary.validCount(),summary.errorCount(),summary.appliedCount(),rows);
    }
    private String summarySql() { return """
        SELECT b.*,COALESCE(SUM(r.valid),0) valid_count,COALESCE(SUM(NOT r.valid),0) error_count,
            COALESCE(SUM(r.state='APPLIED'),0) applied_count
        FROM import_batch b LEFT JOIN import_row r ON r.batch_id=b.id
        """; }

    public Batch apply(long batchId) {
        Dataset dataset=Dataset.parse(get(batchId).dataset());
        var ids=jdbc.sql("SELECT id FROM import_row WHERE batch_id=? ORDER BY row_no").param(batchId).query(Long.class).list();
        for(long id:ids) {
            current.requireAdmin();
            if(row(id,false).state().equals("APPLIED"))continue;
            try {
                // Preserve the received source independently of a failed business transaction.
                tx.executeWithoutResult(status->prepareSource(id,dataset));
                tx.executeWithoutResult(status->applyRow(id,dataset));
            } catch(ApiException | DataIntegrityViolationException e) {
                String issue=e instanceof ApiException?e.getMessage():"数据与已存在记录冲突，请核实编码、来源版本或生效区间后重试";
                tx.executeWithoutResult(status->failed(id,issue));
            }
        }
        tx.executeWithoutResult(status->{
            jdbc.sql("SELECT id FROM import_batch WHERE id=? FOR UPDATE").param(batchId).query(Long.class).single();
            int pending=jdbc.sql("SELECT COUNT(*) FROM import_row WHERE batch_id=? AND state<>'APPLIED'").param(batchId).query(Integer.class).single();
            jdbc.sql("UPDATE import_batch SET state=? WHERE id=?").params(pending==0?"APPLIED":"PARTIAL",batchId).update();
        });
        return get(batchId);
    }

    private void prepareSource(long rowId,Dataset dataset) {
        current.requireAdmin();var row=row(rowId,true);
        if(row.state().equals("APPLIED"))return;
        if(row.parseError()!=null)throw error("IMPORT_ROW_FORMAT",row.parseError());
        Map<String,String> d=row.data();
        boolean versioned=dataset==Dataset.LEAVE||dataset==Dataset.TRIP;
        String key=versioned?text(d,"sourceKey",150):"IMPORT:"+row.batch()+":"+row.number();
        String version=versioned?text(d,"sourceVersion",60):"1";
        String normalized=normalized(d),sha=hash(normalized.getBytes(StandardCharsets.UTF_8));
        jdbc.sql("INSERT IGNORE INTO source_record(dataset,source_key,source_version,normalized_data,content_sha256) VALUES(?,?,?,?,?)")
            .params(dataset.name(),key,version,normalized,sha).update();
        var source=jdbc.sql("SELECT id,content_sha256 FROM source_record WHERE dataset=? AND source_key=? AND source_version=?")
            .params(dataset.name(),key,version).query((rs,n)->new Source(rs.getLong("id"),rs.getString("content_sha256"))).single();
        if(!source.hash().equals(sha))throw error("SOURCE_VERSION_CONFLICT","同一来源版本已收到不同内容；请核实来源版本，不允许覆盖原材料");
        jdbc.sql("UPDATE import_row SET source_record_id=? WHERE id=?").params(source.id(),rowId).update();
    }

    private void applyRow(long rowId,Dataset dataset) {
        long actor=current.requireAdmin().id();var initial=row(rowId,false);
        if(initial.state().equals("APPLIED"))return;
        Map<String,String> d=initial.data();
        // A previously applied opaque source version is a no-op, never a rollback to old content.
        if(initial.source()!=null && sourceApplied(initial.source())) {
            var locked=row(rowId,true);markApplied(locked.id(),locked.source());return;
        }
        validate(dataset,d);
        Long leaveUser=null;
        if(dataset==Dataset.LEAVE) {
            LocalDate day=date(d,"date",false);leaveUser=employee(d.get("employeeNo"),false);periods.lockForAction(day,leaveUser,0,"BASE",null,"BASE");
            ensureCalendar(day);
            // ponytail: one short date lock serializes leave imports; split only if measured throughput requires it.
            jdbc.sql("SELECT work_date FROM work_calendar WHERE work_date=? FOR UPDATE").param(day).query(LocalDate.class).single();
            leaveUser=employee(d.get("employeeNo"),false);
            jdbc.sql("SELECT id FROM app_user WHERE id IN (?,?) ORDER BY id FOR SHARE").params(actor,leaveUser).query(Long.class).list();
            current.requireAdmin();
            jdbc.sql("SELECT id FROM day_record WHERE user_id=? AND work_date=? FOR UPDATE").params(leaveUser,day).query(Long.class).optional();
        } else if(dataset==Dataset.TRIP) {
            LocalDate day=date(d,"date",false);periods.lockForAction(day,employee(d.get("employeeNo"),false),0,"BASE",null,"BASE");ensureCalendar(day);
            jdbc.sql("SELECT work_date FROM work_calendar WHERE work_date=? FOR UPDATE").param(day).query(LocalDate.class).single();
        } else if(dataset!=Dataset.DEPARTMENT&&(dataset!=Dataset.PROJECT||projectLinks.find(d.get("code"))==null)) periods.lockHistory(date(d,"effectiveFrom",false),date(d,"effectiveTo",true));
        Long linkedProject=dataset==Dataset.PROJECT?projectLinks.lockForApply(d.get("code")):null;
        if(dataset==Dataset.TRIP||(dataset==Dataset.PROJECT&&linkedProject!=null)) {
            jdbc.sql("SELECT id FROM app_user WHERE id=? FOR SHARE").param(actor).query(Long.class).single();current.requireAdmin();
        }
        var row=row(rowId,true);
        if(row.state().equals("APPLIED"))return;
        jdbc.sql("SELECT id FROM source_record WHERE id=? FOR UPDATE").param(row.source()).query(Long.class).single();
        if(sourceApplied(row.source())) { markApplied(row.id(),row.source());return; }
        validate(dataset,d);
        switch(dataset) {
            case PROJECT -> {
                if(linkedProject==null) {
                    var created=master.saveWorkItem(null,new WorkItemInput(d.get("code"),d.get("name"),d.get("type"),
                        Long.toString(department(d.get("departmentCode"))),Long.toString(employee(d.get("approverEmployeeNo"),true)),"LOCAL","ACTIVE",date(d,"effectiveFrom",false)));
                    projectLinks.bindCreated(d.get("code"),Long.parseLong(created.id()));
                }
            }
            case RATE -> master.addRate(new RateInput(d.get("dimension"),d.get("dailyRate"),date(d,"effectiveFrom",false),date(d,"effectiveTo",true)));
            case LEAVE -> applyLeave(d,leaveUser,row.source());
            case USER -> master.saveUser(null,new UserInput(d.get("employeeNo"),empty(d.get("wecomUserid")),d.get("name"),
                Long.toString(department(d.get("departmentCode"))),d.get("level"),"ACTIVE",date(d,"effectiveFrom",false),List.of("EMPLOYEE")));
            case DEPARTMENT -> master.saveDepartment(null,new DepartmentInput(d.get("code"),d.get("name"),
                blank(d.get("parentCode"))?null:Long.toString(department(d.get("parentCode"))),
                blank(d.get("approverEmployeeNo"))?null:Long.toString(employee(d.get("approverEmployeeNo"),true)),
                blank(d.get("supervisorEmployeeNo"))?null:Long.toString(employee(d.get("supervisorEmployeeNo"),true)),"ACTIVE"));
            case TRIP -> applyTrip(d,row.source());
        }
        markApplied(row.id(),row.source());
        audit.record(actor,"IMPORT_ROW_APPLIED","IMPORT_ROW",Long.toString(row.id()),null,json.writeValueAsString(d),"管理员确认应用 "+dataset+" 来源行");
    }

    private void markApplied(long rowId,Long source) {
        jdbc.sql("UPDATE import_row SET valid=TRUE,state='APPLIED',error_message=NULL,applied_at=UTC_TIMESTAMP(6) WHERE id=?").param(rowId).update();
        jdbc.sql("UPDATE source_record SET application_state='APPLIED',error_message=NULL,applied_at=COALESCE(applied_at,UTC_TIMESTAMP(6)) WHERE id=?").param(source).update();
    }
    private void failed(long rowId,String issue) {
        var row=row(rowId,true);if(row.state().equals("APPLIED"))return;
        jdbc.sql("UPDATE import_row SET valid=FALSE,state='FAILED',error_message=? WHERE id=?").params(issue,rowId).update();
        if(row.source()!=null)jdbc.sql("UPDATE source_record SET application_state='FAILED',error_message=? WHERE id=? AND application_state<>'APPLIED'").params(issue,row.source()).update();
    }

    private void applyLeave(Map<String,String> d,long user,long source) {
        String key=d.get("sourceKey");LocalDate day=date(d,"date",false);
        jdbc.sql("INSERT IGNORE INTO leave_source(source_key) VALUES(?)").param(key).update();
        var bound=jdbc.sql("SELECT user_id,work_date,current_source_record_id FROM leave_source WHERE source_key=? FOR UPDATE").param(key)
            .query((rs,n)->new Binding(rs.getObject("user_id",Long.class),rs.getObject("work_date",LocalDate.class),rs.getObject("current_source_record_id",Long.class))).single();
        if(bound.user()!=null&&(!bound.user().equals(user)||!bound.date().equals(day)))throw error("SOURCE_IDENTITY_CONFLICT","来源已绑定其他人员或日期；撤销旧来源后使用新 sourceKey，不能静默移动");
        if(bound.source()!=null&&source<bound.source())throw error("STALE_SOURCE_VERSION","此来源行已被之后接收并应用的版本取代，重试不能覆盖新版本；请核实后提供新的来源版本");
        validateLeave(d);
        jdbc.sql("""
            INSERT INTO leave_record(user_id,work_date,source_key,source_version,leave_minutes,start_minute,end_minute,status)
            VALUES(?,?,?,?,?,?,?,?) AS incoming
            ON DUPLICATE KEY UPDATE source_version=incoming.source_version,leave_minutes=incoming.leave_minutes,
              start_minute=incoming.start_minute,end_minute=incoming.end_minute,status=incoming.status
            """).params(user,day,key,d.get("sourceVersion"),integer(d,"leaveMinutes",false),integer(d,"startMinute",true),integer(d,"endMinute",true),d.get("status")).update();
        int defaultBase=day.getDayOfWeek().getValue()<=5?configs.forDate(day).params().defaultDayMinutes():0;
        var calendar=jdbc.sql("SELECT base_minutes,is_workday FROM work_calendar WHERE work_date=? AND is_override=TRUE").param(day)
            .query((rs,n)->new Calendar(rs.getInt("base_minutes"),rs.getBoolean("is_workday"))).optional().orElse(new Calendar(defaultBase,defaultBase>0));
        int leave=DayRules.leaveMinutes(segments(user,day,null),calendar.base());
        jdbc.sql("UPDATE day_record SET base_minutes=?,is_workday=?,leave_minutes=?,required_minutes=?,row_version=row_version+1 WHERE user_id=? AND work_date=?")
            .params(calendar.base(),calendar.workday(),leave,Math.max(calendar.base()-leave,0),user,day).update();
        jdbc.sql("UPDATE leave_source SET user_id=?,work_date=?,current_source_record_id=? WHERE source_key=?").params(user,day,source,key).update();
    }

    private void validate(Dataset dataset,Map<String,String> d) {
        switch(dataset) {
            case PROJECT -> {
                text(d,"code",60);text(d,"name",150);String type=one(d,"type",Set.of("PROJECT","NON_PROJECT"));
                Long linked=projectLinks.find(d.get("code"));
                if(linked!=null) {
                    if(!jdbc.sql("SELECT type FROM work_item WHERE id=?").param(linked).query(String.class).single().equals(type))throw error("PROJECT_TYPE_CONFLICT","来源类型与已关联对象不一致，请核实原始来源，不能覆盖已有类型");
                    break;
                }
                department(text(d,"departmentCode",60));employee(text(d,"approverEmployeeNo",20),true);
                LocalDate from=date(d,"effectiveFrom",false);
                if(from.isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai"))))throw error("FUTURE_MASTER_CHANGE","项目起日必须已经生效");
                if(jdbc.sql("SELECT COUNT(*) FROM work_item WHERE code=?").param(d.get("code")).query(Integer.class).single()>0)throw error("PROJECT_CODE_CONFLICT","该归集编码已存在，请在维护页明确处理，不通过导入覆盖");
                if(jdbc.sql("SELECT COUNT(*) FROM work_item WHERE name=? AND type=?").params(d.get("name"),type).query(Integer.class).single()>0)throw error("PROJECT_POSSIBLE_DUPLICATE","存在同名归集对象，请明确关联后再应用，避免重复创建");
                openPeriod(from,null);
            }
            case RATE -> validateRate(d);
            case LEAVE -> validateLeave(d);
            case USER -> {
                MasterValidation.employeeNo(text(d,"employeeNo",20));text(d,"name",100);
                department(text(d,"departmentCode",60));one(d,"level",Set.of("JUNIOR","MIDDLE","SENIOR"));
                if(jdbc.sql("SELECT COUNT(*) FROM app_user WHERE employee_no=?").param(d.get("employeeNo")).query(Integer.class).single()>0)throw error("EMPLOYEE_EXISTS","工号已经存在；更正或换号请在人员维护中定位原人");
                if(!blank(d.get("wecomUserid"))&&jdbc.sql("SELECT COUNT(*) FROM app_user WHERE wecom_userid=?").param(d.get("wecomUserid")).query(Integer.class).single()>0)throw error("WECom_EXISTS","企微UserID已绑定其他人员");
                LocalDate from=date(d,"effectiveFrom",false);if(from.isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai"))))throw error("FUTURE_MASTER_CHANGE","人员生效日不能晚于今天");openPeriod(from,null);
            }
            case DEPARTMENT -> {
                text(d,"code",60);text(d,"name",100);
                if(jdbc.sql("SELECT COUNT(*) FROM department WHERE code=?").param(d.get("code")).query(Integer.class).single()>0)throw error("DEPARTMENT_EXISTS","部门编码已存在，请在组织维护处理");
                if(!blank(d.get("parentCode")))department(d.get("parentCode"));
                if(!blank(d.get("approverEmployeeNo")))employee(d.get("approverEmployeeNo"),true);
                if(!blank(d.get("supervisorEmployeeNo")))employee(d.get("supervisorEmployeeNo"),true);
            }
            case TRIP -> validateTrip(d);
        }
    }
    private void validateTrip(Map<String,String> d) {
        String key=text(d,"sourceKey",150);text(d,"sourceVersion",60);long user=employee(text(d,"employeeNo",20),false);
        LocalDate day=date(d,"date",false);String state=one(d,"status",Set.of("APPROVED","REVOKED"));
        if(!blank(d.get("projectCode")))tripProject(d.get("projectCode"));
        var old=jdbc.sql("SELECT user_id,work_date FROM trip_day WHERE source_key=?").param(key)
            .query((rs,n)->new Binding(rs.getLong(1),rs.getObject(2,LocalDate.class),null)).optional();
        if(old.isPresent()&&(old.get().user()!=user||!old.get().date().equals(day)))throw error("SOURCE_IDENTITY_CONFLICT","出差来源固定对应一人一天，不能变更人员或日期");
        if(old.isEmpty()&&state.equals("REVOKED"))throw error("UNKNOWN_TRIP_SOURCE","尚未应用的出差来源不能直接撤销");
        if(!periods.scope(day,user,0,"BASE",null,"BASE"))openPeriod(day,day.plusDays(1));
    }
    private void applyTrip(Map<String,String> d,long source) {
        var old=jdbc.sql("SELECT source_record_id FROM trip_day WHERE source_key=? FOR UPDATE").param(d.get("sourceKey")).query(Long.class).optional();
        if(old.isPresent()&&old.get()>source)throw error("STALE_SOURCE_VERSION","旧失败来源已被较新版本取代，不能通过重试恢复旧出差事实");
        validateTrip(d);
        jdbc.sql("""
            INSERT INTO trip_day(source_key,source_version,user_id,work_date,work_item_id,status,source_record_id)
            VALUES(?,?,?,?,?,?,?) AS incoming ON DUPLICATE KEY UPDATE source_version=incoming.source_version,
             work_item_id=incoming.work_item_id,status=incoming.status,source_record_id=incoming.source_record_id
            """).params(d.get("sourceKey"),d.get("sourceVersion"),employee(d.get("employeeNo"),false),date(d,"date",false),
                blank(d.get("projectCode"))?null:tripProject(d.get("projectCode")),d.get("status"),source).update();
    }
    private long tripProject(String code) {Long linked=projectLinks.find(code);return jdbc.sql("SELECT id FROM work_item WHERE ((? IS NOT NULL AND id=?) OR (? IS NULL AND code=?)) AND type='PROJECT'").params(linked,linked,linked,code).query(Long.class).optional().orElseThrow(()->error("UNKNOWN_PROJECT","出差项目编码未匹配到本系统项目，请明确关联后重试"));}
    private static boolean blank(String value) {return value==null||value.isBlank();}
    private static String empty(String value) {return blank(value)?null:value;}
    private void validateRate(Map<String,String> d) {
        String dimension=one(d,"dimension",Set.of("JUNIOR","MIDDLE","SENIOR","ONSITE"));
        BigDecimal amount;
        try { amount=new BigDecimal(text(d,"dailyRate",40)); }
        catch(NumberFormatException e) { throw error("INVALID_RATE","日标准必须为十进制文本"); }
        if(amount.signum()<0||amount.scale()>4||amount.compareTo(new BigDecimal("9999999999.9999"))>0)throw error("INVALID_RATE","日标准非负、最多四位小数且不超过数据库范围");
        LocalDate from=date(d,"effectiveFrom",false),to=date(d,"effectiveTo",true);MasterValidation.interval(from,to);openPeriod(from,to);
        String table=dimension.equals("ONSITE")?"onsite_rate":"rate_card",column=dimension.equals("ONSITE")?"dimension_code":"level_code";
        var existing=jdbc.sql("SELECT valid_from,valid_to FROM "+table+" WHERE "+column+"=?").param(dimension)
            .query((rs,n)->new Interval(rs.getObject("valid_from",LocalDate.class),rs.getObject("valid_to",LocalDate.class))).list();
        for(var old:existing) {
            if(old.from().equals(from))throw error("RATE_OVERLAP","同维度已有该起日标准");
            boolean overlap=(to==null||old.from().isBefore(to))&&(old.to()==null||from.isBefore(old.to()));
            if(overlap && !(old.to()==null&&old.from().isBefore(from)))throw error("RATE_OVERLAP","标准生效区间交叠");
        }
    }
    private void validateLeave(Map<String,String> d) {
        String key=text(d,"sourceKey",150),version=text(d,"sourceVersion",60);
        long user=employee(text(d,"employeeNo",20),false);LocalDate day=date(d,"date",false);
        String state=one(d,"status",Set.of("APPROVED","REVOKED"));
        int minutes=integer(d,"leaveMinutes",false);Integer start=integer(d,"startMinute",true),end=integer(d,"endMinute",true);
        if(minutes<0||minutes>480)throw error("INVALID_LEAVE","请假分钟必须为 0–480 的整数分钟");
        if((start==null)!=(end==null)||(start!=null&&(start<0||end>1440||end<=start||end-start!=minutes)))throw error("INVALID_LEAVE_SLOT","起止分钟成对填写，范围 0–1440，跨度须精确等于请假分钟");
        String normalized=normalized(d),sha=hash(normalized.getBytes(StandardCharsets.UTF_8));
        var received=jdbc.sql("SELECT content_sha256,application_state FROM source_record WHERE dataset='LEAVE' AND source_key=? AND source_version=?")
            .params(key,version).query((rs,n)->new Received(rs.getString("content_sha256"),rs.getString("application_state"))).optional();
        if(received.isPresent()) {
            if(!received.get().hash().equals(sha))throw error("SOURCE_VERSION_CONFLICT","相同来源版本含不同内容，不能覆盖原材料");
            if(received.get().state().equals("APPLIED"))return;
        }
        var previous=jdbc.sql("SELECT user_id,work_date,source_version,leave_minutes,start_minute,end_minute,status FROM leave_record WHERE source_key=?")
            .param(key).query((rs,n)->new ExistingLeave(rs.getLong("user_id"),rs.getObject("work_date",LocalDate.class),rs.getString("source_version"),
                rs.getInt("leave_minutes"),rs.getObject("start_minute",Integer.class),rs.getObject("end_minute",Integer.class),rs.getString("status"))).list();
        for(var old:previous) {
            if(old.user()!=user||!old.date().equals(day))throw error("SOURCE_IDENTITY_CONFLICT","同一来源不能变更人员或日期；请撤销旧来源后使用新来源编码");
            if(old.version().equals(version)&&(!old.same(minutes,start,end,state)))throw error("SOURCE_VERSION_CONFLICT","相同来源版本含不同请假事实");
        }
        if(state.equals("REVOKED")&&previous.isEmpty())throw error("UNKNOWN_LEAVE_SOURCE","尚未应用的来源不能直接撤销，请核对 sourceKey");
        if(!periods.scope(day,user,0,"BASE",null,"BASE"))openPeriod(day,day.plusDays(1));
        var proposed=segments(user,day,key);
        if(state.equals("APPROVED"))proposed.add(new DayRules.LeaveSegment(minutes,start,end));
        int base=jdbc.sql("SELECT base_minutes FROM work_calendar WHERE work_date=? AND is_override=TRUE").param(day).query(Integer.class).optional().orElse(day.getDayOfWeek().getValue()<=5?configs.forDate(day).params().defaultDayMinutes():0);
        DayRules.leaveMinutes(proposed,base);
    }
    private ArrayList<DayRules.LeaveSegment> segments(long user,LocalDate day,String exclude) {
        return new ArrayList<>(jdbc.sql("SELECT leave_minutes,start_minute,end_minute FROM leave_record WHERE user_id=? AND work_date=? AND status='APPROVED' AND (? IS NULL OR source_key<>?)")
            .params(user,day,exclude,exclude).query((rs,n)->new DayRules.LeaveSegment(rs.getInt("leave_minutes"),rs.getObject("start_minute",Integer.class),rs.getObject("end_minute",Integer.class))).list());
    }
    private void openPeriod(LocalDate from,LocalDate to) {
        if(!periods.status(from).equals("OPEN"))throw error("PERIOD_CLOSED","所属月份已到封账时点，已保留来源差异，请按授权流程处理");
        int closed=jdbc.sql("SELECT COUNT(*) FROM accounting_period WHERE LAST_DAY(month_start)>=? AND (? IS NULL OR month_start<?) AND (status='CLOSED' OR scheduled_close_at<=UTC_TIMESTAMP(6))")
            .params(from,to,to).query(Integer.class).single();
        if(closed>0)throw error("PERIOD_CLOSED","影响范围包含已封账月份，来源差异需授权处理");
    }
    private long employee(String number,boolean active) {
        MasterValidation.employeeNo(number);
        return jdbc.sql("SELECT id FROM app_user WHERE employee_no=?"+(active?" AND status='ACTIVE'":"")).param(number).query(Long.class).optional()
            .orElseThrow(()->error("UNKNOWN_EMPLOYEE","工号 "+number+" 未匹配到"+(active?"有效":"现有")+"人员，不按姓名猜测"));
    }
    private long department(String code) {
        return jdbc.sql("SELECT id FROM department WHERE code=? AND status='ACTIVE'").param(code).query(Long.class).optional()
            .orElseThrow(()->error("UNKNOWN_DEPARTMENT","部门编码不存在或已停用"));
    }
    private void ensureCalendar(LocalDate day) {
        if(jdbc.sql("SELECT COUNT(*) FROM work_calendar WHERE work_date=?").param(day).query(Integer.class).single()==0) {
            boolean workday=day.getDayOfWeek().getValue()<=5&&configs.forDate(day).params().defaultDayMinutes()>0;
            jdbc.sql("INSERT IGNORE INTO work_calendar(work_date,base_minutes,is_workday,reason,is_override) VALUES(?,?,?,?,FALSE)")
                .params(day,workday?configs.forDate(day).params().defaultDayMinutes():0,workday,"默认周历，法定休假和调休需管理员核实").update();
        }
    }
    private Row row(long id,boolean lock) {
        return jdbc.sql("SELECT * FROM import_row WHERE id=?"+(lock?" FOR UPDATE":"")).param(id)
            .query((rs,n)->new Row(rs.getLong("id"),rs.getLong("batch_id"),rs.getInt("row_no"),data(rs.getString("raw_data")),rs.getString("parse_error"),rs.getString("state"),rs.getObject("source_record_id",Long.class))).single();
    }
    private boolean sourceApplied(long source) { return jdbc.sql("SELECT application_state='APPLIED' FROM source_record WHERE id=?").param(source).query(Boolean.class).single(); }
    private Map<String,String> data(String value) { return json.readValue(value,new TypeReference<LinkedHashMap<String,String>>(){}); }
    private String normalized(Map<String,String> value) { return json.writeValueAsString(new java.util.TreeMap<>(value)); }
    private static String text(Map<String,String> d,String key,int max) { return MasterValidation.text(d.get(key),max,key); }
    private static String one(Map<String,String> d,String key,Set<String> allowed) { return MasterValidation.oneOf(d.get(key),allowed,key); }
    private static LocalDate date(Map<String,String> d,String key,boolean optional) {
        String value=d.get(key);if(optional&&(value==null||value.isBlank()))return null;
        try { LocalDate date=LocalDate.parse(value);if(date.getYear()<2000||date.getYear()>2100)throw new IllegalArgumentException();return date; }
        catch(Exception e) { throw error("INVALID_DATE",key+" 须为 2000–2100 年的 YYYY-MM-DD 日期文本"); }
    }
    private static Integer integer(Map<String,String> d,String key,boolean optional) {
        String value=d.get(key);if(optional&&(value==null||value.isBlank()))return null;
        try { if(value==null||!value.matches("[0-9]{1,4}"))throw new NumberFormatException();return Integer.valueOf(value); }
        catch(NumberFormatException e) { throw error("INVALID_MINUTES",key+" 须为整数分钟文本"); }
    }
    private static ApiException error(String code,String message) { return new ApiException(422,code,message); }
    private static String safeName(String name) { String simple=name.replace('\\','/');simple=simple.substring(simple.lastIndexOf('/')+1);return simple.length()>255?simple.substring(0,255):simple; }
    private static String hash(byte[] value) { try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(Exception e){throw new IllegalStateException(e);} }
    private long lastId() { return jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single(); }
    private record Row(long id,long batch,int number,Map<String,String> data,String parseError,String state,Long source) {}
    private record Source(long id,String hash) {}
    private record Received(String hash,String state) {}
    private record Binding(Long user,LocalDate date,Long source) {}
    private record Calendar(int base,boolean workday) {}
    private record Interval(LocalDate from,LocalDate to) {}
    private record ExistingLeave(long user,LocalDate date,String version,int minutes,Integer start,Integer end,String state) {
        boolean same(int minutes,Integer start,Integer end,String state) { return this.minutes==minutes&&java.util.Objects.equals(this.start,start)&&java.util.Objects.equals(this.end,end)&&this.state.equals(state); }
    }
}
