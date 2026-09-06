package com.allen.worklog.operations;

import com.allen.worklog.identity.CurrentUser;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/operations")
public class OperationsController {
    private final ConfigService configs;private final JobService jobs;private final NotificationService notices;private final JdbcClient jdbc;private final CurrentUser current;
    public OperationsController(ConfigService configs,JobService jobs,NotificationService notices,JdbcClient jdbc,CurrentUser current) {this.configs=configs;this.jobs=jobs;this.notices=notices;this.jdbc=jdbc;this.current=current;}
    @GetMapping("/config") Map<String,Object> config() {current.requireAdmin();return Map.of("current",configs.current(),"latestVersion",configs.latestVersion(),"history",configs.history());}
    @PostMapping("/config/preview") Map<String,Object> preview(@RequestBody ConfigService.Update input) {return configs.preview(input);}
    @PutMapping("/config") ConfigService.Snapshot save(@RequestBody ConfigService.Update input) {return configs.save(input);}
    @GetMapping("/jobs") List<Map<String,Object>> jobs() {return jobs.list();}
    @PostMapping("/jobs/{id}/retry") void retry(@PathVariable long id) {jobs.retry(id);}
    @GetMapping("/health") Map<String,Object> health() {return jobs.health();}
    @GetMapping("/notifications") List<Map<String,Object>> notices() {return notices.all();}
    @GetMapping("/my-notifications") List<Map<String,Object>> mine() {return notices.mine();}
    @PostMapping("/notifications/{id}/retry") void retryNotice(@PathVariable long id) {notices.retry(id);}
    @GetMapping("/audit") List<Map<String,Object>> audit(@RequestParam(required=false) String objectType,@RequestParam(required=false) String objectId) {
        current.requireAdmin();return jdbc.sql("""
            SELECT CAST(a.id AS CHAR) id,a.actor_type actorType,u.name actorName,CAST(a.job_id AS CHAR) jobId,
             a.action,a.object_type objectType,a.object_id objectId,a.before_data beforeData,a.after_data afterData,a.reason,a.created_at createdAt
            FROM audit_event a LEFT JOIN app_user u ON u.id=a.actor_id
            WHERE (? IS NULL OR a.object_type=?) AND (? IS NULL OR a.object_id=?) ORDER BY a.id DESC LIMIT 100
            """).params(objectType,objectType,objectId,objectId).query().listOfRows();
    }
}
