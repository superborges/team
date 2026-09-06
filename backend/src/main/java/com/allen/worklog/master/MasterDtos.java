package com.allen.worklog.master;

import java.time.LocalDate;
import java.util.List;

public final class MasterDtos {
    private MasterDtos() {}
    public record UserInput(String employeeNo, String wecomUserid, String name, String departmentId,
                            String level, String status, LocalDate effectiveFrom, List<String> roles) {}
    public record UserView(String id, String employeeNo, String wecomUserid, String name, String departmentId,
                           String departmentName, String level, String status, List<String> roles, LocalDate effectiveFrom,int pendingApprovalCount) {}
    public record DepartmentInput(String code, String name, String parentId, String approverUserId,
                                  String supervisorUserId, String status) {}
    public record DepartmentView(String id, String code, String name, String parentId, String approverUserId,
                                 String supervisorUserId, String status) {}
    public record WorkItemInput(String code, String name, String type, String ownerDepartmentId,
                                String approverUserId, String source, String status, LocalDate effectiveFrom) {}
    public record WorkItemView(String id, String code, String name, String type, String ownerDepartmentId,
                               String approverUserId, String approverName, String source, String status, LocalDate effectiveFrom) {}
    public record RateInput(String dimension, String dailyRate, LocalDate effectiveFrom, LocalDate effectiveTo) {}
    public record RateView(String id, String dimension, String dailyRate, LocalDate effectiveFrom, LocalDate effectiveTo) {}
    public record CalendarInput(boolean isWorkday, int baseMinutes, String note) {}
    public record CalendarView(LocalDate date, boolean isWorkday, int baseMinutes, String note) {}
    public record CatalogItem(String id, String code, String name, String type, String ownerDepartmentId, String approverName) {}
    public record CatalogDepartment(String id, String code, String name) {}
    public record Catalog(List<CatalogItem> workItems, List<CatalogDepartment> departments) {}
}
