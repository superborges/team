package com.allen.worklog.reporting;

import com.allen.worklog.common.ApiException;
import java.math.*;
import java.time.*;
import java.time.temporal.*;
import java.util.*;
import java.util.stream.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import static com.allen.worklog.reporting.ReportModels.*;
import static com.allen.worklog.reporting.ReportFacts.Fact;

@Service
public class ReportService {
    private final JdbcClient jdbc;private final ReportingAccess access;private final ReportFacts facts;
    public ReportService(JdbcClient jdbc,ReportingAccess access,ReportFacts facts) {this.jdbc=jdbc;this.access=access;this.facts=facts;}
    public Map<String,Object> capabilities() {
        var s=access.current();return map("canReadCosts",!s.projects().isEmpty(),"canClose",s.canClose(),"canUnlock",s.canUnlock(),"canPublish",s.canPublish(),
          "projects",jdbc.sql("SELECT CAST(id AS CHAR) id,code,name FROM work_item ORDER BY code").query().listOfRows().stream().filter(r->s.projects().contains(Long.parseLong(r.get("id").toString()))).toList(),
          "users",jdbc.sql("SELECT CAST(id AS CHAR) id,employee_no employeeNo,name FROM app_user ORDER BY employee_no").query().listOfRows().stream().filter(r->s.users().contains(Long.parseLong(r.get("id").toString()))).toList(),
          "departments",jdbc.sql("SELECT CAST(id AS CHAR) id,name FROM department ORDER BY id").query().listOfRows().stream().filter(r->s.all()||s.departments().contains(Long.parseLong(r.get("id").toString()))).toList());
    }
    public Query normalize(Query q) {
        if(q==null||q.kind()==null)throw bad("请指定报表类型");
        LocalDate today=jdbc.sql("SELECT DATE(CONVERT_TZ(UTC_TIMESTAMP(),'+00:00','+08:00'))").query(LocalDate.class).single();
        LocalDate to=q.to()==null?today:q.to(),from=q.from()==null?to.minusDays(27):q.from();
        if(from.isAfter(to)||ChronoUnit.DAYS.between(from,to)>365)throw bad("日期范围最多366天");
        if(!Set.of("workload","compliance","project-costs","onsite","details").contains(q.kind()))throw bad("未知报表类型");
        String mode=q.mode()==null?"CURRENT":q.mode(),grain=q.grain()==null?"WEEK":q.grain();
        if(!Set.of("CURRENT","FORMAL").contains(mode)||!Set.of("DAY","WEEK").contains(grain))throw bad("分析模式或粒度无效");
        for(String id:Arrays.asList(q.userId(),q.workItemId(),q.departmentId()))if(id!=null&&!validId(id))throw bad("筛选ID无效");
        if(q.level()!=null&&!Set.of("JUNIOR","MIDDLE","SENIOR").contains(q.level()))throw bad("职级无效");
        if(q.versionIds()!=null&&q.versionIds().stream().anyMatch(id->id==null||!validId(id)))throw bad("月报版本无效");
        return new Query(q.kind(),from,to,mode,q.versionIds()==null?List.of():List.copyOf(q.versionIds()),q.userId(),q.workItemId(),q.departmentId(),q.level(),grain);
    }
    public Report read(Query input) {return read(input,access.current());}
    public Report read(Query input,ReportingAccess.Scope scope) {return read(input,scope,false);}
    public Report readPinned(Query input,ReportingAccess.Scope scope) {return read(input,scope,true);}
    private Report read(Query input,ReportingAccess.Scope scope,boolean pinned) {
        Query q=normalize(input);
        if(q.kind().equals("project-costs")&&scope.projects().isEmpty())throw forbidden();
        if(q.userId()!=null&&!scope.fullPerson(Long.valueOf(q.userId()))&&Set.of("workload","compliance").contains(q.kind()))throw forbidden();
        if(q.workItemId()!=null&&q.kind().equals("project-costs")&&!scope.project(Long.valueOf(q.workItemId())))throw forbidden();
        List<Version> versions=q.mode().equals("FORMAL")?((pinned&&q.versionIds().isEmpty())?List.of():versions(q)):List.of();
        List<String> missing=new ArrayList<>();
        if(q.mode().equals("FORMAL"))for(LocalDate m=q.from().withDayOfMonth(1);!m.isAfter(q.to());m=m.plusMonths(1)) {
            String month=YearMonth.from(m).toString();if(versions.stream().noneMatch(v->v.month().equals(month)))missing.add(month);
        }
        List<Fact> all=q.mode().equals("CURRENT")?facts.current(q.from(),q.to()):versions.stream().flatMap(v->facts.frozen(Long.parseLong(v.id())).stream()).filter(f->f.date()==null||(!f.date().isBefore(q.from())&&!f.date().isAfter(q.to()))).toList();
        LocalDateTime now=jdbc.sql("SELECT UTC_TIMESTAMP(6)").query(LocalDateTime.class).single();
        // Per-month compliance uses its immutable cutoff, not the wall clock when an old report is reopened.
        Map<YearMonth,LocalDateTime> cutoffs=new HashMap<>();for(var v:versions)cutoffs.put(YearMonth.parse(v.month()),v.cutoffAt());
        var ctx=new Context(q,scope,all,now,cutoffs);
        List<Map<String,Object>> rows;Map<String,Object> summary=new LinkedHashMap<>();List<Map<String,Object>> details=new ArrayList<>();
        switch(q.kind()) {
            case "workload" -> rows=workload(ctx,summary);
            case "compliance" -> rows=compliance(ctx,summary,details);
            case "project-costs" -> rows=costs(ctx,summary);
            case "onsite" -> rows=onsite(ctx,summary);
            default -> rows=all.stream().filter(f->Set.of("TIME","ONSITE").contains(f.kind())&&visible(ctx,f)).map(f->publicFact(f,scope)).toList();
        }
        return new Report(q.kind(),q.from(),q.to(),q.mode(),now,versions,List.copyOf(missing),summary,rows,details);
    }
    public List<Version> versions(Query q) {
        var found=jdbc.sql("""
          SELECT v.*,p.month_start FROM monthly_report_version v JOIN accounting_period p ON p.id=v.period_id
          WHERE p.month_start BETWEEN ? AND ? ORDER BY p.month_start,v.version_no
          """).params(q.from().withDayOfMonth(1),q.to().withDayOfMonth(1)).query((rs,n)->new Version(rs.getString("id"),YearMonth.from(rs.getObject("month_start",LocalDate.class)).toString(),rs.getInt("version_no"),rs.getObject("cutoff_at",LocalDateTime.class),rs.getObject("published_at",LocalDateTime.class),rs.getString("reason"),rs.getString("previous_version_id"))).list();
        Map<String,Version> result=new LinkedHashMap<>();
        for(var v:found)if(q.versionIds().isEmpty()||q.versionIds().contains(v.id())) {
            if(!q.versionIds().isEmpty()&&result.containsKey(v.month()))throw bad("每个月只能指定一个正式版本");result.put(v.month(),v);
        }
        if(!q.versionIds().isEmpty()&&result.size()!=q.versionIds().size())throw bad("指定版本不存在、重复或不在查询月份内");
        return List.copyOf(result.values());
    }
    public Query pin(Query q) {q=normalize(q);if(!q.mode().equals("FORMAL"))return q;return new Query(q.kind(),q.from(),q.to(),q.mode(),versions(q).stream().map(Version::id).toList(),q.userId(),q.workItemId(),q.departmentId(),q.level(),q.grain());}
    private record Context(Query q,ReportingAccess.Scope scope,List<Fact> all,LocalDateTime now,Map<YearMonth,LocalDateTime> cutoffs) {}
    private static String key(Fact f) {return f.userId()+":"+f.date();}
    private boolean matches(Context c,Fact f) {
        Map<String,Object> d=f.data();return (c.q.userId()==null||c.q.userId().equals(str(d,"userId")))
            &&(c.q.workItemId()==null||c.q.workItemId().equals(str(d,"workItemId")))
            &&(c.q.departmentId()==null||c.q.departmentId().equals(str(d,c.q.kind().equals("project-costs")?"ownerDepartmentId":"departmentId")))
            &&(c.q.level()==null||c.q.level().equals(str(d,"levelCode")));
    }
    private boolean visible(Context c,Fact f) {return c.scope.detail(f.userId(),f.workItemId())&&matches(c,f);}
    public static boolean confirmed(Fact f) {return Set.of("APPROVED","LOCKED").contains(str(f.data(),"state"))&&str(f.data(),"action").equals("REPORT")&&!bool(f.data(),"disputeOpen");}
    private static boolean unresolved(Fact f) {String s=str(f.data(),"state");return bool(f.data(),"disputeOpen")||!Set.of("APPROVED","LOCKED","CANCELED").contains(s);}
    private record Day(Fact fact,int required,int actual,int project,int nonProject,int idle,int reported,int pending,boolean complete) {}
    private Map<String,Day> days(Context c) {
        Map<String,List<Fact>> entries=c.all.stream().filter(f->f.kind().equals("TIME")).collect(Collectors.groupingBy(ReportService::key));
        Set<String> failedLeaves=new HashSet<>(),unknownLeaveDates=new HashSet<>();
        for(Fact e:c.all)if(e.kind().equals("SOURCE_ERROR")&&str(e.data(),"dataset").equals("LEAVE")) {
            if(e.userId()==null)unknownLeaveDates.add(str(e.data(),"date"));else failedLeaves.add(e.userId()+":"+str(e.data(),"date"));
        }
        Map<String,Day> result=new LinkedHashMap<>();
        for(Fact f:c.all)if(f.kind().equals("DAY")) {
            int actual=0,project=0,non=0,idle=0,reported=0,pending=0;int required=integer(f.data(),"requiredMinutes");
            for(Fact e:entries.getOrDefault(key(f),List.of())) {
                if(unresolved(e))pending++;
                if(str(e.data(),"action").equals("REPORT")&&!str(e.data(),"state").equals("CANCELED"))reported+=integer(e.data(),"minutes");
                if(confirmed(e)) {int min=integer(e.data(),"minutes");if(str(e.data(),"workKind").equals("IDLE"))idle+=min;else {actual+=min;if(str(e.data(),"type").equals("PROJECT"))project+=min;else non+=min;}}
            }
            boolean failedLeave=failedLeaves.contains(key(f))||unknownLeaveDates.contains(f.date().toString());
            boolean complete=pending==0&&actual+idle>=required&&!bool(f.data(),"unknownLeave")&&!failedLeave;
            result.put(key(f),new Day(f,required,actual,project,non,idle,reported,pending,complete));
        }
        return result;
    }
    private List<Map<String,Object>> workload(Context c,Map<String,Object> summary) {
        Map<String,List<Day>> groups=new LinkedHashMap<>();
        for(Day d:days(c).values())if(c.scope.fullPerson(d.fact.userId())&&matches(c,d.fact)) {
            LocalDate start=c.q.grain().equals("DAY")?d.fact.date():d.fact.date().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            groups.computeIfAbsent(d.fact.userId()+":"+start,k->new ArrayList<>()).add(d);
        }
        List<Map<String,Object>> rows=new ArrayList<>();int missing=0;
        for(var values:groups.values()) {
            Day first=values.getFirst();var r=identity(first.fact);int required=0,actual=0,project=0,non=0,idle=0,leave=0,overtime=0,reported=0,pending=0,missingDays=0;boolean complete=true;
            for(Day d:values) {required+=d.required;actual+=d.actual;project+=d.project;non+=d.nonProject;idle+=d.idle;reported+=d.reported;pending+=d.pending;leave+=integer(d.fact.data(),"leaveMinutes");
                overtime+=bool(d.fact.data(),"isWorkday")?Math.max(d.actual-480,0):d.actual;
                if(d.required>0&&d.reported<d.required)missingDays++;complete&=d.complete;}
            LocalDate start=first.fact.date().with(c.q.grain().equals("DAY")?TemporalAdjusters.ofDateAdjuster(d->d):TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            r.putAll(map("periodStart",start,"periodEnd",c.q.grain().equals("DAY")?start:start.plusDays(6),"requiredMinutes",required,"confirmedWorkMinutes",actual,"confirmedProjectMinutes",project,"confirmedNonProjectMinutes",non,"confirmedIdleMinutes",idle,"leaveMinutes",leave,"overtimeMinutes",overtime,"reportedMinutes",reported,"missingDays",missingDays,"pendingCount",pending,"complete",complete,"loadPercent",complete?percent(actual,required):null,"status",!complete?"INCOMPLETE":required==0?"NO_BASE":"CONFIRMED"));
            rows.add(r);missing+=missingDays;
        }
        summary.put("missingDays",missing);summary.put("people",rows.stream().map(r->r.get("userId")).distinct().count());return rows;
    }
    private List<Map<String,Object>> compliance(Context c,Map<String,Object> summary,List<Map<String,Object>> details) {
        Map<Long,Map<String,Object>> byUser=new LinkedHashMap<>();Set<Long> notSubmitted=new HashSet<>();int expectedDays=0,ready=0;
        Set<String> draftDays=c.all.stream().filter(f->Set.of("TIME","ONSITE").contains(f.kind())&&Set.of("DRAFT","REJECTED").contains(str(f.data(),"state"))).map(ReportService::key).collect(Collectors.toSet());
        for(Day day:days(c).values())if(c.scope.fullPerson(day.fact.userId())&&matches(c,day.fact)) {
            var r=byUser.computeIfAbsent(day.fact.userId(),id->{var row=identity(day.fact);row.putAll(map("expectedPersonDays",0,"readyOnTimeDays",0,"missingDays",0,"unresolvedCount",0));return row;});
            if(day.required>0&&bool(day.fact.data(),"enrolled")) {inc(r,"expectedPersonDays",1);expectedDays++;
                var first=time(day.fact.data(),"firstReadyAt");var deadline=time(day.fact.data(),"deadlineAt");if(first!=null&&deadline!=null&&!first.isAfter(deadline)){inc(r,"readyOnTimeDays",1);ready++;}}
            if(day.required>day.reported) {inc(r,"missingDays",1);details.add(map("kind","MISSING","userId",day.fact.userId().toString(),"date",day.fact.date(),"name",day.fact.data().get("name"),"requiredMinutes",day.required,"reportedMinutes",day.reported));}
            inc(r,"unresolvedCount",day.pending);
            var deadline=time(day.fact.data(),"submitDeadline");var cutoff=c.cutoffs.getOrDefault(YearMonth.from(day.fact.date()),c.now);
            boolean draft=draftDays.contains(key(day.fact));
            if(deadline!=null&&!deadline.isAfter(cutoff)&&(day.required>day.reported||draft))notSubmitted.add(day.fact.userId());
        }
        Set<Long> late=new HashSet<>(),submitted=new HashSet<>();Set<String> overdue=new HashSet<>();int total=0,handled=0,approved=0,rejected=0;
        for(Fact f:c.all)if(f.kind().equals("APPROVAL")&&visible(c,f)) {
            var d=f.data();submitted.add(f.userId());var at=time(d,"submittedAt");var deadline=time(d,"submitDeadline");if(at!=null&&deadline!=null&&at.isAfter(deadline))late.add(f.userId());
            LocalDateTime cutoff=c.cutoffs.getOrDefault(YearMonth.from(f.date()),c.now);var due=time(d,"approvalDeadline");
            if(due!=null&&!due.isAfter(cutoff)) {total++;if(!bool(d,"disputeOpen")&&Set.of("APPROVED","REJECTED").contains(str(d,"status"))) {handled++;if(str(d,"status").equals("APPROVED"))approved++;else rejected++;}
                else {overdue.add(str(d,"packageId"));details.add(new LinkedHashMap<>(d));}}
        }
        for(Fact f:c.all)if(Set.of("TIME","ONSITE").contains(f.kind())&&unresolved(f)&&visible(c,f))details.add(publicFact(f,c.scope));

        summary.putAll(map("expectedPersonDays",expectedDays,"readyOnTimeDays",ready,"timelyRatePercent",percent(ready,expectedDays),"lateSubmitPeople",late.size(),"notSubmittedPeople",notSubmitted.size(),"approvalSubmitted",total,"approvalHandled",handled,"approvalApproved",approved,"approvalRejected",rejected,"approvalCompletionPercent",percent(handled,total),"overduePackages",overdue.size()));
        return List.copyOf(byUser.values());
    }
    private List<Map<String,Object>> costs(Context c,Map<String,Object> summary) {
        Map<Long,Map<String,Object>> result=new LinkedHashMap<>();Map<Long,Set<Long>> participants=new HashMap<>();BigDecimal labor=BigDecimal.ZERO,onsite=BigDecimal.ZERO;
        for(Fact f:c.all)if(Set.of("TIME","ONSITE").contains(f.kind())&&c.scope.project(f.workItemId())&&matches(c,f)&&str(f.data(),"type").equals("PROJECT")) {
            var r=result.computeIfAbsent(f.workItemId(),id->map("workItemId",id.toString(),"code",f.data().get("code"),"name",f.data().get("workItemName"),"type","PROJECT","departmentName",f.data().get("ownerDepartmentName"),"pmName",f.data().get("pmName"),"confirmedMinutes",0,"onsiteDays",0,"laborCost","0.00","onsiteCost","0.00","pendingCount",0));
            if(unresolved(f))inc(r,"pendingCount",1);
            if(confirmed(f)) {participants.computeIfAbsent(f.workItemId(),id->new HashSet<>()).add(f.userId());BigDecimal amount=money(f.data(),"costAmount");
                if(f.kind().equals("TIME")) {inc(r,"confirmedMinutes",integer(f.data(),"minutes"));r.put("laborCost",money(r,"laborCost").add(amount).toPlainString());labor=labor.add(amount);}
                else {inc(r,"onsiteDays",1);r.put("onsiteCost",money(r,"onsiteCost").add(amount).toPlainString());onsite=onsite.add(amount);}}
        }
        for(var e:result.entrySet()) {e.getValue().put("participants",participants.getOrDefault(e.getKey(),Set.of()).size());e.getValue().put("totalCost",money(e.getValue(),"laborCost").add(money(e.getValue(),"onsiteCost")).setScale(2).toPlainString());}
        summary.putAll(map("laborCost",labor.setScale(2).toPlainString(),"onsiteCost",onsite.setScale(2).toPlainString(),"totalCost",labor.add(onsite).setScale(2).toPlainString()));return List.copyOf(result.values());
    }
    private List<Map<String,Object>> onsite(Context c,Map<String,Object> summary) {
        var days=days(c);
        Map<String,Integer> projectMinutes=new HashMap<>();for(Fact e:c.all)if(e.kind().equals("TIME")&&confirmed(e)&&!str(e.data(),"workKind").equals("IDLE"))projectMinutes.merge(key(e)+":"+e.workItemId(),integer(e.data(),"minutes"),Integer::sum);
        var oaData=oaContext(c.all);
        List<Map<String,Object>> rows=new ArrayList<>();Set<String> onsiteKeys=new HashSet<>();long sumNumerator=0,sumDenominator=0;
        for(Fact f:c.all)if(f.kind().equals("ONSITE")&&visible(c,f)&&!str(f.data(),"action").equals("CANCEL")) {
            onsiteKeys.add(key(f));var r=publicFact(f,c.scope);Day day=days.get(key(f));int numerator=projectMinutes.getOrDefault(key(f)+":"+f.workItemId(),0);
            boolean complete=day!=null&&day.complete&&confirmed(f);int denominator=day==null?0:day.actual;
            String ratioStatus=!complete?"INCOMPLETE":denominator==0?"NOT_APPLICABLE":"CONFIRMED";
            r.putAll(map("onsiteDays",confirmed(f)?1:0,"projectMinutes",numerator,"totalWorkMinutes",denominator,"ratioPercent",complete?percent(numerator,denominator):null,"ratioStatus",ratioStatus,"reviewRequired",complete&&denominator>0&&numerator*2<denominator));
            if(c.scope.project(f.workItemId()))r.put("onsiteCost",confirmed(f)?money(f.data(),"costAmount").setScale(2).toPlainString():null);
            r.putAll(oa(oaData,f));rows.add(r);if(complete&&denominator>0){sumNumerator+=numerator;sumDenominator+=denominator;}
        }
        for(Fact f:c.all)if(f.kind().equals("TRIP")&&str(f.data(),"state").equals("APPROVED")&&!onsiteKeys.contains(key(f))&&visible(c,f)) {
            var r=new LinkedHashMap<>(f.data());r.putAll(map("state","NOT_REPORTED","onsiteDays",0,"projectMinutes",0,"totalWorkMinutes",0,"ratioPercent",null,"ratioStatus","INCOMPLETE","reviewRequired",false));r.putAll(oa(oaData,f));rows.add(r);
        }
        summary.putAll(map("onsiteDays",rows.stream().mapToInt(r->integer(r,"onsiteDays")).sum(),"ratioPercent",percent(sumNumerator,sumDenominator),"reviewCount",rows.stream().filter(r->bool(r,"reviewRequired")).count()));return rows;
    }
    private record OaContext(List<Fact> coverage,Map<String,List<Fact>> trips,Set<String> failures,Set<String> unknownDates) {}
    private static OaContext oaContext(List<Fact> all) {
        var coverage=all.stream().filter(f->f.kind().equals("COVERAGE")).toList();
        var trips=all.stream().filter(f->f.kind().equals("TRIP")&&str(f.data(),"state").equals("APPROVED")).collect(Collectors.groupingBy(ReportService::key));
        Set<String> failed=new HashSet<>(),unknown=new HashSet<>();for(var f:all)if(f.kind().equals("SOURCE_ERROR")&&str(f.data(),"dataset").equals("TRIP")) {
            if(f.userId()==null)unknown.add(str(f.data(),"date"));else failed.add(f.userId()+":"+str(f.data(),"date"));
        }
        return new OaContext(coverage,trips,failed,unknown);
    }
    private Map<String,Object> oa(OaContext c,Fact f) {
        var coverage=c.coverage.stream().filter(e->str(e.data(),"dataset").equals("TRIP")&&str(e.data(),"from").compareTo(f.date().toString())<=0&&str(e.data(),"to").compareTo(f.date().toString())>=0&&(e.data().get("departmentId")==null||Objects.equals(e.data().get("departmentId"),f.data().get("departmentId")))).max(Comparator.comparingLong(e->Long.parseLong(str(e.data(),"id"))));
        boolean failed=c.failures.contains(key(f))||c.unknownDates.contains(f.date().toString());
        String state;String note="尚未核实出差清单覆盖，不能推断无OA记录";Object at=null;
        if(failed)state="DATA_PENDING";
        else if(coverage.isEmpty())state="NOT_COMPARED";
        else {var cov=coverage.get().data();at=cov.get("verifiedAt");note=str(cov,"note");
            if(!str(cov,"state").equals("COMPLETE"))state="DATA_PENDING";
            else {boolean match=c.trips.getOrDefault(key(f),List.of()).stream().anyMatch(t->t.workItemId()==null||Objects.equals(t.workItemId(),f.workItemId()));state=match?(f.kind().equals("TRIP")?"OA_ONLY":"MATCHED"):"OA_NO_RECORD";}}
        return map("oaStatus",state,"oaComparedAt",at,"oaNote",note);
    }
    public static Map<String,Object> publicFact(Fact f,ReportingAccess.Scope scope) {
        var r=new LinkedHashMap<>(f.data());if(!scope.project(f.workItemId()))for(String field:List.of("costAmount","dailyRate","rateId","policyVersion"))r.remove(field);return r;
    }
    private static Map<String,Object> identity(Fact f) {var r=new LinkedHashMap<String,Object>();for(String field:List.of("userId","employeeNo","name","departmentName","departmentId","levelCode"))r.put(field,f.data().get(field));return r;}
    public static String str(Map<String,Object> d,String k) {Object v=d.get(k);return v==null?"":v.toString();}
    public static int integer(Map<String,Object> d,String k) {Object v=d.get(k);return v==null?0:new BigDecimal(v.toString()).intValue();}
    public static boolean bool(Map<String,Object> d,String k) {return Set.of("true","1").contains(str(d,k));}
    public static BigDecimal money(Map<String,Object> d,String k) {return d.get(k)==null?BigDecimal.ZERO:new BigDecimal(d.get(k).toString());}
    private static LocalDateTime time(Map<String,Object> d,String k) {return d.get(k)==null?null:LocalDateTime.parse(d.get(k).toString().replace(' ','T'));}
    private static String percent(long n,long d) {return d==0?null:BigDecimal.valueOf(n).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(d),2,RoundingMode.HALF_UP).toPlainString();}
    private static void inc(Map<String,Object> d,String k,int n) {d.put(k,integer(d,k)+n);}
    private static boolean validId(String value) {try{return value!=null&&value.matches("[1-9][0-9]*")&&Long.parseLong(value)>0;}catch(NumberFormatException e){return false;}}
    private static ApiException bad(String message) {return new ApiException(422,"REPORT_QUERY",message);}
    private static ApiException forbidden() {return new ApiException(403,"REPORT_SCOPE","无权查看该范围");}
}
