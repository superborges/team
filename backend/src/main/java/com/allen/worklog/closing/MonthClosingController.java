package com.allen.worklog.closing;
import java.time.*;
import java.util.Map;
import com.allen.worklog.common.ApiException;
import com.allen.worklog.reporting.ReportModels.Version;
import org.springframework.web.bind.annotation.*;
import static com.allen.worklog.closing.MonthClosingService.*;
@RestController
@RequestMapping("/api/v1/periods/{month}")
public class MonthClosingController {
    private final MonthClosingService service;
    public MonthClosingController(MonthClosingService service) {this.service=service;}
    private static LocalDate month(String value) {try {return YearMonth.parse(value).atDay(1);}catch(Exception e){throw new ApiException(422,"MONTH_FORMAT","月份须为YYYY-MM");}}
    @GetMapping Map<String,Object> get(@PathVariable String month) {return service.get(month(month));}
    @PostMapping("/close") Version close(@PathVariable String month) {return service.close(month(month));}
    @PostMapping("/requests") Map<String,Object> request(@PathVariable String month,@RequestBody RequestInput input) {return service.request(month(month),input);}
    @PostMapping("/amendments") Map<String,Object> open(@PathVariable String month,@RequestBody AmendmentInput input) {return service.open(month(month),input);}
    @PostMapping("/amendments/{id}/scopes") Map<String,Object> grant(@PathVariable String month,@PathVariable long id,@RequestBody ScopeInput input) {return service.grant(month(month),id,input);}
    @PostMapping("/amendments/{id}/publish") Version publish(@PathVariable String month,@PathVariable long id,@RequestBody PublishInput input) {return service.publish(month(month),id,input);}
    @GetMapping("/amendments/{id}/diff") Map<String,Object> preview(@PathVariable String month,@PathVariable long id) {return service.previewDiff(month(month),id);}
    @GetMapping("/versions/{id}/diff") Map<String,Object> diff(@PathVariable String month,@PathVariable long id) {return service.diff(month(month),id);}
}
