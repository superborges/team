package com.allen.worklog.integrations;

import com.allen.worklog.common.ApiException;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import static com.allen.worklog.integrations.ImportModels.*;

@RestController
@RequestMapping("/api/v1/imports")
public class ImportController {
    private final ImportService service;
    public ImportController(ImportService service) { this.service=service; }
    @GetMapping("/templates/{dataset}") ResponseEntity<byte[]> template(@PathVariable String dataset) {
        byte[] content=service.template(dataset);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+Dataset.parse(dataset).name()+".xlsx\"")
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).body(content);
    }
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE) Batch preview(@RequestParam String dataset,@RequestParam("file") MultipartFile file) throws IOException {
        if(file.getSize()>ImportWorkbook.MAX_BYTES)throw new ApiException(413,"IMPORT_SIZE","文件不能超过 2 MiB");
        return service.preview(dataset,file.getOriginalFilename(),file.getBytes());
    }
    @GetMapping List<BatchSummary> list() { return service.list(); }
    @GetMapping("/{id}") Batch get(@PathVariable long id) { return service.get(id); }
    @GetMapping("/{id}/errors") ResponseEntity<byte[]> errors(@PathVariable long id) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"import-errors-"+id+".xlsx\"")
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .body(ImportWorkbook.errors(service.get(id)));
    }
    @PostMapping("/{id}/apply") Batch apply(@PathVariable long id) { return service.apply(id); }
}
