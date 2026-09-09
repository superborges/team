package com.allen.worklog.work;

import com.allen.worklog.closing.PeriodGate;
import com.allen.worklog.common.ApiException;
import com.allen.worklog.common.AuditService;
import com.allen.worklog.identity.CurrentUser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.DayOfWeek;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import static com.allen.worklog.work.DayModels.*;

@Service
public class DayService {
    private final JdbcClient jdbc;
    private final CurrentUser current;
    private final PeriodGate gate;
    private final AuditService audit;
    private final ObjectMapper json;
    private final com.allen.worklog.operations.ConfigService configs;
    public DayService(JdbcClient jdbc,CurrentUser current,PeriodGate gate,AuditService audit,ObjectMapper json,com.allen.worklog.operations.ConfigService configs) {
        this.jdbc=jdbc;this.current=current;this.gate=gate;this.audit=audit;this.json=json;this.configs=configs;
    }
    @Transactional(readOnly=true)
    public Day get(LocalDate date) { return read(current.require().id(),date); }
    @Transactional(readOnly=true)
    public Week week(LocalDate monday) {
        if(monday.getDayOfWeek()!=DayOfWeek.MONDAY)throw new ApiException(400,"INVALID_WEEK","周起始日期必须是周一");
        long user=current.require().id();
        return new Week(monday.toString(),monday.datesUntil(monday.plusDays(7)).map(d->read(user,d)).toList());
    }
    @Transactional
    public Day save(LocalDate date,SaveDay input,String commandKey) {
        long user=current.require().id();
        if(input.expectedVersion()==null || input.expectedVersion()<0 || input.entries()==null || input.entries().size()>48)
            throw new ApiException(400,"INVALID_REQUEST","请提供日版本号和不超过 48 条的工时记录");
        if(commandKey!=null && !commandKey.matches("[A-Za-z0-9_-]{1,64}"))
            throw new ApiException(400,"INVALID_IDEMPOTENCY_KEY","请求标识无效");
        gate.lockForDay(date,user);
        ensureCalendar(date);
        jdbc.sql("SELECT work_date FROM work_calendar WHERE work_date=:date FOR SHARE")
            .param("date",date).query(LocalDate.class).single();
        // Lock a stable person before reading effective history or acquiring the day lock.
        jdbc.sql("SELECT id FROM app_user WHERE id=:id FOR SHARE").param("id",user).query(Long.class).single();
        current.require();
        Base base=base(user,date);
        if(!base.enrolled())throw new ApiException(403,"NOT_ENROLLED","该日期不在你的填报适用区间内，请联系管理员");
        if(base.departmentId()==null)throw new ApiException(422,"MISSING_DEPARTMENT_HISTORY","该日期缺少有效部门归属，请联系管理员");
        if(findDay(user,date).isEmpty())jdbc.sql("""
            INSERT IGNORE INTO day_record(user_id,work_date,base_minutes,leave_minutes,required_minutes,is_workday,department_id)
            VALUES(:user,:date,:base,:leave,:required,:workday,:department)
            """).param("user",user).param("date",date).param("base",base.baseMinutes()).param("leave",base.leaveMinutes())
                .param("required",base.requiredMinutes()).param("workday",base.workday()).param("department",base.departmentId()).update();
        Row row=jdbc.sql("SELECT id,row_version FROM day_record WHERE user_id=:user AND work_date=:date FOR UPDATE")
            .param("user",user).param("date",date).query((rs,n)->new Row(rs.getLong("id"),rs.getInt("row_version"))).single();
        String command="SAVE_DAY:"+date;
        String hash=hash(json.writeValueAsString(input));
        if(commandKey!=null) {
            var receipt=jdbc.sql("SELECT request_hash,response_json FROM command_receipt WHERE actor_id=:user AND command_type=:type AND idempotency_key=:key")
                .param("user",user).param("type",command).param("key",commandKey)
                .query((rs,n)->new Receipt(rs.getString("request_hash"),rs.getString("response_json"))).optional();
            if(receipt.isPresent()) {
                if(!receipt.get().hash().equals(hash))throw new ApiException(409,"IDEMPOTENCY_CONFLICT","同一请求标识不能提交不同内容");
                return json.readValue(receipt.get().response(),Day.class);
            }
        }
        if(row.version()!=input.expectedVersion())throw new ApiException(409,"VERSION_CONFLICT","该日记录已变化，你的输入仍在，请刷新核对后再保存");
        Day before=read(user,date);
        var parameters=configs.forDate(date).params();
        Map<String,Entry> existing=new HashMap<>();before.entries().stream().filter(e->e.action().equals("REPORT")).forEach(e->existing.put(e.id(),e));
        Set<String> seen=new HashSet<>();
        int total=0;
        for(EntryInput entry:input.entries()) {
            if(entry==null)throw new ApiException(400,"INVALID_REQUEST","工时记录不能空缺");
            int minutes=DayRules.minutes(entry.hours(),parameters.stepMinutes(),parameters.dayLimitMinutes());total+=minutes;
            String kind=Objects.requireNonNullElse(entry.kind(),"");
            if(!Set.of("WORK","TRAVEL","IDLE").contains(kind))throw new ApiException(422,"INVALID_KIND","请选择实际工作、项目交通或待安排工作");
            String content=Objects.requireNonNullElse(entry.content(),"").strip();
            String reason=Objects.requireNonNullElse(entry.redReason(),"").strip();
            if(kind.equals("IDLE") && content.isEmpty())content="暂无任务安排";
            if(content.codePointCount(0,content.length())>200 || reason.length()>500)throw new ApiException(422,"CONTENT_TOO_LONG","工作内容最多 200 字，说明最多 500 字");
            long item=DayRules.id(entry.workItemId());
            long entryId;
            if(entry.id()!=null) {
                DayRules.id(entry.id());
                if(!seen.add(entry.id()))throw new ApiException(422,"DUPLICATE_ENTRY","同一记录不能重复提交");
                Entry old=existing.get(entry.id());
                if(old==null)throw new ApiException(403,"ENTRY_SCOPE","记录不属于当前日期或已取消");
                if(entry.correctionRequestId()!=null&&!Objects.equals(entry.correctionRequestId(),old.correctionRequestId()))throw new ApiException(422,"CORRECTION_LINK_IMMUTABLE","已有记录的更正关联不能修改");
                boolean equal=old.workItemId().equals(entry.workItemId())&&old.kind().equals(kind)&&old.minutes()==minutes
                    &&old.content().equals(content)&&old.redReason().equals(reason);
                if(!old.editable()&&!equal)throw new ApiException(409,"IMMUTABLE_ENTRY","已提交审批的记录不能直接修改，请退回后修改或申请更正");
                if(equal)continue;
                entryId=DayRules.id(entry.id());
                gate.lockForAction(date,user,DayRules.id(old.workItemId()),"TIME",entryId,"SAVE");
                if(!old.workItemId().equals(entry.workItemId()))gate.lockForAction(date,user,item,"TIME",entryId,"SAVE");
            } else {
                gate.lockForAction(date,user,item,"TIME",null,"ADD");
                jdbc.sql("INSERT INTO time_entry(day_id) VALUES(:day)").param("day",row.id()).update();entryId=lastId();
            }
            Item selected=validItem(item);
            if(kind.equals("IDLE")!=selected.type().equals("IDLE") || (kind.equals("TRAVEL")&&!selected.type().equals("PROJECT")))
                throw new ApiException(422,"ITEM_KIND_MISMATCH","待安排工作请选择对应部门的待安排事项；项目交通请选择所属项目");
            if(kind.equals("IDLE")&&jdbc.sql("SELECT COUNT(*) FROM work_item WHERE id=? AND owner_department_id=?").params(item,base.departmentId()).query(Integer.class).single()==0)
                throw new ApiException(422,"IDLE_DEPARTMENT","待安排工作请选择工作当天所属部门的待安排事项");
            Long relocation=entry.id()==null?relocation(entry.correctionRequestId(),user,date,"TIME"):null;
            revision(entryId,item,kind,minutes,content,reason,base.departmentId(),"REPORT",relocation);
        }
        if(total>parameters.dayLimitMinutes())throw new ApiException(422,"DAY_LIMIT","当天工作与待安排时间合计不能超过 "+DayRules.hours(parameters.dayLimitMinutes())+" 小时");
        for(Entry old:existing.values())if(!seen.contains(old.id())) {
            if(!old.editable())throw new ApiException(409,"IMMUTABLE_ENTRY","已提交审批的记录不能直接移除，请申请更正或取消");
            if(old.correctionRequestId()!=null)throw new ApiException(409,"CORRECTION_CANCEL_REQUIRES_APPROVAL","更正版本不能直接删除，请通过取消更正申请处理");
            gate.lockForAction(date,user,DayRules.id(old.workItemId()),"TIME",DayRules.id(old.id()),"SAVE");
            revision(DayRules.id(old.id()),DayRules.id(old.workItemId()),old.kind(),old.minutes(),old.content(),old.redReason(),base.departmentId(),"CANCEL",null);
        }
        if(before.onsite()!=null&&before.onsite().action().equals("CANCEL")) {
            if(input.onsite()!=null)throw new ApiException(409,"CANCEL_PENDING","现场取消更正需先完成审批");
        } else {
            var old=before.onsite();var change=input.onsite();
            if(change==null&&old!=null)gate.lockForAction(date,user,DayRules.id(old.workItemId()),"ONSITE",DayRules.id(old.id()),"SAVE");
            if(change!=null&&(old==null||!Objects.equals(change.workItemId(),old.workItemId())||!Objects.equals(change.reason(),old.reason()))) {
                if(old!=null)gate.lockForAction(date,user,DayRules.id(old.workItemId()),"ONSITE",DayRules.id(old.id()),"SAVE");
                gate.lockForAction(date,user,DayRules.id(change.workItemId()),"ONSITE",old==null?null:DayRules.id(old.id()),old==null?"ADD":"SAVE");
            }
            saveOnsite(row.id(),old,change,user,date);
        }
        jdbc.sql("""
            UPDATE day_record SET row_version=row_version+1,base_minutes=:base,leave_minutes=:leave,
                required_minutes=:required,is_workday=:workday,department_id=:dept WHERE id=:id
            """).param("base",base.baseMinutes()).param("leave",base.leaveMinutes()).param("required",base.requiredMinutes())
            .param("workday",base.workday()).param("dept",base.departmentId()).param("id",row.id()).update();
        Day after=read(user,date);
        if(after.validation().ready()) {
            var config=configs.forDate(date);
            var deadline=date.plusDays(1).atTime(java.time.LocalTime.parse(config.params().dailyCompletionTime()))
                .atZone(java.time.ZoneId.of("Asia/Shanghai")).withZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime();
            jdbc.sql("INSERT IGNORE INTO daily_completion_fact(user_id,work_date,first_ready_at,deadline_at,config_version_id) VALUES(?,?,UTC_TIMESTAMP(6),?,?)")
                .params(user,date,deadline,config.id()).update();
        }
        audit.record(user,"SAVE_DAY","DAY",Long.toString(row.id()),json.writeValueAsString(before),json.writeValueAsString(after),"保存每日草稿");
        if(commandKey!=null)jdbc.sql("""
            INSERT INTO command_receipt(actor_id,command_type,idempotency_key,request_hash,response_json)
            VALUES(:user,:type,:key,:hash,:response)
            """).param("user",user).param("type",command).param("key",commandKey).param("hash",hash).param("response",json.writeValueAsString(after)).update();
        return after;
    }
    private void saveOnsite(long day,Onsite old,OnsiteInput input,long user,LocalDate date) {
        if(input==null && old==null)return;
        if(old!=null&&input!=null&&Objects.equals(old.id(),input.id())&&Objects.equals(old.workItemId(),input.workItemId())&&Objects.equals(old.reason(),input.reason())&&
            (input.correctionRequestId()==null||Objects.equals(input.correctionRequestId(),old.correctionRequestId())))return;
        long item;
        String reason,action;
        Long id;
        if(input==null) {
            if(!old.editable())throw new ApiException(409,"IMMUTABLE_ONSITE","已提交审批的现场日不能直接取消，请退回后处理或申请取消");
            if(old.correctionRequestId()!=null)throw new ApiException(409,"CORRECTION_CANCEL_REQUIRES_APPROVAL","现场更正版本不能直接删除，需重新申请取消");
            id=DayRules.id(old.id());item=DayRules.id(old.workItemId());reason=old.reason();action="CANCEL";
        } else {
            item=DayRules.id(input.workItemId());
            if(!validItem(item).type().equals("PROJECT"))throw new ApiException(422,"ONSITE_PROJECT_REQUIRED","现场日只能归属项目");
            reason=Objects.requireNonNullElse(input.reason(),"").strip();
            if(reason.length()>500)throw new ApiException(422,"CONTENT_TOO_LONG","现场说明最多 500 字");
            if(old!=null) {
                if(input.correctionRequestId()!=null&&!Objects.equals(input.correctionRequestId(),old.correctionRequestId()))throw new ApiException(422,"CORRECTION_LINK_IMMUTABLE","已有现场记录的更正关联不能修改");
                if(input.id()==null||!old.id().equals(input.id()))throw new ApiException(409,"ONSITE_CONFLICT","该日已有现场记录，请刷新后编辑");
                boolean equal=old.workItemId().equals(input.workItemId())&&old.reason().equals(reason);
                if(!old.editable()&&!equal)throw new ApiException(409,"IMMUTABLE_ONSITE","已送审现场日须通过退回或更正流程修改");
                if(equal)return;
                id=DayRules.id(old.id());
            } else {
                if(input.id()!=null)throw new ApiException(403,"ONSITE_SCOPE","现场记录不属于当前日期或已取消");
                id=jdbc.sql("SELECT id FROM onsite_day WHERE day_id=:day").param("day",day).query(Long.class).optional().orElse(null);
                if(id==null) { jdbc.sql("INSERT INTO onsite_day(day_id) VALUES(:day)").param("day",day).update();id=lastId(); }
            }
            action="REPORT";
        }
        jdbc.sql("""
            INSERT INTO onsite_day_revision(onsite_id,revision_no,action,work_item_id,reason,state,correction_request_id)
            SELECT :id,COALESCE(MAX(revision_no),0)+1,:action,:item,:reason,:state,
                CASE WHEN :preserveCorrection THEN (SELECT r.correction_request_id FROM onsite_day o JOIN onsite_day_revision r ON r.id=o.current_revision_id WHERE o.id=:id) ELSE :correction END
            FROM onsite_day_revision WHERE onsite_id=:id
            """).param("id",id).param("action",action).param("item",item).param("reason",reason)
            .param("state",action.equals("CANCEL")?"CANCELED":"DRAFT").param("preserveCorrection",old!=null)
            .param("correction",old==null&&input!=null?relocation(input.correctionRequestId(),user,date,"ONSITE"):null).update();
        long revision=lastId();
        jdbc.sql("UPDATE onsite_day SET current_revision_id=:revision WHERE id=:id").param("revision",revision).param("id",id).update();
    }
    private void revision(long id,long item,String kind,int minutes,String content,String reason,long department,String action,Long correction) {
        jdbc.sql("""
            INSERT INTO time_entry_revision(entry_id,revision_no,action,work_item_id,kind,minutes,content,red_reason,department_id,state,correction_request_id)
            SELECT :id,COALESCE(MAX(revision_no),0)+1,:action,:item,:kind,:minutes,:content,:reason,:department,:state,
                COALESCE(:correction,(SELECT r.correction_request_id FROM time_entry e JOIN time_entry_revision r ON r.id=e.current_revision_id WHERE e.id=:id))
            FROM time_entry_revision WHERE entry_id=:id
            """).param("id",id).param("action",action).param("item",item).param("kind",kind).param("minutes",minutes)
            .param("content",content).param("reason",reason).param("department",department)
            .param("state",action.equals("CANCEL")?"CANCELED":"DRAFT").param("correction",correction).update();
        long revision=lastId();
        jdbc.sql("UPDATE time_entry SET current_revision_id=:revision WHERE id=:id").param("revision",revision).param("id",id).update();
    }
    private Day read(long user,LocalDate date) {
        checkDate(date);
        Base base=base(user,date);
        Optional<Row> stored=findDay(user,date);
        String status=gate.status(date);
        boolean editable=gate.canEditDay(date,user)&&base.enrolled();
        List<Entry> entries=stored.map(row->jdbc.sql("""
            SELECT e.id,r.id revision_id,r.work_item_id,w.name work_item_name,r.kind,r.minutes,r.content,r.red_reason,r.state,
             r.action,r.correction_request_id,r.dispute_open,(SELECT id FROM approval_item WHERE time_revision_id=r.id) approval_item_id
            FROM time_entry e JOIN time_entry_revision r ON r.id=e.current_revision_id
            JOIN work_item w ON w.id=r.work_item_id WHERE e.day_id=:day AND (r.action='REPORT' OR (r.correction_request_id IS NOT NULL AND r.state IN ('DRAFT','PENDING','REJECTED'))) ORDER BY e.id
            """).param("day",row.id()).query((rs,n)->new Entry(rs.getString("id"),rs.getString("revision_id"),rs.getString("work_item_id"),
                rs.getString("work_item_name"),rs.getString("kind"),rs.getString("action").equals("CANCEL")?"0":DayRules.hours(rs.getInt("minutes")),rs.getString("action").equals("CANCEL")?0:rs.getInt("minutes"),
                rs.getString("content"),rs.getString("red_reason"),rs.getString("state"),editable&&!rs.getBoolean("dispute_open")&&rs.getString("action").equals("REPORT")&&Set.of("DRAFT","REJECTED").contains(rs.getString("state")),
                rs.getString("action"),rs.getString("correction_request_id"),rs.getBoolean("dispute_open"),rs.getString("approval_item_id"),!rs.getBoolean("dispute_open")&&Set.of("APPROVED","LOCKED").contains(rs.getString("state")))).list())
            .orElse(List.of());
        Onsite onsite=stored.flatMap(row->jdbc.sql("""
            SELECT o.id,r.id revision_id,r.work_item_id,w.name work_item_name,r.reason,r.state,r.action,r.correction_request_id,r.dispute_open,
             (SELECT id FROM approval_item WHERE onsite_revision_id=r.id) approval_item_id FROM onsite_day o
            JOIN onsite_day_revision r ON r.id=o.current_revision_id JOIN work_item w ON w.id=r.work_item_id
            WHERE o.day_id=:day AND (r.action='REPORT' OR (r.correction_request_id IS NOT NULL AND r.state IN ('DRAFT','PENDING','REJECTED')))
            """).param("day",row.id()).query((rs,n)->new Onsite(rs.getString("id"),rs.getString("revision_id"),rs.getString("work_item_id"),
                rs.getString("work_item_name"),rs.getString("reason"),rs.getString("state"),editable&&!rs.getBoolean("dispute_open")&&rs.getString("action").equals("REPORT")&&Set.of("DRAFT","REJECTED").contains(rs.getString("state")),
                rs.getString("action"),rs.getString("correction_request_id"),rs.getBoolean("dispute_open"),rs.getString("approval_item_id"),!rs.getBoolean("dispute_open")&&Set.of("APPROVED","LOCKED").contains(rs.getString("state")))).optional())
            .orElse(null);
        Set<String> previous=new HashSet<>(jdbc.sql("""
            SELECT r.content FROM day_record d JOIN time_entry e ON e.day_id=d.id JOIN time_entry_revision r ON r.id=e.current_revision_id
            WHERE d.user_id=:user AND d.work_date=:date AND r.action='REPORT' AND r.kind<>'IDLE'
            """).param("user",user).param("date",date.minusDays(1)).query(String.class).list());
        int total=entries.stream().mapToInt(Entry::minutes).sum();
        int idle=entries.stream().filter(e->e.kind().equals("IDLE")).mapToInt(Entry::minutes).sum();
        return new Day(date.toString(),stored.map(Row::version).orElse(0),base.baseMinutes(),base.leaveMinutes(),base.requiredMinutes(),
            base.workday(),base.enrolled(),editable,status,entries,onsite,total,total-idle,idle,
            DayRules.validate(entries,base.requiredMinutes(),base.enrolled(),previous,configs.forDate(date).params().dayLimitMinutes(),configs.forDate(date).params().redFlagMinutes()),
            configs.forDate(date).params().stepMinutes(),configs.forDate(date).params().redFlagMinutes(),configs.forDate(date).params().dayLimitMinutes());
    }
    private Base base(long user,LocalDate date) {
        checkDate(date);
        int defaultBase=date.getDayOfWeek().getValue()<=5?configs.forDate(date).params().defaultDayMinutes():0;
        var calendar=jdbc.sql("SELECT base_minutes,is_workday FROM work_calendar WHERE work_date=:date AND is_override=TRUE").param("date",date)
            .query((rs,n)->new Calendar(rs.getInt("base_minutes"),rs.getBoolean("is_workday"))).optional().orElse(new Calendar(defaultBase,defaultBase>0));
        boolean enrolled=jdbc.sql("""
            SELECT COUNT(*) FROM reporting_enrollment WHERE user_id=:user AND voided=FALSE AND valid_from<=:date AND (valid_to IS NULL OR valid_to>:date)
            """).param("user",user).param("date",date).query(Integer.class).single()>0;
        Long department=jdbc.sql("""
            SELECT department_id FROM user_department_history WHERE user_id=:user AND valid_from<=:date AND (valid_to IS NULL OR valid_to>:date)
            """).param("user",user).param("date",date).query(Long.class).optional().orElse(null);
        List<DayRules.LeaveSegment> leaves=jdbc.sql("SELECT leave_minutes,start_minute,end_minute FROM leave_record WHERE user_id=:user AND work_date=:date AND status='APPROVED'")
            .param("user",user).param("date",date).query((rs,n)->new DayRules.LeaveSegment(rs.getInt("leave_minutes"),rs.getObject("start_minute",Integer.class),rs.getObject("end_minute",Integer.class))).list();
        int leave=DayRules.leaveMinutes(leaves,calendar.base());
        return new Base(calendar.base(),leave,Math.max(calendar.base()-leave,0),calendar.workday(),enrolled,department);
    }
    private void ensureCalendar(LocalDate date) {
        if(jdbc.sql("SELECT COUNT(*) FROM work_calendar WHERE work_date=:date").param("date",date).query(Integer.class).single()==0) {
            boolean workday=date.getDayOfWeek().getValue()<=5&&configs.forDate(date).params().defaultDayMinutes()>0;
            jdbc.sql("INSERT IGNORE INTO work_calendar(work_date,base_minutes,is_workday,reason,is_override) VALUES(:date,:minutes,:workday,:reason,FALSE)")
                .param("date",date).param("minutes",workday?configs.forDate(date).params().defaultDayMinutes():0).param("workday",workday).param("reason","默认周历，法定休假和调休需管理员核实").update();
        }
    }
    private Optional<Row> findDay(long user,LocalDate date) {
        return jdbc.sql("SELECT id,row_version FROM day_record WHERE user_id=:user AND work_date=:date")
            .param("user",user).param("date",date).query((rs,n)->new Row(rs.getLong("id"),rs.getInt("row_version"))).optional();
    }
    private Item validItem(long id) {
        return jdbc.sql("SELECT type FROM work_item WHERE id=:id AND status='ACTIVE' FOR SHARE").param("id",id)
            .query((rs,n)->new Item(rs.getString("type"))).optional()
            .orElseThrow(()->new ApiException(422,"INVALID_WORK_ITEM","所选项目或事项不存在或已停用，请重新选择"));
    }
    private Long relocation(String requestId,long user,LocalDate date,String kind) {
        if(requestId==null)return null;long id=DayRules.id(requestId);
        var original=jdbc.sql("SELECT work_date FROM correction_request WHERE id=? AND user_id=? AND kind=? AND requested_action='CANCEL' AND state='APPROVED' FOR UPDATE")
            .params(id,user,kind).query(LocalDate.class).optional().orElseThrow(()->new ApiException(403,"INVALID_CORRECTION_LINK","只能关联本人已获准的取消更正"));
        if(original.equals(date))throw new ApiException(422,"CORRECTION_SAME_DATE","错日期更正的新日期必须不同于原日期");
        String head=kind.equals("TIME")?"time_entry":"onsite_day",version=kind.equals("TIME")?"time_entry_revision":"onsite_day_revision";
        int existing=jdbc.sql("SELECT COUNT(*) FROM "+head+" e JOIN "+version+" r ON r.id=e.current_revision_id JOIN day_record d ON d.id=e.day_id WHERE r.correction_request_id=? AND d.work_date<>?")
            .params(id,original).query(Integer.class).single();
        if(existing>0)throw new ApiException(409,"CORRECTION_ALREADY_REPLACED","该更正已关联正确日期记录，请编辑原新记录，不能重复新增");
        return id;
    }
    private long lastId() { return jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single(); }
    private static void checkDate(LocalDate date) {
        if(date==null||date.getYear()<2000||date.getYear()>2100)throw new ApiException(400,"INVALID_DATE","日期应在 2000–2100 年范围内");
    }
    private static String hash(String value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private record Row(long id,int version) {}
    private record Base(int baseMinutes,int leaveMinutes,int requiredMinutes,boolean workday,boolean enrolled,Long departmentId) {}
    private record Calendar(int base,boolean workday) {}
    private record Item(String type) {}
    private record Receipt(String hash,String response) {}
}
