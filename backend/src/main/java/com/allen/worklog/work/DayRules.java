package com.allen.worklog.work;

import com.allen.worklog.common.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static com.allen.worklog.work.DayModels.*;

public final class DayRules {
    private DayRules() {}
    public static int minutes(String hours) {
        return minutes(hours,30,1440);
    }
    public static int minutes(String hours,int step,int limit) {
        if(hours==null || !hours.matches("[0-9]{1,2}(\\.[0-9]{1,2})?"))
            throw new ApiException(422,"INVALID_HOURS","工时应为正数，按 0.5 小时填写");
        try {
            int value=new BigDecimal(hours).multiply(BigDecimal.valueOf(60)).intValueExact();
            if(value<=0 || value>limit || value%step!=0)throw new ArithmeticException();
            return value;
        } catch(ArithmeticException e) { throw new ApiException(422,"INVALID_HOURS","工时须大于0，不超过"+hours(limit)+"小时，按"+step+"分钟填写"); }
    }
    public static String hours(int minutes) { return BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60)).stripTrailingZeros().toPlainString(); }
    public static Validation validate(List<Entry> entries,int required,boolean enrolled,Set<String> previousContents) {
        return validate(entries,required,enrolled,previousContents,1440,960);
    }
    public static Validation validate(List<Entry> entries,int required,boolean enrolled,Set<String> previousContents,int limit,int red) {
        List<String> errors=new ArrayList<>(),warnings=new ArrayList<>();
        int total=entries.stream().mapToInt(Entry::minutes).sum();
        int actual=entries.stream().filter(e->!e.kind().equals("IDLE")).mapToInt(Entry::minutes).sum();
        if(!enrolled)errors.add("该日期不在你的填报适用区间内");
        if(total<required)errors.add("尚缺 "+hours(required-total)+" 小时，请补充工作或明确待分配时间");
        if(total>limit)errors.add("单日填报不能超过 "+hours(limit)+" 小时");
        for(Entry entry:entries) {
            if(!entry.kind().equals("IDLE")&&entry.action().equals("REPORT")) {
                int length=entry.content().codePointCount(0,entry.content().length());
                if(length<10 || length>200)errors.add(entry.workItemName()+"：工作内容需填写 10–200 字");
                if(!entry.content().isBlank() && previousContents.contains(entry.content()))errors.add(entry.workItemName()+"：请说明当天实际工作，内容不能与前一日完全重复");
            }
            if(entry.minutes()>480)warnings.add(entry.workItemName()+"：单条超过 8 小时，请核对");
        }
        if(actual>red) {
            warnings.add("实际工作超过 "+hours(red)+" 小时，后续核实时持续标记");
            if(entries.stream().filter(e->!e.kind().equals("IDLE")).noneMatch(e->!e.redReason().isBlank()))
                errors.add("实际工作超过 "+hours(red)+" 小时，请填写原因说明");
        }
        return new Validation(errors.isEmpty(),List.copyOf(errors),List.copyOf(warnings));
    }
    public static long id(String value) {
        if(value==null || !value.matches("[1-9][0-9]{0,18}"))throw new ApiException(400,"INVALID_ID","记录或对象编号无效");
        try { return Long.parseLong(value); }
        catch(NumberFormatException e) { throw new ApiException(400,"INVALID_ID","记录或对象编号无效"); }
    }
    public record LeaveSegment(int minutes,Integer start,Integer end) {}
    public static int leaveMinutes(List<LeaveSegment> leaves,int baseMinutes) {
        if(leaves.isEmpty())return 0;
        for(LeaveSegment leave:leaves) {
            boolean slotsMissing=leave.start()==null&&leave.end()==null;
            if(leave.minutes()<0 || leave.minutes()>480 || (!slotsMissing &&
                (leave.start()==null || leave.end()==null || leave.start()<0 || leave.end()>1440 ||
                 leave.end()<=leave.start() || leave.end()-leave.start()!=leave.minutes())))
                throw new ApiException(422,"INVALID_LEAVE","请假时数与核实时段不一致，请检查来源");
        }
        if(leaves.size()==1)return Math.min(leaves.getFirst().minutes(),baseMinutes);
        if(leaves.stream().anyMatch(l->l.start()==null || l.end()==null))
            throw new ApiException(422,"LEAVE_NEEDS_REVIEW","同日多笔请假缺少已核实的时段，需管理员核实基数后继续");
        boolean[] covered=new boolean[1440];
        for(LeaveSegment leave:leaves)for(int minute=leave.start();minute<leave.end();minute++)covered[minute]=true;
        int total=0;for(boolean minute:covered)if(minute)total++;
        return Math.min(total,baseMinutes);
    }
}
