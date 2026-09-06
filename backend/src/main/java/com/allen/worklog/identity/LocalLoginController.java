package com.allen.worklog.identity;

import com.allen.worklog.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.*;

/** Explicit local profile only. This is not a production SSO implementation. */
@Profile("local & !prod & !production & !worker")
@ConditionalOnProperty(name="app.local-login-enabled",havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/api/v1/auth")
public class LocalLoginController {
    private final JdbcClient jdbc;
    private final CurrentUser current;
    private final HttpSessionSecurityContextRepository repository;
    public LocalLoginController(JdbcClient jdbc,CurrentUser current,HttpSessionSecurityContextRepository repository) {
        this.jdbc=jdbc;this.current=current;this.repository=repository;
    }
    @PostMapping("/local-login")
    Map<String,Object> login(@RequestBody Login input,HttpServletRequest request,HttpServletResponse response) {
        if(input.employeeNo()==null || !input.employeeNo().matches("(?:[0-9]{5}|[A-Za-z]{2}[0-9]{5})"))
            throw new ApiException(400,"INVALID_EMPLOYEE_NO","工号格式应为五位数字或两位字母加五位数字");
        long id=jdbc.sql("SELECT id FROM app_user WHERE employee_no=:no AND status='ACTIVE'")
            .param("no",input.employeeNo()).query(Long.class).optional()
            .orElseThrow(()->new ApiException(401,"UNKNOWN_LOCAL_USER","本地测试账号不存在或已停用"));
        var user=current.load(id);
        var auth=new UsernamePasswordAuthenticationToken(Long.toString(id),null,List.of());
        new ChangeSessionIdAuthenticationStrategy().onAuthentication(auth,request,response);
        new HttpSessionCsrfTokenRepository().saveToken(null,request,response);
        var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(auth);
        SecurityContextHolder.setContext(context); repository.saveContext(context,request,response);
        return user.view();
    }
    record Login(String employeeNo) {}
}
