package com.allen.worklog.identity;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.HtmlUtils;

/** Browser form access for the isolated demo, independent of the chosen test identity. */
@Profile("local & !prod & !production & !worker")
@ConditionalOnProperty(name="app.demo-access-enabled",havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/demo-access")
public class DemoAccessController {
    public static final String SESSION_ATTRIBUTE=DemoAccessController.class.getName()+".ACCESS_GRANTED";
    private final byte[] username;
    private final byte[] password;

    public DemoAccessController(@Value("${app.demo-access-user:}") String username,
                                @Value("${app.demo-access-password:}") String password) {
        if(username.isBlank() || password.isBlank() || password.length()<12)
            throw new IllegalStateException("Demo access requires a username and a password of at least 12 characters");
        this.username=username.getBytes(StandardCharsets.UTF_8);
        this.password=password.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping(value="/login",produces="text/html;charset=UTF-8")
    ResponseEntity<String> page(@RequestParam(defaultValue="admin") String target,
                                @RequestParam(defaultValue="") String error,CsrfToken csrf) throws IOException {
        String html=new ClassPathResource("demo-access.html").getContentAsString(StandardCharsets.UTF_8)
            .replace("{{csrf}}",HtmlUtils.htmlEscape(csrf.getToken()))
            .replace("{{target}}",target(target))
            .replace("{{error}}","1".equals(error)?"访问账号或密码不正确，请重试。":"");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(html);
    }

    @PostMapping(value="/login",consumes=MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    ResponseEntity<Void> login(@RequestParam(defaultValue="") String username,
                               @RequestParam(defaultValue="") String password,
                               @RequestParam(defaultValue="admin") String target,HttpServletRequest request) {
        boolean accepted=MessageDigest.isEqual(this.username,username.getBytes(StandardCharsets.UTF_8))
            & MessageDigest.isEqual(this.password,password.getBytes(StandardCharsets.UTF_8));
        if(!accepted)return redirect("/demo-access/login?target="+target(target)+"&error=1");
        request.getSession();
        request.changeSessionId();
        request.getSession().setAttribute(SESSION_ATTRIBUTE,Boolean.TRUE);
        return redirect("/"+target(target)+"/");
    }

    @GetMapping("/check")
    ResponseEntity<Void> check(HttpServletRequest request) {
        var session=request.getSession(false);
        return ResponseEntity.status(session!=null && Boolean.TRUE.equals(session.getAttribute(SESSION_ATTRIBUTE))
            ?HttpStatus.NO_CONTENT:HttpStatus.UNAUTHORIZED).cacheControl(CacheControl.noStore()).build();
    }

    private static String target(String target) { return "h5".equals(target)?"h5":"admin"; }
    private static ResponseEntity<Void> redirect(String path) {
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(path)).cacheControl(CacheControl.noStore()).build();
    }
}
