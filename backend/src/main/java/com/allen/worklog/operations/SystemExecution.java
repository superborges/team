package com.allen.worklog.operations;

import java.util.List;
import java.util.function.Supplier;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** Worker-only provenance; no HTTP input can select the acting user. */
public final class SystemExecution {
    private static final ThreadLocal<Long> JOB = new ThreadLocal<>();
    private SystemExecution() {}
    public static Long jobId() { return JOB.get(); }
    public static <T> T runAs(long targetUserId,long jobId,Supplier<T> work) {
        var previous=SecurityContextHolder.getContext();Long previousJob=JOB.get();
        var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(Long.toString(targetUserId),null,List.of()));
        SecurityContextHolder.setContext(context);JOB.set(jobId);
        try { return work.get(); }
        finally { SecurityContextHolder.setContext(previous);if(previousJob==null)JOB.remove();else JOB.set(previousJob); }
    }
}
