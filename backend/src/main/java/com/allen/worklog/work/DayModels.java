package com.allen.worklog.work;

import java.util.List;

public final class DayModels {
    private DayModels() {}
    public record SaveDay(Integer expectedVersion,List<EntryInput> entries,OnsiteInput onsite) {}
    public record EntryInput(String id,String workItemId,String kind,String hours,String content,String redReason,String correctionRequestId) {
        public EntryInput(String id,String workItemId,String kind,String hours,String content,String redReason) {this(id,workItemId,kind,hours,content,redReason,null);}
    }
    public record OnsiteInput(String id,String workItemId,String reason,String correctionRequestId) {
        public OnsiteInput(String id,String workItemId,String reason) {this(id,workItemId,reason,null);}
    }
    public record Entry(String id,String revisionId,String workItemId,String workItemName,String kind,
                        String hours,int minutes,String content,String redReason,String state,boolean editable,
                        String action,String correctionRequestId,boolean disputeOpen,String approvalItemId,boolean canRequestCorrection) {
        public Entry(String id,String revisionId,String workItemId,String workItemName,String kind,String hours,int minutes,String content,String redReason,String state,boolean editable) {
            this(id,revisionId,workItemId,workItemName,kind,hours,minutes,content,redReason,state,editable,"REPORT",null,false,null,false);
        }
    }
    public record Onsite(String id,String revisionId,String workItemId,String workItemName,String reason,String state,boolean editable,
                        String action,String correctionRequestId,boolean disputeOpen,String approvalItemId,boolean canRequestCorrection) {
        public Onsite(String id,String revisionId,String workItemId,String workItemName,String reason,String state,boolean editable) {
            this(id,revisionId,workItemId,workItemName,reason,state,editable,"REPORT",null,false,null,false);
        }
    }
    public record Validation(boolean ready,List<String> errors,List<String> warnings) {}
    public record Day(String date,int version,int baseMinutes,int leaveMinutes,int requiredMinutes,boolean isWorkday,
                      boolean enrolled,boolean editable,String periodStatus,List<Entry> entries,Onsite onsite,
                      int totalMinutes,int actualMinutes,int idleMinutes,Validation validation,
                      int stepMinutes,int redFlagMinutes,int dayLimitMinutes) {}
    public record Week(String weekStart,List<Day> days) {}
}
