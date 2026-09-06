package com.allen.worklog.reporting;

import com.allen.worklog.common.*;
import com.allen.worklog.operations.JobService;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import static com.allen.worklog.reporting.ReportModels.*;

@Service
public class ExportService {
    public record Requirement(String userId,String workItemId,boolean fullPerson,boolean cost) {}
    private record Request(long id,long user,Query query,ReportingAccess.Scope scope,String state,String file,String token) {}
    private final JdbcClient jdbc;private final ObjectMapper json;private final ReportingAccess access;private final ReportService reports;private final JobService jobs;private final TransactionTemplate tx;private final AuditService audit;private final Path root;
    public ExportService(JdbcClient jdbc,ObjectMapper json,ReportingAccess access,ReportService reports,JobService jobs,TransactionTemplate tx,AuditService audit,@Value("${app.export-dir:.local/exports}") String dir) {this.jdbc=jdbc;this.json=json;this.access=access;this.reports=reports;this.jobs=jobs;this.tx=tx;this.audit=audit;this.root=Path.of(dir).toAbsolutePath().normalize();}
    @Transactional public Map<String,Object> create(Query input) {
        var scope=access.current();Query q=reports.pin(input);
        if(q.kind().equals("project-costs")&&(scope.projects().isEmpty()||(q.workItemId()!=null&&!scope.project(Long.valueOf(q.workItemId())))))throw forbidden();
        if(q.userId()!=null&&Set.of("workload","compliance").contains(q.kind())&&!scope.fullPerson(Long.valueOf(q.userId())))throw forbidden();
        jdbc.sql("INSERT INTO export_request(requested_by,report_kind,request_json,requested_scope) VALUES(?,?,?,?)").params(scope.userId(),q.kind(),json.writeValueAsString(q),json.writeValueAsString(scope)).update();long id=jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        jobs.enqueue("EXPORT","export:"+id,Map.of("exportId",id),scope.userId(),LocalDateTime.now(ZoneOffset.UTC));
        audit.record(scope.userId(),"EXPORT_REQUESTED","EXPORT",Long.toString(id),null,json.writeValueAsString(q),"按当前授权范围申请导出");return map("id",Long.toString(id),"state","QUEUED");
    }
    public List<Map<String,Object>> list() {var scope=access.current();return jdbc.sql("SELECT CAST(id AS CHAR) id,state,report_kind kind,created_at createdAt,finished_at finishedAt,error_message error FROM export_request WHERE requested_by=? ORDER BY export_request.id DESC LIMIT 100").param(scope.userId()).query().listOfRows().stream().map(this::withUrl).toList();}
    public Map<String,Object> get(long id) {long actor=access.current().userId();return withUrl(jdbc.sql("SELECT CAST(id AS CHAR) id,state,report_kind kind,created_at createdAt,finished_at finishedAt,error_message error FROM export_request WHERE id=? AND requested_by=?").params(id,actor).query().listOfRows().stream().findFirst().orElseThrow(()->new ApiException(404,"EXPORT_NOT_FOUND","导出任务不存在")));}
    private Map<String,Object> withUrl(Map<String,Object> source) {var m=new LinkedHashMap<>(source);m.put("downloadUrl","SUCCEEDED".equals(source.get("state"))?"/api/v1/exports/"+source.get("id")+"/download":null);return m;}
    public void generate(long id) {
        String token=UUID.randomUUID().toString();Request request=tx.execute(status->{
            Request r=request(id,true);if(r.state().equals("SUCCEEDED"))return null;
            jdbc.sql("UPDATE export_request SET state='RUNNING',generation_token=?,error_message=NULL WHERE id=?").params(token,id).update();return r;
        });
        if(request==null)return;Path temp=null,target=null;
        try {
            ReportingAccess.Scope scope=access.forUser(request.user()).intersect(request.scope());
            Report report=reports.readPinned(request.query(),scope);
            var requirements=requirements(report);
            Files.createDirectories(root);
            if(Files.getFileStore(root).supportsFileAttributeView("posix"))Files.setPosixFilePermissions(root,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            String name=UUID.randomUUID()+".xlsx";target=root.resolve(name);temp=Files.createTempFile(root,"export-",".tmp");
            try(var workbook=new SXSSFWorkbook(100);var output=Files.newOutputStream(temp)) {
                var info=workbook.createSheet("口径与版本");int n=0;
                for(var pair:map("类型",report.kind(),"起日",report.from(),"止日",report.to(),"模式",report.mode(),"数据生成UTC",report.generatedAt(),"正式版本",json.writeValueAsString(report.versions()),"缺失月份",String.join(",",report.missingMonths()),"汇总",json.writeValueAsString(report.summary()),"口径","仅当前有效且审批通过的版本计确认数；金额标准日480分钟；缺失不等于空闲；请保留版本说明").entrySet()) {var row=info.createRow(n++);row.createCell(0).setCellValue(pair.getKey());row.createCell(1).setCellValue(String.valueOf(pair.getValue()));}
                info.setColumnWidth(0,22*256);info.setColumnWidth(1,100*256);sheet(workbook,"数据",report.rows());if(!report.details().isEmpty())sheet(workbook,"待处理与明细",report.details());workbook.write(output);
            }
            Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE);temp=null;
            Path written=target;Integer updated=tx.execute(status->{
                // A generation lease may have been replaced; only the winner may publish this file and its permission manifest.
                int count=jdbc.sql("UPDATE export_request SET state='SUCCEEDED',private_file=?,actual_scope=?,finished_at=UTC_TIMESTAMP(6),expires_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 7 DAY) WHERE id=? AND generation_token=?")
                    .params(written.getFileName().toString(),json.writeValueAsString(requirements),id,token).update();
                if(count==1)audit.record(request.user(),"EXPORT_GENERATED","EXPORT",Long.toString(id),null,null,"生成私有导出文件并固化权限清单");return count;
            });
            if(updated==0)Files.deleteIfExists(target);
        } catch(Exception e) {
            String message=e instanceof ApiException?e.getMessage():"导出生成失败，请重试任务";
            tx.executeWithoutResult(status->jdbc.sql("UPDATE export_request SET state='FAILED',error_message=?,finished_at=UTC_TIMESTAMP(6) WHERE id=? AND generation_token=?").params(message,id,token).update());
            if(target!=null)try{Files.deleteIfExists(target);}catch(IOException ignored){}
            if(e instanceof RuntimeException runtime)throw runtime;throw new IllegalStateException(message,e);
        } finally {if(temp!=null)try{Files.deleteIfExists(temp);}catch(IOException ignored){}}
    }
    @Transactional public Path download(long id) {
        var scope=access.current();Request request=request(id,true);
        if(request.user()!=scope.userId())throw new ApiException(404,"EXPORT_NOT_FOUND","导出任务不存在");
        if(!request.state().equals("SUCCEEDED"))throw new ApiException(409,"EXPORT_NOT_READY","导出尚未生成成功");
        var meta=jdbc.sql("SELECT actual_scope,expires_at<UTC_TIMESTAMP(6) expired FROM export_request WHERE id=?").param(id).query().singleRow();
        if(ReportService.bool(meta,"expired"))throw new ApiException(410,"EXPORT_EXPIRED","文件已过期，请重新导出");
        List<Requirement> requirements=json.readValue(meta.get("actual_scope").toString(),new TypeReference<List<Requirement>>(){});
        for(var row:requirements) {
            Long user=row.userId()==null?null:Long.valueOf(row.userId()),item=row.workItemId()==null?null:Long.valueOf(row.workItemId());
            if(row.fullPerson()?!scope.fullPerson(user):!scope.detail(user,item))throw forbidden();
            if(row.cost()&&!scope.project(item))throw forbidden();
        }
        Path file=safe(request.file());if(!Files.isRegularFile(file))throw new ApiException(410,"EXPORT_EXPIRED","文件已清理，请重新导出");
        audit.record(scope.userId(),"EXPORT_DOWNLOADED","EXPORT",Long.toString(id),null,null,"下载前已复查当前权限");return file;
    }
    /** Bounded worker cleanup. Database history remains; only expired private artifacts are removed. */
    @Transactional public int cleanupExpired() {
        int count=0;
        for(var row:jdbc.sql("SELECT id,private_file FROM export_request WHERE expires_at<UTC_TIMESTAMP(6) AND private_file IS NOT NULL ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED").query().listOfRows()) {
            try {Files.deleteIfExists(safe(row.get("private_file").toString()));}
            catch(IOException e) {throw new IllegalStateException("无法清理过期导出文件",e);}
            jdbc.sql("UPDATE export_request SET private_file=NULL WHERE id=?").param(row.get("id")).update();count++;
        }
        if(Files.isDirectory(root))try(var files=Files.list(root)) {
            for(Path file:files.filter(p->p.getFileName().toString().matches("export-[A-Za-z0-9-]+\\.tmp")).limit(100).toList())
                if(Files.getLastModifiedTime(file).toInstant().isBefore(Instant.now().minus(Duration.ofDays(7))))Files.deleteIfExists(file);
        } catch(IOException e) {throw new IllegalStateException("无法清理导出临时文件",e);}
        return count;
    }
    private Request request(long id,boolean lock) {return jdbc.sql("SELECT * FROM export_request WHERE id=?"+(lock?" FOR UPDATE":"")).param(id).query((rs,n)->new Request(id,rs.getLong("requested_by"),json.readValue(rs.getString("request_json"),Query.class),json.readValue(rs.getString("requested_scope"),ReportingAccess.Scope.class),rs.getString("state"),rs.getString("private_file"),rs.getString("generation_token"))).optional().orElseThrow(()->new ApiException(404,"EXPORT_NOT_FOUND","导出任务不存在"));}
    private Path safe(String name) {if(name==null||!name.matches("[0-9a-f-]{36}\\.xlsx"))throw new ApiException(410,"EXPORT_FILE","导出文件不可用");return root.resolve(name);}
    private static List<Requirement> requirements(Report report) {
        Set<Requirement> result=new LinkedHashSet<>();boolean full=Set.of("workload","compliance").contains(report.kind());
        for(var row:report.rows())result.add(new Requirement(value(row.get("userId")),value(row.get("workItemId")),full,row.containsKey("costAmount")||row.containsKey("onsiteCost")||row.containsKey("totalCost")));
        for(var row:report.details())result.add(new Requirement(value(row.get("userId")),value(row.get("workItemId")),"MISSING".equals(row.get("kind")),row.containsKey("costAmount")));
        return List.copyOf(result);
    }
    private static String value(Object o) {return o==null?null:o.toString();}
    private static void sheet(SXSSFWorkbook workbook,String name,List<Map<String,Object>> data) {
        var sheet=workbook.createSheet(name);var columns=new LinkedHashSet<String>();data.forEach(r->columns.addAll(r.keySet()));var names=List.copyOf(columns);
        var header=sheet.createRow(0);for(int i=0;i<names.size();i++)header.createCell(i).setCellValue(names.get(i));
        int n=1;for(var values:data) {if(n>=1_048_576)throw new ApiException(422,"EXPORT_ROWS","导出行数过多，请缩小日期范围");var row=sheet.createRow(n++);for(int i=0;i<names.size();i++){Object value=values.get(names.get(i));if(value!=null)row.createCell(i).setCellValue(value.toString());}}
        sheet.createFreezePane(0,1);for(int i=0;i<names.size();i++)sheet.setColumnWidth(i,24*256);
    }
    private static ApiException forbidden() {return new ApiException(403,"EXPORT_SCOPE","当前权限不足以申请或下载该导出，请重新按可见范围生成");}
}
