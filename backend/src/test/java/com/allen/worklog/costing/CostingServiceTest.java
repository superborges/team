package com.allen.worklog.costing;

import com.allen.worklog.common.ApiException;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class CostingServiceTest {
    @Test void fourHoursUseHalfDailyStandard() {
        assertEquals(new BigDecimal("600.00"),CostingService.laborAmount(new BigDecimal("1200.0000"),240));
    }
    @Test void doesNotRoundHourlyRateBeforeCalculating() {
        assertEquals(new BigDecimal("50.00"),CostingService.laborAmount(new BigDecimal("800.04"),30));
    }
    @Test void roundsEachApprovedRowHalfUpBeforeSumming() {
        BigDecimal daily=new BigDecimal("800.08");
        assertEquals(new BigDecimal("50.01"),CostingService.laborAmount(daily,30));
        assertEquals(new BigDecimal("100.02"),CostingService.laborAmount(daily,30).add(CostingService.laborAmount(daily,30)));
        assertEquals(new BigDecimal("100.01"),CostingService.laborAmount(daily,60));
    }
    @Test void overtimeUsesSameStandardWithoutMultiplier() {
        assertEquals(new BigDecimal("1800.00"),CostingService.laborAmount(new BigDecimal("1200"),720));
    }
    @Test void idleAndIllegalMinutesCannotEnterLaborCostFunction() {
        assertThrows(ApiException.class,()->CostingService.laborAmount(new BigDecimal("1200"),0));
        assertThrows(ApiException.class,()->CostingService.laborAmount(new BigDecimal("1200"),31));
        assertThrows(ApiException.class,()->CostingService.laborAmount(new BigDecimal("-1"),30));
    }
}
