package com.allen.worklog.identity;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration
public class LocalProfileGuard {
    public LocalProfileGuard(Environment env) {
        boolean local=env.acceptsProfiles(Profiles.of("local"));
        if (local && env.acceptsProfiles(Profiles.of("prod","production")))
            throw new IllegalStateException("Local test profile must never be combined with production");
        if (!local && env.getProperty("app.local-login-enabled",Boolean.class,false))
            throw new IllegalStateException("Local login requires the isolated local profile");
        if (!local && env.getProperty("app.demo-access-enabled",Boolean.class,false))
            throw new IllegalStateException("Demo access requires the isolated local profile");
    }
}
