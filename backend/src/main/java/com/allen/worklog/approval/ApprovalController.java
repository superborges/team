package com.allen.worklog.approval;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import static com.allen.worklog.approval.ApprovalModels.*;

@RestController
@RequestMapping("/api/v1")
public class ApprovalController {
    private final ApprovalService approvals;private final CorrectionService corrections;private final DisputeService disputes;
    public ApprovalController(ApprovalService approvals,CorrectionService corrections,DisputeService disputes){this.approvals=approvals;this.corrections=corrections;this.disputes=disputes;}
    @PostMapping("/weeks/{monday}/preview") public WeekPreview preview(@PathVariable LocalDate monday){return approvals.preview(monday);}
    @PostMapping("/weeks/{monday}/submit") public BatchResult submit(@PathVariable LocalDate monday,@RequestHeader("Idempotency-Key") String key){return approvals.submit(monday,key);}
    @PostMapping("/onsite-days/{id}/submit") public BatchResult submitOnsite(@PathVariable long id,@RequestHeader("Idempotency-Key") String key){return approvals.submitOnsite(id,key);}
    @GetMapping("/approval-packages") public List<Map<String,Object>> list(@RequestParam(defaultValue="pending") String view,@RequestParam(required=false) LocalDate weekStart){return approvals.list(view,weekStart);}
    @GetMapping("/approval-packages/{id}") public Map<String,Object> detail(@PathVariable long id){return enrich(id,approvals.detail(id));}
    @PostMapping("/approval-packages/{id}/decide") public BatchResult decide(@PathVariable long id,@RequestBody Decide input,@RequestHeader("Idempotency-Key") String key){return approvals.decide(id,input,key);}
    @PostMapping("/approval-packages/{id}/transfer") public Map<String,Object> transfer(@PathVariable long id,@RequestBody Transfer input,@RequestHeader("Idempotency-Key") String key){return enrich(id,approvals.transfer(id,input,key));}
    @GetMapping("/approval-designations") public List<Map<String,Object>> designations(){return approvals.designations();}
    @PostMapping("/approval-designations") public Map<String,Object> saveDesignation(@RequestBody DesignationInput input,@RequestHeader("Idempotency-Key") String key){return approvals.saveDesignation(input,key);}
    @GetMapping("/correction-requests") public List<Map<String,Object>> corrections(@RequestParam(defaultValue="all") String view){return corrections.list(view);}
    @PostMapping("/correction-requests") public Map<String,Object> correction(@RequestBody CorrectionInput input,@RequestHeader("Idempotency-Key") String key){return corrections.create(input,key);}
    @PostMapping("/correction-requests/{id}/decide") public Map<String,Object> correctionDecision(@PathVariable long id,@RequestBody CorrectionDecision input,@RequestHeader("Idempotency-Key") String key){return corrections.decide(id,input,key);}
    @PostMapping("/correction-requests/{id}/transfer") public Map<String,Object> correctionTransfer(@PathVariable long id,@RequestBody Transfer input,@RequestHeader("Idempotency-Key") String key){return corrections.transfer(id,input,key);}
    @GetMapping("/disputes") public List<Map<String,Object>> disputes(){return disputes.list();}
    @PostMapping("/disputes") public Map<String,Object> dispute(@RequestBody DisputeInput input,@RequestHeader("Idempotency-Key") String key){return disputes.create(input,key);}
    @PostMapping("/disputes/{id}/statements") public Map<String,Object> statement(@PathVariable long id,@RequestBody Statement input,@RequestHeader("Idempotency-Key") String key){return disputes.statement(id,input,key);}
    @PostMapping("/disputes/{id}/resolve") public Map<String,Object> resolve(@PathVariable long id,@RequestBody ResolveDispute input,@RequestHeader("Idempotency-Key") String key){return disputes.resolve(id,input,key);}
    private Map<String,Object> enrich(long id,Map<String,Object> detail){detail.put("correctionRequests",corrections.forPackage(id));detail.put("disputes",disputes.forPackage(id));return detail;}
}
