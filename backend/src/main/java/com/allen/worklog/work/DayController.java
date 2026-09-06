package com.allen.worklog.work;

import java.time.LocalDate;
import org.springframework.web.bind.annotation.*;
import static com.allen.worklog.work.DayModels.*;

@RestController
@RequestMapping("/api/v1")
public class DayController {
    private final DayService service;
    public DayController(DayService service) { this.service=service; }
    @GetMapping("/days/{date}") Day day(@PathVariable LocalDate date) { return service.get(date); }
    @PutMapping("/days/{date}") Day save(@PathVariable LocalDate date,@RequestBody SaveDay input,
        @RequestHeader(name="Idempotency-Key",required=false) String key) { return service.save(date,input,key); }
    @GetMapping("/weeks/{monday}") Week week(@PathVariable LocalDate monday) { return service.week(monday); }
}
