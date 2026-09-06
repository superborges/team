package com.allen.worklog.common;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class AuditService {
    private final JdbcClient jdbc;
    public AuditService(JdbcClient jdbc) { this.jdbc = jdbc; }
    public void record(long actorId, String action, String objectType, String objectId,
                       String beforeJson, String afterJson, String reason) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Business audit must share the business transaction");
        jdbc.sql("""
            INSERT INTO audit_event(actor_id,actor_type,job_id,target_user_id,action,object_type,object_id,before_data,after_data,reason)
            VALUES(:actor,:actorType,:job,:target,:action,:type,:id,:before,:after,:reason)
            """).param("actor",com.allen.worklog.operations.SystemExecution.jobId()==null?actorId:null)
            .param("actorType",com.allen.worklog.operations.SystemExecution.jobId()==null?"USER":"SYSTEM")
            .param("job",com.allen.worklog.operations.SystemExecution.jobId())
            .param("target",com.allen.worklog.operations.SystemExecution.jobId()==null?null:actorId)
            .param("action",action).param("type",objectType).param("id",objectId)
            .param("before",beforeJson).param("after",afterJson).param("reason",reason).update();
    }
}
