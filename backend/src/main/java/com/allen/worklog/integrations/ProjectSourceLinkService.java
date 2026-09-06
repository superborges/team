package com.allen.worklog.integrations;

import com.allen.worklog.common.ApiException;
import com.allen.worklog.common.AuditService;
import com.allen.worklog.identity.CurrentUser;
import com.allen.worklog.work.DayRules;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** A confirmed local source alias preserves the stable work item and its complete business history. */
@Service
public class ProjectSourceLinkService {
    public record LinkInput(String workItemId,String reason) {}
    private final JdbcClient jdbc;private final CurrentUser current;private final AuditService audit;private final ObjectMapper json;
    public ProjectSourceLinkService(JdbcClient jdbc,CurrentUser current,AuditService audit,ObjectMapper json){this.jdbc=jdbc;this.current=current;this.audit=audit;this.json=json;}
    public Long find(String sourceCode){return jdbc.sql("SELECT work_item_id FROM project_source_link WHERE source_code=? AND work_item_id IS NOT NULL").param(code(sourceCode)).query(Long.class).optional().orElse(null);}
    public Long lockForApply(String sourceCode){
        transaction();String code=code(sourceCode);jdbc.sql("INSERT INTO project_source_link(source_code) VALUES(?) ON DUPLICATE KEY UPDATE source_code=project_source_link.source_code").param(code).update();
        return jdbc.sql("SELECT work_item_id FROM project_source_link WHERE source_code=? FOR UPDATE").param(code).query((rs,n)->Optional.ofNullable(rs.getObject(1,Long.class))).single().orElse(null);
    }
    public void bindCreated(String sourceCode,long workItemId){
        transaction();long actor=current.requireAdmin().id();Long previous=lockForApply(sourceCode);if(previous!=null){same(previous,workItemId);return;}
        lockActor(actor);jdbc.sql("SELECT id FROM work_item WHERE id=? FOR SHARE").param(workItemId).query(Long.class).single();
        jdbc.sql("UPDATE project_source_link SET work_item_id=?,linked_by=?,linked_at=UTC_TIMESTAMP(6),reason=? WHERE source_code=?")
            .params(workItemId,actor,"本地项目模板创建新对象时保留来源编码映射",code(sourceCode)).update();
    }
    public List<Map<String,Object>> list(){current.requireAdmin();return rows("l.work_item_id IS NOT NULL",List.of());}
    @Transactional public Map<String,Object> link(long batchId,int rowNumber,LinkInput input){
        long actor=current.requireAdmin().id();if(input==null)throw bad("请选择核实后的归集对象");long target=DayRules.id(input.workItemId());String reason=required(input.reason(),1000,"关联依据");
        SourceRow initial=sourceRow(batchId,rowNumber,false);String sourceCode=code(initial.data().get("code")),type=initial.data().get("type");
        if(!Set.of("PROJECT","NON_PROJECT").contains(type==null?"":type))throw bad("原始来源行的对象类型无效，须先修正模板重新上传");
        Long previous=lockForApply(sourceCode);lockActor(actor);
        var item=jdbc.sql("SELECT type FROM work_item WHERE id=? FOR SHARE").param(target).query(String.class).optional().orElseThrow(()->new ApiException(404,"WORK_ITEM_NOT_FOUND","目标归集对象不存在"));
        if(!item.equals(type))throw bad("来源类型与目标对象类型不一致，不能通过关联更改已有类型");
        Long direct=jdbc.sql("SELECT id FROM work_item WHERE code=? FOR SHARE").param(sourceCode).query(Long.class).optional().orElse(null);
        if(direct!=null&&direct!=target)throw new ApiException(409,"PROJECT_CODE_BOUND","该编码已经属于另一个稳定对象，不能重新指向其他对象");
        SourceRow row=sourceRow(batchId,rowNumber,true);if(!row.data().equals(initial.data()))throw new ApiException(409,"SOURCE_ROW_CHANGED","来源行已变化，请刷新后核实");
        if(previous!=null){same(previous,target);return byCode(sourceCode);}
        if(row.state().equals("APPLIED"))throw new ApiException(409,"SOURCE_ALREADY_APPLIED","该来源已应用，不能事后重新绑定；请检查已有对象与处理历史");
        jdbc.sql("UPDATE project_source_link SET work_item_id=?,import_row_id=?,linked_by=?,reason=?,linked_at=UTC_TIMESTAMP(6) WHERE source_code=?")
            .params(target,row.id(),actor,reason,sourceCode).update();var result=byCode(sourceCode);
        audit.record(actor,"PROJECT_SOURCE_LINKED","PROJECT_SOURCE_LINK",result.get("id").toString(),null,json.writeValueAsString(result),reason);
        return result;
    }
    private void lockActor(long actor){jdbc.sql("SELECT id FROM app_user WHERE id=? FOR SHARE").param(actor).query(Long.class).single();current.requireAdmin();}
    private SourceRow sourceRow(long batch,int number,boolean lock){
        if(lock)jdbc.sql("SELECT id FROM import_row WHERE batch_id=? AND row_no=? FOR UPDATE").params(batch,number).query(Long.class).optional().orElseThrow(()->new ApiException(404,"IMPORT_ROW_NOT_FOUND","来源行不存在"));
        return jdbc.sql("SELECT r.*,b.dataset FROM import_row r JOIN import_batch b ON b.id=r.batch_id WHERE r.batch_id=? AND r.row_no=?").params(batch,number).query((rs,n)->{
            if(!rs.getString("dataset").equals("PROJECT"))throw bad("只能关联 PROJECT 模板中的归集对象行");
            if(rs.getString("parse_error")!=null)throw bad("来源行格式错误，请修正模板后重新上传");
            return new SourceRow(rs.getLong("id"),rs.getString("state"),json.readValue(rs.getString("raw_data"),new TypeReference<Map<String,String>>(){}));
        }).optional().orElseThrow(()->new ApiException(404,"IMPORT_ROW_NOT_FOUND","来源行不存在"));
    }
    private Map<String,Object> byCode(String sourceCode){return rows("l.source_code=?",List.of(sourceCode)).getFirst();}
    private List<Map<String,Object>> rows(String condition,List<Object> params){return jdbc.sql("""
        SELECT l.*,w.code work_item_code,w.name work_item_name,w.type,u.name linked_by_name,r.batch_id,r.row_no
        FROM project_source_link l JOIN work_item w ON w.id=l.work_item_id JOIN app_user u ON u.id=l.linked_by
        LEFT JOIN import_row r ON r.id=l.import_row_id WHERE
        """+condition+" ORDER BY l.id DESC").params(params).query((rs,n)->{
            var row=new LinkedHashMap<String,Object>();row.put("id",rs.getString("id"));row.put("sourceCode",rs.getString("source_code"));row.put("workItemId",rs.getString("work_item_id"));row.put("workItemCode",rs.getString("work_item_code"));row.put("workItemName",rs.getString("work_item_name"));row.put("type",rs.getString("type"));row.put("batchId",rs.getString("batch_id"));row.put("rowNumber",rs.getObject("row_no",Integer.class));row.put("linkedBy",rs.getString("linked_by"));row.put("linkedByName",rs.getString("linked_by_name"));row.put("reason",rs.getString("reason"));row.put("linkedAt",rs.getObject("linked_at",LocalDateTime.class).toInstant(ZoneOffset.UTC));return (Map<String,Object>)row;
        }).list();}
    private static void same(long previous,long target){if(previous!=target)throw new ApiException(409,"PROJECT_SOURCE_ALREADY_BOUND","来源编码已关联另一稳定对象；禁止改绑导致历史数据断链");}
    private static String code(String value){return required(value,60,"来源编码");}
    private static String required(String value,int max,String label){if(value==null||value.isBlank()||value.length()>max)throw bad(label+"须填写1至"+max+"字");return value.strip();}
    private static ApiException bad(String message){return new ApiException(422,"PROJECT_LINK_INVALID",message);}
    private static void transaction(){if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Source code binding requires caller transaction");}
    private record SourceRow(long id,String state,Map<String,String> data) {}
}
