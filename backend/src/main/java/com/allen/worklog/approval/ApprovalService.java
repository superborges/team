package com.allen.worklog.approval;

import com.allen.worklog.common.ApiException;
import com.allen.worklog.operations.ConfigService;
import com.allen.worklog.operations.SystemExecution;
import com.allen.worklog.work.DayRules;
import com.allen.worklog.work.DayService;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.allen.worklog.approval.ApprovalData.*;
import static com.allen.worklog.approval.ApprovalModels.*;

@Service
public class ApprovalService {
    private final ApprovalData d; private final DayService days;
    public ApprovalService(ApprovalData d,DayService days){this.d=d;this.days=days;}

    public WeekPreview preview(LocalDate week) {
        monday(week);long user=d.current.require().id();List<PreviewDay> result=new ArrayList<>();
        for(LocalDate date:week.datesUntil(week.plusDays(7)).toList()) {
            List<String> errors=new ArrayList<>(),warnings=new ArrayList<>();List<PreviewWork> work=new ArrayList<>();PreviewOnsite onsite=null;
            boolean workReady=false,onsiteReady=false;
            try {
                var day=days.get(date);warnings.addAll(day.validation().warnings());
                var candidates=d.dayRevisions(user,date,"TIME").stream().filter(this::unsubmitted).toList();
                boolean reportsReady=day.validation().ready();
                if(!reportsReady&&!candidates.isEmpty())errors.addAll(day.validation().errors());
                boolean routed=true;
                for(var r:candidates) {
                    Route route=null;try {previewGate(r);route=d.routeForSubmission(user,week,r.item());}catch(ApiException e){errors.add(r.itemName()+"："+e.getMessage());routed=false;}
                    work.add(new PreviewWork(str(r.id()),str(r.recordId()),str(r.item()),r.itemName(),r.action().equals("CANCEL")?"CANCEL":r.entryKind(),
                        r.action().equals("CANCEL")?"0":DayRules.hours(r.minutes()),route==null?null:str(route.approver()),route==null?null:d.name(route.approver()),route==null?null:route.reason()));
                }
                workReady=!candidates.isEmpty()&&routed&&(reportsReady||candidates.stream().anyMatch(r->r.action().equals("CANCEL")));
                var on=d.dayRevisions(user,date,"ONSITE").stream().filter(this::unsubmitted).findFirst();
                if(on.isPresent()) {
                    var r=on.get();Route route=null;
                    try {previewGate(r);onsiteValid(r);route=d.routeForSubmission(user,week,r.item());onsiteReady=true;}catch(ApiException e){errors.add("现场日："+e.getMessage());}
                    onsite=new PreviewOnsite(str(r.id()),str(r.recordId()),str(r.item()),r.itemName(),route==null?null:str(route.approver()),route==null?null:d.name(route.approver()),route==null?null:route.reason());
                }
                if(date.isAfter(d.config.today())&&(workReady||onsiteReady)){workReady=false;onsiteReady=false;errors.add("尚未发生的日期不能送审");}
            } catch(ApiException e){errors.add(e.getMessage());}
            result.add(new PreviewDay(date,workReady,onsiteReady,List.copyOf(errors),List.copyOf(warnings),List.copyOf(work),onsite));
        }
        return new WeekPreview(week,List.copyOf(result));
    }
    public BatchResult submit(LocalDate week,String key) {
        monday(week);key(key);d.current.require();var result=new MutableResult();
        for(LocalDate date:week.datesUntil(week.plusDays(7)).toList()) {
            for(String kind:List.of("TIME","ONSITE")) {
                try {result.add(d.tx.execute(status->submitUnit(date,week,kind,key)));}
                catch(ApiException ex){result.failed.add(new ResultItem(null,kind.equals("TIME")?"DAY":kind,date,null,ex.code(),ex.getMessage()));}
            }
        }
        return result.value();
    }
    public BatchResult submitSystem(long userId,LocalDate weekStart,long jobId) {
        return SystemExecution.runAs(userId,jobId,()->submit(weekStart,"job_"+jobId+"_"+userId));
    }
    public BatchResult submitOnsite(long id,String key) {
        key(key);long user=d.current.require().id();var r=d.currentRevision("ONSITE",id);
        if(r.user()!=user)throw forbidden("只能提交本人现场日");
        return d.tx.execute(status->submitUnit(r.date(),r.date().with(DayOfWeek.MONDAY),"ONSITE",key));
    }
    private BatchResult submitUnit(LocalDate date,LocalDate week,String kind,String key) {
        long user=d.current.require().id();String command="SUBMIT_"+kind+":"+date;var body=Map.of("date",date,"kind",kind);
        List<Revision> original=d.dayRevisions(user,date,kind).stream().filter(this::unsubmitted).toList();
        if(original.isEmpty()) {
            var previous=d.receipt(command,key,body,BatchResult.class);
            return previous==null?new BatchResult(List.of(),List.of(),List.of()):previous;
        }
        if(date.isAfter(d.config.today()))throw bad("DAY_NOT_OCCURRED","尚未发生的日期不能送审");
        d.lockDays(original,"SUBMIT");
        var previous=d.receipt(command,key,body,BatchResult.class);if(previous!=null)return previous;
        var eligible=d.dayRevisions(user,date,kind).stream().filter(this::unsubmitted).toList();var result=new MutableResult();
        if(kind.equals("TIME")) {
            var validation=days.get(date).validation();
            if(!validation.ready()) {
                var cancellations=eligible.stream().filter(r->r.action().equals("CANCEL")).toList();
                if(cancellations.isEmpty())throw bad("DAY_INCOMPLETE",String.join("；",validation.errors()));
                result.failed.add(new ResultItem(null,"DAY",date,null,"DAY_INCOMPLETE","取消更正独立送审；其他工时仍需补齐："+String.join("；",validation.errors())));
                eligible=cancellations;
            }
        }
        if(eligible.isEmpty())return result.value();
        Map<Long,Route> routes=new TreeMap<>();
        for(var r:eligible){if(r.disputeOpen())throw bad("DISPUTE_OPEN","待更正记录有未结争议，请先完成协调");if(kind.equals("ONSITE"))onsiteValid(r);routes.put(r.item(),d.routeForSubmission(user,week,r.item()));}
        // All routing checks precede writes so one invalid work route cannot partly submit that day.
        Map<Long,Long> packages=new HashMap<>();
        for(var entry:routes.entrySet())packages.put(entry.getKey(),obtainPackage(user,week,entry.getKey(),entry.getValue()));
        for(var initial:eligible) {
            var r=initial.state().equals("REJECTED")?d.draftCopy(initial,initial.action(),initial.correctionId()):initial;
            long pack=packages.get(r.item());var config=d.config.forDate(week);
            LocalDateTime submitDeadline=deadline(week,config.params().submitWeekday(),config.params().submitTime());
            LocalDateTime approveDeadline=deadline(week,config.params().approveWeekday(),config.params().approveTime());
            d.jdbc.sql("INSERT INTO approval_item(package_id,time_revision_id,onsite_revision_id,submit_deadline,approval_deadline) VALUES(?,?,?,?,?)")
                .params(pack,kind.equals("TIME")?r.id():null,kind.equals("ONSITE")?r.id():null,submitDeadline,approveDeadline).update();
            long item=d.lastId();
            d.jdbc.sql("UPDATE "+d.table(kind)+" SET state='PENDING',submitted_at=UTC_TIMESTAMP(6),owner_department_id=? WHERE id=?")
                .params(r.ownerDepartment(),r.id()).update();
            d.touch(r);d.touchPack(pack);
            d.audit(user,"REVISION_SUBMITTED",kind,r.id(),initial,map("approvalItemId",str(item),"packageId",str(pack)),"按自然周送审当前内容版本");
            d.notifications.enqueue(d.pack(pack,false).approver(),pack,"APPROVAL_PENDING","approval_item_"+item,"你有新的工时或现场日待核实","/h5/approvals/"+pack);
            result.succeeded.add(new ResultItem(str(item),kind,date,str(pack),null,"已送审"));
        }
        BatchResult value=result.value();d.saveReceipt(command,key,body,value);return value;
    }
    private long obtainPackage(long user,LocalDate week,long item,Route initialRoute) {
        var existing=d.jdbc.sql("SELECT id FROM approval_package WHERE user_id=? AND week_start=? AND work_item_id=?")
            .params(user,week,item).query(Long.class).optional();
        var config=d.config.forDate(week);
        if(existing.isEmpty()) {
            d.jdbc.sql("""
                INSERT IGNORE INTO approval_package(user_id,week_start,work_item_id,approver_user_id,default_approver_id,route_reason,submit_deadline,approval_deadline,config_version_id)
                VALUES(?,?,?,?,?,?,?,?,?)
                """).params(user,week,item,initialRoute.approver(),initialRoute.defaultApprover(),initialRoute.reason(),deadline(week,config.params().submitWeekday(),config.params().submitTime()),
                    deadline(week,config.params().approveWeekday(),config.params().approveTime()),config.id()).update();
        }
        long id=d.jdbc.sql("SELECT id FROM approval_package WHERE user_id=? AND week_start=? AND work_item_id=? FOR UPDATE")
            .params(user,week,item).query(Long.class).single();
        int pending=d.jdbc.sql("SELECT COUNT(*) FROM approval_item WHERE package_id=? AND status='PENDING'").param(id).query(Integer.class).single();
        if(pending==0) {
            var route=d.route(user,item);
            d.jdbc.sql("""
                UPDATE approval_package SET approver_user_id=?,default_approver_id=?,route_reason=?,submit_deadline=?,approval_deadline=?,config_version_id=?,row_version=row_version+1 WHERE id=?
                """).params(route.approver(),route.defaultApprover(),route.reason(),deadline(week,config.params().submitWeekday(),config.params().submitTime()),
                    deadline(week,config.params().approveWeekday(),config.params().approveTime()),config.id(),id).update();
        } else d.requireOtherActive(d.pack(id,false).approver(),user);
        return id;
    }
    private boolean unsubmitted(Revision r){return Set.of("DRAFT","REJECTED").contains(r.state());}
    private void previewGate(Revision r){
        if(!d.periods.status(r.date()).equals("OPEN")&&!d.periods.scope(r.date(),r.user(),r.item(),r.kind(),r.recordId(),"SUBMIT"))
            throw new ApiException(409,"PERIOD_CLOSED","该月份已截止或封账，当前记录没有有效送审授权");
        if(r.disputeOpen())throw bad("DISPUTE_OPEN","当前记录正在争议处理中，请先完成协调");
    }
    private void onsiteValid(Revision r){if(!r.itemType().equals("PROJECT"))throw bad("ONSITE_PROJECT_REQUIRED","现场日须归属项目");required(r.content(),500,"现场驻留或出差事由");}
    static LocalDateTime deadline(LocalDate week,int weekday,String time){return week.plusWeeks(1).plusDays(weekday-1).atTime(LocalTime.parse(time)).atZone(ZoneId.of("Asia/Shanghai")).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();}
    private static void monday(LocalDate week){if(week==null||week.getDayOfWeek()!=DayOfWeek.MONDAY)throw bad("INVALID_WEEK","请选择周一作为自然周起日");}

    public List<Map<String,Object>> list(String view,LocalDate week) {
        var actor=d.current.require();if(view==null)view="pending";
        String condition=switch(view) {
            case "pending"->"p.approver_user_id=:actor AND EXISTS(SELECT 1 FROM approval_item a WHERE a.package_id=p.id AND a.status='PENDING')";
            case "handled"->"EXISTS(SELECT 1 FROM approval_item a WHERE a.package_id=p.id AND a.handled_by=:actor)";
            case "mine"->"p.user_id=:actor";
            case "all"->actor.canManage()?"1=1":"(p.approver_user_id=:actor OR p.user_id=:actor OR EXISTS(SELECT 1 FROM approval_item a WHERE a.package_id=p.id AND a.handled_by=:actor))";
            default->throw bad("INVALID_VIEW","审批视图不支持");
        };
        String sql="SELECT p.id FROM approval_package p WHERE "+condition+(week==null?"":" AND p.week_start=:week")+" ORDER BY p.approval_deadline,p.id DESC LIMIT 300";
        var query=d.jdbc.sql(sql);if(sql.contains(":actor"))query=query.param("actor",actor.id());if(week!=null)query=query.param("week",week);
        return query.query(Long.class).list().stream().map(id->summary(d.pack(id,false))).toList();
    }
    Map<String,Object> summary(Pack pack) {
        var actor=d.current.require();d.requireVisible(pack,actor);var items=d.items(pack.id()).stream().filter(i->d.seesItem(pack,i,actor)&&i.rev().current()).toList();
        int pending=(int)items.stream().filter(i->i.status().equals("PENDING")).count();
        int work=items.stream().filter(i->i.rev().kind().equals("TIME")&&!i.rev().action().equals("CANCEL")&&!i.rev().entryKind().equals("IDLE")).mapToInt(i->i.rev().minutes()).sum();
        int idle=items.stream().filter(i->i.rev().kind().equals("TIME")&&!i.rev().action().equals("CANCEL")&&i.rev().entryKind().equals("IDLE")).mapToInt(i->i.rev().minutes()).sum();
        int onsite=(int)items.stream().filter(i->i.rev().kind().equals("ONSITE")&&i.rev().action().equals("REPORT")).count();
        var pendingItems=items.stream().filter(i->i.status().equals("PENDING")&&i.rev().action().equals("REPORT")).toList();
        int pendingWork=pendingItems.stream().filter(i->i.rev().kind().equals("TIME")&&!i.rev().entryKind().equals("IDLE")).mapToInt(i->i.rev().minutes()).sum();
        int pendingIdle=pendingItems.stream().filter(i->i.rev().kind().equals("TIME")&&i.rev().entryKind().equals("IDLE")).mapToInt(i->i.rev().minutes()).sum();
        boolean red=items.stream().anyMatch(i->dayWorkMinutes(i.rev().dayId())>d.config.forDate(i.rev().date()).params().redFlagMinutes());
        boolean transferred=d.jdbc.sql("SELECT COUNT(*) FROM approval_transfer WHERE package_id=?").param(pack.id()).query(Integer.class).single()>0;
        return map("id",str(pack.id()),"userId",str(pack.user()),"employeeNo",pack.employeeNo(),"userName",pack.userName(),"weekStart",pack.week(),
            "workItemId",str(pack.item()),"workItemName",pack.itemName(),"workItemType",pack.itemType(),"approverId",str(pack.approver()),"approverName",pack.approverName(),
            "routeReason",pack.routeReason(),"approvalDeadline",instant(pack.deadline()),"version",pack.version(),"pendingCount",pending,"workMinutes",work,"idleMinutes",idle,
            "onsiteDays",onsite,"pendingWorkMinutes",pendingWork,"pendingIdleMinutes",pendingIdle,"pendingOnsiteDays",pendingItems.stream().filter(i->i.rev().kind().equals("ONSITE")).count(),
            "redFlag",red,"disputedCount",items.stream().filter(i->i.rev().disputeOpen()).count(),"transferred",transferred,
            "canDecide",actor.id()==pack.approver()&&actor.id()!=pack.user()&&pending>0,"canTransfer",actor.canManage()&&pending>0);
    }
    private int dayWorkMinutes(long day){return d.jdbc.sql("SELECT COALESCE(SUM(r.minutes),0) FROM time_entry e JOIN time_entry_revision r ON r.id=e.current_revision_id WHERE e.day_id=? AND r.action='REPORT' AND r.kind<>'IDLE'").param(day).query(Integer.class).single();}
    public Map<String,Object> detail(long packageId) {
        var pack=d.pack(packageId,false);var actor=d.current.require();d.requireVisible(pack,actor);
        var items=d.items(packageId).stream().filter(i->d.seesItem(pack,i,actor)).map(i->itemView(pack,i)).toList();
        int other=0;
        if(actor.id()==pack.approver()||actor.id()==pack.user())other=d.jdbc.sql("""
            SELECT COALESCE(SUM(r.minutes),0) FROM day_record day JOIN time_entry e ON e.day_id=day.id JOIN time_entry_revision r ON r.id=e.current_revision_id
            WHERE day.user_id=? AND day.work_date>=? AND day.work_date<? AND r.work_item_id<>? AND r.kind<>'IDLE' AND r.action='REPORT'
            """).params(pack.user(),pack.week(),pack.week().plusDays(7),pack.item()).query(Integer.class).single();
        return map("package",summary(pack),"items",items,"otherWorkMinutes",other,"correctionRequests",List.of(),"disputes",List.of());
    }
    private Map<String,Object> itemView(Pack pack,Item item) {
        var actor=d.current.require();var r=item.rev();
        var result=map("id",str(item.id()),"kind",r.kind(),"recordId",str(r.recordId()),"revisionId",str(r.id()),"date",r.date(),"action",r.action(),"entryKind",r.entryKind(),
            "minutes",r.action().equals("CANCEL")?0:r.minutes(),"hours",r.action().equals("CANCEL")?"0":DayRules.hours(r.minutes()),"content",r.content(),"redReason",r.redReason(),"state",item.status(),"current",r.current(),
            "handledBy",str(item.handledBy()),"handledName",d.name(item.handledBy()),"handledAt",instant(item.handledAt()),"reason",item.reason(),"disputeOpen",r.disputeOpen(),
            "canDecide",d.canDecide(pack,item,actor),"canApprove",d.canDecide(pack,item,actor)&&!r.disputeOpen(),"canReject",d.canDecide(pack,item,actor),
            "canRequestCorrection",r.current()&&actor.id()==r.user()&&Set.of("APPROVED","LOCKED").contains(r.state())&&(!r.disputeOpen()||d.correctionRequired(r)),
            "canDispute",r.current()&&!r.disputeOpen()&&(actor.id()==r.user()||actor.id()==(item.handledBy()==null?pack.approver():item.handledBy()))&&Set.of("PENDING","APPROVED","REJECTED","LOCKED").contains(r.state()));
        if(d.canSeeCost(actor.id(),r.item()))try {result.put("cost",d.cost(r,item.status().equals("PENDING")));}catch(ApiException e){result.put("costError",e.getMessage());}
        result.putAll(d.jdbc.sql("""
            SELECT COALESCE(SUM(CASE WHEN x.kind<>'IDLE' THEN x.minutes ELSE 0 END),0) day_work,
              COALESCE(SUM(CASE WHEN x.kind<>'IDLE' AND x.work_item_id=? THEN x.minutes ELSE 0 END),0) project_work
            FROM time_entry e JOIN time_entry_revision x ON x.id=e.current_revision_id WHERE e.day_id=? AND x.action='REPORT'
            """).params(r.item(),r.dayId()).query((rs,n)->map("dayWorkMinutes",rs.getInt("day_work"),"projectWorkMinutes",rs.getInt("project_work"))).single());
        return result;
    }

    public BatchResult decide(long packageId,Decide input,String key) {
        key(key);if(input==null||input.expectedVersion()==null||input.itemIds()==null||input.itemIds().isEmpty()||input.itemIds().size()>200)throw bad("INVALID_SELECTION","请选择本包最多 200 项并提供包版本");
        if(!Set.of("APPROVE","REJECT").contains(input.decision()==null?"":input.decision()))throw bad("INVALID_DECISION","仅支持通过或驳回");
        if(input.decision().equals("REJECT"))required(input.reason(),1000,"驳回原因");
        if(new HashSet<>(input.itemIds()).size()!=input.itemIds().size())throw bad("INVALID_SELECTION","审批项不能重复选择");
        return d.tx.execute(status->{
            var actor=d.current.require();var initial=d.pack(packageId,false);
            if(initial.approver()!=actor.id()||initial.user()==actor.id())throw forbidden("只可处理当前指派给本人且非本人填报的审批项");
            String command="DECIDE:"+packageId;var previous=d.receipt(command,key,input,BatchResult.class);if(previous!=null)return previous;
            var requested=input.itemIds().stream().map(ApprovalData::id).sorted().map(id->d.item(id,false)).toList();
            for(var item:requested)if(item.packageId()!=packageId)throw forbidden("所选审批项不属于当前包");
            var result=new MutableResult();var eligible=new ArrayList<Item>();
            for(var item:requested) {
                try {if(!item.status().equals("PENDING")){result.unchanged.add(resultItem(item,null,"该项已处理"));continue;}
                    if(!item.rev().current())throw bad("VERSION_CHANGED","该内容已被新版本替代");
                    if(item.rev().disputeOpen()&&(input.decision().equals("APPROVE")||d.hasOpenDispute(item.rev())))throw bad("DISPUTE_OPEN","该项争议未结束或已确定需更正，不能直接通过；请按协调结论退回");
                    d.gate(item.rev(),input.decision());eligible.add(item);
                }catch(ApiException e){result.failed.add(resultItem(item,e.code(),e.getMessage()));}
            }
            d.lockDays(eligible.stream().map(Item::rev).toList(),input.decision());
            var pack=d.pack(packageId,true);actor=d.current.require();
            if(pack.approver()!=actor.id()||pack.user()==actor.id())throw forbidden("接收人已变化或存在本人审批");
            if(pack.version()!=input.expectedVersion())throw new ApiException(409,"VERSION_CONFLICT","审批包已变化，请刷新后核对");
            Map<Long,Pricing> prices=new HashMap<>();var process=new ArrayList<Item>();
            for(var candidate:eligible) {
                var item=d.item(candidate.id(),true);
                if(!d.canDecide(pack,item,actor)){result.failed.add(resultItem(item,"VERSION_CHANGED","当前状态已变化，请刷新"));continue;}
                try {if(input.decision().equals("APPROVE"))prices.put(item.id(),price(item.rev()));process.add(item);}
                catch(ApiException e){result.failed.add(resultItem(item,e.code(),e.getMessage()));}
            }
            for(var item:process) {
                var r=item.rev();String state=input.decision().equals("APPROVE")?"APPROVED":"REJECTED";
                if(state.equals("APPROVED"))approve(r,prices.get(item.id()),actor.id());else {
                    d.state(r,state);d.jdbc.sql("UPDATE "+d.table(r.kind())+" SET dispute_open=FALSE WHERE id=?").param(r.id()).update();
                }
                d.jdbc.sql("UPDATE approval_item SET status=?,handled_by=?,handled_at=UTC_TIMESTAMP(6),reason=? WHERE id=? AND status='PENDING'")
                    .params(state,actor.id(),input.reason()==null?"":input.reason().strip(),item.id()).update();
                d.touch(r);d.audit(actor.id(),"APPROVAL_"+state,"APPROVAL_ITEM",item.id(),item,map("state",state,"cost",prices.get(item.id())),input.reason());
                if(state.equals("REJECTED"))d.notifications.enqueue(r.user(),null,"REJECTED","rejected_"+item.id(),"有工时或现场日已退回，请查看原因并核实","/h5/work?date="+r.date());
                result.succeeded.add(resultItem(item,null,state.equals("APPROVED")?"已通过":"已驳回"));
            }
            if(!process.isEmpty())d.touchPack(packageId);var value=result.value();d.saveReceipt(command,key,input,value);return value;
        });
    }
    private Pricing price(Revision r) {
        if(r.action().equals("CANCEL")||r.kind().equals("TIME")&&!r.itemType().equals("PROJECT"))return new Pricing(BigDecimal.ZERO,null,null,null,null,"NO_PROJECT_COST_V1");
        if(r.kind().equals("ONSITE")){var p=d.costing.resolveOnsite(r.date());return new Pricing(p.amount(),p.dailyRate(),p.rateId(),null,null,p.policyVersion());}
        var p=d.costing.resolveLabor(r.user(),r.date(),r.minutes());return new Pricing(p.amount(),p.dailyRate(),p.rateId(),p.levelHistoryId(),p.levelCode(),p.policyVersion());
    }
    private void approve(Revision r,Pricing price,long actor) {
        String sql="UPDATE "+d.table(r.kind())+" SET state='APPROVED',approved_by=?,approved_at=UTC_TIMESTAMP(6),cost_amount=?,cost_daily_rate=?,cost_rate_id=?,cost_policy_version=?,owner_department_id=?";
        var params=new ArrayList<Object>(Arrays.asList(actor,price.amount(),price.daily(),price.rate(),price.policy(),r.ownerDepartment()));
        if(r.kind().equals("TIME")){sql+=",cost_level_history_id=?,cost_level_code=?";params.add(price.levelHistory());params.add(price.level());}
        sql+=" WHERE id=?";params.add(r.id());d.jdbc.sql(sql).params(params).update();
    }
    public Map<String,Object> transfer(long packageId,Transfer input,String key) {
        key(key);required(input.reason(),1000,"转交原因");required(input.basis(),1000,"业务指定依据");long next=id(input.newApproverId());
        return d.tx.execute(status->{
            long actor=d.current.requireAdmin().id();var old=d.pack(packageId,false);d.requireOtherActive(next,old.user());
            String command="TRANSFER:"+packageId;Map previous=d.receipt(command,key,input,Map.class);if(previous!=null)return previous;
            var pending=d.items(packageId).stream().filter(i->i.status().equals("PENDING")).toList();if(pending.isEmpty())throw bad("NO_PENDING_ITEMS","没有可转交的未处理项");
            d.lockDays(pending.stream().map(Item::rev).toList(),"TRANSFER");d.lockActor(actor);d.current.requireAdmin();var pack=d.pack(packageId,true);
            if(input.expectedVersion()==null||pack.version()!=input.expectedVersion())throw new ApiException(409,"VERSION_CONFLICT","审批包已变化，请刷新后转交");
            for(var i:pending)d.item(i.id(),true);
            d.jdbc.sql("INSERT INTO approval_transfer(package_id,old_approver_id,new_approver_id,item_ids,basis,reason,created_by) VALUES(?,?,?,?,?,?,?)")
                .params(packageId,pack.approver(),next,d.json.writeValueAsString(pending.stream().map(i->str(i.id())).toList()),input.basis(),input.reason(),actor).update();
            long transfer=d.lastId();d.jdbc.sql("UPDATE approval_package SET approver_user_id=?,route_reason=?,row_version=row_version+1 WHERE id=?")
                .params(next,"业务指定转交："+input.reason(),packageId).update();
            for(var i:pending)d.touch(i.rev());
            d.audit(actor,"APPROVAL_TRANSFERRED","APPROVAL_PACKAGE",packageId,pack,map("newApproverId",str(next),"basis",input.basis(),"items",pending.stream().map(Item::id).toList()),input.reason());
            d.notifications.enqueue(next,packageId,"APPROVAL_PENDING","transfer_"+transfer,"有审批待办已按业务指定转交给你","/h5/approvals/"+packageId);
            var result=detail(packageId);d.saveReceipt(command,key,input,result);return result;
        });
    }
    public List<Map<String,Object>> designations(){d.current.requireAdmin();return d.jdbc.sql("SELECT id FROM approval_designation ORDER BY id DESC").query(Long.class).list().stream().map(this::designation).toList();}
    private Map<String,Object> designation(long id){return d.jdbc.sql("""
        SELECT a.*,u.name user_name,w.name item_name,p.name approver_name FROM approval_designation a JOIN app_user u ON u.id=a.user_id
        JOIN work_item w ON w.id=a.work_item_id JOIN app_user p ON p.id=a.approver_user_id WHERE a.id=?
        """).param(id).query((r,n)->map("id",r.getString("id"),"userId",r.getString("user_id"),"userName",r.getString("user_name"),"workItemId",r.getString("work_item_id"),
            "workItemName",r.getString("item_name"),"approverId",r.getString("approver_user_id"),"approverName",r.getString("approver_name"),"reason",r.getString("reason"),"basis",r.getString("basis"))).single();}
    public Map<String,Object> saveDesignation(DesignationInput input,String key){
        key(key);long user=id(input.userId()),item=id(input.workItemId()),approver=id(input.approverId());required(input.reason(),1000,"原因");required(input.basis(),1000,"业务指定依据");
        return d.tx.execute(status->{long actor=d.current.requireAdmin().id();d.lockActor(actor);d.current.requireAdmin();d.requireOtherActive(approver,user);
            d.jdbc.sql("SELECT id FROM app_user WHERE id=? FOR UPDATE").param(user).query(Long.class).optional().orElseThrow(()->missing("人员"));
            d.jdbc.sql("SELECT id FROM work_item WHERE id=? FOR SHARE").param(item).query(Long.class).optional().orElseThrow(()->missing("对象"));
            String command="DESIGNATE:"+user+":"+item;Map previous=d.receipt(command,key,input,Map.class);if(previous!=null)return previous;
            var old=d.jdbc.sql("SELECT id FROM approval_designation WHERE user_id=? AND work_item_id=?").params(user,item).query(Long.class).optional();
            Object before=old.isPresent()?designation(old.get()):null;
            d.jdbc.sql("""
                INSERT INTO approval_designation(user_id,work_item_id,approver_user_id,basis,reason,created_by) VALUES(?,?,?,?,?,?) AS incoming
                ON DUPLICATE KEY UPDATE approver_user_id=incoming.approver_user_id,basis=incoming.basis,reason=incoming.reason,created_by=incoming.created_by
                """).params(user,item,approver,input.basis(),input.reason(),actor).update();
            long id=d.jdbc.sql("SELECT id FROM approval_designation WHERE user_id=? AND work_item_id=?").params(user,item).query(Long.class).single();var result=designation(id);
            d.audit(actor,"APPROVAL_DESIGNATED","APPROVAL_DESIGNATION",id,before,result,input.reason());d.saveReceipt(command,key,input,result);return result;});
    }
    private ResultItem resultItem(Item item,String code,String message){return new ResultItem(str(item.id()),item.rev().kind(),item.rev().date(),str(item.packageId()),code,message);}
    private record Pricing(BigDecimal amount,BigDecimal daily,Long rate,Long levelHistory,String level,String policy) {}
    private static final class MutableResult {
        final List<ResultItem> succeeded=new ArrayList<>(),failed=new ArrayList<>(),unchanged=new ArrayList<>();
        void add(BatchResult value){succeeded.addAll(value.succeeded());failed.addAll(value.failed());unchanged.addAll(value.unchanged());}
        BatchResult value(){return new BatchResult(List.copyOf(succeeded),List.copyOf(failed),List.copyOf(unchanged));}
    }
}
