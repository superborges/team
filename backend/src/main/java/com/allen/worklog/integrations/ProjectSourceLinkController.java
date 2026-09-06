package com.allen.worklog.integrations;
import java.util.*;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/imports")
public class ProjectSourceLinkController {
    private final ProjectSourceLinkService service;
    public ProjectSourceLinkController(ProjectSourceLinkService service){this.service=service;}
    @GetMapping("/project-links") public List<Map<String,Object>> list(){return service.list();}
    @PostMapping("/{batchId}/rows/{rowNumber}/project-link") public Map<String,Object> link(@PathVariable long batchId,@PathVariable int rowNumber,@RequestBody ProjectSourceLinkService.LinkInput input){return service.link(batchId,rowNumber,input);}
}
