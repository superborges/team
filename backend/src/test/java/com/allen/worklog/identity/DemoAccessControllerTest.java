package com.allen.worklog.identity;

import jakarta.servlet.http.Cookie;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import static org.junit.jupiter.api.Assertions.*;

class DemoAccessControllerTest {
    private static final String PASSWORD="demo-test-password";
    private final DemoAccessController controller=new DemoAccessController("preview",PASSWORD);

    @AfterEach void clearSecurityContext() { SecurityContextHolder.clearContext(); }

    @Test void validAccessRotatesSessionAndRedirectsToTheChosenApp() {
        var request=new MockHttpServletRequest();
        String oldId=request.getSession().getId();
        var result=controller.login("preview",PASSWORD,"h5",request);
        assertEquals(303,result.getStatusCode().value());
        assertEquals("/h5/",result.getHeaders().getLocation().toString());
        assertNotEquals(oldId,request.getSession().getId());
        assertEquals(Boolean.TRUE,request.getSession().getAttribute(DemoAccessController.SESSION_ATTRIBUTE));
        assertEquals(204,controller.check(request).getStatusCode().value());
    }

    @Test void wrongCredentialsCannotGrantAccessAndRedirectTargetsAreWhitelisted() {
        for(String[] credentials:List.of(new String[]{"preview","wrong"},new String[]{"other",PASSWORD},new String[]{"",""})) {
            var request=new MockHttpServletRequest();
            var result=controller.login(credentials[0],credentials[1],"https://example.com/steal",request);
            assertEquals(303,result.getStatusCode().value());
            assertEquals("/demo-access/login?target=admin&error=1",result.getHeaders().getLocation().toString());
            assertNull(request.getSession(false));
        }
        for(String target:List.of("admin","//example.com","/h5/","h5?next=https://example.com","")) {
            var result=controller.login("preview",PASSWORD,target,new MockHttpServletRequest());
            assertEquals("/admin/",result.getHeaders().getLocation().toString());
        }
    }

    @Test void accessCheckNeverCreatesSessionsOrTrustsClientCookiesAndStringMarkers() {
        var request=new MockHttpServletRequest();
        request.setCookies(new Cookie("WORKLOG_DEMO_SESSION","true"));
        assertEquals(401,controller.check(request).getStatusCode().value());
        assertNull(request.getSession(false));
        request.getSession().setAttribute(DemoAccessController.SESSION_ATTRIBUTE,"true");
        assertEquals(401,controller.check(request).getStatusCode().value());
    }

    @Test void loginPageEscapesCsrfAndDoesNotReflectUntrustedParameters() throws Exception {
        var token=new DefaultCsrfToken("X-CSRF-TOKEN","_csrf","\"<script>");
        String page=controller.page("https://example.com/steal","1",token).getBody();
        assertNotNull(page);
        assertTrue(page.contains("&quot;&lt;script&gt;"));
        assertTrue(page.contains("访问账号或口令不正确，请重试。"));
        assertFalse(page.contains("example.com"));
        assertFalse(page.contains("{{csrf}}"));
        assertFalse(page.contains("{{target}}"));
        assertFalse(page.contains("{{error}}"));
    }

    @Test void identityLogoutOnlyCarriesDemoAccessIntoAFreshSession() {
        var request=new MockHttpServletRequest();
        var oldSession=(MockHttpSession)request.getSession();
        oldSession.setAttribute(DemoAccessController.SESSION_ATTRIBUTE,Boolean.TRUE);
        var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("123",null,List.of()));
        SecurityContextHolder.setContext(context);
        oldSession.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,context);
        oldSession.setAttribute("org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository.CSRF_TOKEN","old-csrf");

        new AuthController(new MockEnvironment(),null).logout(request,new MockHttpServletResponse());

        assertTrue(oldSession.isInvalid());
        assertNotEquals(oldSession.getId(),request.getSession().getId());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(List.of(DemoAccessController.SESSION_ATTRIBUTE),Collections.list(request.getSession().getAttributeNames()));
        assertEquals(204,controller.check(request).getStatusCode().value());
        var normalRequest=new MockHttpServletRequest();
        normalRequest.getSession().setAttribute("business-user","123");
        new AuthController(new MockEnvironment(),null).logout(normalRequest,new MockHttpServletResponse());
        assertNull(normalRequest.getSession(false));
    }

    @Test void demoCannotStartWithMissingOrWeakAccessCredentials() {
        assertThrows(IllegalStateException.class,()->new DemoAccessController("",PASSWORD));
        assertThrows(IllegalStateException.class,()->new DemoAccessController("preview",""));
        assertThrows(IllegalStateException.class,()->new DemoAccessController("preview","12345678901"));
        assertThrows(IllegalStateException.class,()->new DemoAccessController("preview","            "));
    }
}
