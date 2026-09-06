package com.allen.worklog.master;

import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.List;
import static com.allen.worklog.master.MasterDtos.*;

@RestController
@RequestMapping("/api/v1")
public class MasterController {
    private final MasterDataService service;
    public MasterController(MasterDataService service) { this.service=service; }

    @GetMapping("/catalog") public Catalog catalog() { return service.catalog(); }
    @GetMapping("/master/users") public List<UserView> users() { return service.users(); }
    @PostMapping("/master/users") public UserView createUser(@RequestBody UserInput input) { return service.saveUser(null,input); }
    @PutMapping("/master/users/{id}") public UserView updateUser(@PathVariable long id,@RequestBody UserInput input) { return service.saveUser(id,input); }

    @GetMapping("/master/departments") public List<DepartmentView> departments() { return service.departments(); }
    @PostMapping("/master/departments") public DepartmentView createDepartment(@RequestBody DepartmentInput input) { return service.saveDepartment(null,input); }
    @PutMapping("/master/departments/{id}") public DepartmentView updateDepartment(@PathVariable long id,@RequestBody DepartmentInput input) { return service.saveDepartment(id,input); }

    @GetMapping("/master/work-items") public List<WorkItemView> workItems() { return service.workItems(); }
    @GetMapping("/master/non-project-options") public java.util.Map<String,Object> nonProjectOptions(){return service.nonProjectOptions();}
    @PostMapping("/master/work-items") public WorkItemView createWorkItem(@RequestBody WorkItemInput input) { return service.saveWorkItem(null,input); }
    @PutMapping("/master/work-items/{id}") public WorkItemView updateWorkItem(@PathVariable long id,@RequestBody WorkItemInput input) { return service.saveWorkItem(id,input); }

    @GetMapping("/master/rates") public List<RateView> rates() { return service.rates(); }
    @PostMapping("/master/rates") public RateView addRate(@RequestBody RateInput input) { return service.addRate(input); }

    @GetMapping("/master/calendar") public List<CalendarView> calendar(@RequestParam LocalDate from,@RequestParam LocalDate to) { return service.calendar(from,to); }
    @PutMapping("/master/calendar/{date}") public CalendarView updateCalendar(@PathVariable LocalDate date,@RequestBody CalendarInput input) { return service.saveCalendar(date,input); }
}
