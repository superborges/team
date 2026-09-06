package com.allen.worklog.approval;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ApprovalDeadlineTest {
    @Test void deadlinesUseNextNaturalWeekAndShanghaiTimeAcrossYearBoundary(){
        assertEquals(LocalDateTime.of(2027,1,4,4,0),ApprovalService.deadline(LocalDate.of(2026,12,28),1,"12:00"));
        assertEquals(LocalDateTime.of(2027,1,6,10,0),ApprovalService.deadline(LocalDate.of(2026,12,28),3,"18:00"));
    }
}
