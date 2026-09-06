package com.allen.worklog.approval;

import com.allen.worklog.common.ApiException;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.allen.worklog.approval.ApprovalData.*;
import static com.allen.worklog.approval.ApprovalModels.*;

@Service
public class DisputeService {
    private final ApprovalData d;
    public DisputeService(ApprovalData d){this.d=d;}
    public List<Map<String,Object>> list(){
        long actor=d.current.require().id();
        return d.jdbc.sql("""
            SELECT c.id FROM dispute_case c JOIN approval_item a ON a.id=c.approval_item_id JOIN approval_package p ON p.id=a.package_id
            WHERE p.user_id=? OR c.approver_user_id=? OR c.coordinator_id=? OR EXISTS(SELECT 1 FROM audit_event e WHERE e.actor_id=? AND e.object_type='DISPUTE'
              AND e.object_id=CAST(c.id AS CHAR) AND e.action IN ('DISPUTE_ESCALATE','DISPUTE_RESOLVE')) ORDER BY c.id DESC LIMIT 300
            """).params(actor,actor,actor,actor).query(Long.class).list().stream().map(this::get).toList();
    }
    List<Map<String,Object>> forPackage(long packageId){
        long actor=d.current.require().id();
        return d.jdbc.sql("""
            SELECT c.id FROM dispute_case c JOIN approval_item a ON a.id=c.approval_item_id JOIN approval_package p ON p.id=a.package_id
            WHERE p.id=? AND (p.user_id=? OR c.approver_user_id=? OR c.coordinator_id=?) ORDER BY c.id DESC
            """).params(packageId,actor,actor,actor).query(Long.class).list().stream().map(this::get).toList();
    }
    public Map<String,Object> get(long id){
        var c=read(id,false);var item=d.item(c.item(),false);var r=item.rev();long actor=d.current.require().id();authorize(c,r,actor);
        return view(c,item,actor);
    }
    private Map<String,Object> view(Case c,Item item,long actor){
        var r=item.rev();long id=c.id();
        boolean open=!c.state().equals("RESOLVED");boolean canResolve=open&&actor==c.coordinator()&&actor!=r.user()&&actor!=c.approver();
        return map("id",str(id),"approvalItemId",str(c.item()),"packageId",str(item.packageId()),"userId",str(r.user()),"userName",d.name(r.user()),"workItemName",r.itemName(),"date",r.date(),
            "kind",r.kind(),"recordId",str(r.recordId()),"revisionId",str(r.id()),"action",r.action(),"entryKind",r.entryKind(),"hours",r.action().equals("CANCEL")?"0":com.allen.worklog.work.DayRules.hours(r.minutes()),
            "content",r.content(),"redReason",r.redReason(),"recordState",r.state(),"openedAt",instant(c.openedAt()),"resolvedAt",instant(c.resolvedAt()),
            "state",c.state(),"stage",c.stage(),"coordinatorId",str(c.coordinator()),"coordinatorName",d.name(c.coordinator()),"employeeStatement",c.employeeStatement(),"approverStatement",c.approverStatement(),
            "outcome",c.outcome(),"resolutionReason",c.resolutionReason(),"canComment",open&&(actor==r.user()||actor==c.approver()),
            "canResolve",canResolve,"canEscalate",canResolve&&c.stage().equals("COORDINATION"));
    }
    public Map<String,Object> create(DisputeInput input,String key){
        key(key);long itemId=id(input.approvalItemId());String statement=required(input.statement(),2000,"分歧说明");
        return d.tx.execute(status->{
            long actor=d.current.require().id();var initial=d.item(itemId,false);var p=d.pack(initial.packageId(),false);long party=initial.handledBy()==null?p.approver():initial.handledBy();
            if(actor!=initial.rev().user()&&actor!=party)throw forbidden("仅该记录员工或实际审批人可申请协调");
            String command="DISPUTE_CREATE:"+itemId;Map previous=d.receipt(command,key,input,Map.class);if(previous!=null)return previous;
            d.lockDays(List.of(initial.rev()),"DISPUTE");p=d.pack(initial.packageId(),true);var item=d.item(itemId,true);var r=item.rev();party=item.handledBy()==null?p.approver():item.handledBy();
            if(actor!=r.user()&&actor!=party)throw forbidden("该项实际处理人已变化，请刷新");
            if(!r.current())throw bad("VERSION_CHANGED","只能对当前记录申请协调，历史内容可在审计中查看");
            if(r.disputeOpen()||d.hasOpenDispute(r))throw bad("DISPUTE_ALREADY_OPEN","该条目已有未结争议或待完成的更正结论");
            long owner=d.jdbc.sql("SELECT owner_department_id FROM work_item WHERE id=?").param(r.item()).query(Long.class).single();
            var route=coordinator(owner,r.user(),party,false);boolean employee=actor==r.user();
            d.jdbc.sql("""
                INSERT INTO dispute_case(approval_item_id,opened_by,approver_user_id,employee_statement,approver_statement,original_statement,state,stage,coordinator_id,owner_department_id)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """).params(itemId,actor,party,employee?statement:"",employee?"":statement,statement,route.stage().equals("RULING")?"ESCALATED":"OPEN",route.stage(),route.user(),owner).update();
            long dispute=d.lastId();d.jdbc.sql("UPDATE "+d.table(r.kind())+" SET dispute_open=TRUE WHERE id=?").param(r.id()).update();d.touch(r);d.touchPack(p.id());
            var result=get(dispute);d.audit(actor,"DISPUTE_OPENED","DISPUTE",dispute,null,result,statement);
            d.notifications.enqueue(route.user(),null,"DISPUTE_OPEN","dispute_open_"+dispute,"有工时事实或归属争议需要协调，请核实双方原始说明","/h5/approvals?dispute="+dispute);
            d.saveReceipt(command,key,input,result);return result;
        });
    }
    public Map<String,Object> statement(long id,Statement input,String key){
        key(key);String text=required(input.statement(),2000,"补充说明");
        return d.tx.execute(status->{
            long actor=d.current.require().id();var initial=read(id,false);var item=d.item(initial.item(),false);authorize(initial,item.rev(),actor);
            if(actor!=item.rev().user()&&actor!=initial.approver())throw forbidden("只可补充本人作为员工或审批人的说明");
            String command="DISPUTE_STATEMENT:"+id;Map previous=d.receipt(command,key,input,Map.class);if(previous!=null)return previous;
            d.lockDays(List.of(item.rev()),"DISPUTE");d.pack(item.packageId(),true);d.item(item.id(),true);var c=read(id,true);
            if(c.state().equals("RESOLVED"))throw bad("DISPUTE_RESOLVED","已结束的争议不能覆盖原始说明");
            boolean employee=actor==item.rev().user();String old=employee?c.employeeStatement():c.approverStatement();
            if(old.length()+text.length()>16000)throw bad("STATEMENT_TOO_LONG","累计说明超过限制，请在处理依据中引用核实材料");
            d.jdbc.sql("UPDATE dispute_case SET "+(employee?"employee_statement":"approver_statement")+"=? WHERE id=?")
                .params(old.isBlank()?text:old+"\n\n补充："+text,id).update();var result=get(id);
            d.audit(actor,"DISPUTE_STATEMENT_ADDED","DISPUTE",id,c,result,text);d.saveReceipt(command,key,input,result);return result;
        });
    }
    public Map<String,Object> resolve(long id,ResolveDispute input,String key){
        key(key);required(input.reason(),1000,"协调或裁定理由");
        if(!Set.of("RESOLVE","ESCALATE").contains(input.action()==null?"":input.action()))throw bad("INVALID_RESOLUTION","请选择结束协调或升级裁定");
        if(input.action().equals("RESOLVE")&&!Set.of("CONFIRM_FACTS","NEEDS_CORRECTION").contains(input.outcome()==null?"":input.outcome()))throw bad("INVALID_OUTCOME","请选择事实确认或需要更正");
        return d.tx.execute(status->{
            long actor=d.current.require().id();var initial=read(id,false);var original=d.item(initial.item(),false);authorizeResolution(initial,original.rev(),actor);
            String command="DISPUTE_RESOLVE:"+id;Map previous=d.receipt(command,key,input,Map.class);if(previous!=null)return previous;
            d.lockDays(List.of(original.rev()),"DISPUTE");d.pack(original.packageId(),true);var item=d.item(original.id(),true);var c=read(id,true);var r=item.rev();
            authorizeResolution(c,r,d.current.require().id());if(c.state().equals("RESOLVED"))throw bad("DISPUTE_RESOLVED","争议已处理，请刷新");
            if(!r.current())throw new ApiException(409,"VERSION_CHANGED","该记录已产生新版本，请核实后处理");
            if(input.action().equals("ESCALATE")) {
                if(!c.stage().equals("COORDINATION"))throw bad("ALREADY_ESCALATED","当前已是分管领导裁定阶段");
                var route=coordinator(c.owner(),r.user(),c.approver(),true);if(route.user()==c.coordinator())throw bad("COORDINATOR_UNRESOLVED","上级与当前协调人为同一人，请核实独立裁定人配置");
                d.jdbc.sql("UPDATE dispute_case SET state='ESCALATED',stage='RULING',coordinator_id=?,resolution_reason=? WHERE id=?").params(route.user(),input.reason(),id).update();
                d.notifications.enqueue(route.user(),null,"DISPUTE_OPEN","dispute_escalated_"+id,"争议协调未达一致，需要分管领导裁定","/h5/approvals?dispute="+id);
            } else {
                d.jdbc.sql("UPDATE dispute_case SET state='RESOLVED',outcome=?,resolution_reason=?,resolved_by=?,resolved_at=UTC_TIMESTAMP(6) WHERE id=?")
                    .params(input.outcome(),input.reason(),actor,id).update();
                // A ruling requiring correction must not silently restore an already disputed confirmed amount.
                boolean keepBlocked=input.outcome().equals("NEEDS_CORRECTION")&&!r.state().equals("REJECTED");
                d.jdbc.sql("UPDATE "+d.table(r.kind())+" SET dispute_open=? WHERE id=?").params(keepBlocked,r.id()).update();
                d.notifications.enqueue(r.user(),null,"DISPUTE_RESOLVED","dispute_resolved_employee_"+id,"争议已有处理结论，请按结论更正或等待实际审批人核实","/h5/approvals?dispute="+id);
                d.notifications.enqueue(c.approver(),null,"DISPUTE_RESOLVED","dispute_resolved_approver_"+id,"争议已有处理结论，仍须按正常业务流程审批或退回","/h5/approvals?dispute="+id);
            }
            d.touch(r);d.touchPack(item.packageId());var result=view(read(id,false),item,actor);d.audit(actor,"DISPUTE_"+input.action(),"DISPUTE",id,c,result,input.reason());
            d.saveReceipt(command,key,input,result);return result;
        });
    }
    private Coordinator coordinator(long department,long user,long approver,boolean ruling){
        var dept=d.jdbc.sql("SELECT designated_manager_id,supervisor_user_id FROM department WHERE id=? AND status='ACTIVE'").param(department)
            .query((r,n)->new DepartmentRoute(r.getObject(1,Long.class),r.getObject(2,Long.class))).optional().orElseThrow(()->bad("COORDINATOR_UNRESOLVED","项目主责部门缺少有效协调配置"));
        if(!ruling&&independent(dept.manager(),user,approver))return new Coordinator(dept.manager(),"COORDINATION");
        if(independent(dept.supervisor(),user,approver))return new Coordinator(dept.supervisor(),"RULING");
        throw bad("COORDINATOR_UNRESOLVED","项目主责部门未配置独立有效的协调或裁定人，请管理员核实；不会由管理员自动裁定");
    }
    private boolean independent(Long candidate,long employee,long approver){return candidate!=null&&candidate!=employee&&candidate!=approver&&d.active(candidate);}
    private void authorize(Case c,Revision r,long actor){if(actor!=r.user()&&actor!=c.approver()&&actor!=c.coordinator()&&d.jdbc.sql("SELECT COUNT(*) FROM audit_event WHERE actor_id=? AND object_type='DISPUTE' AND object_id=? AND action IN ('DISPUTE_ESCALATE','DISPUTE_RESOLVE')")
        .params(actor,str(c.id())).query(Integer.class).single()==0)throw forbidden("仅争议双方及本次协调处理人可查看");}
    private void authorizeResolution(Case c,Revision r,long actor){if(actor!=c.coordinator()||actor==r.user()||actor==c.approver())throw forbidden("仅本次独立协调或裁定人可处理，管理员角色不能代作事实裁定");}
    private Case read(long id,boolean lock){return d.jdbc.sql("SELECT * FROM dispute_case WHERE id=?"+(lock?" FOR UPDATE":"")).param(id)
        .query((r,n)->new Case(r.getLong("id"),r.getLong("approval_item_id"),r.getLong("approver_user_id"),r.getString("employee_statement"),r.getString("approver_statement"),
            r.getString("state"),r.getString("stage"),r.getLong("coordinator_id"),r.getLong("owner_department_id"),r.getString("outcome"),r.getString("resolution_reason"),r.getObject("created_at",LocalDateTime.class),r.getObject("resolved_at",LocalDateTime.class)))
        .optional().orElseThrow(()->missing("争议"));}
    private record Case(long id,long item,long approver,String employeeStatement,String approverStatement,String state,String stage,long coordinator,long owner,String outcome,String resolutionReason,LocalDateTime openedAt,LocalDateTime resolvedAt){}
    private record Coordinator(long user,String stage){}
}
