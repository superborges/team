package com.allen.worklog.identity;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class LocalProfileGuardTest {
    @Test void productionCannotEnableLocalIdentity() {
        var env=new MockEnvironment().withProperty("app.local-login-enabled","true");
        assertThrows(IllegalStateException.class,()->new LocalProfileGuard(env));
        env.setActiveProfiles("local","prod");
        assertThrows(IllegalStateException.class,()->new LocalProfileGuard(env));
        env.setActiveProfiles("local");assertDoesNotThrow(()->new LocalProfileGuard(env));
    }
    @Test void demoAccessRequiresLocalAndRejectsBothProductionProfileNames() {
        var env=new MockEnvironment().withProperty("app.demo-access-enabled","true");
        assertThrows(IllegalStateException.class,()->new LocalProfileGuard(env));
        env.setActiveProfiles("api");
        assertThrows(IllegalStateException.class,()->new LocalProfileGuard(env));
        for(String production:new String[]{"prod","production"}) {
            env.setActiveProfiles("local",production);
            assertThrows(IllegalStateException.class,()->new LocalProfileGuard(env));
        }
        env.setActiveProfiles("local","api");
        assertDoesNotThrow(()->new LocalProfileGuard(env));
    }
}
