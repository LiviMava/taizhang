package com.example.systemlog.web;

import com.example.systemlog.model.Requests.BaseWrite;
import com.example.systemlog.service.LedgerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController @Validated
@RequestMapping("/api/v1/{resource:tc-systems|divisions|partner-systems}")
public class BaseController {
    private final LedgerService service;
    public BaseController(LedgerService service){this.service=service;}
    @GetMapping public Map<String,Object> list(@PathVariable String resource,
        @RequestParam(defaultValue="1") @Min(1) @Max(10000000) int page,
        @RequestParam(name="page_size",defaultValue="20") @Min(1) @Max(100) int pageSize,
        @RequestParam(required=false) @Size(max=200) String name,
        @RequestParam(required=false) @Size(max=100) String maintainer,
        @RequestParam(required=false) String status) {
        return service.listBase(resource,page,pageSize,name,maintainer,status);
    }
    @GetMapping("/{id}") public Map<String,Object> detail(@PathVariable String resource,@PathVariable @Positive long id) {
        return service.baseDetail(resource,id);
    }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> create(@PathVariable String resource,@Valid @RequestBody BaseWrite body,
        @RequestHeader(name="X-Actor",defaultValue="local-demo") @NotBlank @Size(max=64) String actor) {
        return service.saveBase(resource,null,body,actor.trim());
    }
    @PutMapping("/{id}") public Map<String,Object> update(@PathVariable String resource,@PathVariable @Positive long id,
        @Valid @RequestBody BaseWrite body,
        @RequestHeader(name="X-Actor",defaultValue="local-demo") @NotBlank @Size(max=64) String actor) {
        return service.saveBase(resource,id,body,actor.trim());
    }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String resource,@PathVariable @Positive long id) { service.deleteBase(resource,id); }
}
