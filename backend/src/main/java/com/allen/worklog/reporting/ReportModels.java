package com.allen.worklog.reporting;
import java.time.*;
import java.util.*;
public final class ReportModels {
    private ReportModels() {}
    public record Query(String kind,LocalDate from,LocalDate to,String mode,List<String> versionIds,String userId,String workItemId,String departmentId,String level,String grain) {}
    public record Version(String id,String month,int versionNo,LocalDateTime cutoffAt,LocalDateTime publishedAt,String reason,String previousVersionId) {}
    public record Report(String kind,LocalDate from,LocalDate to,String mode,LocalDateTime generatedAt,List<Version> versions,List<String> missingMonths,Map<String,Object> summary,List<Map<String,Object>> rows,List<Map<String,Object>> details) {}
    public static Map<String,Object> map(Object... pairs) {var m=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);return m;}
}
