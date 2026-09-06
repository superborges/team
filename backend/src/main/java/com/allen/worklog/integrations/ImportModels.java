package com.allen.worklog.integrations;

import com.allen.worklog.common.ApiException;
import java.util.List;
import java.util.Map;

public final class ImportModels {
    private ImportModels() {}
    public enum Dataset {
        PROJECT(List.of("code","name","type","departmentCode","approverEmployeeNo","effectiveFrom")),
        RATE(List.of("dimension","dailyRate","effectiveFrom","effectiveTo")),
        LEAVE(List.of("sourceKey","sourceVersion","employeeNo","date","leaveMinutes","startMinute","endMinute","status")),
        USER(List.of("employeeNo","wecomUserid","name","departmentCode","level","effectiveFrom")),
        DEPARTMENT(List.of("code","name","parentCode","approverEmployeeNo","supervisorEmployeeNo")),
        TRIP(List.of("sourceKey","sourceVersion","employeeNo","date","projectCode","status"));
        public final List<String> columns;
        Dataset(List<String> columns) { this.columns=columns; }
        public static Dataset parse(String value) {
            try { return valueOf(value); }
            catch (IllegalArgumentException | NullPointerException e) { throw new ApiException(400,"INVALID_DATASET","请选择项目、费率、请假、人员、部门或出差固定模板"); }
        }
    }
    public record RowView(int rowNumber,Map<String,String> data,boolean valid,String error,String state) {}
    public record BatchSummary(String id,String dataset,String state,String fileName,String createdAt,
                               int validCount,int errorCount,int appliedCount) {}
    public record Batch(String id,String dataset,String state,String fileName,String createdAt,
                        int validCount,int errorCount,int appliedCount,List<RowView> rows) {}
    record ParsedRow(int rowNumber,Map<String,String> data,String parseError) {}
}
