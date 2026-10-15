package com.example.systemlog.web;

import com.example.systemlog.model.Requests.*;
import com.example.systemlog.repository.LedgerRepository;
import com.example.systemlog.service.LedgerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController @Validated
public class LedgerController {
    private final LedgerService service;
    private final LedgerRepository db;
    public LedgerController(LedgerService service,LedgerRepository db){this.service=service;this.db=db;}
    @GetMapping("/health") public Map<String,Object> health() {
        db.count("SELECT 1",Map.of());return Map.of("status","ok","database","postgresql","framework","spring-boot");
    }
    @GetMapping("/api/v1/ledgers") public Map<String,Object> list(
        @RequestParam(defaultValue="1") @Min(1) @Max(10000000) int page,
        @RequestParam(name="page_size",defaultValue="20") @Min(1) @Max(100) int pageSize,
        @RequestParam Map<String,String> filters) {
        validateFilters(filters);return service.list(page,pageSize,filters,false);
    }
    private void validateFilters(Map<String,String> filters) {
        for(var entry:filters.entrySet()) if(entry.getValue().length()>200) throw new ApiException(422,"查询条件过长");
    }
    @GetMapping("/api/v1/ledgers/{id}") public Map<String,Object> detail(@PathVariable @Positive long id) {return service.detail(id);}
    @PostMapping("/api/v1/ledgers") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> create(@Valid @RequestBody LedgerWrite body,
        @RequestHeader(name="X-Actor",defaultValue="local-demo") @NotBlank @Size(max=64) String actor) {return service.create(body,actor.trim());}
    @PutMapping("/api/v1/ledgers/{id}") public Map<String,Object> update(@PathVariable @Positive long id,
        @Valid @RequestBody LedgerUpdate body,
        @RequestHeader(name="X-Actor",defaultValue="local-demo") @NotBlank @Size(max=64) String actor) {return service.update(id,body,actor.trim());}
    @DeleteMapping("/api/v1/ledgers/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable @Positive long id,@RequestParam @Min(1) int version) {service.delete(id,version);}
    @GetMapping("/api/v1/ledgers/export") @SuppressWarnings("unchecked")
    public ResponseEntity<byte[]> export(@RequestParam Map<String,String> filters) {
        validateFilters(filters);
        var rows=(List<Map<String,Object>>)service.list(1,10000,filters,true).get("items");
        StringBuilder csv=new StringBuilder("\ufeff");
        writeCsv(csv,List.of("序号","TC系统名称","事业部","集成方系统名称","TC负责人","集成方负责人","集成方测试IP","集成方生产IP","系统集成状态","首选项","handle","基本信息备注","关联文档","接口提供方","接口清单名称","接口集成状态","实际生效状态","生产nacos服务名","测试nacos服务名","测试环境接口地址","生产环境接口地址","接口报文样例","生产日志路径","测试日志路径","详细信息备注","维护人","创建日期","更新日期"));
        int index=0;
        for(var l:rows) {
            index++;
            var contacts=(List<Map<String,Object>>)l.get("contacts");
            var divisions=(List<Map<String,Object>>)l.get("divisions");
            var attachments=(List<Map<String,Object>>)l.get("attachments");
            var interfaces=(List<Map<String,Object>>)l.get("interfaces");
            if(interfaces.isEmpty()) interfaces=List.of(Map.of());
            for(var i:interfaces) writeCsv(csv,Arrays.asList(index,l.get("tc_system_name"),joined(divisions,"name",null),l.get("partner_system_name"),
                joined(contacts,"contact_name","tc"),joined(contacts,"contact_name","partner"),l.get("test_ip"),l.get("prod_ip"),state(l.get("integration_status")),
                l.get("preferences_text"),l.get("handle_text"),l.get("remark"),joined(attachments,"file_name",null),
                "tc".equals(i.get("provider_side"))?"TC提供":"非TC提供",i.get("interface_name"),state(i.get("integration_status")),state(i.get("effective_status")),
                i.get("prod_nacos_service_name"),i.get("test_nacos_service_name"),i.get("test_url"),i.get("prod_url"),i.get("message_sample"),
                i.get("prod_log_path"),i.get("test_log_path"),i.get("remark"),l.get("maintainer_name"),l.get("created_at"),l.get("updated_at")));
        }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=integration-ledgers.csv")
            .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }
    private String state(Object value){return value==null?"":"active".equals(value)?"生效":"作废";}
    private String joined(List<Map<String,Object>> rows,String key,String side) {
        return String.join("、",rows.stream().filter(r->side==null||side.equals(r.get("contact_side"))).map(r->String.valueOf(r.get(key))).toList());
    }
    private void writeCsv(StringBuilder out,List<?> cells) {
        List<String> encoded=new ArrayList<>();
        for(Object cell:cells) {
            String value=cell==null?"":cell.toString();
            String trimmed=value.stripLeading();
            if((!trimmed.isEmpty() && "=+@-".indexOf(trimmed.charAt(0))>=0)||value.startsWith("\t")||value.startsWith("\r")||value.startsWith("\n"))value="'"+value;
            encoded.add("\""+value.replace("\"","\"\"")+"\"");
        }
        out.append(String.join(",",encoded)).append("\r\n");
    }
}
