package com.allen.worklog.master;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import static com.allen.worklog.master.MasterBatchService.*;
@RestController
@RequestMapping("/api/v1/master")
public class MasterBatchController {
    private final MasterBatchService service;
    public MasterBatchController(MasterBatchService service){this.service=service;}
    @PostMapping("/users/batch/preview") public Map<String,Object> usersPreview(@RequestBody UsersInput input){return service.previewUsers(input);}
    @PostMapping("/users/batch/apply") public Map<String,Object> usersApply(@RequestBody UsersInput input){return service.applyUsers(input);}
    @PostMapping("/department-managers/batch/preview") public Map<String,Object> managersPreview(@RequestBody ManagersInput input){return service.previewManagers(input);}
    @PostMapping("/department-managers/batch/apply") public Map<String,Object> managersApply(@RequestBody ManagersInput input){return service.applyManagers(input);}
}
