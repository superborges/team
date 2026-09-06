package com.allen.worklog.approval;

import java.time.LocalDate;
import java.util.List;

public final class ApprovalModels {
    private ApprovalModels() {}
    public record ResultItem(String id,String kind,LocalDate date,String packageId,String code,String message) {}
    public record BatchResult(List<ResultItem> succeeded,List<ResultItem> failed,List<ResultItem> unchanged) {}
    public record PreviewWork(String revisionId,String recordId,String workItemId,String workItemName,String kind,String hours,
                              String approverId,String approverName,String routeReason) {}
    public record PreviewOnsite(String revisionId,String recordId,String workItemId,String workItemName,
                                String approverId,String approverName,String routeReason) {}
    public record PreviewDay(LocalDate date,boolean workReady,boolean onsiteReady,List<String> errors,List<String> warnings,
                             List<PreviewWork> workItems,PreviewOnsite onsite) {}
    public record WeekPreview(LocalDate weekStart,List<PreviewDay> days) {}
    public record Decide(Integer expectedVersion,List<String> itemIds,String decision,String reason) {}
    public record Transfer(Integer expectedVersion,String newApproverId,String reason,String basis) {}
    public record CorrectionInput(String kind,String recordId,String action,String reason) {}
    public record CorrectionDecision(String decision,String reason) {}
    public record DesignationInput(String userId,String workItemId,String approverId,String reason,String basis) {}
    public record DisputeInput(String approvalItemId,String statement) {}
    public record Statement(String statement) {}
    public record ResolveDispute(String action,String outcome,String reason) {}
}
