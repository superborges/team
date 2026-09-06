package com.allen.worklog.reporting;
import java.time.LocalDate;
import java.util.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import static com.allen.worklog.reporting.ReportModels.*;
@RestController
@RequestMapping("/api/v1")
public class ReportingController {
    private final ReportService reports;private final ExportService exports;
    public ReportingController(ReportService reports,ExportService exports) {this.reports=reports;this.exports=exports;}
    @GetMapping("/reports/capabilities") Map<String,Object> capabilities() {return reports.capabilities();}
    @GetMapping("/reports/{kind}") Report report(@PathVariable String kind,@RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,
      @RequestParam(defaultValue="CURRENT") String mode,@RequestParam(required=false) List<String> versionIds,@RequestParam(required=false) String userId,@RequestParam(required=false) String workItemId,@RequestParam(required=false) String departmentId,@RequestParam(required=false) String level,@RequestParam(defaultValue="WEEK") String grain) {
        return reports.read(new Query(kind,from,to,mode,versionIds,userId,workItemId,departmentId,level,grain));
    }
    @PostMapping("/exports") Map<String,Object> create(@RequestBody Query input) {return exports.create(input);}
    @GetMapping("/exports") List<Map<String,Object>> list() {return exports.list();}
    @GetMapping("/exports/{id}") Map<String,Object> get(@PathVariable long id) {return exports.get(id);}
    @GetMapping("/exports/{id}/download") ResponseEntity<FileSystemResource> download(@PathVariable long id) {
        var path=exports.download(id);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"worklog-export-"+id+".xlsx\"").contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).body(new FileSystemResource(path));
    }
}
