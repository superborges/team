package com.allen.worklog.reporting;

import com.allen.worklog.identity.CurrentUser;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class ReportingAccess {
    private final JdbcClient jdbc;private final CurrentUser current;
    public ReportingAccess(JdbcClient jdbc,CurrentUser current) { this.jdbc=jdbc;this.current=current; }
    public Scope current() { return forUser(current.require().id()); }
    public boolean canReadCost(long workItemId) { return current().projects().contains(workItemId); }
    public boolean canReadCost(long userId,long workItemId) { return forUser(userId).projects().contains(workItemId); }
    public Scope forUser(long userId) {
        var user=current.load(userId);
        List<Grant> grants=jdbc.sql("""
            SELECT role_code,scope_type,department_id FROM role_grant WHERE user_id=? AND revoked_at IS NULL
              AND valid_from<=DATE(CONVERT_TZ(UTC_TIMESTAMP(),'+00:00','+08:00'))
              AND (valid_to IS NULL OR valid_to>DATE(CONVERT_TZ(UTC_TIMESTAMP(),'+00:00','+08:00')))
            """).param(userId).query((rs,n)->new Grant(rs.getString("role_code"),rs.getString("scope_type"),rs.getObject("department_id",Long.class))).list();
        boolean admin=user.roles().contains("ADMIN");
        boolean all=admin||grants.stream().anyMatch(g->g.role().equals("LEADER")&&g.scope().equals("COMPANY"));
        Set<Long> departments=new HashSet<>();
        for(var grant:grants)if(Set.of("DEPARTMENT_MANAGER","LEADER").contains(grant.role())&&grant.department()!=null)departments.add(grant.department());
        if(user.roles().contains("DEPARTMENT_MANAGER"))departments.addAll(jdbc.sql("""
            SELECT department_id FROM department_manager WHERE user_id=? AND revoked_at IS NULL
              AND valid_from<=DATE(CONVERT_TZ(UTC_TIMESTAMP(),'+00:00','+08:00'))
              AND (valid_to IS NULL OR valid_to>DATE(CONVERT_TZ(UTC_TIMESTAMP(),'+00:00','+08:00')))
            """).param(userId).query(Long.class).list());
        var tree=jdbc.sql("SELECT id,parent_id FROM department").query((rs,n)->new Department(rs.getLong("id"),rs.getObject("parent_id",Long.class))).list();
        boolean changed;do { changed=false;for(var dept:tree)if(dept.parent()!=null&&departments.contains(dept.parent()))changed|=departments.add(dept.id()); } while(changed);
        Set<Long> users=new HashSet<>();users.add(userId);
        for(var member:jdbc.sql("SELECT id,department_id FROM app_user").query((rs,n)->new Member(rs.getLong("id"),rs.getLong("department_id"))).list())
            if(all||departments.contains(member.department()))users.add(member.id());
        Set<Long> projects=new HashSet<>(),unlockItems=new HashSet<>();
        for(var item:jdbc.sql("SELECT id,type,owner_department_id,default_approver_id FROM work_item")
            .query((rs,n)->new Item(rs.getLong("id"),rs.getString("type"),rs.getLong("owner_department_id"),rs.getObject("default_approver_id",Long.class))).list()) {
            if(item.type().equals("PROJECT")&&(all||departments.contains(item.department())||(user.roles().contains("PM")&&Long.valueOf(userId).equals(item.pm()))))projects.add(item.id());
            if(admin||departments.contains(item.department()))unlockItems.add(item.id());
        }
        return new Scope(userId,all,Set.copyOf(users),Set.copyOf(projects),Set.copyOf(departments),Set.copyOf(unlockItems),admin,admin||!departments.isEmpty(),admin);
    }
    public record Scope(long userId,boolean all,Set<Long> users,Set<Long> projects,Set<Long> departments,
                        Set<Long> unlockItems,boolean canClose,boolean canUnlock,boolean canPublish) {
        public boolean fullPerson(Long user) { return user!=null&&users.contains(user); }
        public boolean project(Long item) { return item!=null&&projects.contains(item); }
        public boolean detail(Long user,Long item) { return fullPerson(user)||project(item); }
        public Scope intersect(Scope older) {
            Set<Long> u=new HashSet<>(users);u.retainAll(older.users);
            Set<Long> p=new HashSet<>(projects);p.retainAll(older.projects);
            Set<Long> d=new HashSet<>(departments);d.retainAll(older.departments);
            return new Scope(userId,all&&older.all,Set.copyOf(u),Set.copyOf(p),Set.copyOf(d),Set.of(),false,false,false);
        }
    }
    private record Grant(String role,String scope,Long department) {}
    private record Department(long id,Long parent) {}
    private record Member(long id,long department) {}
    private record Item(long id,String type,long department,Long pm) {}
}
