package com.allen.worklog.approval;

import com.allen.worklog.common.ApiException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.allen.worklog.approval.ApprovalData.*;
import static com.allen.worklog.approval.ApprovalModels.*;

@Service
public class CorrectionService {
    private final ApprovalData d;
    public CorrectionService(ApprovalData d){this.d=d;}
    public List<Map<String,Object>> list(String view) {
        var actor=d.current.require();if(view==null)view="all";
        String clause=switch(view){case "mine"->"c.user_id=:actor";case "pending"->"c.approver_user_id=:actor AND c.state='REQUESTED'";
            case "all"->actor.canManage()?"1=1":"(c.user_id=:actor OR c.approver_user_id=:actor)";default->throw bad("INVALID_VIEW","更正请求视图不支持");};
        var q=d.jdbc.sql("SELECT c.id FROM correction_request c WHERE "+clause+" ORDER BY c.id DESC LIMIT 300");if(clause.contains(":actor"))q=q.param("actor",actor.id());
        return q.query(Long.class).list().stream().map(this::get).toList();
    }
    List<Map<String,Object>> forPackage(long packageId) {
        var p=d.pack(packageId,false);long actor=d.current.require().id();boolean admin=d.current.require().canManage();
        return d.jdbc.sql("SELECT id FROM correction_request WHERE user_id=? AND work_item_id=? AND work_date>=? AND work_date<? AND (? OR user_id=? OR approver_user_id=?) ORDER BY id DESC")
            .params(p.user(),p.item(),p.week(),p.week().plusDays(7),admin,actor,actor).query(Long.class).list().stream().map(this::get).toList();
    }
    public Map<String,Object> get(long id) {
        var c=read(id,false);var actor=d.current.require();
        if(actor.id()!=c.user()&&actor.id()!=c.approver()&&!actor.canManage())throw forbidden("无权查看该更正请求");
        return map("id",str(c.id()),"kind",c.kind(),"recordId",str(c.record()),"revisionId",str(c.revision()),"userId",str(c.user()),"userName",d.name(c.user()),
            "workItemName",d.jdbc.sql("SELECT name FROM work_item WHERE id=?").param(c.item()).query(String.class).single(),"date",c.date(),"action",c.action(),"reason",c.reason(),"state",c.state(),
            "approverId",str(c.approver()),"approverName",d.name(c.approver()),"handledAt",instant(c.handledAt()),"newRevisionId",str(c.newRevision()),
            "canDecide",c.state().equals("REQUESTED")&&actor.id()==c.approver()&&actor.id()!=c.user(),"canTransfer",c.state().equals("REQUESTED")&&actor.canManage());
    }
    public Map<String,Object> create(CorrectionInput input,String key) {
        key(key);checkKind(input.kind());long record=id(input.recordId());required(input.reason(),1000,"更正原因");
        if(!Set.of("EDIT","CANCEL").contains(input.action()==null?"":input.action()))throw bad("INVALID_CORRECTION","请选择编辑或取消更正");
        return d.tx.execute(status->{
            long actor=d.current.require().id();var initial=d.currentRevision(input.kind(),record);if(initial.user()!=actor)throw forbidden("只能申请本人记录更正");
            metadataLock(initial);var r=d.currentRevision(input.kind(),record);
            String command="CORRECTION_REQUEST:"+input.kind()+":"+record;Map previous=d.receipt(command,key,input,Map.class);if(previous!=null)return previous;
            if(!Set.of("APPROVED","LOCKED").contains(r.state()))throw bad("CORRECTION_NOT_APPROVED","只有已通过记录需要申请更正；待审批请联系接收人退回");
            if(r.disputeOpen()&&!d.correctionRequired(r))throw bad("DISPUTE_OPEN","请先完成该记录的争议协调");
            if(r.approvedBy()==null||r.approvedBy()==actor)throw bad("APPROVER_UNRESOLVED","缺少有效原实际审批记录，请管理员核实");
            if(d.jdbc.sql("SELECT COUNT(*) FROM correction_request WHERE kind=? AND record_id=? AND state='REQUESTED'").params(input.kind(),record).query(Integer.class).single()>0)
                throw bad("CORRECTION_ALREADY_PENDING","该记录已有待处理更正请求");
            d.jdbc.sql("""
                INSERT INTO correction_request(kind,record_id,revision_id,user_id,work_date,work_item_id,requested_action,requested_by,approver_user_id,reason)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """).params(r.kind(),record,r.id(),actor,r.date(),r.item(),input.action(),actor,r.approvedBy(),input.reason()).update();
            long request=d.lastId();var result=get(request);
            d.audit(actor,"CORRECTION_REQUESTED","CORRECTION_REQUEST",request,null,result,input.reason());
            d.notifications.enqueue(r.approvedBy(),null,"CORRECTION_REQUEST","correction_request_"+request,"有已通过记录申请更正，请核实原始事实","/h5/approvals?correction="+request);
            d.saveReceipt(command,key,input,result);return result;
        });
    }
    public Map<String,Object> decide(long id,CorrectionDecision input,String key) {
        key(key);if(!Set.of("APPROVE","DECLINE").contains(input.decision()==null?"":input.decision()))throw bad("INVALID_DECISION","请选择同意或拒绝更正");
        required(input.reason(),1000,"核实说明");
        return d.tx.execute(status->{
            var actor=d.current.require();var initial=read(id,false);authorizeDecision(initial,actor.id());
            String command="CORRECTION_DECIDE:"+id;Map previous=d.receipt(command,key,input,Map.class);if(previous!=null)return previous;
            var r=d.revision(initial.kind(),initial.revision());
            if(input.decision().equals("APPROVE"))d.lockDays(List.of(r),"CORRECT");else metadataLock(r);
            var c=read(id,true);authorizeDecision(c,d.current.require().id());
            if(!c.state().equals("REQUESTED"))throw new ApiException(409,"CORRECTION_ALREADY_HANDLED","更正请求已处理，请刷新");
            var current=d.currentRevision(c.kind(),c.record());
            if(current.id()!=c.revision()||!Set.of("APPROVED","LOCKED").contains(current.state()))throw new ApiException(409,"VERSION_CHANGED","原内容已变化，请刷新后重新申请");
            if(current.disputeOpen()&&!d.correctionRequired(current))throw bad("DISPUTE_OPEN","原内容仍有争议，请先完成协调");
            Long next=null;String state=input.decision().equals("APPROVE")?"APPROVED":"DECLINED";
            if(state.equals("APPROVED")){next=d.draftCopy(current,c.action().equals("CANCEL")?"CANCEL":"REPORT",c.id()).id();d.touch(current);}
            d.jdbc.sql("UPDATE correction_request SET state=?,handled_by=?,handled_at=UTC_TIMESTAMP(6),handling_reason=?,new_revision_id=? WHERE id=?")
                .params(state,actor.id(),input.reason(),next,id).update();
            var result=get(id);d.audit(actor.id(),"CORRECTION_"+state,"CORRECTION_REQUEST",id,c,result,input.reason());
            d.notifications.enqueue(c.user(),null,"CORRECTION_DECIDED","correction_decided_"+id,"更正申请已处理，请查看核实结果并按需重新送审","/h5/work?date="+c.date());
            d.saveReceipt(command,key,input,result);return result;
        });
    }
    public Map<String,Object> transfer(long id,Transfer input,String key) {
        key(key);long next=id(input.newApproverId());required(input.reason(),1000,"转交原因");required(input.basis(),1000,"业务指定依据");
        return d.tx.execute(status->{
            long actor=d.current.requireAdmin().id();var initial=read(id,false);d.requireOtherActive(next,initial.user());
            metadataLock(d.revision(initial.kind(),initial.revision()));d.lockActor(actor);d.current.requireAdmin();var c=read(id,true);
            String command="CORRECTION_TRANSFER:"+id;Map previous=d.receipt(command,key,input,Map.class);if(previous!=null)return previous;
            if(!c.state().equals("REQUESTED"))throw bad("CORRECTION_ALREADY_HANDLED","只能转交未处理更正请求");
            d.jdbc.sql("UPDATE correction_request SET approver_user_id=? WHERE id=?").params(next,id).update();var result=get(id);
            d.audit(actor,"CORRECTION_TRANSFERRED","CORRECTION_REQUEST",id,c,map("request",result,"basis",input.basis()),input.reason());
            d.notifications.enqueue(next,null,"CORRECTION_REQUEST","correction_transfer_"+id+"_"+key,"更正核实请求已按业务指定转交给你","/h5/approvals?correction="+id);
            d.saveReceipt(command,key,input,result);return result;
        });
    }
    private void metadataLock(Revision r) {
        var users=new TreeSet<Long>();users.add(d.current.require().id());users.add(r.user());
        for(long user:users)d.jdbc.sql("SELECT id FROM app_user WHERE id=? FOR SHARE").param(user).query(Long.class).single();d.current.require();
        d.jdbc.sql("SELECT id FROM day_record WHERE id=? FOR UPDATE").param(r.dayId()).query(Long.class).single();
    }
    private void authorizeDecision(Request c,long actor){if(c.approver()!=actor||c.user()==actor)throw forbidden("仅当前指定的非本人核实人可处理更正请求");}
    private Request read(long id,boolean lock){return d.jdbc.sql("SELECT * FROM correction_request WHERE id=?"+(lock?" FOR UPDATE":"")).param(id).query((r,n)->new Request(r.getLong("id"),r.getString("kind"),
        r.getLong("record_id"),r.getLong("revision_id"),r.getLong("user_id"),r.getObject("work_date",LocalDate.class),r.getLong("work_item_id"),r.getString("requested_action"),
        r.getLong("approver_user_id"),r.getString("reason"),r.getString("state"),r.getObject("handled_at",LocalDateTime.class),r.getObject("new_revision_id",Long.class)))
        .optional().orElseThrow(()->missing("更正请求"));}
    private record Request(long id,String kind,long record,long revision,long user,LocalDate date,long item,String action,long approver,String reason,String state,LocalDateTime handledAt,Long newRevision){}
}
