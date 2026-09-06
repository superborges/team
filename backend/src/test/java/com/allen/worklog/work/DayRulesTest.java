package com.allen.worklog.work;

import com.allen.worklog.common.ApiException;
import com.allen.worklog.closing.PeriodGate;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.allen.worklog.work.DayModels.*;

class DayRulesTest {
    private Entry entry(int minutes,String kind,String content,String reason) {
        return new Entry("1","1","1","测试项目",kind,DayRules.hours(minutes),minutes,content,reason,"DRAFT",true);
    }
    @Test void parsesExactHalfHoursAndRejectsFloatingOrInvalidInput() {
        assertEquals(30,DayRules.minutes("0.5"));assertEquals(1440,DayRules.minutes("24"));assertEquals("1.5",DayRules.hours(90));
        for(String value:List.of("0","-1","1.1","NaN","Infinity","1e2","25","0.333"))assertThrows(ApiException.class,()->DayRules.minutes(value));
    }
    @Test void draftCompletenessIncludesIdleButIdleIsExemptFromContentRule() {
        var rows=List.of(entry(240,"WORK","完成项目设备调试与异常复核",""),entry(60,"WORK","整理部门业务事项与协作需求",""),entry(180,"IDLE","暂无任务安排",""));
        assertTrue(DayRules.validate(rows,480,true,Set.of()).ready());
        assertFalse(DayRules.validate(rows.subList(0,2),480,true,Set.of()).ready());
        assertTrue(DayRules.validate(List.of(entry(240,"WORK","完成项目设备调试与异常复核","")),240,true,Set.of()).ready());
    }
    @Test void excessiveActualWorkRequiresExplanationAndPreviousDayTextCannotRepeat() {
        var longDay=List.of(entry(1020,"WORK","完成项目设备调试与异常复核",""));
        assertFalse(DayRules.validate(longDay,480,true,Set.of()).ready());
        var explained=List.of(entry(1020,"WORK","完成项目设备调试与异常复核","紧急设备修复并确认现场恢复"));
        assertTrue(DayRules.validate(explained,480,true,Set.of()).ready());
        assertFalse(DayRules.validate(explained,480,true,Set.of("完成项目设备调试与异常复核")).ready());
    }
    @Test void zeroWorkRestDayAndCutoffAreExplicit() {
        assertTrue(DayRules.validate(List.of(),0,true,Set.of()).ready());
        assertFalse(DayRules.validate(List.of(),0,false,Set.of()).ready());
        assertEquals(LocalDateTime.of(2026,9,9,16,0),PeriodGate.scheduledClose(LocalDate.of(2026,8,1)));
    }
    @Test void overlappingLeaveIsCountedOnceAndAmbiguousSourceIsRejected() {
        var am=new DayRules.LeaveSegment(240,540,780);
        var overlapping=new DayRules.LeaveSegment(240,660,900);
        assertEquals(360,DayRules.leaveMinutes(List.of(am,overlapping),480));
        assertEquals(240,DayRules.leaveMinutes(List.of(am,am),480));
        assertEquals(0,DayRules.leaveMinutes(List.of(am),0));
        var unknown=new DayRules.LeaveSegment(240,null,null);
        assertThrows(ApiException.class,()->DayRules.leaveMinutes(List.of(am,unknown),480));
        assertThrows(ApiException.class,()->DayRules.leaveMinutes(List.of(new DayRules.LeaveSegment(60,540,780)),480));
    }
}
