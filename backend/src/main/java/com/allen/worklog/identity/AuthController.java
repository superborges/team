package com.allen.worklog.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class AuthController {
    private final Environment env;
    private final CurrentUser current;
    public AuthController(Environment env,CurrentUser current) { this.env=env;this.current=current; }
    @GetMapping("/auth/status")
    Map<String,Object> status() {
        boolean local=env.acceptsProfiles(Profiles.of("local")) && env.getProperty("app.local-login-enabled",Boolean.class,false);
        return Map.of("mode",local?"local":"sso","localLoginEnabled",local,"ssoConfigured",false);
    }
    @GetMapping("/auth/csrf")
    Map<String,String> csrf(CsrfToken token) { return Map.of("headerName",token.getHeaderName(),"token",token.getToken()); }
    @GetMapping("/me")
    Map<String,Object> me() { return current.require().view(); }
    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(HttpServletRequest request,HttpServletResponse response) {
        var session=request.getSession(false);
        boolean demoAccess=session!=null && Boolean.TRUE.equals(session.getAttribute(DemoAccessController.SESSION_ATTRIBUTE));
        if(session!=null)session.invalidate();
        SecurityContextHolder.clearContext();
        if(demoAccess)request.getSession().setAttribute(DemoAccessController.SESSION_ATTRIBUTE,Boolean.TRUE);
    }
}
