package com.allen.worklog.identity;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

@Configuration
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {
    @Bean
    org.springframework.security.core.userdetails.UserDetailsService disabledPasswordLogin() {
        return username->{ throw new org.springframework.security.core.userdetails.UsernameNotFoundException("Use the configured identity provider"); };
    }
    @Bean
    HttpSessionSecurityContextRepository securityContextRepository() { return new HttpSessionSecurityContextRepository(); }
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, HttpSessionSecurityContextRepository repository) throws Exception {
        http.authorizeHttpRequests(auth->auth
            .requestMatchers("/api/v1/auth/status","/api/v1/auth/csrf","/api/v1/auth/local-login","/actuator/health",
                "/demo-access/login","/demo-access/check").permitAll()
            .requestMatchers("/api/**").authenticated().anyRequest().denyAll())
            .csrf(csrf->csrf.csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .securityContext(ctx->ctx.securityContextRepository(repository))
            .requestCache(cache->cache.disable()).formLogin(form->form.disable()).httpBasic(basic->basic.disable())
            .logout(logout->logout.disable())
            .exceptionHandling(errors->errors
                .authenticationEntryPoint((req,res,e)->{
                    res.setStatus(401);res.setContentType("application/json;charset=UTF-8");
                    res.getWriter().write("{\"code\":\"UNAUTHENTICATED\",\"message\":\"请先登录\"}");
                })
                .accessDeniedHandler((req,res,e)->{
                    res.setStatus(403);res.setContentType("application/json;charset=UTF-8");
                    res.getWriter().write("{\"code\":\"FORBIDDEN\",\"message\":\"无权操作或页面验证已失效，请刷新后重试\"}");
                }));
        return http.build();
    }
}
