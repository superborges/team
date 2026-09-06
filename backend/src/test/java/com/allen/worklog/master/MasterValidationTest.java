package com.allen.worklog.master;

import com.allen.worklog.common.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

class MasterValidationTest {
    @ParameterizedTest @ValueSource(strings={"98123","XX12345","ZD23412","00123","ab00001"})
    void preservesExactEmployeeIdentity(String employeeNo) {
        assertEquals(employeeNo,MasterValidation.employeeNo(employeeNo));
    }
    @ParameterizedTest @ValueSource(strings={"1234","123456","X12345","ABC12345","XX1234","98123 ","９８１２３","<98123>"})
    void rejectsAmbiguousOrInvalidEmployeeIdentity(String employeeNo) {
        ApiException error=assertThrows(ApiException.class,()->MasterValidation.employeeNo(employeeNo));
        assertEquals(422,error.status());
        assertEquals("INVALID_EMPLOYEE_NO",error.code());
    }
    @Test void intervalUsesExclusiveEndAndRejectsEmptyInterval() {
        LocalDate august31=LocalDate.of(2026,8,31),september1=LocalDate.of(2026,9,1);
        assertDoesNotThrow(()->MasterValidation.interval(august31,september1));
        assertDoesNotThrow(()->MasterValidation.interval(september1,null));
        assertThrows(ApiException.class,()->MasterValidation.interval(september1,september1));
        assertThrows(ApiException.class,()->MasterValidation.interval(september1,august31));
    }
}
