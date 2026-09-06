package com.allen.worklog.master;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import static com.allen.worklog.master.MasterGovernanceService.*;

@RestController
@RequestMapping("/api/v1/master")
public class MasterGovernanceController {
    private final MasterGovernanceService service;
    public MasterGovernanceController(MasterGovernanceService service){this.service=service;}
    @GetMapping("/departments/{id}/managers") public List<Map<String,Object>> managers(@PathVariable long id){return service.managers(id);}
    @PostMapping("/departments/{id}/managers") public Map<String,Object> addManager(@PathVariable long id,@RequestBody ManagerInput input){return service.addManager(id,input);}
    @PostMapping("/department-managers/{id}/revoke") public Map<String,Object> revokeManager(@PathVariable long id,@RequestBody RevokeInput input){return service.revokeManager(id,input);}
    @GetMapping("/users/{id}/grants") public List<Map<String,Object>> grants(@PathVariable long id){return service.grants(id);}
    @PostMapping("/users/{id}/grants") public Map<String,Object> addGrant(@PathVariable long id,@RequestBody GrantInput input){return service.addGrant(id,input);}
    @PostMapping("/role-grants/{id}/revoke") public Map<String,Object> revokeGrant(@PathVariable long id,@RequestBody RevokeInput input){return service.revokeGrant(id,input);}
    @GetMapping("/users/{id}/history") public Map<String,Object> history(@PathVariable long id){return service.history(id);}
}
