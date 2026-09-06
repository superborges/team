package com.allen.worklog.integrations;
import java.util.*;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/source-coverage")
public class SourceCoverageController {
    private final SourceCoverageService service;
    public SourceCoverageController(SourceCoverageService service) {this.service=service;}
    @GetMapping List<Map<String,Object>> list() {return service.list();}
    @PostMapping Map<String,Object> save(@RequestBody SourceCoverageService.Input input) {return service.save(input);}
}
