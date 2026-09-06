package com.allen.worklog.identity;

import com.allen.worklog.common.ApiException;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class CurrentUser {
    private final JdbcClient jdbc;
    public CurrentUser(JdbcClient jdbc) { this.jdbc = jdbc; }
    public User require() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !a.isAuthenticated() || "anonymousUser".equals(a.getName()))
            throw new ApiException(401,"UNAUTHENTICATED","请先登录");
        try { return load(Long.parseLong(a.getName())); }
        catch (NumberFormatException e) { throw new ApiException(401,"UNAUTHENTICATED","登录状态无效，请重新登录"); }
    }
    public User requireAdmin() {
        User user = require();
        if (!user.canManage()) throw new ApiException(403,"FORBIDDEN","此操作需要系统管理员权限");
        return user;
    }
    public User load(long id) {
        var row = jdbc.sql("""
            SELECT u.id,u.employee_no,u.name,u.department_id,d.name department_name
            FROM app_user u LEFT JOIN department d ON d.id=u.department_id
            WHERE u.id=:id AND u.status='ACTIVE'
            """).param("id",id).query((rs,n)->new User(rs.getLong("id"),rs.getString("employee_no"),
                rs.getString("name"),rs.getObject("department_id",Long.class),rs.getString("department_name"),List.of(),false))
            .optional().orElseThrow(()->new ApiException(401,"ACCOUNT_DISABLED","账号不存在或已停用，请联系管理员"));
        List<String> roles = new java.util.ArrayList<>(jdbc.sql("""
            SELECT DISTINCT role_code FROM role_grant WHERE user_id=:id AND revoked_at IS NULL
            AND valid_from<=DATE(CONVERT_TZ(UTC_TIMESTAMP(),'+00:00','+08:00'))
            AND (valid_to IS NULL OR valid_to>DATE(CONVERT_TZ(UTC_TIMESTAMP(),'+00:00','+08:00')))
            """).param("id",id).query(String.class).list());
        boolean manager=jdbc.sql("""
            SELECT COUNT(*) FROM department_manager WHERE user_id=? AND revoked_at IS NULL
             AND valid_from<=DATE(CONVERT_TZ(UTC_TIMESTAMP(),'+00:00','+08:00'))
             AND (valid_to IS NULL OR valid_to>DATE(CONVERT_TZ(UTC_TIMESTAMP(),'+00:00','+08:00')))
            """).param(id).query(Integer.class).single()>0;
        if(manager&&!roles.contains("DEPARTMENT_MANAGER"))roles.add("DEPARTMENT_MANAGER");
        return new User(row.id(),row.employeeNo(),row.name(),row.departmentId(),row.departmentName(),roles,roles.contains("ADMIN"));
    }
    public record User(long id, String employeeNo, String name, Long departmentId, String departmentName,
                       List<String> roles, boolean canManage) {
        public Map<String,Object> view() {
            Map<String,Object> result = new LinkedHashMap<>();
            result.put("id",Long.toString(id)); result.put("employeeNo",employeeNo); result.put("name",name);
            result.put("departmentId",departmentId==null?null:departmentId.toString()); result.put("departmentName",departmentName);
            result.put("roles",roles); result.put("canManage",canManage); return result;
        }
    }
}
