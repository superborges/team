package com.allen.worklog.identity;

import me.chanjar.weixin.cp.api.impl.WxCpServiceImpl;
import me.chanjar.weixin.cp.config.impl.WxCpDefaultConfigImpl;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WxJavaCompatibilityTest {
    @Test void sdkLoadsWithoutCallingEnterpriseEndpoints() {
        var service=new WxCpServiceImpl();
        var config=new WxCpDefaultConfigImpl();config.setCorpId("local-sdk-compatibility-check");service.setWxCpConfigStorage(config);
        assertEquals("local-sdk-compatibility-check",service.getWxCpConfigStorage().getCorpId());
        assertNotNull(service.getUserService());assertNotNull(service.getMessageService());
    }
}
